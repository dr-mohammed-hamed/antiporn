package com.drmohammed.antiporn.shields

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler

/**
 * Isolated protection shield managing scheduled night curfew (9:30 PM - 7:00 AM)
 * suspending non-whitelisted entertainment apps and isolating network usage during sleep hours.
 */
object CurfewShield : ProtectionShield {

    private const val TAG = "AntiPorn_CurfewShield"

    override val id: String = "curfew"
    override val titleResId: Int = R.string.card_curfew_title

    override fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldResult {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return ShieldResult(
                shieldId = id,
                success = false,
                message = context.getString(R.string.toast_not_device_owner)
            )
        }

        return try {
            CurfewScheduler.scheduleNextAlarms(context)
            CurfewEnforcer.syncCurfewState(context)
            val isActive = CurfewEnforcer.isEffectiveCurfewActive(context)
            val msg = if (isActive) {
                context.getString(R.string.shield_curfew_active_applied)
            } else {
                context.getString(R.string.shield_curfew_scheduled_applied)
            }
            ShieldResult(shieldId = id, success = true, message = msg)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying CurfewShield", e)
            ShieldResult(shieldId = id, success = false, message = e.localizedMessage ?: "Unknown error")
        }
    }

    override fun checkStatus(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldStatus {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return ShieldStatus.Disabled(context.getString(R.string.card_device_owner_inactive))
        }

        return try {
            val isActive = CurfewEnforcer.isEffectiveCurfewActive(context)
            if (isActive) {
                // Ground-truth verification: Ensure stores and search loophole apps are hidden
                val packagesToHide = CurfewConfig.KNOWN_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS
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
                        true
                    }
                }

                if (!allStoresHidden) {
                    Log.w(TAG, "Curfew is active but store/search apps are NOT hidden!")
                    ShieldStatus.NeedsEnforcement(context.getString(R.string.card_curfew_inactive_desc))
                } else {
                    ShieldStatus.Active(context.getString(R.string.card_curfew_active_desc))
                }
            } else {
                ShieldStatus.Active(context.getString(R.string.card_curfew_scheduled_desc))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking CurfewShield status", e)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_curfew_inactive_desc))
        }
    }
}
