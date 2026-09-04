package com.drmohammed.antiporn

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import java.util.concurrent.Executors

object DnsManager {
    private const val TAG = "AntiPorn_DnsManager"
    const val NEXTDNS_HOST = "family.cloudflare-dns.com"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCallbacks = java.util.concurrent.CopyOnWriteArrayList<(PolicyApplyResult) -> Unit>()
    private val isEnforcing = java.util.concurrent.atomic.AtomicBoolean(false)

    data class PolicyApplyResult(
        val success: Boolean,
        val message: String,
        val isDeviceOwner: Boolean,
        val dnsSetStatus: Int = -1,
        val isVpnBlocked: Boolean = false,
        val isDnsLocked: Boolean = false
    )

    data class ProtectionStatus(
        val isDeviceOwner: Boolean,
        val isDnsConfigured: Boolean,
        val currentDnsHost: String?,
        val isPrivateDnsLocked: Boolean,
        val isVpnBlocked: Boolean,
        val isUsbDebuggingKeptOpen: Boolean = true
    )

    fun getAdminComponent(context: Context): ComponentName {
        return ComponentName(context, AdminReceiver::class.java)
    }

    /**
     * Executes policy application asynchronously on a background thread
     * to avoid NetworkOnMainThreadException (since Android checks DNS connectivity via network probe).
     * Thread-safe: queues all incoming callbacks so no callback is dropped or UI button frozen.
     */
    fun applyPoliciesAsync(context: Context, onComplete: ((PolicyApplyResult) -> Unit)? = null) {
        if (onComplete != null) {
            pendingCallbacks.add(onComplete)
        }
        if (!isEnforcing.compareAndSet(false, true)) {
            Log.i(TAG, "Policy application already in progress, registered callback for ongoing execution")
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            try {
                val result = applyPoliciesSync(appContext)
                mainHandler.post {
                    val callbacks = ArrayList(pendingCallbacks)
                    pendingCallbacks.clear()
                    for (cb in callbacks) {
                        try {
                            cb.invoke(result)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error invoking completion callback", e)
                        }
                    }
                }
            } finally {
                isEnforcing.set(false)
            }
        }
    }

    /**
     * Synchronous policy application. Must be called on a BACKGROUND THREAD.
     * Note: strictly leaves USB Debugging (DISALLOW_DEBUGGING_FEATURES) untouched
     * as requested for developer environment preservation.
     */
    fun applyPoliciesSync(context: Context): PolicyApplyResult {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return PolicyApplyResult(
                success = false,
                message = context.getString(R.string.toast_dpm_missing),
                isDeviceOwner = false
            )

        val admin = getAdminComponent(context)
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)

        if (!isDeviceOwner) {
            Log.w(TAG, "App is not Device Owner. Cannot enforce system-wide DNS.")
            return PolicyApplyResult(
                success = false,
                message = context.getString(R.string.toast_not_device_owner),
                isDeviceOwner = false
            )
        }

        var dnsSetResult = -1
        var isDnsLocked = false
        var isVpnBlocked = false

        try {
            // 1. Enforce NextDNS hostname device-wide (Requires Android 10 / API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dnsSetResult = dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, NEXTDNS_HOST)
                Log.i(TAG, "setGlobalPrivateDnsModeSpecifiedHost result: $dnsSetResult")

                // 2. Lock Private DNS settings so user cannot disable or change it in Android Settings
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                isDnsLocked = true
                Log.i(TAG, "Added restriction: DISALLOW_CONFIG_PRIVATE_DNS")
            } else {
                Log.w(TAG, "Global Private DNS API requires Android 10 (API 29) or higher.")
            }

            // 3. Block VPN configuration and usage to prevent DNS bypass
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
            isVpnBlocked = true
            Log.i(TAG, "Added restriction: DISALLOW_CONFIG_VPN")

            // 4. Block network reset to prevent bypassing DNS settings
            dpm.addUserRestriction(admin, UserManager.DISALLOW_NETWORK_RESET)
            Log.i(TAG, "Added restriction: DISALLOW_NETWORK_RESET")

            // 5. Block multi-user creation to prevent bypass via guest profiles on Realme UI
            dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_USER_SWITCH)
            }

            // 6. Block Safe Mode to prevent booting into Safe Mode to disable protection
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)

            // 7. Block factory reset from device settings
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)

            // 8. Prevent uninstalling the protection app from device UI
            try {
                dpm.setUninstallBlocked(admin, context.packageName, true)
            } catch (e: Exception) {
                Log.w(TAG, "Could not set uninstall blocked", e)
            }

            // 9. Auto-grant notification permission on Android 13+ (API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    dpm.setPermissionGrantState(
                        admin,
                        context.packageName,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not auto-grant POST_NOTIFICATIONS permission", e)
                }
            }

            // 10. Launch watchdog service to continuously monitor DNS and network state
            if (!DnsWatchdogService.isRunning) {
                try {
                    DnsWatchdogService.startService(context)
                    Log.i(TAG, "DnsWatchdogService started from applyPoliciesSync")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start DnsWatchdogService", e)
                }
            } else {
                Log.d(TAG, "DnsWatchdogService is already running, skipping launch")
            }

            val isDnsSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dnsSetResult == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
            } else {
                dnsSetResult == -1 // on older android fallback
            }

            val msg = if (isDnsSuccess) {
                context.getString(R.string.toast_enforce_success, NEXTDNS_HOST)
            } else {
                context.getString(R.string.toast_enforce_status_code, dnsSetResult)
            }

            return PolicyApplyResult(
                success = isDnsSuccess,
                message = msg,
                isDeviceOwner = true,
                dnsSetStatus = dnsSetResult,
                isVpnBlocked = isVpnBlocked,
                isDnsLocked = isDnsLocked
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error applying policies", e)
            return PolicyApplyResult(
                success = false,
                message = context.getString(R.string.toast_error_applying, e.localizedMessage ?: "Unknown"),
                isDeviceOwner = true
            )
        }
    }

    /**
     * Checks the current protection and restriction status on the device.
     */
    fun checkStatus(context: Context): ProtectionStatus {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return ProtectionStatus(
                isDeviceOwner = false,
                isDnsConfigured = false,
                currentDnsHost = null,
                isPrivateDnsLocked = false,
                isVpnBlocked = false
            )

        val admin = getAdminComponent(context)
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)

        if (!isDeviceOwner) {
            return ProtectionStatus(
                isDeviceOwner = false,
                isDnsConfigured = false,
                currentDnsHost = null,
                isPrivateDnsLocked = false,
                isVpnBlocked = false
            )
        }

        var currentHost: String? = null
        var isDnsConfigured = false
        var isDnsLocked = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val mode = dpm.getGlobalPrivateDnsMode(admin)
                currentHost = dpm.getGlobalPrivateDnsHost(admin)
                isDnsConfigured = (mode == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME && currentHost == NEXTDNS_HOST)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading DNS mode", e)
            }

            try {
                val userRestrictions = dpm.getUserRestrictions(admin)
                isDnsLocked = userRestrictions.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading DNS restriction", e)
            }
        }

        var isVpnBlocked = false
        try {
            val userRestrictions = dpm.getUserRestrictions(admin)
            isVpnBlocked = userRestrictions.getBoolean(UserManager.DISALLOW_CONFIG_VPN, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading VPN restriction", e)
        }

        return ProtectionStatus(
            isDeviceOwner = true,
            isDnsConfigured = isDnsConfigured,
            currentDnsHost = currentHost,
            isPrivateDnsLocked = isDnsLocked,
            isVpnBlocked = isVpnBlocked,
            isUsbDebuggingKeptOpen = true
        )
    }
}
