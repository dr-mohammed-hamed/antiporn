package com.drmohammed.antiporn.core.time

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.curfew.CurfewConfig
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable value object capturing an anchored time measurement.
 *
 * @property trueTimeMs Network-verified or anti-rollback anchored epoch time in milliseconds.
 * @property elapsedMs Monotonic hardware [SystemClock.elapsedRealtime] reference at anchor establishment.
 * @property isStrictlyTrusted True if anchored directly from an authenticated NTP/HTTP network response in current boot.
 * @property isDegradedOffline True if advancing monotonically across an offline reboot using wall-clock delta heuristics.
 */
data class TimeAnchor(
    val trueTimeMs: Long,
    val elapsedMs: Long,
    val isStrictlyTrusted: Boolean,
    val isDegradedOffline: Boolean
)

/**
 * Singleton provider of tamper-proof, monotonic, network-anchored trusted time.
 *
 * Anti-Tampering & Security Guarantees:
 * 1. Monotonic Hardware Clock Reference:
 *    Calculates true time via [SystemClock.elapsedRealtime] anchored against verified SNTP/HTTP
 *    timestamps. User adjustments to the system wall clock in Android Settings have zero effect.
 *
 * 2. Atomic Lock-Free Time Reading:
 *    In-memory anchor state is held inside an [AtomicReference], ensuring zero torn-reads
 *    between trueTime and elapsedRealtime and non-blocking O(1) performance.
 *
 * 3. Strict Anti-Rollback & Offline Morning Wakeup Heuristic:
 *    Across offline reboots without internet access, effective time advances safely from
 *    [lastKnownTrueTimeMs] using bounded wall-clock delta math (capped at 48 hours),
 *    preventing morning lockouts after overnight shutdowns while maintaining strict anti-rollback.
 *
 * 4. TimeZone Anti-Spoofing:
 *    Maintains a policy-verified [trustedTimeZoneId]. Curfew calendar calculations
 *    strictly reference this timezone, preventing bypasses achieved by altering device timezone.
 *
 * 5. Drift Correction Rescheduling:
 *    Notifies listeners via [onTimeDriftCorrected] whenever network synchronization corrects
 *    clock drift by more than 60 seconds, allowing instant realignment of system alarms.
 */
object TrustedTimeProvider {

    private const val TAG = "AntiPorn_TrustedTime"

    /** SharedPreferences file storing trusted time metadata. */
    const val PREFS_NAME = "antiporn_trusted_time_prefs"

    /** SharedPreferences keys. */
    const val KEY_ANCHOR_TIME = "anchor_time"
    const val KEY_ANCHOR_ELAPSED = "anchor_elapsed"
    const val KEY_LAST_KNOWN_TRUE_TIME = "last_known_true_time"
    const val KEY_LAST_ELAPSED = "last_elapsed"
    const val KEY_LAST_SAVED_WALL_TIME = "last_saved_wall_time"
    const val KEY_BOOT_COUNT = "boot_count"
    const val KEY_TRUSTED_TIME_ZONE_ID = "trusted_time_zone_id"
    const val KEY_HAS_EVER_SYNCED = "has_ever_synced"
    const val KEY_OFFLINE_ESTIMATED_TIME = "offline_estimated_time"

    /** Minimum interval between successful network syncs to debounce callbacks (15 minutes). */
    const val MIN_SYNC_INTERVAL_MS = 15 * 60 * 1000L

    /** Debounce interval for persisting advancing lastKnownTrueTime to disk (60 seconds). */
    const val DEBOUNCE_PERSIST_MS = 60 * 1000L

    /** Maximum allowed offline advance using wall clock heuristics (48 hours). */
    const val MAX_OFFLINE_ADVANCE_MS = 48 * 3600 * 1000L

    /** Minimum drift correction threshold to trigger alarm rescheduling (60 seconds). */
    const val DRIFT_CORRECTION_THRESHOLD_MS = 60 * 1000L

    /** Default fallback reference timezone. */
    val DEFAULT_TIMEZONE_ID: String get() = CurfewConfig.getDefaultFallbackTimeZone()

    /** Injectable time providers for unit testability without Robolectric. */
    var elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
    var wallTimeProvider: () -> Long = { System.currentTimeMillis() }
    @VisibleForTesting
    internal var sntpRequester: () -> SntpResult? = { SntpClient.requestTime() }

    /** Notification listener for significant time drift corrections (e.g. to reschedule alarms). */
    @Volatile
    var onTimeDriftCorrected: ((driftMs: Long) -> Unit)? = null

    // Thread-safe immutable anchor state
    private val currentAnchor = AtomicReference<TimeAnchor>(
        TimeAnchor(0L, 0L, isStrictlyTrusted = false, isDegradedOffline = false)
    )

    private val lastKnownTrueTimeMs = AtomicLong(0L)
    private val lastPersistedElapsedMs = AtomicLong(0L)
    @Volatile private var lastSyncElapsedRealtime: Long = 0L
    @Volatile private var hasEverSyncedFlag: Boolean = false
    @Volatile private var trustedTimeZoneId: String = CurfewConfig.getDefaultFallbackTimeZone()
    @Volatile private var isInitialized: Boolean = false
    @Volatile private var cachedAppContext: Context? = null

    private val stateLock = Any()
    private val persistLock = Any()
    private val persistGen = AtomicLong(0L)

    /** Background CoroutineScope for asynchronous network syncs and single-thread executor for serialized persistence. */
    private val timeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistExecutor = Executors.newSingleThreadExecutor()

    /** Handler targeting Android main thread for UI completion callbacks (lazily evaluated). */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Initializes the trusted time provider using the given application context.
     * Safe to call multiple times; subsequent calls are fast no-ops.
     */
    fun initialize(context: Context) {
        ensureInitialized(context.applicationContext ?: context)
    }

    /**
     * Returns the current trusted epoch timestamp in milliseconds.
     *
     * Execution characteristics:
     * - Ultra-fast O(1) in-memory calculation with zero locks and zero disk I/O.
     * - Reads [currentAnchor] atomically, eliminating torn-read concurrency race conditions.
     * - If synchronized in current boot session: anchorTime + (elapsedRealtime - anchorElapsed).
     * - If offline / rebooted: advances monotonically from heuristic offline anchor.
     * - Strict Anti-Rollback: computed time is strictly >= lastKnownTrueTime.
     */
    fun currentTimeMillis(): Long {
        val anchor = currentAnchor.get()
        val nowElapsed = elapsedRealtimeProvider()

        val computed = if (anchor.trueTimeMs > 0L) {
            anchor.trueTimeMs + (nowElapsed - anchor.elapsedMs)
        } else {
            val wall = wallTimeProvider()
            val initialAnchor = TimeAnchor(wall, nowElapsed, isStrictlyTrusted = false, isDegradedOffline = false)
            currentAnchor.compareAndSet(anchor, initialAnchor)
            wall
        }

        if (!hasEverSyncedFlag) {
            return computed
        }

        val currentLast = lastKnownTrueTimeMs.get()
        val finalTime = maxOf(computed, currentLast)
        if (!anchor.isStrictlyTrusted) {
            // Keep offline wallDelta heuristics strictly in currentAnchor without polluting verified lastKnownTrueTimeMs.
            return finalTime
        }
        val updated = lastKnownTrueTimeMs.accumulateAndGet(finalTime, ::maxOf)
        if (updated > currentLast) {
            maybeDebouncePersist(nowElapsed, updated)
        }
        return updated
    }

    /**
     * Overload ensuring initialization before returning trusted epoch time.
     */
    fun currentTimeMillis(context: Context): Long {
        ensureInitialized(context.applicationContext ?: context)
        return currentTimeMillis()
    }

    /**
     * Synchronously synchronizes trusted time from network servers.
     *
     * @param context Android context.
     * @param force If true, bypasses the 15-minute debounce interval.
     * @return True if sync succeeded, false otherwise.
     */
    fun syncNow(context: Context, force: Boolean = false): Boolean {
        val appContext = context.applicationContext ?: context
        ensureInitialized(appContext)
        updateTrustedTimeZoneFromPolicy(appContext)

        val nowElapsed = elapsedRealtimeProvider()
        val isCurrentAnchorTrusted = currentAnchor.get().isStrictlyTrusted
        if (!force && isCurrentAnchorTrusted && (nowElapsed - lastSyncElapsedRealtime < MIN_SYNC_INTERVAL_MS)) {
            safeLog(Log.DEBUG, "syncNow skipped: already synchronized within debounce interval (${(nowElapsed - lastSyncElapsedRealtime) / 1000}s ago)")
            return true
        }

        val result = sntpRequester()
        return if (result != null) {
            val currentLast = lastKnownTrueTimeMs.get()
            if (hasEverSynced() && currentLast > 0L) {
                if (result.timeMs < currentLast) {
                    safeLog(Log.WARN, "Rejecting sync result earlier than lastKnown: time=${result.timeMs}, lastKnown=$currentLast")
                    return false
                }
                if (result.timeMs > currentLast + SntpClient.MAX_HTTP_FUTURE_DRIFT_MS && !result.isQuorumVerified) {
                    safeLog(Log.WARN, "Rejecting unverified large future jump (>30 days): time=${result.timeMs}, lastKnown=$currentLast")
                    return false
                }
            }
            var driftToNotify: Long? = null

            synchronized(stateLock) {
                val oldComputed = currentTimeMillis()

                val newAnchor = TimeAnchor(
                    trueTimeMs = result.timeMs,
                    elapsedMs = result.elapsedRealtimeMs,
                    isStrictlyTrusted = true,
                    isDegradedOffline = false
                )
                currentAnchor.set(newAnchor)

                lastKnownTrueTimeMs.accumulateAndGet(result.timeMs, ::maxOf)
                lastSyncElapsedRealtime = result.elapsedRealtimeMs
                lastPersistedElapsedMs.set(result.elapsedRealtimeMs)
                hasEverSyncedFlag = true

                val newComputed = currentTimeMillis()
                val driftMs = kotlin.math.abs(newComputed - oldComputed)
                if (driftMs >= DRIFT_CORRECTION_THRESHOLD_MS) {
                    driftToNotify = driftMs
                }

                val currentBootCount = getSystemBootCount(appContext)
                val nowWall = wallTimeProvider()
                val syncedLastKnown = lastKnownTrueTimeMs.get()
                persistGen.incrementAndGet()
                commitPrefs(appContext) {
                    putLong(KEY_ANCHOR_TIME, result.timeMs)
                        .putLong(KEY_ANCHOR_ELAPSED, result.elapsedRealtimeMs)
                        .putLong(KEY_LAST_KNOWN_TRUE_TIME, syncedLastKnown)
                        .putLong(KEY_OFFLINE_ESTIMATED_TIME, syncedLastKnown)
                        .putLong(KEY_LAST_ELAPSED, result.elapsedRealtimeMs)
                        .putLong(KEY_LAST_SAVED_WALL_TIME, nowWall)
                        .putBoolean(KEY_HAS_EVER_SYNCED, true)
                        .putString(KEY_TRUSTED_TIME_ZONE_ID, trustedTimeZoneId)
                    if (currentBootCount != -1) {
                        putInt(KEY_BOOT_COUNT, currentBootCount)
                    }
                }
            }

            driftToNotify?.let { drift ->
                safeLog(Log.INFO, "TrustedTime detected significant time drift ($drift ms). Triggering onTimeDriftCorrected listener.")
                safeRecordTime(
                    context,
                    "TIME_DRIFT_DETECTED",
                    isSuccess = true,
                    details = "drift=${drift}ms"
                )
                try {
                    onTimeDriftCorrected?.invoke(drift)
                } catch (e: Exception) {
                    safeLog(Log.ERROR, "Unhandled exception in onTimeDriftCorrected callback", e)
                }
            }

            safeLog(Log.INFO, "TrustedTime synchronized via ${result.source}: trueTime=${result.timeMs}, latency=${result.roundTripLatencyMs}ms")
            safeRecordTime(
                context,
                "SNTP_SYNC_SUCCESS",
                isSuccess = true,
                details = "source=${result.source}, latency=${result.roundTripLatencyMs}ms"
            )
            true
        } else {
            safeLog(Log.WARN, "TrustedTime network synchronization failed across all sources")
            safeRecordTime(
                context,
                "SNTP_SYNC_FAILED",
                isSuccess = false,
                error = "Network synchronization failed across all sources"
            )
            false
        }
    }

    /**
     * Default synchronous sync overload enforcing debounce.
     */
    fun syncNow(context: Context): Boolean = syncNow(context, force = false)

    /**
     * Asynchronously synchronizes trusted time on a background thread.
     *
     * @param context Android context.
     * @param force If true, bypasses the 15-minute debounce interval.
     * @param onComplete Optional callback delivered on the main UI thread with sync status.
     */
    fun syncNowAsync(context: Context, force: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext ?: context
        timeScope.launch {
            val success = syncNow(appContext, force)
            if (onComplete != null) {
                mainHandler.post {
                    try {
                        onComplete(success)
                    } catch (e: Exception) {
                        Log.e(TAG, "Unhandled exception in syncNowAsync onComplete callback", e)
                    }
                }
            }
        }
    }

    /**
     * Default asynchronous sync overload enforcing debounce.
     */
    fun syncNowAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        syncNowAsync(context, force = false, onComplete = onComplete)
    }

    /**
     * Returns a [Calendar] configured with the anti-spoofed [trustedTimeZoneId]
     * set to the current trusted time.
     *
     * Using this method prevents curfew evasion via device timezone manipulation.
     */
    fun getCurfewCalendar(): Calendar {
        val tz = TimeZone.getTimeZone(trustedTimeZoneId)
        return Calendar.getInstance(tz).apply {
            timeInMillis = currentTimeMillis()
        }
    }

    /**
     * Returns a [Calendar] configured with the anti-spoofed [trustedTimeZoneId]
     * set to the current trusted time, after refreshing timezone preferences if needed.
     */
    fun getCurfewCalendar(context: Context): Calendar {
        val appContext = context.applicationContext ?: context
        ensureInitialized(appContext)
        updateTrustedTimeZoneFromPolicy(appContext)
        val tz = TimeZone.getTimeZone(trustedTimeZoneId)
        return Calendar.getInstance(tz).apply {
            timeInMillis = currentTimeMillis()
        }
    }

    /**
     * Computes the exact [SystemClock.elapsedRealtime] trigger timestamp corresponding
     * to a desired future epoch time in milliseconds.
     *
     * This allows scheduling alarms exclusively via [android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP],
     * which is completely immune to wall-clock manipulation.
     *
     * @param targetTrueTimeMs Desired future epoch time in milliseconds.
     * @return Monotonic elapsed realtime timestamp for AlarmManager.
     */
    fun computeElapsedTrigger(targetTrueTimeMs: Long, context: Context? = null): Long {
        if (context != null) {
            ensureInitialized(context.applicationContext ?: context)
        }
        val currentTrue = currentTimeMillis()
        val delayMs = maxOf(0L, targetTrueTimeMs - currentTrue)
        return elapsedRealtimeProvider() + delayMs
    }

    /**
     * Returns true if time was synchronized via NTP or HTTP in the current boot session.
     * Required for strict security operations such as 24-hour Breakout expiration.
     */
    fun isTrusted(): Boolean = currentAnchor.get().isStrictlyTrusted

    /**
     * Returns true if time is operating in degraded offline mode following a reboot without network.
     */
    fun isDegradedOffline(): Boolean = currentAnchor.get().isDegradedOffline

    /**
     * Returns the active immutable [TimeAnchor].
     */
    fun getCurrentAnchor(): TimeAnchor = currentAnchor.get()

    /**
     * Returns the highest verified or anti-rollback epoch timestamp known to the system.
     */
    fun getLastKnownTrueTime(): Long = lastKnownTrueTimeMs.get()

    /**
     * Returns true if time was synchronized via network at least once in application history.
     */
    fun hasEverSynced(): Boolean = hasEverSyncedFlag

    /**
     * Returns the active trusted timezone ID.
     */
    fun getTrustedTimeZoneId(): String = trustedTimeZoneId

    /**
     * Reads preferences and initializes in-memory state. Thread-safe.
     */
    private fun ensureInitialized(appContext: Context) {
        if (isInitialized) return
        synchronized(stateLock) {
            if (isInitialized) return
            cachedAppContext = appContext

            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedAnchorTime = prefs.getLong(KEY_ANCHOR_TIME, 0L)
            val savedAnchorElapsed = prefs.getLong(KEY_ANCHOR_ELAPSED, 0L)
            val savedLastKnown = prefs.getLong(KEY_LAST_KNOWN_TRUE_TIME, 0L)
            val savedOfflineEstimated = prefs.getLong(KEY_OFFLINE_ESTIMATED_TIME, 0L)
            val savedLastElapsed = prefs.getLong(KEY_LAST_ELAPSED, 0L)
            val savedLastWall = prefs.getLong(KEY_LAST_SAVED_WALL_TIME, 0L)
            val savedTz = prefs.getString(KEY_TRUSTED_TIME_ZONE_ID, DEFAULT_TIMEZONE_ID) ?: DEFAULT_TIMEZONE_ID
            val savedHasEverSynced = prefs.getBoolean(KEY_HAS_EVER_SYNCED, false)
            val savedBootCount = prefs.getInt(KEY_BOOT_COUNT, -1)

            val currentElapsed = elapsedRealtimeProvider()
            val currentBootCount = getSystemBootCount(appContext)

            trustedTimeZoneId = savedTz
            hasEverSyncedFlag = savedHasEverSynced || savedAnchorTime > 0L
            lastKnownTrueTimeMs.set(if (hasEverSyncedFlag) savedLastKnown else 0L)

            // Detect whether the device has rebooted since last recorded state
            val rebootDetected = (savedBootCount != -1 && currentBootCount != -1 && currentBootCount != savedBootCount) ||
                    (savedLastElapsed > 0L && currentElapsed < savedLastElapsed) ||
                    (savedAnchorElapsed > 0L && currentElapsed < savedAnchorElapsed)

            if (savedAnchorTime > 0L && savedAnchorElapsed > 0L && !rebootDetected) {
                currentAnchor.set(
                    TimeAnchor(
                        trueTimeMs = savedAnchorTime,
                        elapsedMs = savedAnchorElapsed,
                        isStrictlyTrusted = true,
                        isDegradedOffline = false
                    )
                )
                Log.d(TAG, "TrustedTime restored valid anchor from same boot session (anchorMs=$savedAnchorTime)")
            } else {
                // Device rebooted or cold boot without network synchronization
                val currentWall = wallTimeProvider()
                if (hasEverSyncedFlag && savedLastKnown > 0L) {
                    val persistedFloor = maxOf(savedLastKnown, savedOfflineEstimated)
                    val wallDelta = if (savedLastWall > 0L) currentWall - savedLastWall else 0L

                    val offlineAnchorTime = if (wallDelta > 0L) {
                        val clampedDelta = minOf(wallDelta, MAX_OFFLINE_ADVANCE_MS)
                        savedLastKnown + clampedDelta
                    } else {
                        // Anti-rollback: wall clock was turned backward or was identical
                        savedLastKnown
                    }

                    val finalTime = maxOf(offlineAnchorTime, persistedFloor)
                    lastKnownTrueTimeMs.set(savedLastKnown)
                    currentAnchor.set(
                        TimeAnchor(
                            trueTimeMs = finalTime,
                            elapsedMs = currentElapsed,
                            isStrictlyTrusted = false,
                            isDegradedOffline = true
                        )
                    )
                    safeLog(Log.DEBUG, "TrustedTime detected reboot/cold boot. Offline mode active: anchorTime=$finalTime, lastKnownVerified=$savedLastKnown, persistedFloor=$persistedFloor, wallDelta=$wallDelta, clamped=${wallDelta > MAX_OFFLINE_ADVANCE_MS}")
                } else {
                    val baselineTime = maxOf(currentWall, 0L)
                    lastKnownTrueTimeMs.set(0L)
                    currentAnchor.set(
                        TimeAnchor(
                            trueTimeMs = baselineTime,
                            elapsedMs = currentElapsed,
                            isStrictlyTrusted = false,
                            isDegradedOffline = true
                        )
                    )
                    safeLog(Log.DEBUG, "TrustedTime cold boot with no prior sync; initialized baseline offline anchor=$baselineTime")
                }
            }

            if (currentBootCount != -1 && savedBootCount != currentBootCount) {
                commitPrefs(appContext) {
                    putInt(KEY_BOOT_COUNT, currentBootCount)
                }
            }

            lastPersistedElapsedMs.set(currentElapsed)
            isInitialized = true
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun commitPrefs(
        appContext: Context,
        expectedGen: Long? = null,
        block: android.content.SharedPreferences.Editor.() -> Unit
    ) {
        synchronized(persistLock) {
            if (expectedGen != null && persistGen.get() != expectedGen) return
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .apply(block)
                .commit()
        }
    }

    /**
     * Synchronously persists advancing trusted time state during OS shutdown or reboot broadcast.
     * Must be invoked on the main thread inside BroadcastReceiver.onReceive without async delegation.
     */
    @SuppressLint("ApplySharedPref")
    fun persistOnShutdown(context: Context) {
        try {
            val appContext = context.applicationContext ?: context
            ensureInitialized(appContext)
            val nowElapsed = elapsedRealtimeProvider()
            val nowTrue = currentTimeMillis()
            val verifiedLastKnown = lastKnownTrueTimeMs.get()
            val isStrictlyTrusted = currentAnchor.get().isStrictlyTrusted
            val nowWall = wallTimeProvider()
            val currentBootCount = getSystemBootCount(appContext)
            persistGen.incrementAndGet()
            val existingOffline = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_OFFLINE_ESTIMATED_TIME, 0L)
            commitPrefs(appContext) {
                putLong(KEY_LAST_ELAPSED, nowElapsed)
                if (currentBootCount != -1) {
                    putInt(KEY_BOOT_COUNT, currentBootCount)
                }
                if (isStrictlyTrusted) {
                    putLong(KEY_LAST_SAVED_WALL_TIME, nowWall)
                    if (hasEverSyncedFlag && verifiedLastKnown > 0L) {
                        putLong(KEY_LAST_KNOWN_TRUE_TIME, verifiedLastKnown)
                        putLong(KEY_OFFLINE_ESTIMATED_TIME, verifiedLastKnown)
                    }
                } else if (hasEverSyncedFlag && verifiedLastKnown > 0L) {
                    // Offline shutdown: keep KEY_LAST_SAVED_WALL_TIME & KEY_LAST_KNOWN_TRUE_TIME intact
                    // so chained offline reboots remain bounded by MAX_OFFLINE_ADVANCE_MS while preserving monotonic floor.
                    putLong(KEY_OFFLINE_ESTIMATED_TIME, maxOf(nowTrue, verifiedLastKnown, existingOffline))
                }
            }
            safeLog(
                Log.INFO,
                "Persisted shutdown timestamp synchronously: effectiveTime=$nowTrue, verifiedLastKnown=$verifiedLastKnown, wallTime=$nowWall, elapsed=$nowElapsed, synced=$hasEverSyncedFlag"
            )
        } catch (e: Exception) {
            safeLog(Log.ERROR, "Failed synchronous persistence during device shutdown", e)
        }
    }

    /**
     * Updates the trusted timezone identifier from the user's active policy if valid.
     */
    private fun updateTrustedTimeZoneFromPolicy(appContext: Context) {
        try {
            val policyTz = CurfewConfig.getTargetTimeZoneId(appContext)
            if (policyTz.isNotBlank() && policyTz != trustedTimeZoneId) {
                trustedTimeZoneId = policyTz
                commitPrefs(appContext) {
                    putString(KEY_TRUSTED_TIME_ZONE_ID, policyTz)
                }
                safeLog(Log.INFO, "Updated trusted timezone from policy: $policyTz")
            }
        } catch (e: Exception) {
            safeLog(Log.WARN, "Failed updating trusted timezone from policy", e)
        }
    }

    /**
     * Debounces persisting advancing [lastKnownTrueTimeMs] and wall clock reference to SharedPreferences.
     * Executes asynchronously on a serialized single-thread executor to ensure [currentTimeMillis]
     * remains strictly non-blocking O(1) while preventing .apply()/.commit() overwrite races.
     */
    private fun maybeDebouncePersist(nowElapsed: Long, timeToPersist: Long) {
        if (!hasEverSyncedFlag || !currentAnchor.get().isStrictlyTrusted) return
        val prevPersisted = lastPersistedElapsedMs.get()
        if (nowElapsed - prevPersisted >= DEBOUNCE_PERSIST_MS &&
            lastPersistedElapsedMs.compareAndSet(prevPersisted, nowElapsed)
        ) {
            val capturedGen = persistGen.get()
            val nowWall = wallTimeProvider()
            val ctx = cachedAppContext ?: return
            persistExecutor.execute {
                try {
                    commitPrefs(ctx, expectedGen = capturedGen) {
                        putLong(KEY_LAST_KNOWN_TRUE_TIME, timeToPersist)
                            .putLong(KEY_OFFLINE_ESTIMATED_TIME, timeToPersist)
                            .putLong(KEY_LAST_ELAPSED, nowElapsed)
                            .putLong(KEY_LAST_SAVED_WALL_TIME, nowWall)
                    }
                } catch (e: Exception) {
                    safeLog(Log.WARN, "Failed debounced persistence of lastKnownTrueTime", e)
                }
            }
        }
    }

    /**
     * Safely reads Settings.Global.BOOT_COUNT if accessible.
     */
    private fun getSystemBootCount(context: Context): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            safeLog(Log.WARN, "Unable to read Settings.Global.BOOT_COUNT: ${e.message}")
            -1
        }
    }

    private fun safeRecordTime(
        context: Context,
        action: String,
        isSuccess: Boolean,
        details: String? = null,
        error: String? = null
    ) {
        try {
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordTime(
                context,
                action,
                isSuccess = isSuccess,
                details = details,
                error = error
            )
        } catch (e: Throwable) {
            safeLog(Log.DEBUG, "Skipping BlackBoxRecorder.recordTime in test/stub context: ${e.message}")
        }
    }

    private fun safeLog(priority: Int, message: String, throwable: Throwable? = null) {
        try {
            when (priority) {
                Log.DEBUG -> Log.d(TAG, message, throwable)
                Log.INFO -> Log.i(TAG, message, throwable)
                Log.WARN -> Log.w(TAG, message, throwable)
                else -> Log.e(TAG, message, throwable)
            }
        } catch (e: RuntimeException) {
            if (e.message?.contains("not mocked") != true) throw e
        }
    }

    @VisibleForTesting
    fun resetForTesting() {
        synchronized(stateLock) {
            persistGen.incrementAndGet()
            currentAnchor.set(TimeAnchor(0L, 0L, isStrictlyTrusted = false, isDegradedOffline = false))
            lastKnownTrueTimeMs.set(0L)
            lastPersistedElapsedMs.set(0L)
            lastSyncElapsedRealtime = 0L
            hasEverSyncedFlag = false
            trustedTimeZoneId = DEFAULT_TIMEZONE_ID
            isInitialized = false
            cachedAppContext = null
            onTimeDriftCorrected = null
            sntpRequester = { SntpClient.requestTime() }
        }
    }

    @VisibleForTesting
    fun setAnchorForTesting(timeMs: Long, elapsedRealtimeMs: Long, isStrictlyTrusted: Boolean = true) {
        synchronized(stateLock) {
            currentAnchor.set(
                TimeAnchor(
                    trueTimeMs = timeMs,
                    elapsedMs = elapsedRealtimeMs,
                    isStrictlyTrusted = isStrictlyTrusted,
                    isDegradedOffline = !isStrictlyTrusted
                )
            )
            lastKnownTrueTimeMs.set(timeMs)
            hasEverSyncedFlag = true
        }
    }
}
