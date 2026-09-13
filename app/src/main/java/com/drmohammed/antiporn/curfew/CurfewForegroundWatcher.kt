package com.drmohammed.antiporn.curfew

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.widget.Toast
import com.drmohammed.antiporn.R
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated high-speed foreground watcher active strictly during night curfew.
 * Prevents user access to Google Play Store UI by instantly redirecting to Home,
 * while allowing Google Play background IPC services (Licensing, Play Games, Billing)
 * to run uninterrupted for whitelisted games (e.g., Phobies, Bullet Echo).
 */
object CurfewForegroundWatcher {

    private const val TAG = "AntiPorn_CurfewWatcher"
    private const val POLL_INTERVAL_MS = 300L
    private const val TOAST_COOLDOWN_MS = 3000L
    private const val INITIAL_LOOKBACK_MS = 5000L

    private val isRunningState = AtomicBoolean(false)
    val isRunning: Boolean get() = isRunningState.get()

    private var executor: ScheduledExecutorService? = null
    private var scheduledTask: ScheduledFuture<*>? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var lastToastTime = 0L

    @Volatile
    private var lastForegroundPackage: String? = null

    @Volatile
    private var lastForegroundClass: String? = null

    @Volatile
    private var lastEventTimestamp: Long = 0L

    /**
     * Determines whether the given activity class belongs to the Gemini AI assistant
     * interface running within Google App (Robin / Assistant surfaces).
     */
    fun isGeminiActivity(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        val lower = className.lowercase()
        return lower.contains(".robin.") || lower.contains(".assistant.")
    }

    /**
     * Verifies if the special PACKAGE_USAGE_STATS AppOp permission is granted to this app.
     */
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
     * Starts the foreground monitoring loop if not already running.
     */
    @Synchronized
    fun start(context: Context) {
        if (isRunningState.compareAndSet(false, true)) {
            val appContext = context.applicationContext
            Log.i(TAG, "Starting CurfewForegroundWatcher for Play Store and Search auto-kickout")

            lastForegroundPackage = null
            lastForegroundClass = null
            lastEventTimestamp = System.currentTimeMillis() - INITIAL_LOOKBACK_MS

            val exec = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "CurfewForegroundWatcherThread").apply { isDaemon = true }
            }
            executor = exec

            scheduledTask = exec.scheduleWithFixedDelay({
                try {
                    checkAndKickout(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in CurfewForegroundWatcher check cycle", e)
                }
            }, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Stops the foreground monitoring loop.
     */
    @Synchronized
    fun stop() {
        if (isRunningState.compareAndSet(true, false)) {
            Log.i(TAG, "Stopping CurfewForegroundWatcher")
            scheduledTask?.cancel(true)
            scheduledTask = null
            executor?.shutdownNow()
            executor = null
            lastForegroundPackage = null
            lastForegroundClass = null
            lastEventTimestamp = 0L
        }
    }

    private fun checkAndKickout(context: Context) {
        if (!CurfewEnforcer.isEffectiveCurfewActive(context)) {
            stop()
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractive == false) {
            // Screen is asleep, skip cycle to preserve battery
            return
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val queryStart = if (lastEventTimestamp > 0L) lastEventTimestamp else (now - INITIAL_LOOKBACK_MS)

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

        if (lastForegroundPackage == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE) {
            Log.w(TAG, "Google Play Store UI detected in foreground during curfew! Kicking out to Home...")
            lastForegroundPackage = null
            lastForegroundClass = null
            kickoutToHome(context, now, R.string.curfew_play_store_blocked)
        } else if (lastForegroundPackage == CurfewConfig.GOOGLE_SEARCH_PACKAGE) {
            if (!isGeminiActivity(lastForegroundClass)) {
                Log.w(
                    TAG,
                    "Google Search / Discover UI detected in foreground ($lastForegroundClass) during curfew! Kicking out to Home..."
                )
                lastForegroundPackage = null
                lastForegroundClass = null
                kickoutToHome(context, now, R.string.curfew_google_search_blocked)
            }
        }
    }

    private fun kickoutToHome(context: Context, now: Long, toastMsgResId: Int) {
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

        if (now - lastToastTime > TOAST_COOLDOWN_MS) {
            lastToastTime = now
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
