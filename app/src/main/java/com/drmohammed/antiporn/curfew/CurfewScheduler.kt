package com.drmohammed.antiporn.curfew

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import java.util.Calendar

/**
 * Schedules exact, Doze-exempt alarms via AlarmManager for curfew start and end times,
 * dynamically supporting distinct weekday and weekend schedules.
 */
object CurfewScheduler {

    private const val TAG = "AntiPorn_CurfewScheduler"
    private const val REQUEST_CODE_PRE_WARNING = 3000
    private const val REQUEST_CODE_START = 3001
    private const val REQUEST_CODE_END = 3002

    /**
     * Returns the curfew start hour and minute for a specific day of the week,
     * accounting for weekend curfew schedules when enabled.
     */
    fun getCurfewStartTimeForDay(context: Context, dayOfWeek: Int): Pair<Int, Int> {
        return if (CurfewConfig.isWeekendCurfewEnabled(context) &&
            (dayOfWeek == Calendar.THURSDAY || dayOfWeek == Calendar.FRIDAY || dayOfWeek == Calendar.SATURDAY)
        ) {
            CurfewConfig.getWeekendCurfewStartHour(context) to CurfewConfig.getWeekendCurfewStartMinute(context)
        } else {
            CurfewConfig.getCurfewStartHour(context) to CurfewConfig.getCurfewStartMinute(context)
        }
    }

    /**
     * Returns the curfew end hour and minute for a specific day of the week,
     * accounting for weekend curfew schedules when enabled.
     */
    fun getCurfewEndTimeForDay(context: Context, dayOfWeek: Int): Pair<Int, Int> {
        return if (CurfewConfig.isWeekendCurfewEnabled(context) &&
            (dayOfWeek == Calendar.FRIDAY || dayOfWeek == Calendar.SATURDAY)
        ) {
            CurfewConfig.getWeekendCurfewEndHour(context) to CurfewConfig.getWeekendCurfewEndMinute(context)
        } else {
            CurfewConfig.getCurfewEndHour(context) to CurfewConfig.getCurfewEndMinute(context)
        }
    }

    /**
     * Calculates the next calendar occurrence for curfew start, dynamically evaluating
     * day-specific and weekend-specific schedules across upcoming days.
     */
    fun getNextCurfewStart(context: Context, now: Calendar = CurfewConfig.getCurfewCalendar(context)): Calendar {
        for (dayOffset in 0..7) {
            val candidate = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
                val dayOfWeek = get(Calendar.DAY_OF_WEEK)
                val (hour, minute) = getCurfewStartTimeForDay(context, dayOfWeek)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis > now.timeInMillis) {
                return candidate
            }
        }
        return getNextOccurrence(CurfewConfig.getCurfewStartHour(context), CurfewConfig.getCurfewStartMinute(context), now)
    }

    /**
     * Calculates the next calendar occurrence for curfew end, dynamically evaluating
     * day-specific and weekend-specific schedules across upcoming days.
     */
    fun getNextCurfewEnd(context: Context, now: Calendar = CurfewConfig.getCurfewCalendar(context)): Calendar {
        for (dayOffset in 0..7) {
            val candidate = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
                val dayOfWeek = get(Calendar.DAY_OF_WEEK)
                val (hour, minute) = getCurfewEndTimeForDay(context, dayOfWeek)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis > now.timeInMillis) {
                return candidate
            }
        }
        return getNextOccurrence(CurfewConfig.getCurfewEndHour(context), CurfewConfig.getCurfewEndMinute(context), now)
    }

    /**
     * Calculates next calendar occurrence for a given hour and minute.
     * If the time today has already passed, schedules for tomorrow.
     */
    fun getNextOccurrence(hour: Int, minute: Int, now: Calendar = CurfewConfig.getCurfewCalendar()): Calendar {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target
    }

    /**
     * Schedules the next exact alarms for Curfew Start and Curfew End,
     * properly accounting for weekday vs weekend timings.
     */
    fun scheduleNextAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Log.e(TAG, "AlarmManager not available")
            return
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val now = CurfewConfig.getCurfewCalendar(context)
        val nextStart = getNextCurfewStart(context, now)
        val nextEnd = getNextCurfewEnd(context, now)

        val startIntent = Intent(context, CurfewAlarmReceiver::class.java).apply {
            action = CurfewAlarmReceiver.ACTION_CURFEW_START
        }
        val startPendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_START, startIntent, flags)

        val endIntent = Intent(context, CurfewAlarmReceiver::class.java).apply {
            action = CurfewAlarmReceiver.ACTION_CURFEW_END
        }
        val endPendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_END, endIntent, flags)

        try {
            val startElapsed = TrustedTimeProvider.computeElapsedTrigger(nextStart.timeInMillis)
            val endElapsed = TrustedTimeProvider.computeElapsedTrigger(nextEnd.timeInMillis)
            setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, startElapsed, startPendingIntent)
            setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, endElapsed, endPendingIntent)

            // Pre-Curfew 15-Minute Warning Alarm (Strictly guarded against retroactive triggers)
            val nowMs = TrustedTimeProvider.currentTimeMillis(context)
            val preWarningMs = nextStart.timeInMillis - 15 * 60 * 1000L
            if (preWarningMs > nowMs) {
                val preWarningIntent = Intent(context, CurfewAlarmReceiver::class.java).apply {
                    action = CurfewAlarmReceiver.ACTION_CURFEW_PRE_WARNING
                }
                val preWarningPendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_PRE_WARNING, preWarningIntent, flags)
                val preElapsed = TrustedTimeProvider.computeElapsedTrigger(preWarningMs)
                setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, preElapsed, preWarningPendingIntent)
                Log.i(TAG, "Scheduled pre-curfew 15-minute warning alarm at $preWarningMs (elapsed=$preElapsed)")
            } else {
                Log.d(TAG, "Skipping pre-curfew warning for today because remaining time is less than 15 minutes")
            }

            Log.i(TAG, "Scheduled exact curfew alarms via ELAPSED_REALTIME: Start at ${nextStart.time} (elapsed=$startElapsed), End at ${nextEnd.time} (elapsed=$endElapsed)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule exact alarms via AlarmManager", e)
        }
    }

    private fun buildNapEndPendingIntent(context: Context, requestCode: Int = NapConfig.REQUEST_CODE_NAP_END): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val napIntent = Intent(context, CurfewAlarmReceiver::class.java).apply {
            action = NapConfig.ACTION_NAP_END
        }
        return PendingIntent.getBroadcast(context, requestCode, napIntent, flags)
    }

    private fun setExactCompat(alarmManager: AlarmManager, type: Int, triggerMs: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(type, triggerMs, pi)
                } else {
                    alarmManager.setAndAllowWhileIdle(type, triggerMs, pi)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(type, triggerMs, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm permission restricted; falling back to inexact alarm", e)
            alarmManager.setAndAllowWhileIdle(type, triggerMs, pi)
        }
    }

    /**
     * Schedules the nap-end alarm via ELAPSED_REALTIME_WAKEUP computed from monotonic hardware elapsed time.
     */
    fun scheduleNapEndAlarm(context: Context, triggerWallMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Log.e(TAG, "AlarmManager not available for nap end")
            return
        }
        try {
            val piElapsed = buildNapEndPendingIntent(context, NapConfig.REQUEST_CODE_NAP_END_ELAPSED)
            val elapsedTrigger = TrustedTimeProvider.computeElapsedTrigger(triggerWallMs)
            setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, piElapsed)
            Log.i(TAG, "Scheduled nap end alarm via ELAPSED_REALTIME: wall=$triggerWallMs elapsedTrigger=$elapsedTrigger")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule nap end alarms", e)
        }
    }

    /**
     * Cancels both nap-end alarms (RTC and ELAPSED).
     */
    fun cancelNapEndAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val piElapsed = buildNapEndPendingIntent(context, NapConfig.REQUEST_CODE_NAP_END_ELAPSED)
            val piRtc = buildNapEndPendingIntent(context, NapConfig.REQUEST_CODE_NAP_END_RTC)
            alarmManager.cancel(piElapsed)
            alarmManager.cancel(piRtc)
            piElapsed.cancel()
            piRtc.cancel()
            Log.i(TAG, "Cancelled dual nap end alarms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel nap end alarms", e)
        }
    }

    private const val REQUEST_CODE_SNOOZE = 3003

    fun scheduleCurfewSnoozeAlarm(context: Context, triggerWallMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(context, CurfewAlarmReceiver::class.java).apply {
            action = CurfewConfig.ACTION_CURFEW_SNOOZE_END
        }
        val pi = PendingIntent.getBroadcast(context, REQUEST_CODE_SNOOZE, intent, flags)
        val elapsedTrigger = TrustedTimeProvider.computeElapsedTrigger(triggerWallMs)
        setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pi)
        Log.i(TAG, "Scheduled snooze expiration alarm via ELAPSED_REALTIME at $triggerWallMs (elapsed=$elapsedTrigger)")
    }

    fun cancelCurfewSnoozeAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val intent = Intent(context, CurfewAlarmReceiver::class.java).apply {
                action = CurfewConfig.ACTION_CURFEW_SNOOZE_END
            }
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE_SNOOZE, intent, flags)
            alarmManager.cancel(pi)
            pi.cancel()
            Log.i(TAG, "Cancelled curfew snooze alarm")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel curfew snooze alarm", e)
        }
    }
}
