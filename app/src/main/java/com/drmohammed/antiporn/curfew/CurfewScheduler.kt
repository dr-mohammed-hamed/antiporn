package com.drmohammed.antiporn.curfew

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * Schedules exact, Doze-exempt alarms via AlarmManager for curfew start and end times.
 */
object CurfewScheduler {

    private const val TAG = "AntiPorn_CurfewScheduler"
    private const val REQUEST_CODE_START = 3001
    private const val REQUEST_CODE_END = 3002

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
     * Schedules the next exact alarms for 20:30 (Curfew Start) and 07:00 (Curfew End).
     */
    fun scheduleNextAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Log.e(TAG, "AlarmManager not available")
            return
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val now = CurfewConfig.getCurfewCalendar()
        val nextStart = getNextOccurrence(CurfewConfig.START_HOUR, CurfewConfig.START_MINUTE, now)
        val nextEnd = getNextOccurrence(CurfewConfig.END_HOUR, CurfewConfig.END_MINUTE, now)

        val startIntent = Intent(context, CurfewAlarmReceiver::class.java).apply {
            action = CurfewAlarmReceiver.ACTION_CURFEW_START
        }
        val startPendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_START, startIntent, flags)

        val endIntent = Intent(context, CurfewAlarmReceiver::class.java).apply {
            action = CurfewAlarmReceiver.ACTION_CURFEW_END
        }
        val endPendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_END, endIntent, flags)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextStart.timeInMillis,
                        startPendingIntent
                    )
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextEnd.timeInMillis,
                        endPendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextStart.timeInMillis,
                        startPendingIntent
                    )
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextEnd.timeInMillis,
                        endPendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextStart.timeInMillis,
                    startPendingIntent
                )
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextEnd.timeInMillis,
                    endPendingIntent
                )
            }
            Log.i(TAG, "Scheduled exact curfew alarms: Start at ${nextStart.time}, End at ${nextEnd.time}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule exact alarms via AlarmManager", e)
        }
    }
}
