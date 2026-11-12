package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates night curfew & nap schedule evaluation, snooze invariants,
 * and synchronized entry points to [CurfewLifecycleHandler].
 */
object CurfewEnforcer {

    private const val TAG = "AntiPorn_CurfewEnforcer"
    private val enforcerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Evaluates whether [calendar] falls inside the configured weekday or weekend
     * curfew schedule window (including Thursday-evening transitions and cross-midnight spills),
     * independent of whether the curfew master switch is enabled.
     */
    fun isScheduleWindowActive(
        calendar: Calendar,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        isWeekendCurfewEnabled: Boolean,
        weekendStartHour: Int,
        weekendStartMinute: Int,
        weekendEndHour: Int,
        weekendEndMinute: Int
    ): Boolean = CurfewScheduleExt.isScheduleWindowActive(
        calendar = calendar,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
        isWeekendCurfewEnabled = isWeekendCurfewEnabled,
        weekendStartHour = weekendStartHour,
        weekendStartMinute = weekendStartMinute,
        weekendEndHour = weekendEndHour,
        weekendEndMinute = weekendEndMinute
    )

    fun isCurfewActive(calendar: Calendar = CurfewConfig.getCurfewCalendar()): Boolean =
        isScheduleWindowActive(
            calendar = calendar,
            startHour = CurfewConfig.START_HOUR,
            startMinute = CurfewConfig.START_MINUTE,
            endHour = CurfewConfig.END_HOUR,
            endMinute = CurfewConfig.END_MINUTE,
            isWeekendCurfewEnabled = false,
            weekendStartHour = CurfewConfig.START_HOUR,
            weekendStartMinute = CurfewConfig.START_MINUTE,
            weekendEndHour = CurfewConfig.END_HOUR,
            weekendEndMinute = CurfewConfig.END_MINUTE
        )

    fun isCurfewActive(
        context: Context,
        calendar: Calendar = CurfewConfig.getCurfewCalendar(context),
        useFailSecureEnabled: Boolean = false
    ): Boolean {
        val enabled = if (useFailSecureEnabled) {
            CurfewConfig.isCurfewEnabledFailSecure(context)
        } else {
            CurfewConfig.isCurfewEnabled(context)
        }
        if (!enabled) return false
        return isScheduleWindowActive(
            calendar = calendar,
            startHour = CurfewConfig.getCurfewStartHour(context),
            startMinute = CurfewConfig.getCurfewStartMinute(context),
            endHour = CurfewConfig.getCurfewEndHour(context),
            endMinute = CurfewConfig.getCurfewEndMinute(context),
            isWeekendCurfewEnabled = CurfewConfig.isWeekendCurfewEnabled(context),
            weekendStartHour = CurfewConfig.getWeekendCurfewStartHour(context),
            weekendStartMinute = CurfewConfig.getWeekendCurfewStartMinute(context),
            weekendEndHour = CurfewConfig.getWeekendCurfewEndHour(context),
            weekendEndMinute = CurfewConfig.getWeekendCurfewEndMinute(context)
        )
    }

    fun isCurfewFeatureEnabled(context: Context): Boolean = CurfewConfig.isCurfewEnabled(context)

    @Deprecated(
        message = "DevicePolicyBundle.isCurfewEnabled is the single source of truth; update via PolicyRepository."
    )
    fun setCurfewFeatureEnabled(context: Context, enabled: Boolean) {
        Log.w(TAG, "Ignoring deprecated setCurfewFeatureEnabled($enabled); DevicePolicyBundle.isCurfewEnabled is authoritative")
        syncBlockState(context)
    }

    fun isManualTestActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(CurfewConfig.KEY_MANUAL_TEST_ACTIVE, false)
    }

    @Synchronized
    fun setManualTestActive(context: Context, active: Boolean): Boolean {
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(CurfewConfig.KEY_MANUAL_TEST_ACTIVE, active).commit()
        return syncBlockState(context)
    }

    fun setManualTestActiveAsync(context: Context, active: Boolean, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext ?: context
        enforcerScope.launch {
            var success = false
            try {
                success = setManualTestActive(appContext, active)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing setManualTestActiveAsync", e)
            } finally {
                if (onComplete != null) {
                    withContext(Dispatchers.Main) {
                        onComplete.invoke(success)
                    }
                }
            }
        }
    }

    fun isSnoozeActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val snoozeEnd = prefs.getLong(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL, 0L)
        return TrustedTimeProvider.currentTimeMillis(context) < snoozeEnd
    }

    fun getSnoozeEndWall(context: Context): Long {
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL, 0L)
    }

    @Synchronized
    fun clearSnooze(context: Context) {
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL).commit()
        Log.i(TAG, "Curfew snooze cleared")
    }

    /**
     * Requests a temporary curfew snooze. Rejected whenever a Nap session is active
     * because Naps never yield to curfew snooze.
     */
    @Synchronized
    fun snoozeCurfew(context: Context, minutes: Int = 15): Boolean {
        if (NapController.isNapActive(context)) {
            logWarn("Curfew snooze rejected: Nap session is currently active")
            return false
        }
        val safeMinutes = minutes.coerceIn(1, 60)
        val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val snoozeEnd = TrustedTimeProvider.currentTimeMillis(context) + (safeMinutes * 60 * 1000L)
        prefs.edit().putLong(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL, snoozeEnd).commit()
        CurfewScheduler.scheduleCurfewSnoozeAlarm(context, snoozeEnd)
        val released = enforce(context, false)
        Log.i(TAG, "Curfew snoozed for $safeMinutes minutes until $snoozeEnd (released=$released)")
        return released
    }

    fun isEffectiveCurfewActive(context: Context): Boolean {
        if (!isCurfewFeatureEnabled(context)) return false
        if (isSnoozeActive(context)) return false
        if (isManualTestActive(context)) return true
        return isCurfewActive(context)
    }

    /**
     * Effective block = night curfew OR active nap. Nap merges into the same engine.
     */
    fun isEffectiveBlockActive(context: Context): Boolean =
        isEffectiveCurfewActive(context) || NapController.isNapActive(context)

    internal fun isPackageInstalled(
        pm: PackageManager,
        packageName: String,
        includeHidden: Boolean = false
    ): Boolean = CurfewLifecycleHandler.isPackageInstalled(pm, packageName, includeHidden)

    internal fun isVpnTeardownAllowedOnReleaseFailSecure(
        fetchPolicyFailed: Boolean,
        instantKill: Boolean,
        isVpnTeardownNeeded: Boolean,
        isAlwaysOnConfigured: Boolean
    ): Boolean = !fetchPolicyFailed && !instantKill && (isVpnTeardownNeeded || isAlwaysOnConfigured)

    internal fun shouldExecuteReleaseCleanup(
        isVpnTeardownNeeded: Boolean,
        isAlwaysOnConfigured: Boolean,
        wasBlockEnforced: Boolean,
        hasTrackedSuspendedPackages: Boolean,
        hasLingeringSuspendedPackages: Boolean
    ): Boolean = isVpnTeardownNeeded ||
        isAlwaysOnConfigured ||
        wasBlockEnforced ||
        hasTrackedSuspendedPackages ||
        hasLingeringSuspendedPackages

    /**
     * Synchronously applies or releases curfew/nap suspensions and store lockdowns.
     * Guaranteed invariant: Nap ALWAYS vetoes release (never overridden by curfew snooze),
     * and night curfew window holds unless explicitly snoozed.
     */
    @Synchronized
    fun enforce(
        context: Context,
        activate: Boolean,
        calendar: Calendar = CurfewConfig.getCurfewCalendar(context)
    ): Boolean {
        if (!SafeDpmGateway.isDeviceOwner(context)) {
            Log.w(TAG, "Cannot enforce curfew: Not Device/Profile Owner")
            return false
        }

        val napActive = NapController.isNapActive(context)
        val isCurfewEffective = isCurfewActive(context, calendar, useFailSecureEnabled = true)
        if (!activate && (napActive || (!isSnoozeActive(context) && isCurfewEffective))) {
            Log.w(TAG, "Security Alert: Block release rejected - night curfew window or active nap still holds!")
            BlackBoxRecorder.recordCurfew(
                context,
                "RELEASE_REJECTED",
                isSuccess = false,
                error = "Security invariant: release rejected (effective=$isCurfewEffective, nap=$napActive)"
            )
            return true
        }

        return if (activate) {
            CurfewLifecycleHandler.enforceActivation(context)
        } else {
            CurfewLifecycleHandler.enforceRelease(context)
        }
    }

    @Synchronized
    fun syncCurfewState(context: Context): Boolean {
        val shouldBeActive = isEffectiveBlockActive(context)
        Log.d(TAG, "Syncing curfew/block state: shouldBeActive = $shouldBeActive")
        return enforce(context, shouldBeActive)
    }

    fun syncCurfewStateAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext ?: context
        enforcerScope.launch {
            var success = false
            try {
                success = syncCurfewState(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncCurfewStateAsync", e)
            } finally {
                if (onComplete != null) {
                    withContext(Dispatchers.Main) {
                        onComplete.invoke(success)
                    }
                }
            }
        }
    }

    @Synchronized
    fun syncBlockState(context: Context): Boolean {
        val shouldBeActive = isEffectiveBlockActive(context)
        Log.d(TAG, "Syncing block state: shouldBeActive = $shouldBeActive")
        return enforce(context, shouldBeActive)
    }

    fun syncBlockStateAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext ?: context
        enforcerScope.launch {
            var success = false
            try {
                success = syncBlockState(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncBlockStateAsync", e)
            } finally {
                if (onComplete != null) {
                    withContext(Dispatchers.Main) {
                        onComplete.invoke(success)
                    }
                }
            }
        }
    }

    @Synchronized
    fun handlePackageAdded(context: Context, packageName: String) {
        CurfewLifecycleHandler.handlePackageAdded(context, packageName)
    }

    @Synchronized
    fun repairStuckSuspendedApps(context: Context): Int =
        CurfewLifecycleHandler.repairStuckSuspendedApps(context)

    private fun logWarn(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (e: RuntimeException) {
            if (e.message?.contains("not mocked") != true) throw e
        }
    }
}
