package com.drmohammed.antiporn.ai.core.pipeline

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.drmohammed.antiporn.accessibility.bus.ForegroundAppEventBus
import com.drmohammed.antiporn.ai.core.policy.AiExemptionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * مراقب السياق وصمام الأمان الهجين لحماية شاشة الذكاء الاصطناعي.
 * - عند اتصال [HisnAccessibilityService]: يستقبل التغيرات لحظياً في 0ms عبر [ForegroundAppEventBus]
 *   مع إيقاف حلقة الفحص الدوري تماماً (Zero-Polling).
 * - عند انقطاع خدمة الوصول: ينشط كـ Fallback احتياطي عبر [UsageStatsManager] كل 1000ms.
 */
class ContextualGatingWatcher(
    context: Context,
    private val checkIntervalMs: Long = 1000L,
    private val onStateChanged: ((Boolean, String) -> Unit)? = null
) {
    init {
        require(checkIntervalMs >= 500L) { "checkIntervalMs must be >= 500ms" }
    }

    private val context = context.applicationContext
    private var watcherThread: HandlerThread? = null
    private var handler: Handler? = null
    private val usageStatsManager = this.context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val eventBus = ForegroundAppEventBus.getInstance(this.context)

    private var watcherScope: CoroutineScope? = null
    private var busCollectorJob: Job? = null
    private var stateCollectorJob: Job? = null

    @Volatile
    var isTargetAppForeground: Boolean = false
        private set

    @Volatile
    var currentForegroundPackage: String = ""
        private set

    @Volatile
    var lastQueryFailed: Boolean = false
        private set

    @Volatile
    var lastQueryTimeMs: Long = 0L
        private set

    @Volatile
    private var isRunning = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            // قانون الصفر بولينج: إذا كانت خدمة الوصول نشطة، يتوقف الفحص الدوري تماماً
            if (eventBus.isAccessibilityActive.value) {
                return
            }

            evaluateForegroundPackage()
            handler?.postDelayed(this, checkIntervalMs)
        }
    }

    companion object {
        private const val TAG = "ContextualGatingWatcher"
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true

        val scope = CoroutineScope(Dispatchers.Default)
        watcherScope = scope

        // مراقبة تفاعلية لحالة خدمة الوصول المركزية
        stateCollectorJob = scope.launch {
            eventBus.isAccessibilityActive.collect { active ->
                if (active) {
                    handler?.removeCallbacks(checkRunnable)
                    Timber.tag(TAG).d("HisnAccessibilityService connected: AI Gating loop stopped (Zero-Polling Active)")
                    val currentPkg = eventBus.activePackage.value
                    if (!currentPkg.isNullOrBlank() && isRunning) {
                        evaluatePackageDirectly(currentPkg)
                    }
                } else if (isRunning) {
                    Timber.tag(TAG).d("HisnAccessibilityService disconnected: Starting AI Gating fallback loop")
                    handler?.removeCallbacks(checkRunnable)
                    handler?.post(checkRunnable)
                }
            }
        }

        // استقبال أحداث التطبيق النشط اللحظية من الناقل المركزي في 0ms
        busCollectorJob = scope.launch {
            eventBus.activePackage.collect { pkg ->
                if (!pkg.isNullOrBlank() && eventBus.isAccessibilityActive.value && isRunning) {
                    evaluatePackageDirectly(pkg)
                }
            }
        }

        val thread = HandlerThread("AiGatingWatcher", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        watcherThread = thread
        val h = Handler(thread.looper)
        handler = h

        if (!eventBus.isAccessibilityActive.value) {
            h.post(checkRunnable)
            Timber.tag(TAG).d("Contextual gating watcher started in fallback mode on background HandlerThread")
        } else {
            Timber.tag(TAG).d("Contextual gating watcher started in dormant event-driven mode (0ms Active)")
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        handler?.removeCallbacks(checkRunnable)
        handler = null
        watcherThread?.quitSafely()
        watcherThread = null

        busCollectorJob?.cancel()
        busCollectorJob = null
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        watcherScope?.cancel()
        watcherScope = null

        isTargetAppForeground = false
        currentForegroundPackage = ""
        Timber.tag(TAG).d("Contextual gating watcher stopped")
    }

    @Synchronized
    private fun evaluatePackageDirectly(pkg: String) {
        lastQueryFailed = false
        lastQueryTimeMs = System.currentTimeMillis()

        if (pkg != currentForegroundPackage) {
            currentForegroundPackage = pkg
            val isTarget = try {
                isTargetPackage(pkg)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Policy check failed, fail-closed")
                true
            }

            if (isTarget != isTargetAppForeground) {
                isTargetAppForeground = isTarget
                Timber.tag(TAG).d("Foreground changed (0ms A11y) -> Target: $isTarget (pkg: $pkg)")
                onStateChanged?.invoke(isTarget, pkg)
            }
        }
    }

    private fun evaluateForegroundPackage() {
        val usm = usageStatsManager ?: run {
            failClosed("usageStatsManager null")
            return
        }
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(now - (checkIntervalMs * 3), now)
        } catch (e: SecurityException) {
            failClosed("usage permission revoked: ${e.message}")
            return
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to query usage events")
            lastQueryFailed = true
            null
        } ?: run {
            failClosed("null events")
            return
        }
        lastQueryFailed = false
        lastQueryTimeMs = now

        var latestEventPackage = ""
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            val isForeground = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (isForeground) {
                latestEventPackage = event.packageName ?: ""
            }
        }

        if (latestEventPackage.isEmpty()) {
            if (currentForegroundPackage.isNotEmpty()) {
                return
            }
            failClosed("empty foreground signal")
            return
        }

        evaluatePackageDirectly(latestEventPackage)
    }

    /** Fail CLOSED (keep scanning) — safe direction for a protection feature. */
    private fun failClosed(reason: String) {
        Timber.tag(TAG).w("Gating fail-closed: $reason")
        lastQueryFailed = true
        if (currentForegroundPackage == context.packageName) {
            if (isTargetAppForeground) {
                isTargetAppForeground = false
                onStateChanged?.invoke(false, currentForegroundPackage)
            }
            return
        }
        if (!isTargetAppForeground) {
            isTargetAppForeground = true
            onStateChanged?.invoke(true, currentForegroundPackage)
        }
    }

    private fun isTargetPackage(pkg: String): Boolean {
        if (pkg == context.packageName) return false
        if (AiExemptionRegistry.isExempt(pkg)) return false

        val policy = try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to load policy for AI gating target check")
            null
        }

        if (policy != null && policy.customAiScanPackages.contains(pkg)) {
            return true
        }

        return AiExemptionRegistry.isTarget(pkg)
    }
}
