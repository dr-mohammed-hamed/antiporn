package com.drmohammed.antiporn.ai.core.pipeline

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.drmohammed.antiporn.ai.core.policy.AiExemptionRegistry
import timber.log.Timber

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

    private var watcherThread: HandlerThread? = null
    private var handler: Handler? = null
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
        val thread = HandlerThread("AiGatingWatcher", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        watcherThread = thread
        val h = Handler(thread.looper)
        handler = h
        h.post(checkRunnable)
        Timber.tag(TAG).d("Contextual gating watcher started on background HandlerThread")
    }

    @Synchronized
    fun stop() {
        isRunning = false
        handler?.removeCallbacks(checkRunnable)
        handler = null
        watcherThread?.quitSafely()
        watcherThread = null
        isTargetAppForeground = false
        currentForegroundPackage = ""
        Timber.tag(TAG).d("Contextual gating watcher stopped")
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
            // Fail-closed security policy: permission revoked -> keep scanning, never silent fail-open.
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
            val isForeground = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (isForeground) {
                latestEventPackage = event.packageName ?: ""
            }
        }

        // إذا لم توجد أحداث تنقل جديدة ولكن لدينا تطبيق نشط مسجل مسبقاً، نحافظ على حالته لتفادي إغراق السجل
        if (latestEventPackage.isEmpty()) {
            if (currentForegroundPackage.isNotEmpty()) {
                return
            }
            failClosed("empty foreground signal")
            return
        }

        if (latestEventPackage != currentForegroundPackage) {
            currentForegroundPackage = latestEventPackage
            // Defensive resilience: AppPolicyRegistry delegates run on background thread — never let one throw kill watcher.
            val isTarget = try {
                isTargetPackage(latestEventPackage)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Policy check failed, fail-closed")
                true
            }

            if (isTarget != isTargetAppForeground) {
                isTargetAppForeground = isTarget
                Timber.tag(TAG).d("Foreground changed -> Target: $isTarget (pkg: $latestEventPackage)")
                onStateChanged?.invoke(isTarget, latestEventPackage)
            }
        }
    }

    /** Fail CLOSED (keep scanning) — safe direction for a protection feature. */
    private fun failClosed(reason: String) {
        Timber.tag(TAG).w("Gating fail-closed: $reason")
        lastQueryFailed = true
        if (!isTargetAppForeground) {
            isTargetAppForeground = true
            onStateChanged?.invoke(true, currentForegroundPackage)
        }
    }

    private fun isTargetPackage(pkg: String): Boolean {
        // إذا كان تطبيقنا نفسه فهو آمن (شاشة التجربة)
        if (pkg == context.packageName) return false

        // تطبيقات النظام النظيفة المعفاة حصراً (لانشر، إعدادات، هاتف، ساعة، آلة حاسبة)
        if (AiExemptionRegistry.isExempt(pkg)) return false

        val policy = try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to load policy for AI gating target check")
            null
        }

        // إذا كان التطبيق مخصصاً يدوياً من المستخدم/الوصي للمسح بالذكاء الاصطناعي
        if (policy != null && policy.customAiScanPackages.contains(pkg)) {
            return true
        }

        // الاستهداف القياسي: المتصفحات المقيدة وشبكات التواصل والمتاجر
        return AiExemptionRegistry.isTarget(pkg)
    }
}
