package com.drmohammed.antiporn.waqfa.engine

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationCategory

/**
 * مساعد إرسال إشعارات نظام «وَقْفَة» عبر [AppNotificationManager].
 * يدعم التنبيهات المباشرة والتسجيل في مركز الإشعارات الداخلي، متوافق 100% مع معايير الدستور وخالٍ من الإيموجي.
 */
object WaqfaNotificationHelper {

    private const val TAG = "AntiPorn_WaqfaNotif"

    fun postCooldownStartedNotification(context: Context, appName: String, cooldownMinutes: Int) {
        try {
            val title = context.getString(R.string.waqfa_notif_cooldown_started_title)
            val message = context.getString(R.string.waqfa_notif_cooldown_started_msg, appName, cooldownMinutes)
            AppNotificationManager.getInstance(context.applicationContext).postEventNotification(
                category = NotificationCategory.SHIELDS,
                title = title,
                message = message,
                dedupKey = "waqfa_freeze_${appName}_${System.currentTimeMillis() / 60_000}",
                cooldownMs = 10_000L,
                postSystemNotification = true,
                importance = 2
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed posting cooldown started notification", e)
        }
    }

    fun postCooldownEndedNotification(context: Context, appName: String) {
        try {
            val title = context.getString(R.string.waqfa_notif_cooldown_ended_title)
            val message = context.getString(R.string.waqfa_notif_cooldown_ended_msg, appName)
            AppNotificationManager.getInstance(context.applicationContext).postEventNotification(
                category = NotificationCategory.SHIELDS,
                title = title,
                message = message,
                dedupKey = "waqfa_unfreeze_${appName}_${System.currentTimeMillis() / 60_000}",
                cooldownMs = 10_000L,
                postSystemNotification = true,
                importance = 1
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed posting cooldown ended notification", e)
        }
    }

    fun postSessionTimeoutNotification(context: Context, appName: String, durationMinutes: Int) {
        try {
            val title = context.getString(R.string.waqfa_notif_session_timeout_title)
            val message = context.getString(R.string.waqfa_notif_session_timeout_msg, durationMinutes, appName)
            AppNotificationManager.getInstance(context.applicationContext).postEventNotification(
                category = NotificationCategory.SHIELDS,
                title = title,
                message = message,
                dedupKey = "waqfa_timeout_${appName}_${System.currentTimeMillis() / 60_000}",
                cooldownMs = 10_000L,
                postSystemNotification = true,
                importance = 2
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed posting session timeout notification", e)
        }
    }
}
