package com.drmohammed.antiporn.waqfa.engine

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import timber.log.Timber
import androidx.core.app.NotificationCompat
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.waqfa.model.WaqfaSession

/**
 * مساعد إرسال وتحديث وإلغاء إشعارات نظام «وَقْفَة» عبر [AppNotificationManager] ونظام الإشعارات المباشر.
 * يدعم العداد التنازلي التفاعلي الموثوق وتنبيهات ما قبل النهاية بـ 60 ثانية متوافقاً مع معايير الدستور وخالياً من الإيموجي.
 */
object WaqfaNotificationHelper {

    private const val TAG = "AntiPorn_WaqfaNotif"

    /**
     * إطلاق إشعار دائم ومثبت يحمل عداداً تنازلياً نشطاً في الوقت الفعلي في شريط الحالة.
     */
    fun postOngoingSessionNotification(context: Context, session: WaqfaSession) {
        try {
            val appContext = context.applicationContext
            val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // 1. عند النقر على جسم الإشعار: العودة فوراً للتطبيق المستهدف
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(session.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            val contentPendingIntent = if (launchIntent != null) {
                PendingIntent.getActivity(
                    appContext,
                    NotificationRegistry.NOTIF_WAQFA_ONGOING_ID,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else null

            // 2. زر الإجراء التفاعلي: إنهاء الجلسة الواعية فوراً
            val exitIntent = Intent(appContext, WaqfaAlarmReceiver::class.java).apply {
                action = WaqfaAlarmScheduler.ACTION_WAQFA_EARLY_EXIT
                putExtra(WaqfaAlarmScheduler.EXTRA_PACKAGE_NAME, session.packageName)
                putExtra(WaqfaAlarmScheduler.EXTRA_SESSION_ID, session.id)
            }
            val exitPendingIntent = PendingIntent.getBroadcast(
                appContext,
                NotificationRegistry.NOTIF_WAQFA_ONGOING_ID + 10,
                exitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val isOpen = session.isOpenSession || session.plannedEndAtTrustedMs <= 0L
            val title = if (isOpen) {
                appContext.getString(R.string.waqfa_notif_open_session_title, session.appName)
            } else {
                appContext.getString(R.string.waqfa_notif_ongoing_title, session.appName)
            }
            val msg = if (isOpen) {
                appContext.getString(R.string.waqfa_notif_open_session_msg)
            } else {
                appContext.getString(R.string.waqfa_notif_ongoing_msg)
            }
            val timerBaseMs = if (isOpen) session.startedAtTrustedMs else session.plannedEndAtTrustedMs

            val builder = NotificationCompat.Builder(appContext, NotificationRegistry.CHANNEL_ID_WAQFA_ONGOING)
                .setSmallIcon(R.drawable.ic_shield_dns)
                .setContentTitle(title)
                .setContentText(msg)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(!isOpen)
                .setWhen(timerBaseMs)
                .setShowWhen(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    appContext.getString(R.string.waqfa_notif_btn_early_exit),
                    exitPendingIntent
                )

            if (contentPendingIntent != null) {
                builder.setContentIntent(contentPendingIntent)
            }

            notificationManager.notify(NotificationRegistry.NOTIF_WAQFA_ONGOING_ID, builder.build())
            Timber.tag(TAG).i("Posted ongoing countdown notification for %s (isOpen=%b, plannedEnd=%d)", session.packageName, isOpen, session.plannedEndAtTrustedMs)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed posting ongoing session notification for %s", session.packageName)
        }
    }

    /**
     * إلغاء إشعار العداد التنازلي الحي فور انتهاء أو إلغاء الجلسة.
     */
    fun cancelOngoingSessionNotification(context: Context) {
        try {
            val notificationManager = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NotificationRegistry.NOTIF_WAQFA_ONGOING_ID)
            Timber.tag(TAG).d("Cancelled ongoing Waqfa countdown notification")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error cancelling ongoing Waqfa notification")
        }
    }

    /**
     * إطلاق تنبيه تحذيري مسبق برأس الشاشة (Heads-up) قبل انتهاء الوقت بـ 60 ثانية مع زر التمديد الطارئ.
     */
    fun postPreTimeoutWarningNotification(context: Context, session: WaqfaSession) {
        try {
            val appContext = context.applicationContext
            val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // النقر على التنبيه يعيد المستخدم للتطبيق المستهدف
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(session.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            val contentPendingIntent = if (launchIntent != null) {
                PendingIntent.getActivity(
                    appContext,
                    NotificationRegistry.NOTIF_WAQFA_WARNING_ID,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else null

            val title = appContext.getString(R.string.waqfa_notif_warning_title)
            val msg = appContext.getString(R.string.waqfa_notif_warning_msg, session.appName)

            val builder = NotificationCompat.Builder(appContext, NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS)
                .setSmallIcon(R.drawable.ic_shield_curfew)
                .setContentTitle(title)
                .setContentText(msg)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)

            if (contentPendingIntent != null) {
                builder.setContentIntent(contentPendingIntent)
            }

            // زر التمديد الطارئ لمرة واحدة فقط لإنقاذ العمل
            if (!session.isExtended) {
                val extendIntent = Intent(appContext, WaqfaAlarmReceiver::class.java).apply {
                    action = WaqfaAlarmScheduler.ACTION_WAQFA_EXTEND_SESSION
                    putExtra(WaqfaAlarmScheduler.EXTRA_PACKAGE_NAME, session.packageName)
                    putExtra(WaqfaAlarmScheduler.EXTRA_SESSION_ID, session.id)
                }
                val extendPendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    NotificationRegistry.NOTIF_WAQFA_WARNING_ID + 20,
                    extendIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_input_add,
                    appContext.getString(R.string.waqfa_notif_btn_extend_2m),
                    extendPendingIntent
                )
            }

            notificationManager.notify(NotificationRegistry.NOTIF_WAQFA_WARNING_ID, builder.build())
            Timber.tag(TAG).i("Posted pre-timeout warning notification (T-60s) for %s", session.packageName)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed posting pre-timeout warning notification for %s", session.packageName)
        }
    }

    /**
     * إلغاء تنبيه ما قبل النهاية.
     */
    fun cancelPreTimeoutWarningNotification(context: Context) {
        try {
            val notificationManager = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NotificationRegistry.NOTIF_WAQFA_WARNING_ID)
            Timber.tag(TAG).d("Cancelled pre-timeout warning notification")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error cancelling pre-timeout warning notification")
        }
    }

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
            Timber.tag(TAG).e(e, "Failed posting cooldown started notification")
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
            Timber.tag(TAG).e(e, "Failed posting cooldown ended notification")
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
            Timber.tag(TAG).e(e, "Failed posting session timeout notification")
        }
    }
}
