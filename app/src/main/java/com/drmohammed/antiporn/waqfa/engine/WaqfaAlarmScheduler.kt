package com.drmohammed.antiporn.waqfa.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber
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
    const val ACTION_WAQFA_SESSION_WARNING = "com.drmohammed.antiporn.waqfa.ACTION_SESSION_WARNING"
    const val ACTION_WAQFA_EARLY_EXIT = "com.drmohammed.antiporn.waqfa.ACTION_EARLY_EXIT"
    const val ACTION_WAQFA_EXTEND_SESSION = "com.drmohammed.antiporn.waqfa.ACTION_EXTEND_SESSION"

    const val EXTRA_PACKAGE_NAME = "extra_package_name"
    const val EXTRA_APP_NAME = "extra_app_name"
    const val EXTRA_SESSION_ID = "extra_session_id"
    const val EXTRA_DURATION_MINUTES = "extra_duration_minutes"

    private fun getRequestCodeForPackage(action: String, packageName: String): Int {
        return (action.hashCode() xor packageName.hashCode()) and 0x7FFFFFFF
    }

    fun scheduleSessionTimeoutAlarm(context: Context, session: WaqfaSession) {
        if (session.isOpenSession) {
            Timber.tag(TAG).d("Session for %s is open session. Skipping timeout alarm.", session.packageName)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Timber.tag(TAG).e("AlarmManager not available for session timeout")
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
            Timber.tag(TAG).i("Scheduled session timeout alarm for %s at elapsed=%d", session.packageName, elapsedTrigger)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed scheduling session timeout alarm for %s", session.packageName)
        }
    }

    fun schedulePreTimeoutWarningAlarm(context: Context, session: WaqfaSession) {
        if (session.isOpenSession) {
            Timber.tag(TAG).d("Session for %s is open session. Skipping warning alarm.", session.packageName)
            return
        }

        val nowTrusted = TrustedTimeProvider.currentTimeMillis(context)
        val remainingMs = session.remainingMs(nowTrusted)
        // لا نجدول إنذار الدقيقة إلا إذا كانت المدة المتبقية تتجاوز 75 ثانية لضمان دقة التنفيذ
        if (remainingMs <= 75_000L) {
            Timber.tag(TAG).d("Remaining time <= 75s (%d ms), skipping pre-timeout warning alarm for %s", remainingMs, session.packageName)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Timber.tag(TAG).e("AlarmManager not available for session warning")
            return
        }

        val warningWallMs = session.plannedEndAtTrustedMs - 60_000L
        val intent = Intent(context, WaqfaAlarmReceiver::class.java).apply {
            action = ACTION_WAQFA_SESSION_WARNING
            putExtra(EXTRA_PACKAGE_NAME, session.packageName)
            putExtra(EXTRA_APP_NAME, session.appName)
            putExtra(EXTRA_SESSION_ID, session.id)
            putExtra(EXTRA_DURATION_MINUTES, session.durationMinutes)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val reqCode = getRequestCodeForPackage(ACTION_WAQFA_SESSION_WARNING, session.packageName)
        val pendingIntent = PendingIntent.getBroadcast(context, reqCode, intent, flags)

        try {
            val elapsedTrigger = TrustedTimeProvider.computeElapsedTrigger(warningWallMs)
            setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pendingIntent)
            Timber.tag(TAG).i("Scheduled pre-timeout warning alarm for %s at elapsed=%d (wallMs=%d)", session.packageName, elapsedTrigger, warningWallMs)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed scheduling pre-timeout warning alarm for %s", session.packageName)
        }
    }

    fun cancelPreTimeoutWarningAlarm(context: Context, packageName: String) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, WaqfaAlarmReceiver::class.java).apply {
                action = ACTION_WAQFA_SESSION_WARNING
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val reqCode = getRequestCodeForPackage(ACTION_WAQFA_SESSION_WARNING, packageName)
            val pendingIntent = PendingIntent.getBroadcast(context, reqCode, intent, flags)
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Timber.tag(TAG).d("Cancelled pre-timeout warning alarm for %s", packageName)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error cancelling pre-timeout warning alarm for %s", packageName)
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
            Timber.tag(TAG).d("Cancelled session timeout alarm for %s", packageName)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error cancelling session timeout alarm for %s", packageName)
        }
    }

    fun scheduleCooldownEndAlarm(context: Context, packageName: String, appName: String, triggerWallMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Timber.tag(TAG).e("AlarmManager not available for cooldown alarm")
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
            Timber.tag(TAG).i("Scheduled cooldown end alarm for %s at elapsed=%d (wall=%d)", packageName, elapsedTrigger, triggerWallMs)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed scheduling cooldown alarm for %s", packageName)
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
            Timber.tag(TAG).d("Cancelled cooldown end alarm for %s", packageName)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error cancelling cooldown alarm for %s", packageName)
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
