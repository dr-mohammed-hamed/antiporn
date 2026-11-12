package com.drmohammed.antiporn.shields

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.data.repository.PolicyRepository

/**
 * Isolated security shield enforcing AntiPorn DNS over TLS and locking DNS settings.
 * Delegates unified VPN/DNS reconciliation cleanly to [CurfewVpnDnsBridge] while
 * enforcing fallback protection if VPN readiness cannot be verified.
 */
object DnsShield : ProtectionShield {

    private const val TAG = "AntiPorn_DnsShield"
    const val DEFAULT_DNS_HOST = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST

    override val id: String = "dns"
    override val titleResId: Int = R.string.card_dns_title

    fun getEffectiveDnsHost(context: Context): String {
        return try {
            val policy = PolicyRepository.getInstance(context).getCurrentPolicy()
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

        return try {
            CurfewVpnDnsBridge.applyDnsShieldPolicy(
                context = context,
                dpm = dpm,
                admin = admin,
                shieldId = id,
                targetHost = getEffectiveDnsHost(context)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed applying DNS policy", e)
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ShieldStatus.Unsupported(Build.VERSION_CODES.Q)
        }

        return try {
            CurfewVpnDnsBridge.checkDnsShieldStatus(
                context = context,
                dpm = dpm,
                admin = admin,
                targetHost = getEffectiveDnsHost(context)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error checking DNS status", e)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_dns_inactive))
        }
    }
}
