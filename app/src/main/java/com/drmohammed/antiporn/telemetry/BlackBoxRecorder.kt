package com.drmohammed.antiporn.telemetry

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.entity.DiagnosticEventEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Enterprise In-App Black Box Flight Recorder.
 * Provides a clean, decoupled, and strictly non-blocking asynchronous event logging engine
 * capturing critical operational events across Curfew, Cloud Sync, Identity Migrations,
 * SNTP Time Shifts, DPM Restrictions, and Gate Access.
 */
object BlackBoxRecorder {

    private const val TAG = "AntiPorn_BlackBox"
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingWriteJobs = ConcurrentHashMap.newKeySet<Job>()

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
    const val TAG_NETWORK = "NETWORK"

    private const val HEALTH_PREFS_NAME = "antiporn_blackbox_health_prefs"
    private const val KEY_LAST_HEALTHY_SHIELD_CHECK_MS = "last_healthy_shield_check_ms"
    private const val HEALTH_PERSIST_DEBOUNCE_MS = 60_000L

    @Volatile
    private var lastHealthyCheckWallMs: Long = 0L

    @Volatile
    private var lastHealthPersistElapsedMs: Long = -1L

    private val healthPersistLock = Any()

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
        val appContext = context.applicationContext ?: context
        val job = recorderScope.launch {
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

                // Periodically enforce the 5,000 max row cap (prunes on 1st event of process and every 100 thereafter)
                if (eventCounter.getAndIncrement() % CAP_CHECK_INTERVAL == 0) {
                    val count = dao.getEventsCount()
                    if (count > 5000) {
                        dao.deleteOldestExcess((count - 5000).toInt())
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Failed writing diagnostic event [$tag / $actionName]: ${e.message}")
            }
        }
        if (pendingWriteJobs.add(job)) {
            if (job.isCompleted) {
                pendingWriteJobs.remove(job)
            } else {
                job.invokeOnCompletion { pendingWriteJobs.remove(job) }
            }
        }
    }

    /**
     * Waits briefly for in-flight asynchronous BlackBox SQLite writes to flush before synchronous reads
     * (such as telemetry report generation or unit assertions). Bounded by [timeoutMs].
     */
    fun awaitPendingWritesBlocking(timeoutMs: Long = 1000L) {
        if (pendingWriteJobs.isEmpty()) return
        val startNs = System.nanoTime()
        while (true) {
            val pending = pendingWriteJobs.toList().filter { !it.isCompleted }
            if (pending.isEmpty()) return
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            val remaining = timeoutMs - elapsedMs
            if (remaining <= 0L) return
            try {
                runBlocking {
                    withTimeoutOrNull(remaining) {
                        pending.forEach { it.join() }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Interrupted while awaiting pending BlackBox writes: ${e.message}")
                return
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

    /**
     * Records a physical network lifecycle or capability transition event under [TAG_NETWORK].
     */
    fun recordNetwork(
        context: Context,
        action: String,
        isSuccess: Boolean = true,
        details: String? = null,
        error: String? = null,
        executionTimeMs: Long = 0L
    ) {
        record(context, TAG_NETWORK, action, isSuccess, executionTimeMs, error, details)
    }

    /**
     * Updates the latest healthy shield check timestamp in volatile memory and persists it
     * asynchronously to SharedPreferences when at least [HEALTH_PERSIST_DEBOUNCE_MS] has elapsed,
     * avoiding Room DB row churn on routine healthy checks.
     */
    fun markHealthyShieldCheck(context: Context) {
        val appContext = context.applicationContext ?: context
        val nowWall = TrustedTimeProvider.currentTimeMillis(appContext)
        val nowElapsed = SystemClock.elapsedRealtime()
        val timestampToPersist: Long? = synchronized(healthPersistLock) {
            val updatedWall = maxOf(lastHealthyCheckWallMs, nowWall)
            lastHealthyCheckWallMs = updatedWall
            if (lastHealthPersistElapsedMs < 0L || nowElapsed - lastHealthPersistElapsedMs >= HEALTH_PERSIST_DEBOUNCE_MS) {
                lastHealthPersistElapsedMs = nowElapsed
                updatedWall
            } else {
                null
            }
        }

        if (timestampToPersist != null) {
            try {
                appContext.getSharedPreferences(HEALTH_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_HEALTHY_SHIELD_CHECK_MS, timestampToPersist)
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed persisting healthy shield check timestamp: ${e.message}")
            }
        }
    }

    /**
     * Returns the most recent healthy shield verification timestamp in milliseconds,
     * taking the maximum of in-memory volatile state and SharedPreferences state.
     */
    fun getLastHealthyShieldCheckTimestamp(context: Context): Long {
        val appContext = context.applicationContext ?: context
        val persisted = try {
            appContext.getSharedPreferences(HEALTH_PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_HEALTHY_SHIELD_CHECK_MS, 0L)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading healthy shield check timestamp: ${e.message}")
            0L
        }
        return maxOf(lastHealthyCheckWallMs, persisted)
    }
}
