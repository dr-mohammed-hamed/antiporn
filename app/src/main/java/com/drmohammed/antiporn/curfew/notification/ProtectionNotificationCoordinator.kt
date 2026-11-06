package com.drmohammed.antiporn.curfew.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.NapController
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import java.util.Calendar

/**
 * Unified Central Coordinator for all Curfew, Nap, and Guardian Lockdown notifications.
 * Enforces Clean Architecture Single-Source-of-Truth and eliminates semantic mismatch.
 */
object ProtectionNotificationCoordinator {

    private const val TAG = "AntiPorn_NotifCoord"

    const val CHANNEL_ID_PROTECTION = NotificationRegistry.CHANNEL_ID_PROTECTION_VPN
    const val CHANNEL_ID_EVENTS = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS

    const val NOTIFICATION_ID_PROTECTION = NotificationRegistry.FGS_CURFEW_VPN_ID
    const val NOTIFICATION_ID_EVENTS = NotificationRegistry.EVENT_NAP_ENDED_ID

    sealed class ProtectionState {
        data class GuardianLockdown(val isCurfewAlsoActive: Boolean, val isNapAlsoActive: Boolean) : ProtectionState()
        data class NapActive(val endWallMs: Long, val remainingMs: Long) : ProtectionState()
        data class CurfewActive(val endHour: Int, val endMinute: Int) : ProtectionState()
        data class AppsOnlyActive(val isNap: Boolean, val endHour: Int, val endMinute: Int, val endWallMs: Long) : ProtectionState()
        object Inactive : ProtectionState()
    }

    fun canPostNotification(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannels(context: Context) {
        AppNotificationManager.getInstance(context).createNotificationChannels()
    }

    fun getActiveCurfewEndHourAndMinute(context: Context): Pair<Int, Int> {
        val cal = CurfewConfig.getCurfewCalendar(context)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val isWeekendCurfew = CurfewConfig.isWeekendCurfewEnabled(context)
        return if (isWeekendCurfew && (dayOfWeek == Calendar.FRIDAY || dayOfWeek == Calendar.SATURDAY)) {
            Pair(CurfewConfig.getWeekendCurfewEndHour(context), CurfewConfig.getWeekendCurfewEndMinute(context))
        } else {
            Pair(CurfewConfig.getCurfewEndHour(context), CurfewConfig.getCurfewEndMinute(context))
        }
    }

    fun resolveCurrentState(context: Context): ProtectionState {
        val instantKill = CurfewConfig.isInstantInternetKillEnabled(context)
        val napActive = NapController.isNapActive(context)
        val curfewActive = CurfewEnforcer.isEffectiveCurfewActive(context)
        val vpnEnabled = CurfewConfig.isVpnBlackholeEnabled(context)

        return when {
            instantKill -> {
                ProtectionState.GuardianLockdown(
                    isCurfewAlsoActive = curfewActive,
                    isNapAlsoActive = napActive
                )
            }
            napActive -> {
                val endWall = NapController.getEndWall(context)
                val remaining = NapController.remainingMs(context)
                if (vpnEnabled) {
                    ProtectionState.NapActive(endWallMs = endWall, remainingMs = remaining)
                } else {
                    ProtectionState.AppsOnlyActive(
                        isNap = true,
                        endHour = 0,
                        endMinute = 0,
                        endWallMs = endWall
                    )
                }
            }
            curfewActive -> {
                val (endH, endM) = getActiveCurfewEndHourAndMinute(context)
                if (vpnEnabled) {
                    ProtectionState.CurfewActive(endHour = endH, endMinute = endM)
                } else {
                    ProtectionState.AppsOnlyActive(
                        isNap = false,
                        endHour = endH,
                        endMinute = endM,
                        endWallMs = 0L
                    )
                }
            }
            else -> ProtectionState.Inactive
        }
    }

    fun buildProtectionNotification(
        context: Context,
        state: ProtectionState = resolveCurrentState(context)
    ): Notification {
        ensureChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_PROTECTION)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)

        when (state) {
            is ProtectionState.GuardianLockdown -> {
                val title = context.getString(R.string.notif_guardian_lockdown_title)
                val desc = when {
                    state.isNapAlsoActive -> context.getString(R.string.notif_guardian_lockdown_desc_nap)
                    state.isCurfewAlsoActive -> context.getString(R.string.notif_guardian_lockdown_desc_curfew)
                    else -> context.getString(R.string.notif_guardian_lockdown_desc)
                }
                builder.setContentTitle(title)
                    .setContentText(desc)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(desc))
                    .setSmallIcon(R.drawable.ic_shield_vpn)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
            }
            is ProtectionState.NapActive -> {
                val title = context.getString(R.string.notif_nap_active_title)
                val desc = context.getString(R.string.notif_nap_active_desc)
                builder.setContentTitle(title)
                    .setContentText(desc)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(desc))
                    .setSmallIcon(R.drawable.ic_shield_curfew)
                    .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                    .setUsesChronometer(true)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder.setChronometerCountDown(true)
                }
                builder.setWhen(state.endWallMs)
                builder.setShowWhen(true)

                val extendIntent = Intent(context, NapNotificationReceiver::class.java).apply {
                    action = NapNotificationReceiver.ACTION_EXTEND_NAP
                }
                val extendPendingIntent = PendingIntent.getBroadcast(
                    context,
                    101,
                    extendIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                )
                builder.addAction(
                    R.drawable.ic_shield_curfew,
                    context.getString(R.string.notif_action_extend_nap),
                    extendPendingIntent
                )
            }
            is ProtectionState.CurfewActive -> {
                val title = context.getString(R.string.notif_curfew_active_title)
                val desc = context.getString(R.string.notif_curfew_active_desc_format, state.endHour, state.endMinute)
                builder.setContentTitle(title)
                    .setContentText(desc)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(desc))
                    .setSmallIcon(R.drawable.ic_shield_curfew)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
            }
            is ProtectionState.AppsOnlyActive -> {
                val title = context.getString(R.string.notif_apps_suspended_title)
                val desc = if (state.isNap) {
                    context.getString(R.string.notif_apps_suspended_nap_desc)
                } else {
                    context.getString(R.string.notif_apps_suspended_curfew_desc_format, state.endHour, state.endMinute)
                }
                builder.setContentTitle(title)
                    .setContentText(desc)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(desc))
                    .setSmallIcon(R.drawable.ic_shield_freeze)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)

                if (state.isNap && state.endWallMs > 0L) {
                    builder.setUsesChronometer(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        builder.setChronometerCountDown(true)
                    }
                    builder.setWhen(state.endWallMs)
                    builder.setShowWhen(true)

                    val extendIntent = Intent(context, NapNotificationReceiver::class.java).apply {
                        action = NapNotificationReceiver.ACTION_EXTEND_NAP
                    }
                    val extendPendingIntent = PendingIntent.getBroadcast(
                        context,
                        101,
                        extendIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                    )
                    builder.addAction(
                        R.drawable.ic_shield_freeze,
                        context.getString(R.string.notif_action_extend_nap),
                        extendPendingIntent
                    )
                }
            }
            is ProtectionState.Inactive -> {
                builder.setContentTitle(context.getString(R.string.curfew_vpn_notification_title))
                    .setContentText(context.getString(R.string.curfew_vpn_notification_desc))
                    .setSmallIcon(R.drawable.ic_shield_curfew)
            }
        }

        return builder.build()
    }

    @Synchronized
    fun syncProtectionNotification(context: Context) {
        val state = resolveCurrentState(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (state is ProtectionState.Inactive) {
            if (!CurfewBlackholeVpnService.isRunning) {
                try {
                    notificationManager.cancel(NOTIFICATION_ID_PROTECTION)
                } catch (e: Exception) {
                    Log.w(TAG, "Error cancelling protection notification", e)
                }
            }
            return
        }

        if (canPostNotification(context)) {
            try {
                notificationManager.notify(NOTIFICATION_ID_PROTECTION, buildProtectionNotification(context, state))
            } catch (e: Exception) {
                Log.e(TAG, "Error posting protection notification", e)
            }
        }
    }

    fun postNapEndedNotification(context: Context) {
        if (!canPostNotification(context)) return
        try {
            ensureChannels(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val descRes = if (CurfewEnforcer.isEffectiveBlockActive(context)) {
                R.string.notif_nap_ended_desc_curfew_still_active
            } else {
                R.string.notif_nap_ended_desc
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID_EVENTS)
                .setSmallIcon(R.drawable.ic_shield_curfew)
                .setContentTitle(context.getString(R.string.notif_nap_ended_title))
                .setContentText(context.getString(descRes))
                .setContentIntent(contentPendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            notificationManager.notify(NOTIFICATION_ID_EVENTS, notification)

            try {
                AppNotificationManager.getInstance(context).postEventNotification(
                    category = NotificationCategory.CURFEW,
                    title = context.getString(R.string.notif_nap_ended_title),
                    message = context.getString(descRes),
                    dedupKey = "nap_ended_${TrustedTimeProvider.currentTimeMillis(context) / 60000}",
                    cooldownMs = 60_000L,
                    actionType = NotificationActionType.NONE,
                    postSystemNotification = false
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error recording nap ended in notification center", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error posting nap ended notification", e)
        }
    }
}

