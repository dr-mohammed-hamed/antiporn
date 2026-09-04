package com.drmohammed.antiporn.shields

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
 * Isolated security shield enforcing NextDNS over TLS and locking DNS settings.
 * Adheres strictly to the Check-Before-Write rule to avoid observer loops.
 */
object DnsShield : ProtectionShield {

    private const val TAG = "AntiPorn_DnsShield"
    const val NEXTDNS_HOST = "family.cloudflare-dns.com"

    override val id: String = "dns"
    override val titleResId: Int = R.string.card_nextdns_title

    override fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldResult {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
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

        try {
            // Check-Before-Write: Only set if not already matching target host and mode
            val currentMode = try { dpm.getGlobalPrivateDnsMode(admin) } catch (e: Exception) { -1 }
            val currentHost = try { dpm.getGlobalPrivateDnsHost(admin) } catch (e: Exception) { null }

            if (currentMode != DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME || currentHost != NEXTDNS_HOST) {
                dnsSetResult = dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, NEXTDNS_HOST)
                Log.i(TAG, "setGlobalPrivateDnsModeSpecifiedHost result: $dnsSetResult")
            } else {
                Log.d(TAG, "Private DNS already set to $NEXTDNS_HOST, skipping redundant write")
            }

            // Lock Private DNS and network reset
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_NETWORK_RESET)

            val success = dnsSetResult == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
            val msg = if (success) {
                context.getString(R.string.shield_dns_success)
            } else {
                context.getString(R.string.toast_enforce_status_code, dnsSetResult)
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
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return ShieldStatus.Disabled(context.getString(R.string.card_device_owner_inactive))
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ShieldStatus.Unsupported(Build.VERSION_CODES.Q)
        }

        return try {
            val mode = dpm.getGlobalPrivateDnsMode(admin)
            val host = dpm.getGlobalPrivateDnsHost(admin)
            val restrictions = dpm.getUserRestrictions(admin)
            val isLocked = restrictions.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false)

            if (mode == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME && host == NEXTDNS_HOST && isLocked) {
                ShieldStatus.Active(context.getString(R.string.card_nextdns_active, NEXTDNS_HOST))
            } else {
                val currentHostDisplay = host ?: context.getString(R.string.default_dns_text)
                ShieldStatus.NeedsEnforcement(context.getString(R.string.card_nextdns_pending, currentHostDisplay, NEXTDNS_HOST))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking DNS status", e)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_nextdns_inactive))
        }
    }
}
