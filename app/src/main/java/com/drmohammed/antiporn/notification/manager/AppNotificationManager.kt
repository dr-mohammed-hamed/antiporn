package com.drmohammed.antiporn.notification.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.notification.model.InAppNotification
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.notification.repository.NotificationRepository
import com.drmohammed.antiporn.ui.notification.NotificationCenterActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Unified Central Notification Manager for AntiPorn.
 * Coordinates system status bar alerts and in-app persistent history,
 * strictly enforcing channel creation, cooldown throttling, and dedup invariants.
 */
class AppNotificationManager(
    context: Context,
    private val repository: NotificationRepository,
    private val managerScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val appContext = context.applicationContext

    init {
        createNotificationChannels()
    }

    /**
     * Checks if notification posting permission is granted.
     */
    fun canPostNotification(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }
    }

    /**
     * Creates all application notification channels centrally at startup.
     */
    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // 1. Watchdog Channel (MIN)
        val channelWatchdog = NotificationChannel(
            NotificationRegistry.CHANNEL_ID_WATCHDOG,
            appContext.getString(R.string.watchdog_notification_title),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = appContext.getString(R.string.watchdog_notification_desc)
            setShowBadge(false)
        }

        // 2. Curfew / VPN Ongoing Channel (LOW)
        val channelVpn = NotificationChannel(
            NotificationRegistry.CHANNEL_ID_PROTECTION_VPN,
            appContext.getString(R.string.protection_vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = appContext.getString(R.string.protection_vpn_channel_desc)
            setShowBadge(false)
        }

        // 3. Modesty AI Screen Channel (LOW)
        val channelAi = NotificationChannel(
            NotificationRegistry.CHANNEL_ID_AI,
            appContext.getString(R.string.ai_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = appContext.getString(R.string.ai_service_channel_desc)
            setShowBadge(false)
        }

        // 4. OTA Update Channel (LOW)
        val channelOta = NotificationChannel(
            NotificationRegistry.CHANNEL_ID_OTA,
            appContext.getString(R.string.ota_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = appContext.getString(R.string.ota_notification_channel_desc)
            setShowBadge(false)
        }

        // 5. Protection Events & Approvals Channel (HIGH)
        val channelEvents = NotificationChannel(
            NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
            appContext.getString(R.string.protection_events_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = appContext.getString(R.string.protection_events_channel_desc)
            setShowBadge(true)
            enableVibration(true)
        }

        // 6. ADB Pairing Channel (HIGH)
        val channelPairing = NotificationChannel(
            NotificationRegistry.CHANNEL_ID_PAIRING,
            appContext.getString(R.string.provisioning_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = appContext.getString(R.string.provisioning_channel_desc)
            setShowBadge(true)
        }

        // 7. Critical Alerts Channel (HIGH)
        val channelCritical = NotificationChannel(
            NotificationRegistry.CHANNEL_ID_CRITICAL_ALERTS,
            appContext.getString(R.string.critical_alerts_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = appContext.getString(R.string.critical_alerts_channel_desc)
            setShowBadge(true)
            enableVibration(true)
        }

        manager.createNotificationChannels(
            listOf(channelWatchdog, channelVpn, channelAi, channelOta, channelEvents, channelPairing, channelCritical)
        )
        Log.i(TAG, "All 7 NotificationChannels created/verified centrally via NotificationRegistry")
    }

    /**
     * Posts an event notification: records in persistent SQLite database and optionally
     * displays an Android status bar notification with cooldown throttling and deduplication.
     */
    fun postEventNotification(
        category: NotificationCategory,
        title: String,
        message: String,
        dedupKey: String? = null,
        cooldownMs: Long = 0L,
        actionType: NotificationActionType = NotificationActionType.NONE,
        actionPayload: String? = null,
        systemNotificationId: Int? = null,
        channelId: String = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
        postSystemNotification: Boolean = true,
        importance: Int = 1
    ) {
        managerScope.launch {
            try {
                val timestamp = TrustedTimeProvider.currentTimeMillis(appContext)
                val notification = InAppNotification(
                    dedupKey = dedupKey,
                    category = category,
                    title = title,
                    message = message,
                    timestamp = timestamp,
                    isRead = false,
                    actionType = actionType,
                    actionPayload = actionPayload,
                    importance = importance
                )

                // 1. Save to local database with cooldown guard
                val rowId = repository.saveNotification(notification, cooldownMs)
                if (rowId <= 0L && cooldownMs > 0L) {
                    Log.d(TAG, "Notification throttled by cooldown (dedupKey: $dedupKey)")
                    return@launch
                }

                // 2. Dispatch system notification if requested and permitted
                if (postSystemNotification && canPostNotification()) {
                    val notifId = systemNotificationId ?: (dedupKey?.hashCode() ?: rowId.toInt())
                    displaySystemNotification(
                        id = notifId,
                        channelId = channelId,
                        title = title,
                        message = message,
                        category = category,
                        actionType = actionType,
                        actionPayload = actionPayload
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error posting event notification [$title]", e)
            }
        }
    }

    /**
     * Displays a system status bar notification with appropriate category icon and click intent.
     */
    private fun displaySystemNotification(
        id: Int,
        channelId: String,
        title: String,
        message: String,
        category: NotificationCategory,
        actionType: NotificationActionType,
        actionPayload: String?
    ) {
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        // Destination: Open NotificationCenterActivity directly on tap
        val clickIntent = Intent(appContext, NotificationCenterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ACTION_TYPE, actionType.key)
            putExtra(EXTRA_ACTION_PAYLOAD, actionPayload)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPendingIntent = PendingIntent.getActivity(appContext, id, clickIntent, flags)

        val smallIconRes = resolveCategoryIcon(category)

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(
                if (channelId == NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS)
                    NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )

        notificationManager.notify(id, builder.build())
    }

    /**
     * Resolves the proper shield/category drawable for status bar presentation.
     */
    private fun resolveCategoryIcon(category: NotificationCategory): Int {
        return when (category) {
            NotificationCategory.APPROVALS -> R.drawable.ic_shield_freeze
            NotificationCategory.GUARDIAN -> R.drawable.ic_shield_device_owner
            NotificationCategory.CURFEW -> R.drawable.ic_shield_curfew
            NotificationCategory.SHIELDS -> R.drawable.ic_shield_dns
            NotificationCategory.AI -> R.drawable.ic_ai_shield
            NotificationCategory.OTA -> R.drawable.ic_system_update
            NotificationCategory.SYSTEM -> R.drawable.ic_shield_device_owner
        }
    }

    /**
     * Cancels an active system notification by ID.
     */
    fun cancelSystemNotification(id: Int) {
        try {
            val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(id)
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling system notification $id", e)
        }
    }

    /**
     * Checks whether an in-app notification with the given dedupKey already exists in history.
     */
    fun hasNotificationWithDedupKey(dedupKey: String): Boolean {
        return repository.hasNotificationWithDedupKey(dedupKey)
    }

    companion object {
        private const val TAG = "AntiPorn_AppNotifMgr"
        const val EXTRA_ACTION_TYPE = "extra_action_type"
        const val EXTRA_ACTION_PAYLOAD = "extra_action_payload"

        @Volatile
        private var instance: AppNotificationManager? = null

        fun getInstance(context: Context): AppNotificationManager {
            return instance ?: synchronized(this) {
                instance ?: AppNotificationManager(
                    context.applicationContext,
                    NotificationRepository.getInstance(context.applicationContext)
                ).also { instance = it }
            }
        }
    }
}
