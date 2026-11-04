package com.drmohammed.antiporn.curfew

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Enforces and releases app suspensions based on the night curfew schedule.
 * Strictly guarantees:
 * 1. 24/7 blacklisted apps (Telegram, TikTok, proxy browsers, custom blacklist) are NEVER unsuspended.
 * 2. Play Store and OEM stores are completely hidden and suspended.
 * 3. Newly installed non-whitelisted apps are immediately frozen if curfew is active.
 */
object CurfewEnforcer {

    private const val TAG = "AntiPorn_CurfewEnforcer"
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private fun isMinuteInWindow(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean {
        return if (startMinute <= endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    /**
     * Determines whether the current Makkah time falls within the curfew window.
     */
    fun isCurfewActive(calendar: Calendar = CurfewConfig.getCurfewCalendar()): Boolean {
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val startMinute = CurfewConfig.START_HOUR * 60 + CurfewConfig.START_MINUTE
        val endMinute = CurfewConfig.END_HOUR * 60 + CurfewConfig.END_MINUTE

        return isMinuteInWindow(minuteOfDay, startMinute, endMinute)
    }

    fun isCurfewActive(context: Context, calendar: Calendar = CurfewConfig.getCurfewCalendar(context)): Boolean {
        if (!CurfewConfig.isCurfewEnabled(context)) return false

        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        val weekdayStart = CurfewConfig.getCurfewStartHour(context) * 60 + CurfewConfig.getCurfewStartMinute(context)
        val weekdayEnd = CurfewConfig.getCurfewEndHour(context) * 60 + CurfewConfig.getCurfewEndMinute(context)

        if (!CurfewConfig.isWeekendCurfewEnabled(context)) {
            return isMinuteInWindow(minuteOfDay, weekdayStart, weekdayEnd)
        }

        val weekendStart = CurfewConfig.getWeekendCurfewStartHour(context) * 60 + CurfewConfig.getWeekendCurfewStartMinute(context)
        val weekendEnd = CurfewConfig.getWeekendCurfewEndHour(context) * 60 + CurfewConfig.getWeekendCurfewEndMinute(context)

        return when (dayOfWeek) {
            Calendar.FRIDAY, Calendar.SATURDAY -> {
                isMinuteInWindow(minuteOfDay, weekendStart, weekendEnd)
            }
            Calendar.THURSDAY -> {
                // Morning window (from Wednesday night) ends at weekdayEnd
                val inMorning = if (weekdayStart > weekdayEnd) {
                    minuteOfDay < weekdayEnd
                } else {
                    minuteOfDay in weekdayStart until weekdayEnd
                }
                // Evening window (weekend night begins) starts at weekendStart
                val inEvening = if (weekendStart > weekendEnd) {
                    minuteOfDay >= weekendStart
                } else {
                    minuteOfDay in weekendStart until weekendEnd
                }
                inMorning || inEvening
            }
            else -> {
                // Sunday, Monday, Tuesday, Wednesday
                isMinuteInWindow(minuteOfDay, weekdayStart, weekdayEnd)
            }
        }
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

    fun isSnoozeActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val snoozeEnd = prefs.getLong(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL, 0L)
        return TrustedTimeProvider.currentTimeMillis(context) < snoozeEnd
    }

    fun getSnoozeEndWall(context: Context): Long {
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL, 0L)
    }

    fun clearSnooze(context: Context) {
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL).apply()
        Log.i(TAG, "Curfew snooze cleared")
    }

    fun snoozeCurfew(context: Context, minutes: Int = 15) {
        val safeMinutes = minutes.coerceIn(1, 60)
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val snoozeEnd = TrustedTimeProvider.currentTimeMillis(context) + (safeMinutes * 60 * 1000L)
        prefs.edit().putLong(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL, snoozeEnd).apply()
        CurfewScheduler.scheduleCurfewSnoozeAlarm(context, snoozeEnd)
        enforce(context, false)
        Log.i(TAG, "Curfew snoozed for $safeMinutes minutes until $snoozeEnd")
    }

    fun isEffectiveCurfewActive(context: Context): Boolean {
        if (!isCurfewFeatureEnabled(context)) return false
        if (isSnoozeActive(context)) return false
        if (isManualTestActive(context)) return true
        if (!CurfewConfig.isCurfewEnabled(context)) return false
        return isCurfewActive(context)
    }

    /**
     * Effective block = night curfew OR active nap. Nap merges into the same engine.
     */
    fun isEffectiveBlockActive(context: Context): Boolean =
        isEffectiveCurfewActive(context) || NapController.isNapActive(context)

    /**
     * Synchronously applies or releases curfew suspensions and store lockdowns.
     * Guaranteed invariant: Cannot release curfew if current time is inside night curfew window.
     */
    @Synchronized
    fun enforce(context: Context, activate: Boolean, calendar: Calendar = CurfewConfig.getCurfewCalendar(context)): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val admin = ComponentName(context, AdminReceiver::class.java)

        if (!com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(context)) {
            Log.w(TAG, "Cannot enforce curfew: Not Device/Profile Owner")
            return false
        }

        // Ironclad Security Invariant: NEVER release block if current time is within
        // scheduled night window OR a nap is still active (unless an explicit snooze is active).
        val isCurfewEffective = CurfewConfig.isCurfewEnabled(context) && isCurfewActive(context, calendar)
        if (!activate && !isSnoozeActive(context) && (isCurfewEffective || NapController.isNapActive(context))) {
            Log.w(TAG, "Security Alert: Block release rejected - night curfew window or active nap still holds!")
            return true
        }

        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)

        // 0. Unified VPN Blackhole & Instant Internet Kill Control
        val instantKill = CurfewConfig.isInstantInternetKillEnabled(context)
        val vpnEnabled = instantKill || (activate && CurfewConfig.isVpnBlackholeEnabled(context))

        if (vpnEnabled) {
            try {
                val currentVpn = dpm.getAlwaysOnVpnPackage(admin)
                val bypassedPackages = CurfewConfig.getVpnBypassedPackages(context)
                val isLockdownActive = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        dpm.isAlwaysOnVpnLockdownEnabled(admin)
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    false
                }
                if (currentVpn != context.packageName || !isLockdownActive) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        dpm.setAlwaysOnVpnPackage(admin, context.packageName, true, bypassedPackages)
                    } else {
                        dpm.setAlwaysOnVpnPackage(admin, context.packageName, true)
                    }
                    Log.i(TAG, "Always-on Blackhole VPN configured to ${context.packageName} with lockdown=true")
                }
                CurfewBlackholeVpnService.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring Always-On Blackhole VPN", e)
            }
        } else {
            try {
                val requiredMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
                if (requiredMode == CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY) {
                    CurfewBlackholeVpnService.syncPolicy(context)
                    if (dpm.getAlwaysOnVpnPackage(admin) == context.packageName) {
                        dpm.setAlwaysOnVpnPackage(admin, context.packageName, false)
                        Log.i(TAG, "Always-on VPN transitioned to DNS-only filtering mode (lockdown=false)")
                    }
                } else {
                    CurfewBlackholeVpnService.forceReleaseVpn(context)
                    if (dpm.getAlwaysOnVpnPackage(admin) == context.packageName) {
                        dpm.setAlwaysOnVpnPackage(admin, null, false)
                        Log.i(TAG, "Always-on Blackhole VPN released")
                    }
                }
                Log.i(TAG, "VPN Blackhole is disabled and network is released.")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing Always-On Blackhole VPN", e)
            }
        }

        // Live synchronize unified protection & curfew notifications (VPN, AppsOnly, or Guardian Lockdown)
        try {
            ProtectionNotificationCoordinator.syncProtectionNotification(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error synchronizing protection notification in enforce()", e)
        }

        return if (activate) {
            val targets = CurfewAppResolver.getCurfewTargetPackages(context)
            Log.i(TAG, "Enforcing Curfew ON for ${targets.size} targeted packages")

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
            // strictly verifying it is NOT in permanent blacklist AND NOT in custom blacklist AND NOT pending/rejected by guardian!
            var fetchPolicyFailed = false
            val customBlacklist = try {
                PolicyRepository.getInstance(context).getCurrentPolicy().customBlacklistPackages
            } catch (e: Exception) {
                Log.w(TAG, "Failed querying customBlacklistPackages during curfew enforcement", e)
                fetchPolicyFailed = true
                emptySet()
            }
            var fetchApprovalsFailed = false
            val unapprovedPackages = try {
                ApprovalRepository.getInstance(context).getLocalPendingApprovals()
                    .filter { !it.isApproved }
                    .map { it.packageName }
                    .toSet()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching pending approvals for curfew enforcement - failing secure", e)
                fetchApprovalsFailed = true
                emptySet()
            }
            val immunePackages = CurfewAppResolver.resolveImmunePackages(context)
            val immuneToUnsuspend = if (fetchPolicyFailed || fetchApprovalsFailed) {
                Log.w(TAG, "Fail-secure active: preserving suspended immune packages because policy blacklist or approvals could not be verified, but ensuring core critical packages remain unsuspended")
                val coreCritical = CurfewAppResolver.resolveCoreSystemCriticalPackages(context)
                coreCritical.filter { pkg ->
                    !AppSuspensionShield.isBlacklisted(pkg) &&
                    try { dpm.isPackageSuspended(admin, pkg) } catch (e: Exception) { false }
                }
            } else {
                immunePackages.filter { pkg ->
                    !AppSuspensionShield.isBlacklisted(pkg) &&
                    !customBlacklist.contains(pkg) &&
                    !unapprovedPackages.contains(pkg) &&
                    try { dpm.isPackageSuspended(admin, pkg) } catch (e: Exception) { false }
                }
            }
            if (immuneToUnsuspend.isNotEmpty()) {
                try {
                    dpm.setPackagesSuspended(admin, immuneToUnsuspend.toTypedArray(), false)
                    Log.i(TAG, "Unsuspended whitelisted/immune packages during curfew: $immuneToUnsuspend")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unsuspending immune packages during curfew", e)
                }
            }

            // Ensure DISALLOW_INSTALL_APPS is NOT active, preserving ADB developer workflow.
            try {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear DISALLOW_INSTALL_APPS restriction", e)
            }

            // 4. Start foreground watcher for instant auto-kickout if Play Store UI is launched
            CurfewForegroundWatcher.start(context)

            // Save set of curfew-suspended packages (only apps actually suspended during this enforcement cycle)
            val savedSet = prefs.getStringSet(CurfewConfig.KEY_CURFEW_SUSPENDED_PACKAGES, emptySet())?.toMutableSet()
                ?: mutableSetOf()
            savedSet.addAll(toSuspend)
            prefs.edit().putStringSet(CurfewConfig.KEY_CURFEW_SUSPENDED_PACKAGES, savedSet).apply()
            true
        } else {
            // Wakeup: Restore daytime apps and stores
            Log.i(TAG, "Enforcing Curfew OFF: Releasing daytime apps and stores")

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
            val immunePackages = CurfewAppResolver.resolveImmunePackages(context)
            val candidates = (recordedCurfewPackages + allInstalledTargets + immunePackages).toSet()

            // Retrieve pending/rejected unapproved packages and custom blacklisted packages (Fail-Secure)
            var fetchApprovalsFailed = false
            val localApprovals = try {
                ApprovalRepository.getInstance(context).getLocalPendingApprovals()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching pending approvals for curfew release - failing secure", e)
                fetchApprovalsFailed = true
                emptyList()
            }
            val approvedPackages = localApprovals.filter { it.isApproved }.map { it.packageName }.toSet()
            val unapprovedPackages = localApprovals.filter { !it.isApproved }.map { it.packageName }.toSet()

            var fetchPolicyFailed = false
            val currentPolicy = try {
                PolicyRepository.getInstance(context).getCurrentPolicy()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching policy for curfew release - failing secure", e)
                fetchPolicyFailed = true
                null
            }
            val customBlacklistPackages = currentPolicy?.customBlacklistPackages ?: emptySet()
            val isZeroTrustActive = currentPolicy?.lockNewInstallsUntilApproved ?: true

            if (fetchApprovalsFailed || fetchPolicyFailed) {
                Log.w(TAG, "Fail-secure active: preserving suspended packages because pending approvals or policy blacklist could not be verified")
                return false
            }

            // IRON RULE: NEVER unsuspend permanently blacklisted apps or unapproved/rejected apps or custom blacklists!
            // Under Zero-Trust, stray/unrecorded candidates must NOT be unsuspended unless explicitly approved, immune/store, or pre-installed system apps.
            val safeToUnsuspend = candidates.filter { pkg ->
                if (AppSuspensionShield.isBlacklisted(pkg)) return@filter false
                if (customBlacklistPackages.contains(pkg)) return@filter false
                if (unapprovedPackages.contains(pkg)) return@filter false

                if (isZeroTrustActive) {
                    val isApproved = approvedPackages.contains(pkg)
                    val isImmuneOrStore = CurfewAppResolver.isSystemCriticalOrImmune(context, pkg) ||
                        immunePackages.contains(pkg) ||
                        pkg == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE ||
                        CurfewConfig.KNOWN_APP_STORES.contains(pkg)
                    val isSystemOrUpdatedApp = try {
                        val pm = context.packageManager
                        val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getApplicationInfo(pkg, 0)
                        }
                        (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                        (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                    } catch (e: Exception) {
                        false
                    }
                    val wasSuspendedByCurfew = recordedCurfewPackages.contains(pkg)
                    if (!isApproved && !isImmuneOrStore && !isSystemOrUpdatedApp && !wasSuspendedByCurfew) {
                        return@filter false
                    }
                }
                true
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
            try {
                ProtectionNotificationCoordinator.syncProtectionNotification(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error synchronizing notification on curfew release", e)
            }
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
    @Synchronized
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
        if (!com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(appContext)) return

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

    /**
     * Rescues and unsuspends any application that was inadvertently stuck in suspended state
     * (e.g. system apps, whitelisted apps, or pre-existing apps) during daytime when no curfew is active.
     */
    fun repairStuckSuspendedApps(context: Context): Int {
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return 0
        val admin = ComponentName(appContext, AdminReceiver::class.java)
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) return 0

        val pm = appContext.packageManager ?: return 0
        val isCurfewRunning = isEffectiveBlockActive(appContext)

        val policy = try {
            PolicyRepository.getInstance(appContext).getCurrentPolicy()
        } catch (e: Exception) {
            null
        }
        val customBlacklist = policy?.customBlacklistPackages ?: emptySet()
        val immunePackages = CurfewAppResolver.resolveImmunePackages(appContext)
        val unapproved = try {
            ApprovalRepository.getInstance(appContext).getLocalPendingApprovals()
                .filter { !it.isApproved }
                .map { it.packageName }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }

        val installed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (e: Exception) {
            emptyList()
        }

        val stuckToUnsuspend = mutableListOf<String>()
        for (app in installed) {
            val pkg = app.packageName
            if (pkg == appContext.packageName) continue
            if (AppSuspensionShield.isBlacklisted(pkg)) continue
            if (customBlacklist.contains(pkg)) continue
            if (unapproved.contains(pkg)) continue

            // If curfew is running, only unsuspend if immune/whitelisted
            if (isCurfewRunning && !immunePackages.contains(pkg)) continue

            val isSuspended = try { dpm.isPackageSuspended(admin, pkg) } catch (e: Exception) { false }
            if (isSuspended) {
                stuckToUnsuspend.add(pkg)
            }
        }

        if (stuckToUnsuspend.isNotEmpty()) {
            try {
                dpm.setPackagesSuspended(admin, stuckToUnsuspend.toTypedArray(), false)
                Log.i(TAG, "🔧 [REPAIR_STUCK_APPS] Successfully unsuspended ${stuckToUnsuspend.size} stuck apps: $stuckToUnsuspend")
            } catch (e: Exception) {
                Log.e(TAG, "Failed repairing stuck apps via DPM", e)
            }
        }
        return stuckToUnsuspend.size
    }
}
