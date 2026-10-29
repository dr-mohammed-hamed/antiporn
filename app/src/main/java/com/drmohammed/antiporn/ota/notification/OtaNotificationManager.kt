package com.drmohammed.antiporn.ota.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.ota.model.OtaVersionInfo

/**
 * Manages subtle, low-importance system notifications for OTA auto-update workflows.
 * Adheres to silent installation requirements with discreet status indicators.
 */
object OtaNotificationManager {

    private const val TAG = "AntiPorn_OtaNotif"
    const val CHANNEL_ID = "antiporn_ota_updates"
    private const val NOTIFICATION_ID = 2005

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val channelName = context.getString(R.string.ota_notification_channel_name)
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.ota_notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun canPostNotification(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Displays or updates a subtle progress notification while the update is downloading.
     */
    fun showProgressNotification(context: Context, percent: Int, info: OtaVersionInfo) {
        if (!canPostNotification(context)) {
            Log.d(TAG, "POST_NOTIFICATIONS permission not granted, skipping progress notification")
            return
        }

        try {
            ensureChannel(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val progressText = if (percent >= 0) {
                context.getString(R.string.ota_downloading, percent)
            } else {
                context.getString(R.string.ota_notification_downloading_indeterminate)
            }

            val isIndeterminate = percent < 0
            val currentProgress = percent.coerceIn(0, 100)

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_system_update)
                .setContentTitle(context.getString(R.string.ota_notification_title, info.versionName))
                .setContentText(progressText)
                .setProgress(100, currentProgress, isIndeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException while posting progress notification: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error posting progress notification", e)
        }
    }

    /**
     * Displays a discreet notification indicating package installation is in progress.
     */
    fun showInstallingNotification(context: Context, info: OtaVersionInfo) {
        if (!canPostNotification(context)) {
            Log.d(TAG, "POST_NOTIFICATIONS permission not granted, skipping installing notification")
            return
        }

        try {
            ensureChannel(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_system_update)
                .setContentTitle(context.getString(R.string.ota_notification_title, info.versionName))
                .setContentText(context.getString(R.string.ota_notification_installing, info.versionName))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException while posting installing notification: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error posting installing notification", e)
        }
    }

    /**
     * Displays a notification confirming successful update completion.
     */
    fun showSuccessNotification(context: Context, versionName: String) {
        if (!canPostNotification(context)) {
            Log.d(TAG, "POST_NOTIFICATIONS permission not granted, skipping success notification")
            return
        }

        try {
            ensureChannel(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_system_update)
                .setContentTitle(context.getString(R.string.card_ota_title))
                .setContentText(context.getString(R.string.ota_notification_success, versionName))
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException while posting success notification: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error posting success notification", e)
        }
    }

    /**
     * Cancels any active OTA update notification.
     */
    fun cancelNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling OTA notification: ${e.message}")
        }
    }
}
