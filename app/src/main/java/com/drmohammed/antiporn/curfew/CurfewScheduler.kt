package com.drmohammed.antiporn.curfew

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.Calendar

/**
 * Schedules exact, Doze-exempt alarms via AlarmManager for curfew start and end times,
 * dynamically supporting distinct weekday and weekend schedules.
 */
object CurfewScheduler {

    private const val TAG = "AntiPorn_CurfewScheduler"
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
    fun getNextCurfewStart(context: Context, now: Calendar = CurfewConfig.getCurfewCalendar()): Calendar {
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
    fun getNextCurfewEnd(context: Context, now: Calendar = CurfewConfig.getCurfewCalendar()): Calendar {
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

        val now = CurfewConfig.getCurfewCalendar()
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
            setExactCompat(alarmManager, AlarmManager.RTC_WAKEUP, nextStart.timeInMillis, startPendingIntent)
            setExactCompat(alarmManager, AlarmManager.RTC_WAKEUP, nextEnd.timeInMillis, endPendingIntent)
            Log.i(TAG, "Scheduled exact curfew alarms: Start at ${nextStart.time}, End at ${nextEnd.time}")
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
     * Schedules the dual nap-end alarm (ELAPSED_REALTIME_WAKEUP + RTC_WAKEUP) for the given wall-clock end.
     * Uses distinct PendingIntent request codes so neither alarm overwrites the other.
     */
    fun scheduleNapEndAlarm(context: Context, triggerWallMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Log.e(TAG, "AlarmManager not available for nap end")
            return
        }
        try {
            val piElapsed = buildNapEndPendingIntent(context, NapConfig.REQUEST_CODE_NAP_END_ELAPSED)
            val piRtc = buildNapEndPendingIntent(context, NapConfig.REQUEST_CODE_NAP_END_RTC)
            var elapsedDelay = triggerWallMs - System.currentTimeMillis()
            if (elapsedDelay <= 0) elapsedDelay = 1000L
            val elapsedTrigger = SystemClock.elapsedRealtime() + elapsedDelay
            setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, piElapsed)
            setExactCompat(alarmManager, AlarmManager.RTC_WAKEUP, triggerWallMs, piRtc)
            Log.i(TAG, "Scheduled dual nap end alarms: wall=$triggerWallMs elapsedTrigger=$elapsedTrigger")
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
        setExactCompat(alarmManager, AlarmManager.RTC_WAKEUP, triggerWallMs, pi)
        Log.i(TAG, "Scheduled snooze expiration alarm at $triggerWallMs")
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
            Log.i(TAG, "Cancelled curfew snooze alarm")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel curfew snooze alarm", e)
        }
    }
}
