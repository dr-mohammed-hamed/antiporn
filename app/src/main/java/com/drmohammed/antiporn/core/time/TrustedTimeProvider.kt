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

    /** Notification listener for significant time drift corrections (e.g. to reschedule alarms). */
    @Volatile
    var onTimeDriftCorrected: ((driftMs: Long) -> Unit)? = null

    // Thread-safe immutable anchor state
    private val currentAnchor = AtomicReference<TimeAnchor>(
        TimeAnchor(0L, 0L, isStrictlyTrusted = false, isDegradedOffline = false)
    )

    private val lastKnownTrueTimeMs = AtomicLong(0L)
    @Volatile private var lastPersistedElapsedMs: Long = 0L
    @Volatile private var lastSyncElapsedRealtime: Long = 0L
    @Volatile private var hasEverSyncedFlag: Boolean = false
    @Volatile private var trustedTimeZoneId: String = CurfewConfig.getDefaultFallbackTimeZone()
    @Volatile private var isInitialized: Boolean = false
    @Volatile private var cachedAppContext: Context? = null

    private val stateLock = Any()

    /** Background CoroutineScope for asynchronous network syncs and debounced disk persistence. */
    private val timeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Handler targeting Android main thread for UI completion callbacks (lazily evaluated). */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Initializes the trusted time provider using the given application context.
     * Safe to call multiple times; subsequent calls are fast no-ops.
     */
    fun initialize(context: Context) {
        ensureInitialized(context.applicationContext)
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

        val currentLast = lastKnownTrueTimeMs.get()
        val finalTime = maxOf(computed, currentLast)
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
        ensureInitialized(context.applicationContext)
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
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        updateTrustedTimeZoneFromPolicy(appContext)

        val nowElapsed = elapsedRealtimeProvider()
        val isCurrentAnchorTrusted = currentAnchor.get().isStrictlyTrusted
        if (!force && isCurrentAnchorTrusted && (nowElapsed - lastSyncElapsedRealtime < MIN_SYNC_INTERVAL_MS)) {
            Log.d(TAG, "syncNow skipped: already synchronized within debounce interval (${(nowElapsed - lastSyncElapsedRealtime) / 1000}s ago)")
            return true
        }

        val result = SntpClient.requestTime()
        return if (result != null) {
            val currentLast = lastKnownTrueTimeMs.get()
            if (currentLast > 0L && (result.timeMs < currentLast || result.timeMs > currentLast + SntpClient.MAX_HTTP_FUTURE_DRIFT_MS)) {
                Log.w(TAG, "Rejecting sync result out of bounds: time=${result.timeMs}, lastKnown=$currentLast")
                return false
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
                lastPersistedElapsedMs = result.elapsedRealtimeMs
                hasEverSyncedFlag = true

                val newComputed = currentTimeMillis()
                val driftMs = kotlin.math.abs(newComputed - oldComputed)
                if (driftMs >= DRIFT_CORRECTION_THRESHOLD_MS) {
                    driftToNotify = driftMs
                }

                val currentBootCount = getSystemBootCount(appContext)
                val nowWall = wallTimeProvider()
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                    .putLong(KEY_ANCHOR_TIME, result.timeMs)
                    .putLong(KEY_ANCHOR_ELAPSED, result.elapsedRealtimeMs)
                    .putLong(KEY_LAST_KNOWN_TRUE_TIME, lastKnownTrueTimeMs.get())
                    .putLong(KEY_LAST_ELAPSED, result.elapsedRealtimeMs)
                    .putLong(KEY_LAST_SAVED_WALL_TIME, nowWall)
                    .putBoolean(KEY_HAS_EVER_SYNCED, true)
                    .putString(KEY_TRUSTED_TIME_ZONE_ID, trustedTimeZoneId)

                if (currentBootCount != -1) {
                    editor.putInt(KEY_BOOT_COUNT, currentBootCount)
                }
                editor.apply()
            }

            driftToNotify?.let { drift ->
                Log.i(TAG, "TrustedTime detected significant time drift ($drift ms). Triggering onTimeDriftCorrected listener.")
                com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordTime(
                    context,
                    "TIME_DRIFT_DETECTED",
                    isSuccess = true,
                    details = "drift=${drift}ms"
                )
                try {
                    onTimeDriftCorrected?.invoke(drift)
                } catch (e: Exception) {
                    Log.e(TAG, "Unhandled exception in onTimeDriftCorrected callback", e)
                }
            }

            Log.i(TAG, "TrustedTime synchronized via ${result.source}: trueTime=${result.timeMs}, latency=${result.roundTripLatencyMs}ms")
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordTime(
                context,
                "SNTP_SYNC_SUCCESS",
                isSuccess = true,
                details = "source=${result.source}, latency=${result.roundTripLatencyMs}ms"
            )
            true
        } else {
            Log.w(TAG, "TrustedTime network synchronization failed across all sources")
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordTime(
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
        val appContext = context.applicationContext
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
        val appContext = context.applicationContext
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
    fun computeElapsedTrigger(targetTrueTimeMs: Long): Long {
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
    fun hasEverSynced(): Boolean = hasEverSyncedFlag || lastKnownTrueTimeMs.get() > 0L

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
            val savedLastElapsed = prefs.getLong(KEY_LAST_ELAPSED, 0L)
            val savedLastWall = prefs.getLong(KEY_LAST_SAVED_WALL_TIME, 0L)
            val savedTz = prefs.getString(KEY_TRUSTED_TIME_ZONE_ID, DEFAULT_TIMEZONE_ID) ?: DEFAULT_TIMEZONE_ID
            val savedHasEverSynced = prefs.getBoolean(KEY_HAS_EVER_SYNCED, false)
            val savedBootCount = prefs.getInt(KEY_BOOT_COUNT, -1)

            val currentElapsed = elapsedRealtimeProvider()
            val currentBootCount = getSystemBootCount(appContext)

            trustedTimeZoneId = savedTz
            hasEverSyncedFlag = savedHasEverSynced || savedAnchorTime > 0L || savedLastKnown > 0L
            lastKnownTrueTimeMs.set(savedLastKnown)

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
                val wallDelta = if (savedLastWall > 0L) currentWall - savedLastWall else 0L

                val offlineAnchorTime = if (wallDelta > 0L) {
                    val clampedDelta = minOf(wallDelta, MAX_OFFLINE_ADVANCE_MS)
                    savedLastKnown + clampedDelta
                } else {
                    // Anti-rollback: wall clock was turned backward or was identical
                    savedLastKnown
                }

                val finalTime = maxOf(offlineAnchorTime, savedLastKnown)
                lastKnownTrueTimeMs.set(finalTime)
                currentAnchor.set(
                    TimeAnchor(
                        trueTimeMs = finalTime,
                        elapsedMs = currentElapsed,
                        isStrictlyTrusted = false,
                        isDegradedOffline = true
                    )
                )
                Log.d(TAG, "TrustedTime detected reboot/cold boot. Offline mode active: anchorTime=$finalTime, wallDelta=$wallDelta, clamped=${wallDelta > MAX_OFFLINE_ADVANCE_MS}")
            }

            if (savedBootCount == -1 && currentBootCount != -1) {
                prefs.edit().putInt(KEY_BOOT_COUNT, currentBootCount).apply()
            }

            lastPersistedElapsedMs = currentElapsed
            isInitialized = true
        }
    }

    /**
     * Synchronously persists advancing trusted time state during OS shutdown or reboot broadcast.
     * Must be invoked on the main thread inside BroadcastReceiver.onReceive without async delegation.
     */
    @SuppressLint("ApplySharedPref")
    fun persistOnShutdown(context: Context) {
        try {
            ensureInitialized(context.applicationContext)
            val nowElapsed = elapsedRealtimeProvider()
            val nowTrue = currentTimeMillis()
            val nowWall = wallTimeProvider()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_KNOWN_TRUE_TIME, nowTrue)
                .putLong(KEY_LAST_ELAPSED, nowElapsed)
                .putLong(KEY_LAST_SAVED_WALL_TIME, nowWall)
                .commit()
            Log.i(TAG, "Persisted shutdown timestamp synchronously: trueTime=$nowTrue, wallTime=$nowWall, elapsed=$nowElapsed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed synchronous persistence during device shutdown", e)
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
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_TRUSTED_TIME_ZONE_ID, policyTz)
                    .apply()
                Log.i(TAG, "Updated trusted timezone from policy: $policyTz")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating trusted timezone from policy", e)
        }
    }

    /**
     * Debounces persisting advancing [lastKnownTrueTimeMs] and wall clock reference to SharedPreferences.
     * Executes asynchronously on [backgroundExecutor] to ensure [currentTimeMillis]
     * remains strictly non-blocking O(1).
     */
    private fun maybeDebouncePersist(nowElapsed: Long, timeToPersist: Long) {
        if (nowElapsed - lastPersistedElapsedMs >= DEBOUNCE_PERSIST_MS) {
            lastPersistedElapsedMs = nowElapsed
            val nowWall = wallTimeProvider()
            val ctx = cachedAppContext ?: return
            timeScope.launch {
                try {
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putLong(KEY_LAST_KNOWN_TRUE_TIME, timeToPersist)
                        .putLong(KEY_LAST_ELAPSED, nowElapsed)
                        .putLong(KEY_LAST_SAVED_WALL_TIME, nowWall)
                        .apply()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed debounced persistence of lastKnownTrueTime", e)
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
            Log.w(TAG, "Unable to read Settings.Global.BOOT_COUNT: ${e.message}")
            -1
        }
    }

    @VisibleForTesting
    fun resetForTesting() {
        synchronized(stateLock) {
            currentAnchor.set(TimeAnchor(0L, 0L, isStrictlyTrusted = false, isDegradedOffline = false))
            lastKnownTrueTimeMs.set(0L)
            lastPersistedElapsedMs = 0L
            lastSyncElapsedRealtime = 0L
            hasEverSyncedFlag = false
            trustedTimeZoneId = DEFAULT_TIMEZONE_ID
            isInitialized = false
            cachedAppContext = null
            onTimeDriftCorrected = null
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
