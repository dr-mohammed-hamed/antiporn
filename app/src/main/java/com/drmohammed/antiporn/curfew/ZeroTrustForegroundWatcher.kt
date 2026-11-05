package com.drmohammed.antiporn.curfew

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated high-speed foreground watcher active STRICTLY and CONDITIONALLY when
 * unapproved Zero-Trust applications exist in the local SQLite database waiting for supervisor permission.
 *
 * Battery-Protection Guarantees:
 * 1. ZERO BACKGROUND POLLING: If no applications are in STATUS_PENDING, the 1s loop is completely STOPPED,
 *    its executor is shut down, and it consumes 0 CPU cycles.
 * 2. Active only when a package is installed and pending approval, until approved or rejected.
 * 3. Screen-off gate: If screen is turned off (powerManager.isInteractive == false), cycles are skipped.
 * 4. System Whitelist: Never targets default launcher, keyboards (IME), dialer, SMS, or system apps.
 */
object ZeroTrustForegroundWatcher {

    private const val TAG = "AntiPorn_ZeroTrustWatcher"
    private const val POLL_INTERVAL_MS = 1000L // 1 ثانية لتخفيف استهلاك المعالج
    private const val TOAST_COOLDOWN_MS = 2500L
    private const val INITIAL_LOOKBACK_MS = 60000L // 60s lookback on startup
    private const val CACHE_REFRESH_INTERVAL_MS = 3000L

    private val isRunningState = AtomicBoolean(false)
    val isRunning: Boolean get() = isRunningState.get()

    private var executor: ScheduledExecutorService? = null
    private var scheduledTask: ScheduledFuture<*>? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var lastToastTime = 0L

    @Volatile
    private var lastCacheRefreshTime = 0L

    @Volatile
    private var pendingBlockedPackagesCache: Set<String> = emptySet()

    @Volatile
    private var lastForegroundPackage: String? = null

    @Volatile
    private var lastForegroundClass: String? = null

    @Volatile
    private var lastEventTimestamp: Long = 0L

    /**
     * Verifies if the special PACKAGE_USAGE_STATS AppOp permission is granted to this app.
     */
    @Suppress("DEPRECATION")
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check usage stats permission", e)
            false
        }
    }

    /**
     * Returns true if there are currently any unapproved applications waiting for guardian approval.
     */
    fun hasPendingApprovals(context: Context): Boolean {
        return getPendingBlockedPackages(context).isNotEmpty()
    }

    /**
     * Retrieves the set of currently unapproved pending packages, cached for 3 seconds to avoid SQLite churn.
     * Strictest system whitelist is enforced: launchers, dialer, keyboards, and immune apps are NEVER blocked.
     */
    fun getPendingBlockedPackages(context: Context, forceRefresh: Boolean = false): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (forceRefresh || (now - lastCacheRefreshTime > CACHE_REFRESH_INTERVAL_MS) || lastCacheRefreshTime == 0L) {
            val appContext = context.applicationContext
            val pending = try {
                ApprovalRepository.getInstance(appContext)
                    .getLocalPendingApprovals()
                    .filter { it.isPending }
                    .map { it.packageName }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching pending approvals from SQLite", e)
                emptyList()
            }

            if (pending.isEmpty()) {
                pendingBlockedPackagesCache = emptySet()
                lastCacheRefreshTime = now
                return emptySet()
            }

            // Exclude self and critical system framework / launcher / dialer / keyboard, and filter uninstalled
            val criticalPackages = CurfewAppResolver.resolveCoreSystemCriticalPackages(appContext)
            val pm = appContext.packageManager
            val updated = pending.filter { pkg ->
                pkg.isNotBlank() &&
                    !criticalPackages.contains(pkg) &&
                    !AppPolicyRegistry.isImmune(pkg) &&
                    isPackageInstalled(pm, pkg)
            }.toSet()

            pendingBlockedPackagesCache = updated
            lastCacheRefreshTime = now
        }
        return pendingBlockedPackagesCache
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking package info for $packageName", e)
            false
        }
    }

    /**
     * Synchronizes watcher state with current pending approvals in SQLite.
     * If there are pending approvals, starts the high-speed watcher.
     * If no pending approvals exist, completely STOPS the watcher and shuts down its thread.
     */
    @Synchronized
    fun syncWatcherState(context: Context) {
        val appContext = context.applicationContext
        val pending = getPendingBlockedPackages(appContext, forceRefresh = true)
        Log.i(TAG, "syncWatcherState: ${pending.size} pending unapproved packages awaiting supervisor ($pending)")
        if (pending.isNotEmpty()) {
            start(appContext)
        } else {
            // BATTERY SHIELD: Complete dormant state when no pending approvals exist
            stop()
        }
    }

    /**
     * Starts the foreground monitoring loop if there are pending unapproved applications.
     */
    @Synchronized
    fun start(context: Context) {
        val appContext = context.applicationContext
        val pending = getPendingBlockedPackages(appContext, forceRefresh = true)
        if (pending.isEmpty()) {
            Log.d(TAG, "Zero pending approvals found; ZeroTrustForegroundWatcher remains dormant (stopped)")
            stop()
            return
        }

        if (!hasUsageStatsPermission(appContext)) {
            Log.w(TAG, "PACKAGE_USAGE_STATS permission not granted; ZeroTrustForegroundWatcher cannot monitor foreground events")
            stop()
            return
        }

        if (isRunningState.compareAndSet(false, true)) {
            Log.i(TAG, "Starting ZeroTrustForegroundWatcher loop (250ms) for ${pending.size} pending packages")
            lastForegroundPackage = null
            lastForegroundClass = null
            lastEventTimestamp = System.currentTimeMillis() - INITIAL_LOOKBACK_MS

            val exec = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "ZeroTrustForegroundWatcherThread").apply { isDaemon = true }
            }
            executor = exec

            scheduledTask = exec.scheduleWithFixedDelay({
                try {
                    checkAndKickout(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in ZeroTrustForegroundWatcher check cycle", e)
                }
            }, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Stops the foreground monitoring loop completely and shuts down its thread pool.
     */
    @Synchronized
    fun stop() {
        if (isRunningState.compareAndSet(true, false)) {
            Log.i(TAG, "ZeroTrustForegroundWatcher entering full dormant state: Stopped and executor released.")
            scheduledTask?.cancel(true)
            scheduledTask = null
            executor?.shutdownNow()
            executor = null
            lastForegroundPackage = null
            lastForegroundClass = null
            lastEventTimestamp = 0L
        }
    }

    /**
     * Evaluates whether the currently foreground package is an unapproved pending package
     * or matches [immediateTargetPackage].
     * Dispatches to [executor] to serialize execution and eliminate race conditions with the scheduled watcher.
     */
    fun evaluateCurrentForegroundApp(context: Context, immediateTargetPackage: String? = null) {
        val appContext = context.applicationContext
        if (!hasUsageStatsPermission(appContext)) return

        val exec = executor
        if (exec != null && !exec.isShutdown) {
            exec.execute {
                try {
                    checkAndKickout(appContext, immediateTargetPackage)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in serialized ZeroTrust check", e)
                }
            }
        } else {
            syncWatcherState(appContext)
            val fallbackExec = executor
            if (fallbackExec != null && !fallbackExec.isShutdown) {
                fallbackExec.execute {
                    try {
                        checkAndKickout(appContext, immediateTargetPackage)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in fallback ZeroTrust check", e)
                    }
                }
            } else if (!immediateTargetPackage.isNullOrBlank()) {
                // If watcher is dormant, dispatch immediate target kickout evaluation on background coroutine
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        checkAndKickout(appContext, immediateTargetPackage)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in one-shot ZeroTrust check", e)
                    }
                }
            }
        }
    }

    private fun checkAndKickout(context: Context, immediateTargetPackage: String? = null) {
        val hasImmediateTarget = !immediateTargetPackage.isNullOrBlank()
        val pending = if (hasImmediateTarget) {
            emptySet()
        } else {
            val queried = getPendingBlockedPackages(context, forceRefresh = false)
            if (queried.isEmpty()) {
                Log.i(TAG, "All pending approvals resolved or cleared; entering full sleep and stopping watcher")
                stop()
                return
            }
            queried
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractive == false) {
            // Screen is asleep, skip cycle to preserve battery
            return
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        if (lastEventTimestamp > now || (lastEventTimestamp > 0L && now - lastEventTimestamp > INITIAL_LOOKBACK_MS * 2)) {
            Log.w(TAG, "Clock shift detected (lastEventTimestamp: $lastEventTimestamp, now: $now). Resetting to now - INITIAL_LOOKBACK_MS.")
            lastEventTimestamp = now - INITIAL_LOOKBACK_MS
        }
        val queryStart = if (lastEventTimestamp > 0L) lastEventTimestamp else (now - INITIAL_LOOKBACK_MS)
        if (queryStart >= now) return

        val events = try {
            usageStatsManager.queryEvents(queryStart, now)
        } catch (e: Exception) {
            Log.w(TAG, "Could not query usage events", e)
            null
        }

        if (events != null) {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        lastForegroundPackage = event.packageName
                        lastForegroundClass = event.className
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (event.packageName == lastForegroundPackage) {
                            lastForegroundPackage = null
                            lastForegroundClass = null
                        }
                    }
                }
            }
            lastEventTimestamp = now
        }

        val fgPkg = lastForegroundPackage
        if (fgPkg != null) {
            if (CurfewAppResolver.isSystemCriticalOrImmune(context, fgPkg)) {
                return
            }

            val isTargetMatch = hasImmediateTarget && fgPkg == immediateTargetPackage
            val isPendingMatch = if (!isTargetMatch) {
                val effectivePending = if (pending.isNotEmpty()) pending else getPendingBlockedPackages(context, forceRefresh = false)
                effectivePending.contains(fgPkg)
            } else true

            if (isTargetMatch || isPendingMatch) {
                Log.w(TAG, "Zero-Trust violation: Unapproved package '$fgPkg' detected in foreground (immediateTarget=$immediateTargetPackage)! Kicking out to Home...")
                lastForegroundPackage = null
                lastForegroundClass = null
                kickoutToHome(context, R.string.toast_app_suspended_by_guardian)
            }
        }
    }

    private fun kickoutToHome(context: Context, toastMsgResId: Int) {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        try {
            context.startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch home intent for kickout", e)
        }

        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastToastTime > TOAST_COOLDOWN_MS) {
            lastToastTime = elapsed
            mainHandler.post {
                try {
                    Toast.makeText(context, toastMsgResId, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to show kickout toast", e)
                }
            }
        }
    }
}
