package com.drmohammed.antiporn.shields

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus

/**
 * Isolated security shield that prevents system-level bypassing:
 * Blocks Safe Boot, Factory Reset, Multi-User Guest profiles, and App Uninstallation.
 *
 * STRICT CONSTITUTIONAL RULE:
 * NEVER configure DISALLOW_DEBUGGING_FEATURES or DISALLOW_CONFIG_DEVELOPER_OPTIONS.
 * USB debugging must remain 100% accessible to the developer at all times.
 */
object SystemTamperShield : ProtectionShield {

    private const val TAG = "AntiPorn_SystemTamper"

    override val id: String = "system_tamper"
    override val titleResId: Int = R.string.card_system_tamper_title

    override fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldResult {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return ShieldResult(
                shieldId = id,
                success = false,
                message = context.getString(R.string.toast_not_device_owner)
            )
        }

        return try {
            // 1. Block Safe Mode to prevent booting into Safe Mode to disable protection
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)

            // 2. Block Factory Reset from device settings
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)

            // 3. Block Multi-User & Guest creation (common bypass route on Realme/ColorOS/Xiaomi)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_USER_SWITCH)
            }

            // 4. Prevent uninstalling the protection app from device UI
            try {
                dpm.setUninstallBlocked(admin, context.packageName, true)
            } catch (e: Exception) {
                Log.w(TAG, "Could not set uninstall blocked", e)
            }

            // 5. Auto-grant notification permission on Android 13+ (API 33+) for the watchdog FGS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    dpm.setPermissionGrantState(
                        admin,
                        context.packageName,
                        Manifest.permission.POST_NOTIFICATIONS,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not auto-grant POST_NOTIFICATIONS permission", e)
                }
            }

            ShieldResult(
                shieldId = id,
                success = true,
                message = context.getString(R.string.shield_system_tamper_success)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error applying anti-tamper policies", e)
            ShieldResult(
                shieldId = id,
                success = false,
                message = context.getString(R.string.toast_error_applying, e.localizedMessage ?: "Unknown")
            )
        }
    }

    override fun checkStatus(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldStatus {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return ShieldStatus.Disabled(context.getString(R.string.card_device_owner_inactive))
        }

        return try {
            val restrictions = dpm.getUserRestrictions(admin)
            val isSafeBootBlocked = restrictions?.getBoolean(UserManager.DISALLOW_SAFE_BOOT, false) ?: false
            val isFactoryResetBlocked = restrictions?.getBoolean(UserManager.DISALLOW_FACTORY_RESET, false) ?: false
            val isAddUserBlocked = restrictions?.getBoolean(UserManager.DISALLOW_ADD_USER, false) ?: false
            val isUninstallBlocked = try {
                dpm.isUninstallBlocked(admin, context.packageName)
            } catch (e: Exception) {
                false
            }

            val isFullyProtected = isSafeBootBlocked && isFactoryResetBlocked && isAddUserBlocked && isUninstallBlocked

            if (isFullyProtected) {
                ShieldStatus.Active(context.getString(R.string.card_system_tamper_active))
            } else {
                ShieldStatus.NeedsEnforcement(context.getString(R.string.card_system_tamper_pending))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking anti-tamper status", e)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_system_tamper_inactive))
        }
    }
}
