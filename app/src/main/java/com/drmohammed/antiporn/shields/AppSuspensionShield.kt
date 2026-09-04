package com.drmohammed.antiporn.shields

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus

/**
 * Pluggable security shield targeting unauthorized and unrestricted applications:
 * Suspends, hides, and silently uninstalls Telegram clients and proxy/unrestricted browsers.
 */
object AppSuspensionShield : ProtectionShield {

    private const val TAG = "AntiPorn_AppSuspension"
    const val ACTION_UNINSTALL_STATUS = "com.drmohammed.antiporn.ACTION_UNINSTALL_STATUS"

    override val id: String = "app_suspension"
    override val titleResId: Int = R.string.card_app_suspension_title

    val BLACKLISTED_PACKAGES: Set<String> = setOf(
        // Telegram & clients
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus",
        "nekox.messenger", "org.thunderdog.challegram",
        // Unrestricted / Proxy browsers
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
        "org.torproject.torbrowser", "com.duckduckgo.mobile.android",
        "com.cloudmosa.puffinFree", "com.kiwibrowser.browser", "com.opera.touch"
    )

    fun isBlacklisted(packageName: String): Boolean = BLACKLISTED_PACKAGES.contains(packageName)

    fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_UNINSTALLED_PACKAGES
            }
            pm.getPackageInfo(packageName, flags)
            true
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun uninstallSilently(context: Context, packageName: String) {
        try {
            val packageInstaller = context.packageManager?.packageInstaller ?: run {
                Log.e(TAG, "PackageInstaller not available for $packageName")
                return
            }
            val intent = Intent(ACTION_UNINSTALL_STATUS).apply { setPackage(context.packageName) }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, packageName.hashCode(), intent, flags)
            packageInstaller.uninstall(packageName, pendingIntent.intentSender)
            Log.i(TAG, "Requested silent uninstallation for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request silent uninstall for $packageName", e)
        }
    }

    fun freezeAndUninstall(context: Context, dpm: DevicePolicyManager, admin: ComponentName, packageName: String) {
        if (packageName == context.packageName) {
            Log.w(TAG, "Self-protection guard: Cannot freeze or uninstall self ($packageName)")
            return
        }
        if (!isPackageInstalled(context.packageManager, packageName)) {
            Log.d(TAG, "Package $packageName is not installed; skipping freeze/uninstall")
            return
        }

        try {
            if (dpm.isUninstallBlocked(admin, packageName)) {
                dpm.setUninstallBlocked(admin, packageName, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not unblock uninstall for $packageName", e)
        }

        try {
            val failedPackages = dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            if (failedPackages.contains(packageName)) {
                Log.w(TAG, "Failed to suspend package: $packageName")
            } else {
                Log.i(TAG, "Suspended package: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to suspend package: $packageName", e)
        }

        try {
            val hidden = dpm.setApplicationHidden(admin, packageName, true)
            if (hidden) {
                Log.i(TAG, "Hidden package: $packageName")
            } else {
                Log.w(TAG, "Failed to hide package: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide package: $packageName", e)
        }

        uninstallSilently(context, packageName)
    }

    fun handlePackageAdded(context: Context, packageName: String) {
        if (!isBlacklisted(packageName)) return
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null) {
            Log.e(TAG, "DevicePolicyManager unavailable in handlePackageAdded")
            return
        }
        val admin = ComponentName(appContext, com.drmohammed.antiporn.AdminReceiver::class.java)
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) {
            Log.w(TAG, "Cannot freeze $packageName: App is not Device Owner")
            return
        }
        Log.i(TAG, "Freezing and uninstalling blacklisted app on install: $packageName")
        freezeAndUninstall(appContext, dpm, admin, packageName)
    }

    override fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldResult {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return ShieldResult(id, false, context.getString(R.string.toast_not_device_owner))
        }

        return try {
            val pm = context.packageManager
            val installedTargets = BLACKLISTED_PACKAGES.filter { isPackageInstalled(pm, it) }
            for (pkg in installedTargets) {
                freezeAndUninstall(context, dpm, admin, pkg)
            }
            val msg = if (installedTargets.isEmpty()) {
                context.getString(R.string.shield_app_suspension_none_found)
            } else {
                context.getString(R.string.shield_app_suspension_blocked_count, installedTargets.size)
            }
            ShieldResult(shieldId = id, success = true, message = msg)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying AppSuspensionShield", e)
            ShieldResult(id, false, context.getString(R.string.toast_error_applying, e.localizedMessage ?: "Unknown"))
        }
    }

    override fun checkStatus(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldStatus {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return ShieldStatus.Disabled(context.getString(R.string.card_device_owner_inactive))
        }

        return try {
            val pm = context.packageManager
            val installedTargets = BLACKLISTED_PACKAGES.filter { isPackageInstalled(pm, it) }
            if (installedTargets.isEmpty()) {
                return ShieldStatus.Active(context.getString(R.string.card_app_suspension_active))
            }

            val unSuspended = installedTargets.filter { pkg ->
                try { !dpm.isPackageSuspended(admin, pkg) } catch (e: Exception) {
                    Log.w(TAG, "Error reading package suspension for $pkg", e)
                    true
                }
            }

            if (unSuspended.isNotEmpty()) {
                ShieldStatus.NeedsEnforcement(context.getString(R.string.card_app_suspension_pending, unSuspended.size))
            } else {
                ShieldStatus.Active(context.getString(R.string.card_app_suspension_suspended, installedTargets.size))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating AppSuspensionShield status", e)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_app_suspension_inactive))
        }
    }
}
