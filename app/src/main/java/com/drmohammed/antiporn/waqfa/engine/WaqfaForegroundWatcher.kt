package com.drmohammed.antiporn.waqfa.engine

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import timber.log.Timber

/**
 * مراقب التطبيق النشط في الواجهة لنظام «وَقْفَة».
 * يعمل بنمط النبض الخفيف (400ms) حصراً أثناء إضاءة الشاشة (SCREEN_ON)،
 * ويتوقف تماماً فور إطفاء الشاشة لتوفير 100% من البطارية.
 */
class WaqfaForegroundWatcher(
    private val context: Context,
    private val checkIntervalMs: Long = 400L
) {
    private val handlerThread = HandlerThread("WaqfaWatcherThread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    @Volatile
    private var isRunning = false

    @Volatile
    private var isScreenOn = false

    @Volatile
    private var lastForegroundPackage: String = ""

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || !isScreenOn) return
            val config = WaqfaSessionManager.getInstance(context).getConfig()
            if (!config.isEnabled || config.targetPackages.isEmpty()) {
                Timber.tag(TAG).d("Waqfa is disabled or has no targets; pausing foreground watcher loop")
                return
            }
            evaluateForegroundPackage(config)
            handler.postDelayed(this, checkIntervalMs)
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = pm?.isInteractive ?: false
        isScreenOn = interactive
        if (!interactive) {
            Log.i(TAG, "WaqfaForegroundWatcher started in standby: screen is not interactive")
            return
        }
        handler.removeCallbacks(checkRunnable)
        handler.post(checkRunnable)
        Log.i(TAG, "WaqfaForegroundWatcher started (interval: ${checkIntervalMs}ms)")
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(checkRunnable)
        lastForegroundPackage = ""
        Log.i(TAG, "WaqfaForegroundWatcher stopped")
    }

    fun notifyConfigChanged() {
        if (isRunning && isScreenOn) {
            handler.removeCallbacks(checkRunnable)
            val config = WaqfaSessionManager.getInstance(context).getConfig()
            if (config.isEnabled && config.targetPackages.isNotEmpty()) {
                handler.post(checkRunnable)
            }
        }
    }

    fun onScreenStateChanged(screenOn: Boolean) {
        isScreenOn = screenOn
        if (screenOn) {
            if (isRunning) {
                handler.removeCallbacks(checkRunnable)
                handler.post(checkRunnable)
            }
        } else {
            handler.removeCallbacks(checkRunnable)
        }
        Log.d(TAG, "WaqfaForegroundWatcher screen state: screenOn=$screenOn")
    }

    private fun evaluateForegroundPackage(config: com.drmohammed.antiporn.waqfa.model.WaqfaConfig) {
        val usm = usageStatsManager ?: return
        if (!config.isEnabled || config.targetPackages.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(now - (checkIntervalMs * 3), now)
        } catch (e: Exception) {
            Log.w(TAG, "Error querying usage events in WaqfaForegroundWatcher", e)
            null
        } ?: return

        var latestForeground = ""
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            val isForeground = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (isForeground) {
                val pkg = event.packageName ?: ""
                if (pkg.isNotBlank()) {
                    latestForeground = pkg
                }
            }
        }

        if (latestForeground.isNotBlank()) {
            if (latestForeground != lastForegroundPackage) {
                lastForegroundPackage = latestForeground
                WaqfaInterceptor.evaluateForegroundPackage(context, latestForeground, isPackageChanged = true)
            } else if (config.targetPackages.contains(latestForeground)) {
                // استمرار التحقق من الجلسة حتى مع بقاء نفس التطبيق دون إطلاق Coroutines متكررة
                WaqfaInterceptor.evaluateForegroundPackage(context, latestForeground, isPackageChanged = false)
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WaqfaWatcher"

        @Volatile
        private var instance: WaqfaForegroundWatcher? = null

        fun getInstance(context: Context): WaqfaForegroundWatcher {
            return instance ?: synchronized(this) {
                instance ?: WaqfaForegroundWatcher(context.applicationContext).also { instance = it }
            }
        }
    }
}
