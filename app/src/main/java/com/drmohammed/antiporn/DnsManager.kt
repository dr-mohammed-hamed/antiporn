package com.drmohammed.antiporn

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import java.util.concurrent.Executors

object DnsManager {
    private const val TAG = "AntiPorn_DnsManager"
    const val NEXTDNS_HOST = "family.cloudflare-dns.com"

    val TARGET_BROWSER_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.microsoft.emmx",
        "com.microsoft.emmx.canary",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.vivaldi.browser"
    )

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
        val isDnsLocked: Boolean = false,
        val isBrowserRestricted: Boolean = false
    )

    data class ProtectionStatus(
        val isDeviceOwner: Boolean,
        val isDnsConfigured: Boolean,
        val currentDnsHost: String?,
        val isPrivateDnsLocked: Boolean,
        val isVpnBlocked: Boolean,
        val isUsbDebuggingKeptOpen: Boolean = true,
        val isBrowserRestricted: Boolean = false
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
            val result = try {
                applyPoliciesSync(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Fatal error executing applyPoliciesSync", t)
                PolicyApplyResult(
                    success = false,
                    message = appContext.getString(R.string.toast_error_applying, t.localizedMessage ?: "Unknown"),
                    isDeviceOwner = true
                )
            }
            mainHandler.post {
                val callbacks = ArrayList(pendingCallbacks)
                pendingCallbacks.clear()
                isEnforcing.set(false)
                for (cb in callbacks) {
                    try {
                        cb.invoke(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error invoking completion callback", e)
                    }
                }
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
        var isBrowserRestricted = false

        try {
            // 1. Enforce NextDNS hostname device-wide (Requires Android 10 / API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val currentMode = try { dpm.getGlobalPrivateDnsMode(admin) } catch (e: Exception) { -1 }
                val currentHost = try { dpm.getGlobalPrivateDnsHost(admin) } catch (e: Exception) { null }
                if (currentMode != DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME || currentHost != NEXTDNS_HOST) {
                    dnsSetResult = dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, NEXTDNS_HOST)
                    Log.i(TAG, "setGlobalPrivateDnsModeSpecifiedHost result: $dnsSetResult")
                } else {
                    dnsSetResult = DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
                    Log.d(TAG, "Private DNS already set to $NEXTDNS_HOST, skipping redundant write")
                }

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

            // 10. Enforce Browser Incognito Lock and SafeSearch on Chrome, Brave, and Chromium browsers
            isBrowserRestricted = applyBrowserRestrictions(dpm, admin)
            Log.i(TAG, "Browser restrictions applied: $isBrowserRestricted")

            // 11. Launch watchdog service to continuously monitor DNS and network state
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
                isDnsLocked = isDnsLocked,
                isBrowserRestricted = isBrowserRestricted
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error applying policies", e)
            return PolicyApplyResult(
                success = false,
                message = context.getString(R.string.toast_error_applying, e.localizedMessage ?: "Unknown"),
                isDeviceOwner = true,
                isBrowserRestricted = isBrowserRestricted
            )
        }
    }

    /**
     * Builds the enterprise restriction policy bundle for Chromium-based browsers.
     * Enforces:
     * - IncognitoModeAvailability = 1 (Integer): Disables Incognito / Private browsing completely
     * - ForceGoogleSafeSearch = true (Boolean): Enforces SafeSearch for Google queries
     * - ForceYouTubeSafetyMode = true (Boolean): Enforces YouTube Restricted Mode
     * - ForceYouTubeRestrict = 2 (Integer): Enforces Strict YouTube Restricted Mode
     * - SafeSitesFilterBehavior = 1 (Integer): Blocks explicit sites via Google SafeSites filter
     */
    fun buildBrowserRestrictionsBundle(): Bundle {
        return Bundle().apply {
            putInt("IncognitoModeAvailability", 1)
            putInt("InPrivateModeAvailability", 1)
            putBoolean("ForceGoogleSafeSearch", true)
            putBoolean("ForceYouTubeSafetyMode", true)
            putInt("ForceYouTubeRestrict", 2)
            putInt("SafeSitesFilterBehavior", 1)
            putBoolean("BrowserGuestModeEnabled", false)
        }
    }

    /**
     * Applies enterprise browser policies (disabling Incognito mode and forcing SafeSearch / YouTube Safety)
     * across all major Chromium-based browsers via DevicePolicyManager.setApplicationRestrictions.
     */
    fun applyBrowserRestrictions(dpm: DevicePolicyManager, admin: ComponentName): Boolean {
        val bundle = buildBrowserRestrictionsBundle()
        var appliedCount = 0
        for (pkg in TARGET_BROWSER_PACKAGES) {
            try {
                dpm.setApplicationRestrictions(admin, pkg, bundle)
                appliedCount++
                Log.d(TAG, "Applied browser restrictions to $pkg")
            } catch (e: Exception) {
                Log.d(TAG, "Could not apply browser restrictions to $pkg: ${e.message}")
            }
        }
        Log.i(TAG, "Applied browser restrictions to $appliedCount/${TARGET_BROWSER_PACKAGES.size} packages")
        return appliedCount > 0
    }

    /**
     * Checks if browser restrictions are configured and active across all currently
     * installed target browsers on the device.
     */
    fun checkBrowserRestrictions(context: Context, dpm: DevicePolicyManager, admin: ComponentName): Boolean {
        val pm = context.packageManager
        val installedBrowsers = TARGET_BROWSER_PACKAGES.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }

        // If specific target browsers are installed, ensure ALL of them have restrictions active
        if (installedBrowsers.isNotEmpty()) {
            for (pkg in installedBrowsers) {
                try {
                    val restrictions = dpm.getApplicationRestrictions(admin, pkg)
                    if (restrictions?.getInt("IncognitoModeAvailability", 0) != 1) {
                        Log.w(TAG, "Installed browser $pkg is missing Incognito restriction!")
                        return false
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Could not check restrictions for $pkg: ${e.message}")
                    return false
                }
            }
            return true
        }

        // Fallback when no target browser is installed yet: verify DPM has registered restrictions for target packages
        for (pkg in TARGET_BROWSER_PACKAGES) {
            try {
                val restrictions = dpm.getApplicationRestrictions(admin, pkg)
                if (restrictions?.getInt("IncognitoModeAvailability", 0) == 1) {
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not check restrictions for $pkg: ${e.message}")
            }
        }
        return false
    }

    /**
     * Overload for checking restrictions without Context (fallback).
     */
    fun checkBrowserRestrictions(dpm: DevicePolicyManager, admin: ComponentName): Boolean {
        for (pkg in TARGET_BROWSER_PACKAGES) {
            try {
                val restrictions = dpm.getApplicationRestrictions(admin, pkg)
                if (restrictions?.getInt("IncognitoModeAvailability", 0) == 1) {
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not check restrictions for $pkg: ${e.message}")
            }
        }
        return false
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
                isVpnBlocked = false,
                isUsbDebuggingKeptOpen = true,
                isBrowserRestricted = false
            )

        val admin = getAdminComponent(context)
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)

        if (!isDeviceOwner) {
            return ProtectionStatus(
                isDeviceOwner = false,
                isDnsConfigured = false,
                currentDnsHost = null,
                isPrivateDnsLocked = false,
                isVpnBlocked = false,
                isUsbDebuggingKeptOpen = true,
                isBrowserRestricted = false
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

        val isBrowserRestricted = checkBrowserRestrictions(context, dpm, admin)

        return ProtectionStatus(
            isDeviceOwner = true,
            isDnsConfigured = isDnsConfigured,
            currentDnsHost = currentHost,
            isPrivateDnsLocked = isDnsLocked,
            isVpnBlocked = isVpnBlocked,
            isUsbDebuggingKeptOpen = true,
            isBrowserRestricted = isBrowserRestricted
        )
    }
}
