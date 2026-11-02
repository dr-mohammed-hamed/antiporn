package com.drmohammed.antiporn.shields

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler

/**
 * Isolated protection shield managing scheduled night curfew (8:00 PM - 7:00 AM)
 * suspending non-whitelisted entertainment apps and isolating network usage during sleep hours.
 */
object CurfewShield : ProtectionShield {

    private const val TAG = "AntiPorn_CurfewShield"

    override val id: String = "curfew"
    override val titleResId: Int = R.string.card_curfew_title

    override fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldResult {
        if (!ProtectionEngine.isDeviceOwner(context)) {
            return ShieldResult(
                shieldId = id,
                success = false,
                message = context.getString(R.string.toast_not_device_owner)
            )
        }

        return try {
            CurfewScheduler.scheduleNextAlarms(context)
            CurfewEnforcer.syncBlockState(context)
            val isActive = CurfewEnforcer.isEffectiveBlockActive(context)
            val msg = if (isActive) {
                context.getString(R.string.shield_curfew_active_applied)
            } else {
                context.getString(R.string.shield_curfew_scheduled_applied)
            }
            ShieldResult(shieldId = id, success = true, message = msg)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying CurfewShield", e)
            ShieldResult(shieldId = id, success = false, message = e.localizedMessage ?: context.getString(R.string.error_unknown))
        }
    }

    override fun checkStatus(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldStatus {
        if (!ProtectionEngine.isDeviceOwner(context)) {
            return ShieldStatus.Disabled(context.getString(R.string.card_device_owner_inactive))
        }

        return try {
            val isActive = CurfewEnforcer.isEffectiveBlockActive(context)
            if (isActive) {
                // Ground-truth verification: Ensure OEM stores and search loophole apps are hidden
                val packagesToHide = CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS
                val allStoresHidden = packagesToHide.all { pkg ->
                    try {
                        val isInstalled = try {
                            context.packageManager.getPackageInfo(pkg, 0)
                            true
                        } catch (e: Exception) {
                            false
                        }
                        if (isInstalled) dpm.isApplicationHidden(admin, pkg) else true
                    } catch (e: Exception) {
                        Log.w(TAG, "Error checking application hidden state for $pkg", e)
                        false
                    }
                }

                val isVpnBlackholeConfigured = CurfewConfig.isVpnBlackholeEnabled(context)
                val isVpnEnforced = if (isVpnBlackholeConfigured) {
                    try {
                        val isPkgMatch = dpm.getAlwaysOnVpnPackage(admin) == context.packageName
                        val isLockdown = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            dpm.isAlwaysOnVpnLockdownEnabled(admin)
                        } else {
                            true
                        }
                        isPkgMatch && isLockdown
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to query always-on VPN package during curfew check", e)
                        false
                    }
                } else {
                    true
                }

                if (!allStoresHidden || !isVpnEnforced) {
                    Log.w(TAG, "Curfew is active but store hiding ($allStoresHidden) or Blackhole VPN ($isVpnEnforced) is not fully enforced!")
                    ShieldStatus.NeedsEnforcement(context.getString(R.string.card_curfew_inactive_desc))
                } else {
                    ShieldStatus.Active(context.getString(R.string.card_curfew_active_desc))
                }
            } else {
                val isLockdownLingering = try {
                    val isBlackholeRunning = CurfewBlackholeVpnService.isRunning &&
                            CurfewBlackholeVpnService.currentMode == CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE
                    val isVpnLingeringWhenIdle = CurfewBlackholeVpnService.evaluateRequiredMode(context) == CurfewBlackholeVpnService.VpnMode.IDLE &&
                            dpm.getAlwaysOnVpnPackage(admin) == context.packageName
                    val isLockdownActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        dpm.isAlwaysOnVpnLockdownEnabled(admin)
                    } else {
                        false
                    }
                    isBlackholeRunning || isVpnLingeringWhenIdle || isLockdownActive
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query always-on VPN lockdown during release check", e)
                    false
                }

                if (isLockdownLingering) {
                    Log.w(TAG, "Curfew is inactive but Blackhole VPN lockdown is still active!")
                    ShieldStatus.NeedsEnforcement(context.getString(R.string.card_curfew_inactive_desc))
                } else {
                    ShieldStatus.Active(context.getString(R.string.card_curfew_scheduled_desc))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking CurfewShield status", e)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_curfew_inactive_desc))
        }
    }
}
