package com.drmohammed.antiporn.curfew

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.DpmExecutionResult
import com.drmohammed.antiporn.telemetry.SafeDpmGateway

/**
 * Coordinates Always-On VPN lockdown and Private DNS state during curfew transitions
 * and shield verification without racing async TUN establishment.
 */
internal object CurfewVpnDnsBridge {

    private const val TAG = "AntiPorn_CurfewVpnBridge"
    private const val COMPAT_PRIVATE_DNS_MODE_UNKNOWN = 0
    private const val COMPAT_PRIVATE_DNS_MODE_OFF = 1
    private const val COMPAT_PRIVATE_DNS_MODE_OPPORTUNISTIC = 2
    private const val COMPAT_PRIVATE_DNS_MODE_PROVIDER_HOSTNAME = 3
    private const val COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING = 2

    private fun privateDnsModeUnknown(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DevicePolicyManager.PRIVATE_DNS_MODE_UNKNOWN
        else COMPAT_PRIVATE_DNS_MODE_UNKNOWN

    private fun privateDnsModeOff(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DevicePolicyManager.PRIVATE_DNS_MODE_OFF
        else COMPAT_PRIVATE_DNS_MODE_OFF

    private fun privateDnsModeOpportunistic(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
        else COMPAT_PRIVATE_DNS_MODE_OPPORTUNISTIC

    private fun privateDnsModeProviderHostname(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME
        else COMPAT_PRIVATE_DNS_MODE_PROVIDER_HOSTNAME

    @Volatile
    private var lastAppliedBypassedPackages: Set<String>? = null

    fun ensureBlackholeVpnActive(
        context: Context,
        alwaysOnPkg: String?,
        isLockdown: Boolean
    ): Pair<Boolean, Boolean> {
        var changed = false
        var dpmOk = true
        try {
            val bypassedPackages = CurfewConfig.getVpnBypassedPackages(context)
            val bypassChanged = lastAppliedBypassedPackages != bypassedPackages
            if (alwaysOnPkg != context.packageName || !isLockdown || bypassChanged) {
                val res = SafeDpmGateway.setAlwaysOnVpnPackageBlocking(
                    context = context,
                    vpnPackage = context.packageName,
                    lockdown = true,
                    bypassedPackages = bypassedPackages
                )
                if (res.isSuccess) {
                    lastAppliedBypassedPackages = bypassedPackages
                    Log.i(TAG, "Always-on Blackhole VPN configured to ${context.packageName} with lockdown=true (bypassCount=${bypassedPackages.size})")
                    changed = true
                } else {
                    dpmOk = false
                    Log.e(TAG, "Failed configuring Always-On Blackhole VPN via SafeDpmGateway")
                }
            }
            if (dpmOk || isVpnConsentGranted(context)) {
                if (!CurfewBlackholeVpnService.isServiceOrTunnelActive() ||
                    CurfewBlackholeVpnService.currentMode != CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE
                ) {
                    CurfewBlackholeVpnService.start(context)
                    changed = true
                } else if (bypassChanged) {
                    CurfewBlackholeVpnService.syncPolicy(context)
                    changed = true
                }
            } else {
                dpmOk = false
                Log.w(TAG, "Skipping Blackhole VPN start because Always-On setup failed and VPN consent is not granted")
            }
            if (waitForAndVerifyVpnReady(context)) {
                if (!ensureOpportunisticDnsForLocalVpn(context)) {
                    dpmOk = false
                    Log.w(TAG, "Failed ensuring opportunistic DNS while activating Blackhole VPN")
                }
            } else {
                dpmOk = false
                Log.w(TAG, "Blackhole VPN consent or TUN not ready; skipping opportunistic Private DNS downgrade")
            }
        } catch (e: Exception) {
            dpmOk = false
            Log.e(TAG, "Error configuring Always-On Blackhole VPN", e)
        }
        return changed to dpmOk
    }

    internal fun isLocalVpnDnsStateConsistent(
        dpmMode: Int?,
        globalMode: String?,
        globalHost: String?
    ): Boolean {
        val isGlobalHealthy = (globalMode == "opportunistic" || globalMode == "off" || globalMode == null) &&
            globalHost.isNullOrEmpty()
        val isDpmHealthy = dpmMode == privateDnsModeOpportunistic() ||
            dpmMode == privateDnsModeOff()
        return isGlobalHealthy && isDpmHealthy
    }

    internal fun isStandaloneDnsStateConsistent(
        dpmMode: Int?,
        dpmHost: String?,
        globalMode: String?,
        globalHost: String?,
        targetHost: String
    ): Boolean {
        if (targetHost.isBlank()) return false
        val globalMatches = globalMode == "hostname" &&
            globalHost?.equals(targetHost, ignoreCase = true) == true
        val dpmMatches = dpmMode == privateDnsModeProviderHostname() &&
            dpmHost?.equals(targetHost, ignoreCase = true) == true
        val dpmConsistentOrInitialFallback = dpmMatches ||
            dpmMode == privateDnsModeUnknown()
        return globalMatches && dpmConsistentOrInitialFallback
    }

    fun isStandaloneDnsShieldActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val targetHost = DnsShield.getEffectiveDnsHost(context)
        val dpmMode = SafeDpmGateway.getGlobalPrivateDnsModeBlocking(context, recordSuccess = false).getOrNull()
        val dpmHost = SafeDpmGateway.getGlobalPrivateDnsHostBlocking(context, recordSuccess = false).getOrNull()
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalHost = readGlobalSetting(context, "private_dns_specifier")
        return isStandaloneDnsStateConsistent(dpmMode, dpmHost, globalMode, globalHost, targetHost)
    }

    fun isLocalVpnDnsModeHealthy(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val dpmMode = SafeDpmGateway.getGlobalPrivateDnsModeBlocking(context, recordSuccess = false).getOrNull()
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalHost = readGlobalSetting(context, "private_dns_specifier")
        return isLocalVpnDnsStateConsistent(dpmMode, globalMode, globalHost)
    }

    fun restoreDnsShieldOnCurfewRelease(context: Context): Boolean {
        if (!SafeDpmGateway.isDeviceOwner(context)) return true
        val result = SafeDpmGateway.executeRawBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            actionName = "restoreDnsShieldOnCurfewRelease"
        ) { dpm, admin ->
            DnsShield.apply(context, dpm, admin)
        }
        return result.getOrNull()?.success == true
    }

    fun ensureOpportunisticDnsForLocalVpn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        if (!SafeDpmGateway.isDeviceOwner(context)) return false
        return ensureOpportunisticDnsForLocalVpnInternal(context)
    }

    fun ensureOpportunisticDnsForLocalVpn(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        if (!SafeDpmGateway.isDeviceOwner(context)) return false
        return ensureOpportunisticDnsForLocalVpnInternal(context)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ensureOpportunisticDnsForLocalVpnInternal(context: Context): Boolean {
        var dpmOk = true
        val dpmMode = readDpmPrivateDnsMode(context)
        if (dpmMode != DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC) {
            val dpmRes = SafeDpmGateway.setGlobalPrivateDnsOpportunisticBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD
            )
            if (!dpmRes.isSuccess) {
                dpmOk = false
                Log.w(TAG, "SafeDpmGateway setGlobalPrivateDnsOpportunisticBlocking failed")
            }
        }
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalHost = readGlobalSetting(context, "private_dns_specifier")
        var globalWriteOk = true
        if (globalMode != "opportunistic" || !globalHost.isNullOrEmpty()) {
            try {
                Settings.Global.putString(context.contentResolver, "private_dns_mode", "opportunistic")
                Settings.Global.putString(context.contentResolver, "private_dns_specifier", "")
            } catch (e: Exception) {
                globalWriteOk = false
                Log.w(TAG, "Could not write Settings.Global opportunistic mode for Local VPN", e)
            }
        }
        lockDnsAndNetworkRestrictions(context)
        val verifiedMode = readDpmPrivateDnsMode(context)
        val verifiedGlobalMode = readGlobalSetting(context, "private_dns_mode")
        val verifiedGlobalHost = readGlobalSetting(context, "private_dns_specifier")
        return dpmOk && globalWriteOk && isLocalVpnDnsStateConsistent(
            dpmMode = verifiedMode,
            globalMode = verifiedGlobalMode,
            globalHost = verifiedGlobalHost
        )
    }

    fun applyDnsShieldPolicy(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        shieldId: String,
        targetHost: String
    ): ShieldResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ShieldResult(
                shieldId = shieldId,
                success = false,
                message = context.getString(R.string.shield_dns_unsupported)
            )
        }
        val requiredMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
        if (requiredMode != CurfewBlackholeVpnService.VpnMode.IDLE) {
            val hasVpnConsent = isVpnConsentGranted(context)
            if (!hasVpnConsent) {
                Log.w(TAG, "VpnService.prepare returned non-null; refusing opportunistic DoT downgrade and preserving standalone DoT")
                applyStandaloneDot(context, targetHost)
                lockDnsAndNetworkRestrictions(context)
                return ShieldResult(
                    shieldId = shieldId,
                    success = false,
                    message = context.getString(R.string.card_dns_inactive),
                    statusCode = DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                )
            }

            try {
                CurfewBlackholeVpnService.syncPolicy(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync Unified VPN policy from DnsShield", e)
            }

            val vpnReady = waitForAndVerifyVpnReady(context)
            if (!vpnReady) {
                Log.w(TAG, "VPN TUN verification failed; preserving standalone DoT instead of opportunistic downgrade")
                applyStandaloneDot(context, targetHost)
                lockDnsAndNetworkRestrictions(context)
                return ShieldResult(
                    shieldId = shieldId,
                    success = false,
                    message = context.getString(R.string.card_dns_inactive),
                    statusCode = DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                )
            }

            val opportunisticOk = ensureOpportunisticDnsForLocalVpnInternal(context)
            return if (opportunisticOk) {
                ShieldResult(
                    shieldId = shieldId,
                    success = true,
                    message = context.getString(R.string.shield_dns_local_filter_active),
                    statusCode = DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
                )
            } else {
                ShieldResult(
                    shieldId = shieldId,
                    success = false,
                    message = context.getString(R.string.card_dns_inactive),
                    statusCode = DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                )
            }
        }

        val (dnsSetResult, usedFallback) = applyStandaloneDot(context, targetHost)
        lockDnsAndNetworkRestrictions(context)
        val isDpmNoError = dnsSetResult == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
        val success = isDpmNoError || usedFallback
        val msg = when {
            isDpmNoError && !usedFallback -> context.getString(R.string.shield_dns_success)
            usedFallback -> context.getString(R.string.shield_dns_fallback_active)
            else -> context.getString(R.string.card_dns_inactive)
        }
        return ShieldResult(
            shieldId = shieldId,
            success = success,
            message = msg,
            statusCode = dnsSetResult
        )
    }

    fun checkDnsShieldStatus(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName,
        targetHost: String
    ): ShieldStatus {
        val currentRestrictions = SafeDpmGateway.getUserRestrictionsBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            recordSuccess = false
        ).getOrNull()
        val isDnsLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentRestrictions?.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) == true
        } else {
            false
        }
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalSpecifier = readGlobalSetting(context, "private_dns_specifier")
        val modeFromDpm = readDpmPrivateDnsMode(context)

        val isVpnExpectedAndActive = CurfewBlackholeVpnService.isRunning &&
            CurfewBlackholeVpnService.evaluateRequiredMode(context) != CurfewBlackholeVpnService.VpnMode.IDLE &&
            isVpnConsentGranted(context)

        if (isVpnExpectedAndActive) {
            val isHealthy = isLocalVpnDnsStateConsistent(modeFromDpm, globalMode, globalSpecifier)
            return if (isHealthy && isDnsLocked) {
                ShieldStatus.Active(context.getString(R.string.shield_dns_local_filter_active))
            } else {
                ShieldStatus.NeedsEnforcement(context.getString(R.string.shield_dns_local_filter_needs_sync))
            }
        }

        val hostFromDpm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SafeDpmGateway.getGlobalPrivateDnsHostBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                recordSuccess = false
            ).getOrNull()
        } else {
            null
        }
        val isConsistent = isStandaloneDnsStateConsistent(
            dpmMode = modeFromDpm,
            dpmHost = hostFromDpm,
            globalMode = globalMode,
            globalHost = globalSpecifier,
            targetHost = targetHost
        )
        return if (isConsistent && isDnsLocked) {
            val activeHost = globalSpecifier?.takeIf { it.isNotBlank() } ?: hostFromDpm ?: targetHost
            ShieldStatus.Active(context.getString(R.string.card_dns_active, activeHost))
        } else {
            val currentHostDisplay = globalSpecifier?.takeIf { it.isNotBlank() }
                ?: hostFromDpm
                ?: context.getString(R.string.default_dns_text)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_dns_pending, currentHostDisplay, targetHost))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun applyStandaloneDot(
        context: Context,
        targetHost: String
    ): Pair<Int, Boolean> {
        var dnsSetResult = DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
        var usedFallback = false
        val currentMode = readDpmPrivateDnsMode(context)
        val currentHost = SafeDpmGateway.getGlobalPrivateDnsHostBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            recordSuccess = false
        ).getOrNull()
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalHost = readGlobalSetting(context, "private_dns_specifier")

        val dpmMatches = currentMode == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
            currentHost?.equals(targetHost, ignoreCase = true) == true
        val globalMatches = globalMode == "hostname" &&
            globalHost?.equals(targetHost, ignoreCase = true) == true

        if (!dpmMatches || !globalMatches) {
            var dpmSucceeded = dpmMatches
            if (!dpmMatches && SafeDpmGateway.isDeviceOwner(context)) {
                val dpmRes = SafeDpmGateway.setGlobalPrivateDnsBlocking(
                    context = context,
                    host = targetHost,
                    tag = BlackBoxRecorder.TAG_SHIELD
                )
                dnsSetResult = when (dpmRes) {
                    is DpmExecutionResult.Success -> dpmRes.data
                    is DpmExecutionResult.Failure -> COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                }
                Log.i(TAG, "setGlobalPrivateDnsBlocking to $targetHost, result: $dnsSetResult (ok=${dpmRes.isSuccess})")
                dpmSucceeded = dpmRes.isSuccess && dnsSetResult == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
            } else if (!dpmMatches) {
                dnsSetResult = DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
            }

            if (!globalMatches) {
                try {
                    Settings.Global.putString(context.contentResolver, "private_dns_mode", "hostname")
                    Settings.Global.putString(context.contentResolver, "private_dns_specifier", targetHost)
                    Log.i(TAG, "Enforced private DNS via Settings.Global to $targetHost")
                    if (!dpmSucceeded) {
                        usedFallback = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write Settings.Global for private DNS", e)
                    if (!dpmSucceeded && dnsSetResult == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                        dnsSetResult = DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                    }
                }
            } else if (!dpmSucceeded) {
                usedFallback = true
            }
        } else {
            Log.d(TAG, "Private DNS already set to $targetHost via both DPM and Settings.Global, skipping redundant write")
        }
        return dnsSetResult to usedFallback
    }

    private fun lockDnsAndNetworkRestrictions(context: Context) {
        val restrictions = SafeDpmGateway.getUserRestrictionsBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            recordSuccess = false
        ).getOrNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            restrictions?.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) != true
        ) {
            val res = SafeDpmGateway.addUserRestrictionBlocking(
                context = context,
                userRestriction = UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
                tag = BlackBoxRecorder.TAG_SHIELD
            )
            if (!res.isSuccess) {
                Log.w(TAG, "Failed enforcing DISALLOW_CONFIG_PRIVATE_DNS via SafeDpmGateway")
            }
        }
        if (restrictions?.getBoolean(UserManager.DISALLOW_NETWORK_RESET, false) != true) {
            val res = SafeDpmGateway.addUserRestrictionBlocking(
                context = context,
                userRestriction = UserManager.DISALLOW_NETWORK_RESET,
                tag = BlackBoxRecorder.TAG_SHIELD
            )
            if (!res.isSuccess) {
                Log.w(TAG, "Failed enforcing DISALLOW_NETWORK_RESET via SafeDpmGateway")
            }
        }
    }

    internal fun isVpnConsentGranted(context: Context): Boolean {
        return try {
            VpnService.prepare(context) == null
        } catch (e: Exception) {
            Log.w(TAG, "VpnService.prepare check failed", e)
            false
        }
    }

    private fun waitForAndVerifyVpnReady(context: Context): Boolean {
        if (!isVpnConsentGranted(context)) return false
        val ready = if (CurfewBlackholeVpnService.isRunning) {
            true
        } else {
            CurfewBlackholeVpnService.awaitTunnelReadyBlocking()
        }
        return ready && isVpnConsentGranted(context) && CurfewBlackholeVpnService.isRunning
    }

    private fun readDpmPrivateDnsMode(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return SafeDpmGateway.getGlobalPrivateDnsModeBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            recordSuccess = false
        ).getOrNull() ?: -1
    }

    fun reconcileNonBlackholeVpnAndDns(
        context: Context,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        alwaysOnPkg: String?,
        isLockdown: Boolean
    ): Boolean {
        return try {
            var allOk = true
            lastAppliedBypassedPackages = null
            if (requiredMode == CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY) {
                if (alwaysOnPkg != context.packageName || isLockdown) {
                    val vpnRes = SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, context.packageName, false)
                    if (!vpnRes.isSuccess) {
                        Log.e(TAG, "Failed resetting Always-on VPN lockdown=false for DNS filtering mode")
                        allOk = false
                    } else {
                        Log.i(TAG, "Always-on VPN configured to DNS-only filtering mode (lockdown=false)")
                    }
                }
                if (!CurfewBlackholeVpnService.isServiceOrTunnelActive() ||
                    CurfewBlackholeVpnService.currentMode != CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY
                ) {
                    CurfewBlackholeVpnService.syncPolicy(context)
                }
                if (waitForAndVerifyVpnReady(context) && !isLocalVpnDnsModeHealthy(context)) {
                    if (!ensureOpportunisticDnsForLocalVpn(context)) {
                        allOk = false
                    }
                }
            } else {
                if (alwaysOnPkg == context.packageName || (alwaysOnPkg == null && isLockdown)) {
                    val vpnRes = SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, null, false)
                    if (!vpnRes.isSuccess) {
                        Log.e(TAG, "Failed clearing Always-on Blackhole VPN via SafeDpmGateway")
                        allOk = false
                    } else {
                        Log.i(TAG, "Always-on Blackhole VPN released via SafeDpmGateway")
                    }
                }
                var vpnWasTornDown = false
                if (CurfewBlackholeVpnService.isServiceOrTunnelActive()) {
                    CurfewBlackholeVpnService.forceReleaseVpn(context)
                    vpnWasTornDown = true
                }
                if (vpnWasTornDown || !isStandaloneDnsShieldActive(context)) {
                    if (!restoreDnsShieldOnCurfewRelease(context)) {
                        allOk = false
                    }
                }
            }
            Log.i(TAG, "VPN Blackhole is disabled and network is released (ok=$allOk).")
            allOk
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Always-On Blackhole VPN", e)
            false
        }
    }

    fun isBlackholeVpnLockdownEnforced(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName
    ): Boolean {
        return try {
            val currentPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                recordSuccess = false
            ).getOrNull()
            val isPkgMatch = currentPkg == context.packageName
            val isLockdown = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(
                    context = context,
                    tag = BlackBoxRecorder.TAG_SHIELD,
                    recordSuccess = false
                ).getOrNull() ?: false
            } else {
                true
            }
            isPkgMatch && isLockdown
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query always-on VPN package during curfew check", e)
            false
        }
    }

    fun isLingeringVpnLockdownPresent(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName
    ): Boolean {
        return try {
            val isBlackholeRunning = CurfewBlackholeVpnService.isServiceOrTunnelActive() &&
                CurfewBlackholeVpnService.currentMode == CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE
            val currentPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                recordSuccess = false
            ).getOrNull()
            val isVpnLingeringWhenIdle = CurfewBlackholeVpnService.evaluateRequiredMode(context) == CurfewBlackholeVpnService.VpnMode.IDLE &&
                currentPkg == context.packageName
            val isLockdownActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(
                    context = context,
                    tag = BlackBoxRecorder.TAG_SHIELD,
                    recordSuccess = false
                ).getOrNull() ?: false
            } else {
                false
            }
            isBlackholeRunning || isVpnLingeringWhenIdle || isLockdownActive
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query always-on VPN lockdown during release check", e)
            false
        }
    }

    private fun readGlobalSetting(context: Context, key: String): String? {
        return try {
            Settings.Global.getString(context.contentResolver, key)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading Settings.Global key=$key", e)
            null
        }
    }
}
