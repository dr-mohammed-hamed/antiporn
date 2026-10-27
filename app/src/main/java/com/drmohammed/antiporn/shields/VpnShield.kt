package com.drmohammed.antiporn.shields

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus

/**
 * Isolated security shield that closes the VPN bypass loophole by enforcing DISALLOW_CONFIG_VPN.
 */
object VpnShield : ProtectionShield {

    private const val TAG = "AntiPorn_VpnShield"

    override val id: String = "vpn"
    override val titleResId: Int = R.string.card_vpn_title

    override fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldResult {
        if (!ProtectionEngine.isDeviceOwner(context)) {
            return ShieldResult(
                shieldId = id,
                success = false,
                message = context.getString(R.string.toast_not_device_owner)
            )
        }

        return try {
            val restrictions = dpm.getUserRestrictions(admin)
            val alreadyBlocked = restrictions?.getBoolean(UserManager.DISALLOW_CONFIG_VPN, false) ?: false
            if (!alreadyBlocked) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
                Log.i(TAG, "Added restriction: DISALLOW_CONFIG_VPN")
            } else {
                Log.d(TAG, "DISALLOW_CONFIG_VPN already set, skipping write")
            }

            ShieldResult(
                shieldId = id,
                success = true,
                message = context.getString(R.string.shield_vpn_success)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error applying VPN restriction", e)
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
            val isVpnBlocked = restrictions?.getBoolean(UserManager.DISALLOW_CONFIG_VPN, false) ?: false
            if (isVpnBlocked) {
                ShieldStatus.Active(context.getString(R.string.card_vpn_blocked))
            } else {
                ShieldStatus.NeedsEnforcement(context.getString(R.string.card_vpn_unblocked))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking VPN restriction status", e)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_vpn_inactive))
        }
    }
}
