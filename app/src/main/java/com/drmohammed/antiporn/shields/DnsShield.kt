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

/**
 * Isolated security shield enforcing NextDNS over TLS and locking DNS settings.
 * Adheres strictly to the Check-Before-Write rule to avoid observer loops.
 */
object DnsShield : ProtectionShield {

    private const val TAG = "AntiPorn_DnsShield"
    const val NEXTDNS_HOST = "family.cloudflare-dns.com"

    override val id: String = "dns"
    override val titleResId: Int = R.string.card_nextdns_title

    fun getEffectiveDnsHost(context: Context): String {
        return try {
            val policy = com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy()
            policy.dnsHost.takeIf { it.isNotBlank() } ?: com.drmohammed.antiporn.core.model.DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read policy for DNS host, falling back to default", e)
            com.drmohammed.antiporn.core.model.DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST
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
            // Check-Before-Write: Only set if not already matching target host and mode
            val currentMode = try { dpm.getGlobalPrivateDnsMode(admin) } catch (e: Exception) { -1 }
            val currentHost = try { dpm.getGlobalPrivateDnsHost(admin) } catch (e: Exception) { null }

            if (dpm.isDeviceOwnerApp(context.packageName)) {
                if (currentMode != DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME || currentHost != targetHost) {
                    dnsSetResult = try {
                        dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, targetHost)
                    } catch (e: Exception) {
                        Log.w(TAG, "DPM setGlobalPrivateDnsModeSpecifiedHost failed: ${e.message}")
                        DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                    }
                    Log.i(TAG, "setGlobalPrivateDnsModeSpecifiedHost to $targetHost, result: $dnsSetResult")
                } else {
                    Log.d(TAG, "Private DNS already set to $targetHost via DPM, skipping redundant write")
                }
            } else {
                Log.i(TAG, "Running under Profile Owner: applying DNS via Settings.Global with WRITE_SECURE_SETTINGS")
                try {
                    val globalMode = android.provider.Settings.Global.getString(context.contentResolver, "private_dns_mode")
                    val globalHost = android.provider.Settings.Global.getString(context.contentResolver, "private_dns_specifier")
                    if (globalMode != "hostname" || globalHost != targetHost) {
                        android.provider.Settings.Global.putString(context.contentResolver, "private_dns_mode", "hostname")
                        android.provider.Settings.Global.putString(context.contentResolver, "private_dns_specifier", targetHost)
                        Log.i(TAG, "Enforced private DNS via Settings.Global to $targetHost")
                    }
                    dnsSetResult = DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write Settings.Global for private DNS under Profile Owner", e)
                    dnsSetResult = DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
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
        if (!ProtectionEngine.isDeviceOwner(context)) {
            return ShieldStatus.Disabled(context.getString(R.string.card_device_owner_inactive))
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ShieldStatus.Unsupported(Build.VERSION_CODES.Q)
        }

        return try {
            val targetHost = getEffectiveDnsHost(context)
            val modeFromDpm = try { dpm.getGlobalPrivateDnsMode(admin) } catch (e: Exception) { -1 }
            val hostFromDpm = try { dpm.getGlobalPrivateDnsHost(admin) } catch (e: Exception) { null }

            val globalMode = try {
                android.provider.Settings.Global.getString(context.contentResolver, "private_dns_mode")
            } catch (e: Exception) { null }
            val globalSpecifier = try {
                android.provider.Settings.Global.getString(context.contentResolver, "private_dns_specifier")
            } catch (e: Exception) { null }

            val activeHost = hostFromDpm ?: globalSpecifier
            val isConfigured = (modeFromDpm == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME && !hostFromDpm.isNullOrBlank()) ||
                    (globalMode == "hostname" && !globalSpecifier.isNullOrBlank())

            if (isConfigured && !activeHost.isNullOrBlank()) {
                ShieldStatus.Active(context.getString(R.string.card_nextdns_active, activeHost))
            } else {
                val currentHostDisplay = activeHost ?: context.getString(R.string.default_dns_text)
                ShieldStatus.NeedsEnforcement(context.getString(R.string.card_nextdns_pending, currentHostDisplay, targetHost))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking DNS status", e)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_nextdns_inactive))
        }
    }
}
