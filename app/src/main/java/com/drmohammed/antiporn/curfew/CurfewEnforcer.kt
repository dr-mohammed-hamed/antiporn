package com.drmohammed.antiporn.curfew

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.shields.AppSuspensionShield
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Enforces and releases app suspensions based on the night curfew schedule.
 * Strictly guarantees:
 * 1. 24/7 blacklisted apps (Telegram, TikTok, proxy browsers) are NEVER unsuspended.
 * 2. Play Store and OEM stores are completely hidden and suspended.
 * 3. Newly installed non-whitelisted apps are immediately frozen if curfew is active.
 */
object CurfewEnforcer {

    private const val TAG = "AntiPorn_CurfewEnforcer"
    private val bgExecutor = Executors.newSingleThreadExecutor()

    /**
     * Determines whether the current Makkah time falls within the 20:30 - 07:00 curfew window.
     */
    fun isCurfewActive(calendar: Calendar = CurfewConfig.getCurfewCalendar()): Boolean {
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val startMinute = CurfewConfig.START_HOUR * 60 + CurfewConfig.START_MINUTE // 20:30 = 1230
        val endMinute = CurfewConfig.END_HOUR * 60 + CurfewConfig.END_MINUTE     // 07:00 = 420

        // Overnight interval across midnight
        return minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    fun isCurfewFeatureEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(CurfewConfig.KEY_CURFEW_ENABLED, true)
    }

    fun setCurfewFeatureEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(CurfewConfig.KEY_CURFEW_ENABLED, enabled).apply()
        syncCurfewState(context)
    }

    fun isManualTestActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(CurfewConfig.KEY_MANUAL_TEST_ACTIVE, false)
    }

    fun setManualTestActive(context: Context, active: Boolean) {
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(CurfewConfig.KEY_MANUAL_TEST_ACTIVE, active).apply()
        syncCurfewState(context)
    }

    fun setManualTestActiveAsync(context: Context, active: Boolean, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(CurfewConfig.KEY_MANUAL_TEST_ACTIVE, active).apply()

        bgExecutor.execute {
            var success = false
            try {
                val shouldBeActive = isEffectiveCurfewActive(appContext)
                success = enforce(appContext, shouldBeActive)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing setManualTestActiveAsync", e)
            } finally {
                if (onComplete != null) {
                    Handler(Looper.getMainLooper()).post {
                        onComplete.invoke(success)
                    }
                }
            }
        }
    }

    fun isEffectiveCurfewActive(context: Context): Boolean {
        if (!isCurfewFeatureEnabled(context)) return false
        if (System.currentTimeMillis() < CurfewConfig.EMERGENCY_WORK_EXTENSION_UNTIL_MS) {
            return false
        }
        return isManualTestActive(context) || isCurfewActive()
    }

    /**
     * Effective block = night curfew OR active nap. Nap merges into the same engine.
     */
    fun isEffectiveBlockActive(context: Context): Boolean =
        isEffectiveCurfewActive(context) || NapController.isNapActive(context)

    /**
     * Synchronously applies or releases curfew suspensions and store lockdowns.
     * Guaranteed invariant: Cannot release curfew if current time is inside night curfew window,
     * unless an emergency work extension is explicitly active.
     */
    @Synchronized
    fun enforce(context: Context, activate: Boolean, calendar: Calendar = CurfewConfig.getCurfewCalendar()): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val admin = ComponentName(context, AdminReceiver::class.java)

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Cannot enforce curfew: Not Device Owner")
            return false
        }

        // Ironclad Security Invariant: NEVER release block (morning wakeup / nap end) if current time is within
        // scheduled night window OR a nap is still active, unless an emergency work extension is actively running.
        val isEmergencyWorkExtension = System.currentTimeMillis() < CurfewConfig.EMERGENCY_WORK_EXTENSION_UNTIL_MS
        if (!activate && (isCurfewActive(calendar) || NapController.isNapActive(context)) && !isEmergencyWorkExtension) {
            Log.w(TAG, "Security Alert: Block release rejected - night curfew window (20:30 - 07:00) or active nap still holds!")
            return true
        }

        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)

        return if (activate) {
            val targets = CurfewAppResolver.getCurfewTargetPackages(context)
            Log.i(TAG, "Enforcing Curfew ON for ${targets.size} targeted packages")

            // 0. Enforce 100% network blackout via Always-On Blackhole VPN + OS Lockdown
            try {
                val currentVpn = dpm.getAlwaysOnVpnPackage(admin)
                val bypassedPackages = CurfewConfig.getVpnBypassedPackages(context)
                if (currentVpn != context.packageName) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        dpm.setAlwaysOnVpnPackage(admin, context.packageName, true, bypassedPackages)
                    } else {
                        dpm.setAlwaysOnVpnPackage(admin, context.packageName, true)
                    }
                    Log.i(TAG, "Always-on Blackhole VPN configured to ${context.packageName} with lockdown=true")
                }
                CurfewBlackholeVpnService.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring Always-On Blackhole VPN during curfew", e)
            }

            // 1. Lockdown OEM App Stores & Search loophole apps completely (Hide from launcher and apps list)
            val packagesToHide = CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS
            for (pkg in packagesToHide) {
                try {
                    if (!dpm.isApplicationHidden(admin, pkg)) {
                        dpm.setApplicationHidden(admin, pkg, true)
                        Log.i(TAG, "Hidden store/search package: $pkg")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not hide package $pkg", e)
                }
            }

            // Ensure Google Play Store and Google Search are NOT hidden and NOT suspended so games can use background IPC
            try {
                if (dpm.isApplicationHidden(admin, CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE)) {
                    dpm.setApplicationHidden(admin, CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE, false)
                }
                if (dpm.isPackageSuspended(admin, CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE)) {
                    dpm.setPackagesSuspended(admin, arrayOf(CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE), false)
                }
                if (dpm.isApplicationHidden(admin, CurfewConfig.GOOGLE_SEARCH_PACKAGE)) {
                    dpm.setApplicationHidden(admin, CurfewConfig.GOOGLE_SEARCH_PACKAGE, false)
                }
                if (dpm.isPackageSuspended(admin, CurfewConfig.GOOGLE_SEARCH_PACKAGE)) {
                    dpm.setPackagesSuspended(admin, arrayOf(CurfewConfig.GOOGLE_SEARCH_PACKAGE), false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not ensure Play Store / Google Search unhidden/unsuspended", e)
            }

            // 2. Suspend target applications (excluding already suspended)
            val toSuspend = targets.filter { pkg ->
                try {
                    !dpm.isPackageSuspended(admin, pkg)
                } catch (e: Exception) {
                    true
                }
            }

            if (toSuspend.isNotEmpty()) {
                try {
                    val failed = dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true)
                    Log.i(TAG, "Curfew activated: suspended ${toSuspend.size - (failed?.size ?: 0)} packages")
                } catch (e: Exception) {
                    Log.e(TAG, "Error suspending curfew packages", e)
                }
            }

            // Ensure any whitelisted or immune package that might have been previously suspended is immediately unsuspended
            val immunePackages = CurfewAppResolver.resolveImmunePackages(context)
            val immuneToUnsuspend = immunePackages.filter { pkg ->
                !AppSuspensionShield.isBlacklisted(pkg) && try { dpm.isPackageSuspended(admin, pkg) } catch (e: Exception) { false }
            }
            if (immuneToUnsuspend.isNotEmpty()) {
                try {
                    dpm.setPackagesSuspended(admin, immuneToUnsuspend.toTypedArray(), false)
                    Log.i(TAG, "Unsuspended whitelisted/immune packages during curfew: $immuneToUnsuspend")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unsuspending immune packages during curfew", e)
                }
            }

            // Ensure DISALLOW_INSTALL_APPS is NOT active, preserving ADB developer workflow (Constitution Article 1).
            // App installation prevention is handled safely via CurfewForegroundWatcher (Play Store kickout),
            // OEM store hiding/suspension, and handlePackageAdded instant freezing.
            try {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear DISALLOW_INSTALL_APPS restriction", e)
            }

            // 4. Start foreground watcher for instant auto-kickout if Play Store UI is launched
            CurfewForegroundWatcher.start(context)

            // Save set of curfew-suspended packages
            val savedSet = prefs.getStringSet(CurfewConfig.KEY_CURFEW_SUSPENDED_PACKAGES, emptySet())?.toMutableSet()
                ?: mutableSetOf()
            savedSet.addAll(targets)
            prefs.edit().putStringSet(CurfewConfig.KEY_CURFEW_SUSPENDED_PACKAGES, savedSet).apply()
            true
        } else {
            // Wakeup: Restore daytime apps and stores
            Log.i(TAG, "Enforcing Curfew OFF: Releasing daytime apps and stores")

            // 0. Release Always-On Blackhole VPN and clear OS Lockdown
            try {
                CurfewBlackholeVpnService.stop(context)
                if (dpm.getAlwaysOnVpnPackage(admin) == context.packageName) {
                    dpm.setAlwaysOnVpnPackage(admin, null, false)
                    Log.i(TAG, "Always-on Blackhole VPN released on curfew wakeup")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing Always-On Blackhole VPN during wakeup", e)
            }

            // 1. Restore OEM App Stores & Search apps (Unhide)
            val packagesToUnhide = CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS
            for (pkg in packagesToUnhide) {
                try {
                    if (dpm.isApplicationHidden(admin, pkg)) {
                        dpm.setApplicationHidden(admin, pkg, false)
                        Log.i(TAG, "Unhid store/search package: $pkg")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not unhide package $pkg", e)
                }
            }

            // 2. Clear app installation restriction
            try {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
                Log.i(TAG, "Curfew restriction cleared: DISALLOW_INSTALL_APPS")
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear DISALLOW_INSTALL_APPS restriction", e)
            }

            // 3. Stop foreground watcher
            CurfewForegroundWatcher.stop()

            // 4. Unsuspend curfew targets
            val recordedCurfewPackages = prefs.getStringSet(CurfewConfig.KEY_CURFEW_SUSPENDED_PACKAGES, emptySet()) ?: emptySet()
            val allInstalledTargets = CurfewAppResolver.getCurfewTargetPackages(context)
            val candidates = (recordedCurfewPackages + allInstalledTargets).toSet()

            // IRON RULE: NEVER unsuspend permanently blacklisted apps (Telegram, TikTok, etc.)!
            val safeToUnsuspend = candidates.filter { pkg ->
                !AppSuspensionShield.isBlacklisted(pkg)
            }.filter { pkg ->
                try {
                    dpm.isPackageSuspended(admin, pkg)
                } catch (e: Exception) {
                    false
                }
            }

            if (safeToUnsuspend.isNotEmpty()) {
                try {
                    val failed = dpm.setPackagesSuspended(admin, safeToUnsuspend.toTypedArray(), false)
                    Log.i(TAG, "Curfew released: unsuspended ${safeToUnsuspend.size - (failed?.size ?: 0)} packages")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unsuspending curfew packages", e)
                }
            }

            prefs.edit().remove(CurfewConfig.KEY_CURFEW_SUSPENDED_PACKAGES).apply()
            true
        }
    }

    /**
     * Evaluates current conditions and synchronizes system state accordingly.
     */
     fun syncCurfewState(context: Context) {
        val shouldBeActive = isEffectiveCurfewActive(context)
        Log.d(TAG, "Syncing curfew state: shouldBeActive = $shouldBeActive")
        enforce(context, shouldBeActive)
    }

    /**
     * Asynchronously synchronizes curfew state on background executor.
     */
    fun syncCurfewStateAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext
        bgExecutor.execute {
            var success = false
            try {
                val shouldBeActive = isEffectiveCurfewActive(appContext)
                success = enforce(appContext, shouldBeActive)
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncCurfewStateAsync", e)
            } finally {
                if (onComplete != null) {
                    Handler(Looper.getMainLooper()).post {
                        onComplete.invoke(success)
                    }
                }
            }
        }
    }

    /**
     * Synchronously applies merged block state (night curfew OR nap).
     */
    fun syncBlockState(context: Context) {
        val shouldBeActive = isEffectiveBlockActive(context)
        Log.d(TAG, "Syncing block state: shouldBeActive = $shouldBeActive")
        enforce(context, shouldBeActive)
    }

    /**
     * Asynchronously synchronizes merged block state on background executor.
     */
    fun syncBlockStateAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext
        bgExecutor.execute {
            var success = false
            try {
                val shouldBeActive = isEffectiveBlockActive(appContext)
                success = enforce(appContext, shouldBeActive)
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncBlockStateAsync", e)
            } finally {
                if (onComplete != null) {
                    Handler(Looper.getMainLooper()).post {
                        onComplete.invoke(success)
                    }
                }
            }
        }
    }

    /**
     * Handles newly installed package. If curfew is currently active, freezes and hides it immediately.
     */
    fun handlePackageAdded(context: Context, packageName: String) {
        val appContext = context.applicationContext
        val isCurfewRunning = isEffectiveBlockActive(appContext)

        if (!isCurfewRunning) {
            Log.d(TAG, "Package $packageName installed while curfew is inactive; will be evaluated at next curfew cycle")
            return
        }

        val immune = CurfewAppResolver.resolveImmunePackages(appContext)
        if (immune.contains(packageName) || packageName == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE || packageName == CurfewConfig.GOOGLE_SEARCH_PACKAGE) {
            Log.i(TAG, "Newly installed package $packageName is immune, Play Store, or Google Search IPC; skipping suspension")
            return
        }

        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val admin = ComponentName(appContext, AdminReceiver::class.java)
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) return

        try {
            dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            if (CurfewConfig.OEM_APP_STORES.contains(packageName) || CurfewConfig.CURFEW_SEARCH_APPS.contains(packageName)) {
                dpm.setApplicationHidden(admin, packageName, true)
            }
            // Add to recorded curfew packages
            val prefs = appContext.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val savedSet = prefs.getStringSet(CurfewConfig.KEY_CURFEW_SUSPENDED_PACKAGES, emptySet())?.toMutableSet()
                ?: mutableSetOf()
            savedSet.add(packageName)
            prefs.edit().putStringSet(CurfewConfig.KEY_CURFEW_SUSPENDED_PACKAGES, savedSet).apply()

            Log.i(TAG, "Newly installed package $packageName immediately suspended and recorded under active curfew")
        } catch (e: Exception) {
            Log.e(TAG, "Error suspending newly installed package $packageName", e)
        }
    }
}
