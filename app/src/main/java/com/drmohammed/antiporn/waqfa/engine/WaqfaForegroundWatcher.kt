package com.drmohammed.antiporn.waqfa.engine

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import com.drmohammed.antiporn.accessibility.bus.ForegroundAppEventBus
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * مراقب التطبيق النشط في الواجهة لنظام «وَقْفَة».
 * يعمل بنمط هجين ذكي متوافق تماماً مع المادة 8 من الدستور (قانون الصفر بولينج):
 * - طالما أن [HisnAccessibilityService] متصلة ونشطة: يدخل في سبات تام (0% CPU / Zero Polling).
 * - في حال انقطاع الخدمة: يحاول أولاً الإفاقة الصامتة (مع مهلة تبريد 15 ثانية)،
 *   ثم ينشط كـ Fallback احتياطي بمعدل 400ms حصراً أثناء إضاءة الشاشة.
 */
class WaqfaForegroundWatcher(
    context: Context,
    private val checkIntervalMs: Long = 400L
) {
    private val context = context.applicationContext
    private val handlerThread = HandlerThread("WaqfaWatcherThread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val usageStatsManager = this.context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val eventBus = ForegroundAppEventBus.getInstance(this.context)

    private var watcherScope: CoroutineScope? = null
    private var stateCollectorJob: Job? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var isScreenOn = false

    @Volatile
    private var lastForegroundPackage: String = ""

    @Volatile
    private var lastHealingAttemptTimeMs: Long = 0L

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || !isScreenOn) return

            // قانون الصفر بولينج: إذا كانت خدمة الوصول نشطة، يتوقف الفحص الدوري تماماً
            if (eventBus.isAccessibilityActive.value) {
                Timber.tag(TAG).d("HisnAccessibilityService is active; WaqfaForegroundWatcher dormant (Zero-Polling)")
                return
            }

            // محاولة الإفاقة الذاتية الصامتة مع مهلة تبريد 15 ثانية
            val now = SystemClock.elapsedRealtime()
            if (now - lastHealingAttemptTimeMs >= SELF_HEALING_COOLDOWN_MS) {
                lastHealingAttemptTimeMs = now
                if (AccessibilitySelfActivator.enableSilently(context)) {
                    Timber.tag(TAG).i("Self-healing triggered HisnAccessibilityService activation; holding polling loop")
                    handler.postDelayed(this, checkIntervalMs * 2)
                    return
                }
            }

            val config = WaqfaSessionManager.getInstance(context).getConfig()
            if (!config.isEnabled || config.targetPackages.isEmpty()) {
                Timber.tag(TAG).d("Waqfa is disabled or has no targets; pausing foreground watcher loop")
                return
            }

            evaluateForegroundPackage(config)
            handler.postDelayed(this, checkIntervalMs)
        }
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true

        stateCollectorJob?.cancel()
        stateCollectorJob = null
        watcherScope?.cancel()

        val scope = CoroutineScope(Dispatchers.Default)
        watcherScope = scope

        // مراقبة تفاعلية لحالة خدمة الوصول المركزية
        stateCollectorJob = scope.launch {
            eventBus.isAccessibilityActive.collect { active ->
                if (active) {
                    handler.removeCallbacks(checkRunnable)
                    Timber.tag(TAG).i("HisnAccessibilityService connected: Waqfa polling stopped (Zero-Polling Active)")
                } else if (isRunning && isScreenOn) {
                    Timber.tag(TAG).w("HisnAccessibilityService disconnected: Activating Waqfa fallback polling loop")
                    handler.removeCallbacks(checkRunnable)
                    handler.post(checkRunnable)
                }
            }
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = pm?.isInteractive ?: false
        isScreenOn = interactive

        if (!interactive) {
            Timber.tag(TAG).i("WaqfaForegroundWatcher started in standby: screen is not interactive")
            return
        }

        if (!eventBus.isAccessibilityActive.value) {
            handler.removeCallbacks(checkRunnable)
            handler.post(checkRunnable)
            Timber.tag(TAG).i("WaqfaForegroundWatcher fallback started (interval: ${checkIntervalMs}ms)")
        } else {
            Timber.tag(TAG).i("WaqfaForegroundWatcher started in dormant state (Accessibility 0ms active)")
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        handler.removeCallbacks(checkRunnable)
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        watcherScope?.cancel()
        watcherScope = null
        lastForegroundPackage = ""
        Timber.tag(TAG).i("WaqfaForegroundWatcher stopped")
    }

    fun notifyConfigChanged() {
        if (isRunning && isScreenOn && !eventBus.isAccessibilityActive.value) {
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
            if (isRunning && !eventBus.isAccessibilityActive.value) {
                handler.removeCallbacks(checkRunnable)
                handler.post(checkRunnable)
            }
        } else {
            handler.removeCallbacks(checkRunnable)
        }
        Timber.tag(TAG).d("WaqfaForegroundWatcher screen state: screenOn=$screenOn, a11yActive=${eventBus.isAccessibilityActive.value}")
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
            Timber.tag(TAG).w(e, "Error querying usage events in WaqfaForegroundWatcher")
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
            lastForegroundPackage = latestForeground
            eventBus.emitFromFallbackWatcher(latestForeground)
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WaqfaWatcher"
        private const val SELF_HEALING_COOLDOWN_MS = 15_000L // 15 ثانية

        @Volatile
        private var instance: WaqfaForegroundWatcher? = null

        fun getInstance(context: Context): WaqfaForegroundWatcher {
            return instance ?: synchronized(this) {
                instance ?: WaqfaForegroundWatcher(context.applicationContext).also { instance = it }
            }
        }
    }
}
