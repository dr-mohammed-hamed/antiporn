package com.drmohammed.antiporn.telemetry

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.entity.DiagnosticEventEntity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Enterprise In-App Black Box Flight Recorder.
 * Provides a clean, decoupled, and strictly non-blocking asynchronous event logging engine
 * capturing critical operational events across Curfew, Cloud Sync, Identity Migrations,
 * SNTP Time Shifts, DPM Restrictions, and Gate Access.
 */
object BlackBoxRecorder {

    private const val TAG = "AntiPorn_BlackBox"
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val eventCounter = AtomicInteger(0)
    private const val CAP_CHECK_INTERVAL = 100 // Enforce max row cap every 100 events

    // Standardized Architectural Categories
    const val CATEGORY_ALL = "ALL"
    const val TAG_CURFEW = "CURFEW"
    const val TAG_CLOUD_SYNC = "CLOUD_SYNC"
    const val TAG_IDENTITY = "IDENTITY"
    const val TAG_TIME = "TIME"
    const val TAG_DPM = "DPM"
    const val TAG_SHIELD = "SHIELD"
    const val TAG_GATE = "GATE"
    const val TAG_PAIRING = "PAIRING"
    const val TAG_CRASH = "CRASH"
    const val TAG_WAQFA = "WAQFA"
    const val TAG_A11Y = "A11Y"

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
        recorderScope.launch {
            try {
                val timestamp = TrustedTimeProvider.currentTimeMillis(appContext)
                val dao = HisnRoomDatabase.getInstance(appContext).diagnosticEventDao()
                val event = DiagnosticEventEntity(
                    timestamp = timestamp,
                    tag = tag,
                    actionName = actionName,
                    executionTimeMs = executionTimeMs,
                    isSuccess = isSuccess,
                    errorDetails = errorDetails,
                    contextMetadata = metadata
                )
                dao.insertDiagnosticEvent(event)

                // Periodically enforce the 5,000 max row cap
                if (eventCounter.incrementAndGet() % CAP_CHECK_INTERVAL == 0) {
                    val count = dao.getEventsCount()
                    if (count > 5000) {
                        dao.deleteOldestExcess((count - 5000).toInt())
                    }
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

    fun recordShield(
        context: Context,
        shieldId: String,
        isSuccess: Boolean = true,
        details: String? = null,
        error: String? = null
    ) {
        record(context, TAG_SHIELD, shieldId, isSuccess, 0L, error, details)
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

    fun recordWaqfa(
        context: Context,
        action: String,
        isSuccess: Boolean = true,
        executionTimeMs: Long = 0L,
        details: String? = null,
        error: String? = null
    ) {
        record(context, TAG_WAQFA, action, isSuccess, executionTimeMs, error, details)
    }

    fun recordA11y(
        context: Context,
        action: String,
        isSuccess: Boolean = true,
        executionTimeMs: Long = 0L,
        details: String? = null,
        error: String? = null
    ) {
        record(context, TAG_A11Y, action, isSuccess, executionTimeMs, error, details)
    }
}
