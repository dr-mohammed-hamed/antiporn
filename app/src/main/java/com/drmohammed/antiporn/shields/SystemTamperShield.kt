package com.drmohammed.antiporn.shields

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import android.provider.Settings
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.repository.PolicyRepository
import java.util.TimeZone

/**
 * Isolated security shield that prevents system-level bypassing:
 * Blocks Safe Boot, Factory Reset, Multi-User Guest profiles, App Uninstallation,
 * Date/Time manipulation, and enforces persistent Makkah Al-Mukarramah timezone (GMT+3).
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
        if (!ProtectionEngine.isDeviceOwner(context)) {
            return ShieldResult(
                shieldId = id,
                success = false,
                message = context.getString(R.string.toast_not_device_owner)
            )
        }

        return try {
            val isDo = dpm.isDeviceOwnerApp(context.packageName)

            // 1. Block Safe Mode (Requires DO)
            if (isDo) {
                try { dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT) } catch (e: Exception) { Log.w(TAG, "Safe boot block: ${e.message}") }
            }

            // 2. Block Factory Reset (Requires DO)
            if (isDo) {
                try { dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET) } catch (e: Exception) { Log.w(TAG, "Factory reset block: ${e.message}") }
            }

            // 3. Block Multi-User & Guest creation (Works for DO and PO)
            try { dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER) } catch (e: Exception) { Log.w(TAG, "Add user block: ${e.message}") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try { dpm.addUserRestriction(admin, UserManager.DISALLOW_USER_SWITCH) } catch (e: Exception) { Log.w(TAG, "User switch block: ${e.message}") }
            }
            // Supplementary direct user switcher deactivation via Settings.Global (Check-Before-Write)
            try {
                if (Settings.Global.getInt(context.contentResolver, "user_switcher_enabled", 1) != 0) {
                    Settings.Global.putInt(context.contentResolver, "user_switcher_enabled", 0)
                }
                if (Settings.Global.getInt(context.contentResolver, "guest_user_enabled", 1) != 0) {
                    Settings.Global.putInt(context.contentResolver, "guest_user_enabled", 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "User switcher global settings disable: ${e.message}")
            }

            // 4. Prevent uninstalling the protection app from device UI (Works for DO and PO)
            try {
                dpm.setUninstallBlocked(admin, context.packageName, true)
                Log.i(TAG, "setUninstallBlocked=true successfully applied for ${context.packageName}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set uninstall blocked", e)
            }

            // 4.1 Prevent clearing application data for self package (Requires DO)
            if (isDo) {
                try {
                    // Invoked via reflection to support OEM/extended DPM APIs without breaking public SDK compilation
                    val method = dpm.javaClass.getMethod(
                        "setClearApplicationUserDataBlocked",
                        ComponentName::class.java,
                        String::class.java,
                        Boolean::class.javaPrimitiveType
                    )
                    method.invoke(dpm, admin, context.packageName, true)
                    Log.i(TAG, "Application data clearing blocked successfully for self package")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not setClearApplicationUserDataBlocked: ${e.message}")
                }

                // 4.2 Prevent force stop & application tampering via settings UI
                try {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
                    Log.i(TAG, "DISALLOW_APPS_CONTROL restriction applied successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not add DISALLOW_APPS_CONTROL: ${e.message}")
                }
            }

            // 5. Block Date & Time tampering (Requires DO and isDateTimeLocked policy enabled by supervisor)
            val isDateTimeLocked = try {
                com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().isDateTimeLocked
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query isDateTimeLocked policy: ${e.message}", e)
                false
            }

            // Always synchronize device timezone with policy timezone
            val targetTzId = CurfewConfig.getTargetTimeZoneId(context)
            if (isDo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    TimeZone.setDefault(null) // Invalidate JVM cached default timezone
                    val currentTz = TimeZone.getDefault()
                    if (currentTz.id != targetTzId) {
                        dpm.setTimeZone(admin, targetTzId)
                        TimeZone.setDefault(null) // Invalidate JVM cached default timezone
                        Log.i(TAG, "Applied timezone via DPM: $targetTzId")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set timezone via DPM", e)
                }
            }

            if (isDateTimeLocked) {
                if (isDo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME) } catch (e: Exception) { Log.w(TAG, "Date time block: ${e.message}") }
                }

                // 7. Enforce atomic network time sync (NTP) while keeping timezone locked to Makkah
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        dpm.setAutoTimeZoneEnabled(admin, false) // Prevent cell towers overriding Makkah timezone
                        dpm.setAutoTimeEnabled(admin, true)       // Keep seconds/minutes strictly synced with network
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not configure auto time/timezone on Android 11+", e)
                    }
                } else {
                    try {
                        @Suppress("DEPRECATION")
                        dpm.setAutoTimeRequired(admin, true)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set auto time required", e)
                    }
                }

                // Supplementary direct Settings.Global configuration via DevicePolicyManager or ContentResolver (Check-Before-Write)
                try {
                    if (isDo) {
                        dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME, "1")
                        dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME_ZONE, "0")
                    } else {
                        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 0) != 1) {
                            Settings.Global.putInt(context.contentResolver, Settings.Global.AUTO_TIME, 1)
                        }
                        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE, 1) != 0) {
                            Settings.Global.putInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE, 0)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write Global auto time settings", e)
                }
            } else {
                // Time lock is NOT enabled by supervisor: Lift user restriction and allow user manual/auto time
                if (isDo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
                        Log.i(TAG, "Cleared DISALLOW_CONFIG_DATE_TIME restriction (time lock is not active)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not clear date time block: ${e.message}")
                    }
                }
                if (isDo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        dpm.setAutoTimeZoneEnabled(admin, true)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not restore auto timezone setting: ${e.message}")
                    }
                } else if (isDo) {
                    try {
                        @Suppress("DEPRECATION")
                        dpm.setAutoTimeRequired(admin, false)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not clear auto time required: ${e.message}")
                    }
                }
            }

            // 8. Auto-grant notification permission on Android 13+ (API 33+) for the watchdog FGS
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
        if (!ProtectionEngine.isDeviceOwner(context)) {
            return ShieldStatus.Disabled(context.getString(R.string.card_device_owner_inactive))
        }

        return try {
            val restrictions = dpm.getUserRestrictions(admin)
            val isSafeBootBlocked = restrictions?.getBoolean(UserManager.DISALLOW_SAFE_BOOT, false) ?: false
            val isFactoryResetBlocked = restrictions?.getBoolean(UserManager.DISALLOW_FACTORY_RESET, false) ?: false
            val isAddUserBlocked = restrictions?.getBoolean(UserManager.DISALLOW_ADD_USER, false) ?: false
            val isDateTimeBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                restrictions?.getBoolean(UserManager.DISALLOW_CONFIG_DATE_TIME, false) ?: false
            } else {
                true // API < 28 does not support user restriction
            }
            val isUninstallBlocked = try {
                dpm.isUninstallBlocked(admin, context.packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not query isUninstallBlocked", e)
                false
            }

            val targetTzId = CurfewConfig.getTargetTimeZoneId(context)
            val targetTz = TimeZone.getTimeZone(targetTzId)
            TimeZone.setDefault(null) // Invalidate JVM cached default timezone to query fresh OS status
            val currentTz = TimeZone.getDefault()
            val nowMs = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(context)
            val isTargetTz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                currentTz.id == targetTzId || currentTz.hasSameRules(targetTz) ||
                    currentTz.getOffset(nowMs) == targetTz.getOffset(nowMs)
            } else {
                true // API < 28 cannot set system timezone via DPM
            }

            val isDateTimeLocked = try {
                com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().isDateTimeLocked
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query isDateTimeLocked policy during evaluation: ${e.message}", e)
                false
            }

            val isDo = dpm.isDeviceOwnerApp(context.packageName)
            val isClearDataBlocked = if (isDo) {
                try {
                    val method = dpm.javaClass.getMethod(
                        "isClearApplicationUserDataBlocked",
                        ComponentName::class.java,
                        String::class.java
                    )
                    (method.invoke(dpm, admin, context.packageName) as? Boolean) ?: true
                } catch (e: Exception) {
                    Log.d(TAG, "isClearApplicationUserDataBlocked reflection fallback: ${e.message}")
                    true // If not supported directly by SDK/OEM, covered via DISALLOW_APPS_CONTROL
                }
            } else {
                true
            }

            val isFullyProtected = if (isDo) {
                val baseProtected = isSafeBootBlocked && isFactoryResetBlocked && isAddUserBlocked && isUninstallBlocked && isClearDataBlocked
                if (isDateTimeLocked) {
                    baseProtected && isDateTimeBlocked && isTargetTz
                } else {
                    baseProtected
                }
            } else {
                isUninstallBlocked
            }

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
