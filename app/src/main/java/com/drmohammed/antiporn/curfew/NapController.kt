package com.drmohammed.antiporn.curfew

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * منطق وضع القيلولة Nap Mode — حظر تام 60 دقيقة بلا فك.
 *
 * Dual-clock truth (elapsedRealtime + wall clock, fail-closed OR), unlimited +60m
 * extends, no public cancel/shorten API. Never calls CurfewEnforcer (merged by integrator).
 */
object NapController {

    private const val TAG = "AntiPorn_NapController"

    var elapsedProvider: () -> Long = { SystemClock.elapsedRealtime() }
    var wallProvider: () -> Long = { com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis() }

    fun computeActive(elapsed: Long, endElapsed: Long, wall: Long, endWall: Long, flagActive: Boolean): Boolean {
        if (!flagActive) return false
        return elapsed < endElapsed || wall < endWall
    }

    fun computeRemaining(elapsed: Long, endElapsed: Long, wall: Long, endWall: Long, flagActive: Boolean): Long {
        if (!flagActive) return 0L
        return maxOf(0L, maxOf(endElapsed - elapsed, endWall - wall))
    }

    fun computeStartEnds(elapsedNow: Long, wallNow: Long, durationMs: Long = NapConfig.NAP_DURATION_MS): Pair<Long, Long> =
        Pair(elapsedNow + durationMs, wallNow + durationMs)

    fun computeExtendedEnds(currentEndElapsed: Long, currentEndWall: Long, stepMs: Long = NapConfig.EXTEND_STEP_MS): Pair<Long, Long> =
        Pair(currentEndElapsed + stepMs, currentEndWall + stepMs)

    internal fun resolveStartEnds(
        currentEndElapsed: Long,
        currentEndWall: Long,
        flagActive: Boolean,
        elapsedNow: Long,
        wallNow: Long
    ): Pair<Long, Long> =
        if (computeActive(elapsedNow, currentEndElapsed, wallNow, currentEndWall, flagActive)) {
            Pair(currentEndElapsed, currentEndWall)
        } else {
            computeStartEnds(elapsedNow, wallNow)
        }

    internal fun resolveExtendedEnds(
        currentEndElapsed: Long,
        currentEndWall: Long,
        flagActive: Boolean,
        elapsedNow: Long,
        wallNow: Long
    ): Pair<Long, Long> =
        if (!computeActive(elapsedNow, currentEndElapsed, wallNow, currentEndWall, flagActive)) {
            computeStartEnds(elapsedNow, wallNow)
        } else {
            computeExtendedEnds(currentEndElapsed, currentEndWall)
        }

    internal fun shouldAutoClear(elapsed: Long, endElapsed: Long, wall: Long, endWall: Long, flagActive: Boolean): Boolean =
        flagActive && elapsed >= endElapsed && wall >= endWall

    @Synchronized
    fun startNap(context: Context): Long {
        try {
            val prefs = context.getSharedPreferences(NapConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val flagActive = prefs.getBoolean(NapConfig.KEY_NAP_ACTIVE, false)
            val currentEndElapsed = prefs.getLong(NapConfig.KEY_NAP_END_ELAPSED, 0L)
            val currentEndWall = prefs.getLong(NapConfig.KEY_NAP_END_WALL_MS, 0L)
            val elapsedNow = elapsedProvider()
            val wallNow = wallProvider()
            if (computeActive(elapsedNow, currentEndElapsed, wallNow, currentEndWall, flagActive)) {
                return currentEndElapsed
            }
            val (endElapsed, endWall) = computeStartEnds(elapsedNow, wallNow)
            prefs.edit()
                .putLong(NapConfig.KEY_NAP_START_ELAPSED, elapsedNow)
                .putLong(NapConfig.KEY_NAP_END_ELAPSED, endElapsed)
                .putLong(NapConfig.KEY_NAP_END_WALL_MS, endWall)
                .putBoolean(NapConfig.KEY_NAP_ACTIVE, true)
                .apply()
            return endElapsed
        } catch (e: Exception) {
            Log.e(TAG, "startNap failed", e)
            return 0L
        }
    }

    @Synchronized
    fun extendNap(context: Context): Long {
        try {
            val prefs = context.getSharedPreferences(NapConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val flagActive = prefs.getBoolean(NapConfig.KEY_NAP_ACTIVE, false)
            val currentEndElapsed = prefs.getLong(NapConfig.KEY_NAP_END_ELAPSED, 0L)
            val currentEndWall = prefs.getLong(NapConfig.KEY_NAP_END_WALL_MS, 0L)
            val elapsedNow = elapsedProvider()
            val wallNow = wallProvider()
            if (!computeActive(elapsedNow, currentEndElapsed, wallNow, currentEndWall, flagActive)) {
                return startNap(context)
            }
            val (newEndElapsed, newEndWall) = computeExtendedEnds(currentEndElapsed, currentEndWall)
            prefs.edit()
                .putLong(NapConfig.KEY_NAP_START_ELAPSED, elapsedNow)
                .putLong(NapConfig.KEY_NAP_END_ELAPSED, newEndElapsed)
                .putLong(NapConfig.KEY_NAP_END_WALL_MS, newEndWall)
                .putBoolean(NapConfig.KEY_NAP_ACTIVE, true)
                .apply()
            return newEndElapsed
        } catch (e: Exception) {
            Log.e(TAG, "extendNap failed", e)
            return 0L
        }
    }

    @Synchronized
    private fun getSanitizedEndElapsed(
        prefs: android.content.SharedPreferences,
        context: Context,
        elapsedNow: Long,
        wallNow: Long,
        endWall: Long
    ): Long {
        val startElapsed = prefs.getLong(NapConfig.KEY_NAP_START_ELAPSED, 0L)
        var endElapsed = prefs.getLong(NapConfig.KEY_NAP_END_ELAPSED, 0L)
        // Detect system reboot: elapsedRealtime resets to 0, which is strictly smaller than startElapsed
        if (startElapsed > 0L && elapsedNow < startElapsed) {
            if (wallNow >= endWall) {
                clearNap(context)
                return 0L
            }
            val remainingWall = endWall - wallNow
            endElapsed = elapsedNow + remainingWall
            prefs.edit()
                .putLong(NapConfig.KEY_NAP_START_ELAPSED, elapsedNow)
                .putLong(NapConfig.KEY_NAP_END_ELAPSED, endElapsed)
                .apply()
        }
        return endElapsed
    }

    fun isNapActive(context: Context): Boolean {
        try {
            val prefs = context.getSharedPreferences(NapConfig.PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(NapConfig.KEY_NAP_ACTIVE, false)) return false
            val elapsedNow = elapsedProvider()
            val wallNow = wallProvider()
            val endWall = prefs.getLong(NapConfig.KEY_NAP_END_WALL_MS, 0L)
            val endElapsed = getSanitizedEndElapsed(prefs, context, elapsedNow, wallNow, endWall)
            val active = computeActive(elapsedNow, endElapsed, wallNow, endWall, true)
            if (!active) {
                try {
                    clearNap(context)
                } catch (e: Exception) {
                    Log.e(TAG, "auto-clear failed", e)
                }
            }
            return active
        } catch (e: Exception) {
            Log.e(TAG, "isNapActive failed", e)
            return false
        }
    }

    fun remainingMs(context: Context): Long {
        try {
            val prefs = context.getSharedPreferences(NapConfig.PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(NapConfig.KEY_NAP_ACTIVE, false)) return 0L
            val elapsedNow = elapsedProvider()
            val wallNow = wallProvider()
            val endWall = prefs.getLong(NapConfig.KEY_NAP_END_WALL_MS, 0L)
            val endElapsed = getSanitizedEndElapsed(prefs, context, elapsedNow, wallNow, endWall)
            return computeRemaining(elapsedNow, endElapsed, wallNow, endWall, true)
        } catch (e: Exception) {
            Log.e(TAG, "remainingMs failed", e)
            return 0L
        }
    }

    fun getEndElapsed(context: Context): Long {
        return try {
            context.getSharedPreferences(NapConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(NapConfig.KEY_NAP_END_ELAPSED, 0L)
        } catch (e: Exception) {
            Log.e(TAG, "getEndElapsed failed", e)
            0L
        }
    }

    fun getEndWall(context: Context): Long {
        return try {
            context.getSharedPreferences(NapConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(NapConfig.KEY_NAP_END_WALL_MS, 0L)
        } catch (e: Exception) {
            Log.e(TAG, "getEndWall failed", e)
            0L
        }
    }

    @Synchronized
    private fun clearNap(context: Context) {
        try {
            context.getSharedPreferences(NapConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(NapConfig.KEY_NAP_ACTIVE, false)
                .putLong(NapConfig.KEY_NAP_START_ELAPSED, 0L)
                .putLong(NapConfig.KEY_NAP_END_ELAPSED, 0L)
                .putLong(NapConfig.KEY_NAP_END_WALL_MS, 0L)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "clearNap failed", e)
        }
    }
}
