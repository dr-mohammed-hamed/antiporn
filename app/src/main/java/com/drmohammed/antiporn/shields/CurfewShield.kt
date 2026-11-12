package com.drmohammed.antiporn.shields

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge

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
            val synced = CurfewEnforcer.syncBlockState(context)
            if (!synced) {
                val msgRes = if (!CurfewConfig.isCurfewEnabled(context) &&
                    CurfewConfig.isInstantInternetKillEnabled(context)
                ) {
                    R.string.card_instant_kill_pending
                } else {
                    R.string.card_curfew_pending
                }
                return ShieldResult(
                    shieldId = id,
                    success = false,
                    message = context.getString(msgRes)
                )
            }
            val isActive = CurfewEnforcer.isEffectiveBlockActive(context)
            val msgResId = when {
                isActive -> R.string.shield_curfew_active_applied
                !CurfewConfig.isCurfewEnabled(context) -> R.string.shield_curfew_disabled_applied
                else -> R.string.shield_curfew_scheduled_applied
            }
            ShieldResult(shieldId = id, success = true, message = context.getString(msgResId))
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
                // Ground-truth verification: Ensure OEM stores and search loophole apps are suspended (Zero Hiding policy).
                val packagesToVerify = CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS
                val allStoresSuspended = packagesToVerify.all { pkg ->
                    try {
                        val isInstalled = try {
                            context.packageManager.getPackageInfo(pkg, 0)
                            true
                        } catch (_: PackageManager.NameNotFoundException) {
                            false
                        }
                        if (isInstalled) dpm.isPackageSuspended(admin, pkg) else true
                    } catch (e: Exception) {
                        Log.w(TAG, "Error checking package suspension state for $pkg", e)
                        false
                    }
                }

                val isVpnBlackholeConfigured = CurfewConfig.isVpnBlackholeEnabled(context)
                val isVpnEnforced = if (isVpnBlackholeConfigured) {
                    CurfewVpnDnsBridge.isBlackholeVpnLockdownEnforced(context, dpm, admin)
                } else {
                    true
                }

                if (!allStoresSuspended || !isVpnEnforced) {
                    val errorReason = when {
                        !allStoresSuspended && !isVpnEnforced -> "Curfew active: stores suspension and VPN blackhole unverified"
                        !allStoresSuspended -> "Curfew active: stores suspension unverified"
                        else -> "Curfew active: VPN blackhole unverified"
                    }
                    Log.w(TAG, "Curfew is active but store suspension ($allStoresSuspended) or Blackhole VPN ($isVpnEnforced) is not fully enforced! ($errorReason)")
                    ShieldStatus.NeedsEnforcement(context.getString(R.string.card_curfew_pending))
                } else {
                    ShieldStatus.Active(context.getString(R.string.card_curfew_active_desc))
                }
            } else {
                val isInstantKill = CurfewConfig.isInstantInternetKillEnabled(context)
                val isCurfewEnabled = CurfewConfig.isCurfewEnabled(context)
                val isInstantVpnEnforced = if (isInstantKill) {
                    CurfewVpnDnsBridge.isBlackholeVpnLockdownEnforced(context, dpm, admin)
                } else {
                    false
                }
                val isLockdownLingering = if (!isInstantKill) {
                    CurfewVpnDnsBridge.isLingeringVpnLockdownPresent(context, dpm, admin)
                } else {
                    false
                }

                val outcome = resolveInactiveOutcome(
                    isCurfewEnabled = isCurfewEnabled,
                    isInstantKillEnabled = isInstantKill,
                    isInstantVpnEnforced = isInstantVpnEnforced,
                    isLockdownLingering = isLockdownLingering
                )

                if (outcome.needsEnforcement) {
                    if (isInstantKill) {
                        Log.w(TAG, "Instant Internet Kill is enabled while curfew is inactive, but Blackhole VPN lockdown is unverified!")
                    } else {
                        Log.w(TAG, "Curfew is inactive but Blackhole VPN lockdown is still active!")
                    }
                    ShieldStatus.NeedsEnforcement(context.getString(outcome.messageResId))
                } else {
                    ShieldStatus.Active(context.getString(outcome.messageResId))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking CurfewShield status", e)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_curfew_pending))
        }
    }

    internal data class InactiveCurfewOutcome(
        val needsEnforcement: Boolean,
        val messageResId: Int
    )

    internal fun resolveInactiveOutcome(
        isCurfewEnabled: Boolean,
        isInstantKillEnabled: Boolean,
        isInstantVpnEnforced: Boolean,
        isLockdownLingering: Boolean
    ): InactiveCurfewOutcome {
        val needsEnforcement = if (isInstantKillEnabled) {
            !isInstantVpnEnforced
        } else {
            isLockdownLingering
        }
        val activeDescResId = if (!isCurfewEnabled) {
            R.string.card_curfew_off_optional_desc
        } else {
            R.string.card_curfew_scheduled_desc
        }
        val pendingDescResId = if (isInstantKillEnabled && !isCurfewEnabled) {
            R.string.card_instant_kill_pending
        } else {
            R.string.card_curfew_pending
        }
        return InactiveCurfewOutcome(
            needsEnforcement = needsEnforcement,
            messageResId = if (needsEnforcement) pendingDescResId else activeDescResId
        )
    }
}
