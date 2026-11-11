package com.drmohammed.antiporn.ui.shields

import android.content.Context
import android.os.Build
import android.os.UserManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.BrowserShield
import com.drmohammed.antiporn.shields.CurfewShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.shields.VpnShield
import java.util.TimeZone

/**
 * Strict ground-truth evaluator for currently active security shields.
 * Only returns shields whose security invariants are 100% active and enforced.
 */
object ActiveShieldsEvaluator {

    /**
     * Evaluates and returns all active protection shields.
     * Does NOT return disabled, partially-configured, or pending shields.
     */
    fun evaluateActiveShields(context: Context): List<ActiveShieldInfo> {
        val isDeviceOwner = ProtectionEngine.isDeviceOwner(context)
        if (!isDeviceOwner) {
            return emptyList()
        }

        val badgeText = context.getString(R.string.shield_active_badge)
        val activeList = mutableListOf<ActiveShieldInfo>()
        val systemStatuses = ProtectionEngine.evaluateSystemStatus(context)
        val policy = try {
            PolicyRepository.getInstance(context).getCurrentPolicy()
        } catch (_: Exception) {
            null
        }

        // 1. Full Privilege Shield (Device Owner)
        activeList.add(
            ActiveShieldInfo(
                id = "device_owner",
                title = context.getString(R.string.shield_active_do_title),
                description = context.getString(R.string.shield_active_do_desc),
                iconRes = R.drawable.ic_shield_device_owner,
                badgeText = badgeText
            )
        )

        // 2. AntiPorn DNS Shield
        val isDnsActive = systemStatuses[DnsShield.id] is ShieldStatus.Active
        val effectiveHost = DnsShield.getEffectiveDnsHost(context)
        val isAdultBlockingDns = isHostAdultFiltering(effectiveHost)
        val isLocalVpnFiltering = com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.isRunning
        if (isDnsActive && (isAdultBlockingDns || isLocalVpnFiltering)) {
            val desc = if (isLocalVpnFiltering) {
                context.getString(R.string.shield_dns_local_filter_active)
            } else {
                context.getString(R.string.shield_active_dns_desc, effectiveHost)
            }
            activeList.add(
                ActiveShieldInfo(
                    id = "dns",
                    title = context.getString(R.string.shield_active_dns_title),
                    description = desc,
                    iconRes = R.drawable.ic_shield_dns,
                    badgeText = badgeText
                )
            )
        }

        // 3. Night Curfew Shield (الحصن الليلي)
        val isCurfewPolicyEnabled = policy?.isCurfewEnabled ?: true
        val isCurfewActive = systemStatuses[CurfewShield.id] is ShieldStatus.Active
        if (isCurfewPolicyEnabled && isCurfewActive) {
            activeList.add(
                ActiveShieldInfo(
                    id = "curfew",
                    title = context.getString(R.string.shield_active_curfew_title),
                    description = context.getString(R.string.shield_active_curfew_desc),
                    iconRes = R.drawable.ic_shield_curfew,
                    badgeText = badgeText
                )
            )
        }

        // 4. Browser Incognito & SafeSearch Shield
        val isIncognitoBlockedPolicy = policy?.isIncognitoBlocked ?: true
        val isBrowserShieldActive = systemStatuses[BrowserShield.id] is ShieldStatus.Active
        if (isIncognitoBlockedPolicy && isBrowserShieldActive) {
            activeList.add(
                ActiveShieldInfo(
                    id = "browser",
                    title = context.getString(R.string.shield_active_browser_title),
                    description = context.getString(R.string.shield_active_browser_desc),
                    iconRes = R.drawable.ic_shield_incognito,
                    badgeText = badgeText
                )
            )
        }

        // 5. Prevent Time Tampering Shield (منع تغيير الوقت)
        val isDateTimeLockedPolicy = policy?.isDateTimeLocked ?: false
        if (isDateTimeLockedPolicy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val isDateTimeRestricted = com.drmohammed.antiporn.telemetry.SafeDpmGateway.hasUserRestriction(context, UserManager.DISALLOW_CONFIG_DATE_TIME)

            val targetTzId = CurfewConfig.getTargetTimeZoneId(context)
            val targetTz = TimeZone.getTimeZone(targetTzId)
            val currentTz = TimeZone.getDefault()
            val trustedTime = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(context)
            val isTargetTz = currentTz.id == targetTzId || currentTz.hasSameRules(targetTz) ||
                currentTz.getOffset(trustedTime) == targetTz.getOffset(trustedTime)

            if (isDateTimeRestricted && isTargetTz) {
                activeList.add(
                    ActiveShieldInfo(
                        id = "time_tamper",
                        title = context.getString(R.string.shield_active_time_title),
                        description = context.getString(R.string.shield_active_time_desc),
                        iconRes = R.drawable.ic_shield_time,
                        badgeText = badgeText
                    )
                )
            }
        }

        // 6. Freeze New Apps Shield (Zero-Trust)
        val lockNewInstalls = policy?.lockNewInstallsUntilApproved ?: true
        val isAppSuspensionActive = systemStatuses[AppSuspensionShield.id] is ShieldStatus.Active
        if (lockNewInstalls && isAppSuspensionActive) {
            activeList.add(
                ActiveShieldInfo(
                    id = "freeze_new_apps",
                    title = context.getString(R.string.shield_active_freeze_title),
                    description = context.getString(R.string.shield_active_freeze_desc),
                    iconRes = R.drawable.ic_shield_freeze,
                    badgeText = badgeText
                )
            )
        }

        // 7. VPN Restriction Shield
        val isVpnBlocked = systemStatuses[VpnShield.id] is ShieldStatus.Active
        if (isVpnBlocked) {
            activeList.add(
                ActiveShieldInfo(
                    id = "vpn",
                    title = context.getString(R.string.shield_active_vpn_title),
                    description = context.getString(R.string.shield_active_vpn_desc),
                    iconRes = R.drawable.ic_shield_vpn,
                    badgeText = badgeText
                )
            )
        }

        return activeList
    }

    /**
     * Determines whether the given host filters adult content.
     */
    fun isHostAdultFiltering(host: String): Boolean {
        val clean = host.trim().lowercase()
        return DnsFilteringValidator.isKnownFamilyPreset(clean) ||
                clean.contains("cloudflare") ||
                clean.contains("family") ||
                clean.contains("cleanbrowsing")
    }
}
