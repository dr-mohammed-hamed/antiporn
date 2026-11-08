package com.drmohammed.antiporn.shields

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.ui.shields.ActiveShieldsEvaluator

/**
 * Isolated security shield enforcing AntiPorn DNS over TLS and locking DNS settings.
 * Adheres strictly to the Check-Before-Write rule to avoid observer loops.
 * Supports dual-tier fallback (DPM -> Settings.Global -> Local Filter) to gracefully
 * handle OEM restrictions (such as Oppo/Realme ColorOS code 1).
 */
object DnsShield : ProtectionShield {

    private const val TAG = "AntiPorn_DnsShield"
    const val DEFAULT_DNS_HOST = com.drmohammed.antiporn.core.model.DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST
    const val NEXTDNS_HOST = "family.cloudflare-dns.com"

    override val id: String = "dns"
    override val titleResId: Int = R.string.card_nextdns_title

    fun getEffectiveDnsHost(context: Context): String {
        return try {
            val policy = com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy()
            policy.dnsHost.takeIf { it.isNotBlank() } ?: DEFAULT_DNS_HOST
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read policy for DNS host, falling back to default", e)
            DEFAULT_DNS_HOST
        }
    }

    override fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldResult {
        if (!ProtectionEngine.isDeviceOwner(context)) {
            return ShieldResult(
                shieldId = id,
                success = false,
                message = context.getString(R.string.toast_not_device_owner)
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ShieldResult(
                shieldId = id,
                success = false,
                message = context.getString(R.string.shield_dns_unsupported)
            )
        }

        var dnsSetResult = DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
        val targetHost = getEffectiveDnsHost(context)

        try {
            // 1. Sync and activate Unified VPN in local 24/7 DNS filtering mode
            try {
                com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.syncPolicy(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync Unified VPN policy from DnsShield", e)
            }

            val isVpnActive = com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.isRunning
            var usedFallback = false

            if (isVpnActive) {
                // When 24/7 Local DNS VPN is active, set Private DNS to Opportunistic so Android
                // resolves via the local TUN UDP 53 without hanging on Port 853 TLS to an unreachable host.
                val dpmMode = try { dpm.getGlobalPrivateDnsMode(admin) } catch (e: Exception) { -1 }
                if (dpmMode != DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC) {
                    try {
                        SafeDpmGateway.setGlobalPrivateDnsOpportunisticBlocking(context)
                        Log.i(TAG, "Private DNS switched to Opportunistic mode for Local DNS VPN")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed setting Private DNS to Opportunistic mode", e)
                    }
                }

                val globalMode = try {
                    android.provider.Settings.Global.getString(context.contentResolver, "private_dns_mode")
                } catch (e: Exception) { null }
                val globalHost = try {
                    android.provider.Settings.Global.getString(context.contentResolver, "private_dns_specifier")
                } catch (e: Exception) { null }

                if (globalMode != "opportunistic" || !globalHost.isNullOrEmpty()) {
                    try {
                        android.provider.Settings.Global.putString(context.contentResolver, "private_dns_mode", "opportunistic")
                        android.provider.Settings.Global.putString(context.contentResolver, "private_dns_specifier", "")
                        Log.i(TAG, "Enforced opportunistic mode & cleared specifier via Settings.Global for active VPN")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not write Settings.Global opportunistic mode for VPN", e)
                    }
                }
            } else {
                // Fallback / Standalone: If VPN is not active, enforce DoT via DPM and Settings.Global
                val currentMode = try { dpm.getGlobalPrivateDnsMode(admin) } catch (e: Exception) { -1 }
                val currentHost = try { dpm.getGlobalPrivateDnsHost(admin) } catch (e: Exception) { null }

                val globalMode = try {
                    android.provider.Settings.Global.getString(context.contentResolver, "private_dns_mode")
                } catch (e: Exception) { null }
                val globalHost = try {
                    android.provider.Settings.Global.getString(context.contentResolver, "private_dns_specifier")
                } catch (e: Exception) { null }

                val dpmMatches = currentMode == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                        currentHost?.equals(targetHost, ignoreCase = true) == true
                val globalMatches = globalMode == "hostname" &&
                        globalHost?.equals(targetHost, ignoreCase = true) == true

                if (!dpmMatches || !globalMatches) {
                    var dpmSucceeded = false
                    if (dpm.isDeviceOwnerApp(context.packageName) && !dpmMatches) {
                        dnsSetResult = try {
                            dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, targetHost)
                        } catch (e: Exception) {
                            Log.w(TAG, "DPM setGlobalPrivateDnsModeSpecifiedHost failed: ${e.message}")
                            DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                        }
                        Log.i(TAG, "setGlobalPrivateDnsModeSpecifiedHost to $targetHost, result: $dnsSetResult")
                        dpmSucceeded = (dnsSetResult == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR)
                    } else if (dpmMatches) {
                        dpmSucceeded = true
                    }

                    // ALWAYS ensure Settings.Global is written if globalMode or globalHost does not match targetHost
                    if (!globalMatches) {
                        try {
                            android.provider.Settings.Global.putString(context.contentResolver, "private_dns_mode", "hostname")
                            android.provider.Settings.Global.putString(context.contentResolver, "private_dns_specifier", targetHost)
                            Log.i(TAG, "Enforced private DNS via Settings.Global to $targetHost")
                            if (!dpmSucceeded) {
                                dnsSetResult = DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
                                usedFallback = true
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not write Settings.Global for private DNS", e)
                            if (!dpmSucceeded) {
                                dnsSetResult = DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                            }
                        }
                    } else if (!dpmSucceeded && dnsSetResult != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                        // Settings.Global already matched, treat as fallback success even if DPM threw
                        dnsSetResult = DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
                        usedFallback = true
                    }
                } else {
                    Log.d(TAG, "Private DNS already set to $targetHost via both DPM and Settings.Global, skipping redundant write")
                }
            }

            // Lock Private DNS and network reset (Check-Before-Write)
            val currentRestrictions = try { dpm.getUserRestrictions(admin) } catch (e: Exception) { null }
            if (currentRestrictions?.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) != true) {
                try { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS) } catch (e: Exception) { Log.w(TAG, "Lock DNS failed: ${e.message}") }
            }
            if (currentRestrictions?.getBoolean(UserManager.DISALLOW_NETWORK_RESET, false) != true) {
                try { dpm.addUserRestriction(admin, UserManager.DISALLOW_NETWORK_RESET) } catch (e: Exception) { Log.w(TAG, "Lock network reset failed: ${e.message}") }
            }

            val success = isVpnActive || dnsSetResult == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
            val msg = when {
                isVpnActive -> context.getString(R.string.shield_dns_local_filter_active)
                success && !usedFallback -> context.getString(R.string.shield_dns_success)
                success && usedFallback -> context.getString(R.string.shield_dns_fallback_active)
                else -> context.getString(R.string.card_nextdns_inactive)
            }

            return ShieldResult(
                shieldId = id,
                success = success,
                message = msg,
                statusCode = dnsSetResult
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed applying DNS policy", e)
            return ShieldResult(
                shieldId = id,
                success = false,
                message = context.getString(R.string.toast_error_applying, e.localizedMessage ?: "Unknown")
            )
        }
    }

    override fun checkStatus(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldStatus {
        if (!ProtectionEngine.isDeviceOwner(context)) {
            return ShieldStatus.Disabled(context.getString(R.string.card_device_owner_inactive))
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ShieldStatus.Unsupported(Build.VERSION_CODES.Q)
        }

        return try {
            val targetHost = getEffectiveDnsHost(context)
            val isVpnActive = com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.isRunning
            val currentRestrictions = try { dpm.getUserRestrictions(admin) } catch (e: Exception) { null }
            val isDnsLocked = currentRestrictions?.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) == true

            val globalMode = try {
                android.provider.Settings.Global.getString(context.contentResolver, "private_dns_mode")
            } catch (e: Exception) { null }

            if (isVpnActive) {
                val dpmMode = try { dpm.getGlobalPrivateDnsMode(admin) } catch (e: Exception) { -1 }
                val globalSpecifier = try {
                    android.provider.Settings.Global.getString(context.contentResolver, "private_dns_specifier")
                } catch (e: Exception) { null }
                val isAppropriateMode = dpmMode == DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC ||
                        globalMode == "opportunistic" ||
                        dpmMode == DevicePolicyManager.PRIVATE_DNS_MODE_OFF ||
                        globalMode == "off"
                val isSpecifierClean = globalSpecifier.isNullOrEmpty()
                if (isAppropriateMode && isSpecifierClean && isDnsLocked) {
                    ShieldStatus.Active(context.getString(R.string.shield_dns_local_filter_active))
                } else {
                    ShieldStatus.NeedsEnforcement("Local DNS Filter active but Private DNS or restrictions need sync")
                }
            } else {
                val modeFromDpm = try { dpm.getGlobalPrivateDnsMode(admin) } catch (e: Exception) { -1 }
                val hostFromDpm = try { dpm.getGlobalPrivateDnsHost(admin) } catch (e: Exception) { null }
                val globalSpecifier = try {
                    android.provider.Settings.Global.getString(context.contentResolver, "private_dns_specifier")
                } catch (e: Exception) { null }

                val activeHost = globalSpecifier?.takeIf { it.isNotBlank() } ?: hostFromDpm
                val isConfigured = (globalMode == "hostname" && !globalSpecifier.isNullOrBlank()) ||
                        (modeFromDpm == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME && !hostFromDpm.isNullOrBlank())
                val isHostMatching = activeHost?.equals(targetHost, ignoreCase = true) == true
                val isFilteringAdult = ActiveShieldsEvaluator.isHostAdultFiltering(activeHost ?: "")

                if (isConfigured && (isHostMatching || isFilteringAdult) && !activeHost.isNullOrBlank() && isDnsLocked) {
                    ShieldStatus.Active(context.getString(R.string.card_nextdns_active, activeHost))
                } else {
                    val currentHostDisplay = activeHost ?: context.getString(R.string.default_dns_text)
                    ShieldStatus.NeedsEnforcement(context.getString(R.string.card_nextdns_pending, currentHostDisplay, targetHost))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking DNS status", e)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_nextdns_inactive))
        }
    }
}
