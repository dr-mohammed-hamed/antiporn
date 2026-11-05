package com.drmohammed.antiporn.telemetry

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enterprise In-App Black Box Flight Recorder.
 * Provides a clean, decoupled, and strictly non-blocking asynchronous event logging engine
 * capturing critical operational events across Curfew, Cloud Sync, Identity Migrations,
 * SNTP Time Shifts, DPM Restrictions, and Gate Access.
 */
object BlackBoxRecorder {

    private const val TAG = "AntiPorn_BlackBox"
    private val bgExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BlackBox-Recorder-Thread").apply {
            priority = Thread.MIN_PRIORITY // Run at low priority to never contest UI or audio
            isDaemon = true
        }
    }

    private val eventCounter = AtomicInteger(0)
    private const val CAP_CHECK_INTERVAL = 100 // Enforce max row cap every 100 events

    // Standardized Architectural Categories
    const val CATEGORY_ALL = "ALL"
    const val TAG_CURFEW = "CURFEW"
    const val TAG_CLOUD_SYNC = "CLOUD_SYNC"
    const val TAG_IDENTITY = "IDENTITY"
    const val TAG_TIME = "TIME"
    const val TAG_DPM = "DPM"
    const val TAG_GATE = "GATE"
    const val TAG_PAIRING = "PAIRING"
    const val TAG_CRASH = "CRASH"

    /**
     * Core generic recording dispatch. Executes asynchronously off the main thread.
     */
    fun record(
        context: Context,
        tag: String,
        actionName: String,
        isSuccess: Boolean = true,
        executionTimeMs: Long = 0L,
        errorDetails: String? = null,
        metadata: String? = null
    ) {
        val appContext = context.applicationContext
        bgExecutor.execute {
            try {
                val timestamp = TrustedTimeProvider.currentTimeMillis(appContext)
                val db = AntiPornDatabase.getInstance(appContext)
                db.insertDiagnosticEvent(
                    tag = tag,
                    actionName = actionName,
                    executionTimeMs = executionTimeMs,
                    isSuccess = isSuccess,
                    errorDetails = errorDetails,
                    contextMetadata = metadata,
                    timestamp = timestamp
                )

                // Periodically enforce the 5,000 max row cap
                if (eventCounter.incrementAndGet() % CAP_CHECK_INTERVAL == 0) {
                    db.enforceMaxEventsCap(5000)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing diagnostic event [$tag / $actionName]: ${e.message}")
            }
        }
    }

    // ==========================================
    // Specialized Domain Convenience Helpers
    // ==========================================

    fun recordCurfew(
        context: Context,
        action: String,
        isSuccess: Boolean = true,
        details: String? = null,
        error: String? = null,
        executionTimeMs: Long = 0L
    ) {
        record(context, TAG_CURFEW, action, isSuccess, executionTimeMs, error, details)
    }

    fun recordCloudSync(
        context: Context,
        action: String,
        isSuccess: Boolean = true,
        details: String? = null,
        error: String? = null,
        executionTimeMs: Long = 0L
    ) {
        record(context, TAG_CLOUD_SYNC, action, isSuccess, executionTimeMs, error, details)
    }

    fun recordIdentity(
        context: Context,
        action: String,
        isSuccess: Boolean = true,
        details: String? = null,
        error: String? = null
    ) {
        record(context, TAG_IDENTITY, action, isSuccess, 0L, error, details)
    }

    fun recordTime(
        context: Context,
        action: String,
        isSuccess: Boolean = true,
        details: String? = null,
        error: String? = null
    ) {
        record(context, TAG_TIME, action, isSuccess, 0L, error, details)
    }

    fun recordDpm(
        context: Context,
        action: String,
        latencyMs: Long,
        isSuccess: Boolean = true,
        details: String? = null,
        error: String? = null
    ) {
        record(context, TAG_DPM, action, isSuccess, latencyMs, error, details)
    }

    fun recordGate(
        context: Context,
        action: String,
        isSuccess: Boolean = true,
        details: String? = null,
        error: String? = null
    ) {
        record(context, TAG_GATE, action, isSuccess, 0L, error, details)
    }

    fun recordPairing(
        context: Context,
        action: String,
        isSuccess: Boolean = true,
        details: String? = null,
        error: String? = null,
        executionTimeMs: Long = 0L
    ) {
        record(context, TAG_PAIRING, action, isSuccess, executionTimeMs, error, details)
    }
}
