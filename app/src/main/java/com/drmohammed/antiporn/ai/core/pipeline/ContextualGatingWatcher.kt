package com.drmohammed.antiporn.ai.core.pipeline

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.drmohammed.antiporn.ai.core.policy.AiExemptionRegistry

/**
 * مراقب السياق وصمام الأمان القائم على UsageStatsManager
 * يفحص التطبيق النشط في الواجهة بشكل دوري خفيف ويحدث راية [isTargetAppForeground]
 * لإبقاء خط المعالجة في سبات تام في التطبيقات الآمنة
 */
class ContextualGatingWatcher(
    private val context: Context,
    private val checkIntervalMs: Long = 1000L,
    private val onStateChanged: ((Boolean, String) -> Unit)? = null
) {
    init {
        // MINOR: non-positive interval makes a zero/negative query window.
        require(checkIntervalMs >= 500L) { "checkIntervalMs must be >= 500ms" }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    @Volatile
    var isTargetAppForeground: Boolean = false
        private set

    @Volatile
    var currentForegroundPackage: String = ""
        private set

    /** True when the last UsageStats query failed / was denied — lets UI distinguish dead vs idle. */
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
            evaluateForegroundPackage()
            handler.postDelayed(this, checkIntervalMs)
        }
    }

    companion object {
        private const val TAG = "ContextualGatingWatcher"
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        handler.post(checkRunnable)
        Log.d(TAG, "Contextual gating watcher started")
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(checkRunnable)
        isTargetAppForeground = false
        currentForegroundPackage = ""
        Log.d(TAG, "Contextual gating watcher stopped")
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
            // CRITICAL(C5): permission revoked → fail CLOSED (keep scanning), never silent fail-open.
            failClosed("usage permission revoked: ${e.message}")
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query usage events", e)
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
            val isForeground = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (isForeground) {
                latestEventPackage = event.packageName ?: ""
            }
        }

        // CRITICAL(C5): empty signal (denied/empty) must fail CLOSED, not freeze fail-open.
        if (latestEventPackage.isEmpty()) {
            failClosed("empty foreground signal")
            return
        }

        if (latestEventPackage != currentForegroundPackage) {
            currentForegroundPackage = latestEventPackage
            // MAJOR(M17): AppPolicyRegistry delegates run on main looper — never let one throw kill watcher.
            val isTarget = try {
                isTargetPackage(latestEventPackage)
            } catch (e: Exception) {
                Log.e(TAG, "Policy check failed, fail-closed", e)
                true
            }

            if (isTarget != isTargetAppForeground) {
                isTargetAppForeground = isTarget
                Log.d(TAG, "Foreground changed -> Target: $isTarget")
                onStateChanged?.invoke(isTarget, latestEventPackage)
            }
        }
    }

    /** Fail CLOSED (keep scanning) — safe direction for a protection feature. */
    private fun failClosed(reason: String) {
        Log.w(TAG, "Gating fail-closed: $reason")
        lastQueryFailed = true
        if (!isTargetAppForeground) {
            isTargetAppForeground = true
            onStateChanged?.invoke(true, currentForegroundPackage)
        }
    }

    private fun isTargetPackage(pkg: String): Boolean {
        // إذا كان تطبيقنا نفسه فهو آمن (شاشة التجربة)
        if (pkg == context.packageName) return false

        // التفويض الكامل إلى سجل الاستثناءات المعياري: الهدف يغلب الإعفاء
        if (AiExemptionRegistry.isTarget(pkg)) return true
        if (AiExemptionRegistry.isExempt(pkg)) return false

        // في الوضع الافتراضي، نعتبر التطبيقات الأخرى آمنة للحفاظ على البطارية
        return false
    }
}
