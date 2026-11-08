package com.drmohammed.antiporn.waqfa.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.waqfa.model.WaqfaSession

/**
 * مجدول المنبهات الدقيقة (Exact Alarms) لنظام «وَقْفَة».
 * يضمن تفعيل ردود فعل انتهاء الجلسات وفك التجميد بدقة رتيبة متناهية (ELAPSED_REALTIME_WAKEUP).
 */
object WaqfaAlarmScheduler {

    private const val TAG = "AntiPorn_WaqfaAlarm"

    const val ACTION_WAQFA_SESSION_TIMEOUT = "com.drmohammed.antiporn.waqfa.ACTION_SESSION_TIMEOUT"
    const val ACTION_WAQFA_COOLDOWN_EXPIRED = "com.drmohammed.antiporn.waqfa.ACTION_COOLDOWN_EXPIRED"

    const val EXTRA_PACKAGE_NAME = "extra_package_name"
    const val EXTRA_APP_NAME = "extra_app_name"
    const val EXTRA_SESSION_ID = "extra_session_id"
    const val EXTRA_DURATION_MINUTES = "extra_duration_minutes"

    private fun getRequestCodeForPackage(action: String, packageName: String): Int {
        return (action.hashCode() xor packageName.hashCode()) and 0x7FFFFFFF
    }

    fun scheduleSessionTimeoutAlarm(context: Context, session: WaqfaSession) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Log.e(TAG, "AlarmManager not available for session timeout")
            return
        }

        val intent = Intent(context, WaqfaAlarmReceiver::class.java).apply {
            action = ACTION_WAQFA_SESSION_TIMEOUT
            putExtra(EXTRA_PACKAGE_NAME, session.packageName)
            putExtra(EXTRA_APP_NAME, session.appName)
            putExtra(EXTRA_SESSION_ID, session.id)
            putExtra(EXTRA_DURATION_MINUTES, session.durationMinutes)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val reqCode = getRequestCodeForPackage(ACTION_WAQFA_SESSION_TIMEOUT, session.packageName)
        val pendingIntent = PendingIntent.getBroadcast(context, reqCode, intent, flags)

        try {
            val elapsedTrigger = TrustedTimeProvider.computeElapsedTrigger(session.plannedEndAtTrustedMs)
            setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pendingIntent)
            Log.i(TAG, "Scheduled session timeout alarm for ${session.packageName} at elapsed=$elapsedTrigger")
        } catch (e: Exception) {
            Log.e(TAG, "Failed scheduling session timeout alarm for ${session.packageName}", e)
        }
    }

    fun cancelSessionTimeoutAlarm(context: Context, packageName: String) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, WaqfaAlarmReceiver::class.java).apply {
                action = ACTION_WAQFA_SESSION_TIMEOUT
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val reqCode = getRequestCodeForPackage(ACTION_WAQFA_SESSION_TIMEOUT, packageName)
            val pendingIntent = PendingIntent.getBroadcast(context, reqCode, intent, flags)
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Cancelled session timeout alarm for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling session timeout alarm for $packageName", e)
        }
    }

    fun scheduleCooldownEndAlarm(context: Context, packageName: String, appName: String, triggerWallMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Log.e(TAG, "AlarmManager not available for cooldown alarm")
            return
        }

        val intent = Intent(context, WaqfaAlarmReceiver::class.java).apply {
            action = ACTION_WAQFA_COOLDOWN_EXPIRED
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_APP_NAME, appName)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val reqCode = getRequestCodeForPackage(ACTION_WAQFA_COOLDOWN_EXPIRED, packageName)
        val pendingIntent = PendingIntent.getBroadcast(context, reqCode, intent, flags)

        try {
            val elapsedTrigger = TrustedTimeProvider.computeElapsedTrigger(triggerWallMs)
            setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pendingIntent)
            Log.i(TAG, "Scheduled cooldown end alarm for $packageName at elapsed=$elapsedTrigger (wall=$triggerWallMs)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed scheduling cooldown alarm for $packageName", e)
        }
    }

    fun cancelCooldownEndAlarm(context: Context, packageName: String) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, WaqfaAlarmReceiver::class.java).apply {
                action = ACTION_WAQFA_COOLDOWN_EXPIRED
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val reqCode = getRequestCodeForPackage(ACTION_WAQFA_COOLDOWN_EXPIRED, packageName)
            val pendingIntent = PendingIntent.getBroadcast(context, reqCode, intent, flags)
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Cancelled cooldown end alarm for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling cooldown alarm for $packageName", e)
        }
    }

    private fun setExactCompat(
        alarmManager: AlarmManager,
        type: Int,
        triggerAtMillis: Long,
        operation: PendingIntent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(type, triggerAtMillis, operation)
        } else {
            alarmManager.setExact(type, triggerAtMillis, operation)
        }
    }
}
