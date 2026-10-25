package com.drmohammed.antiporn.provisioning.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.PromotionResult
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import android.app.PendingIntent
import android.os.Build
import android.provider.Settings
import com.drmohammed.antiporn.provisioning.engine.AccountAuditor
import com.drmohammed.antiporn.provisioning.ui.ProvisioningWizardActivity

/**
 * BroadcastReceiver triggered when the user enters the 6-digit pairing code
 * into the interactive notification drawer.
 */
class PairingCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val pairingCode = remoteInput?.getCharSequence(EXTRA_PAIRING_CODE)?.toString()?.trim()

        val pairingPort = intent.getIntExtra(EXTRA_PAIRING_PORT, 0).takeIf { it > 0 }
            ?: AdbPairingNotificationService.capturedPairingPort
        val connectPort = intent.getIntExtra(EXTRA_CONNECT_PORT, 0).takeIf { it > 0 }
            ?: AdbPairingNotificationService.capturedConnectPort

        Log.i(TAG, "Received pairing code from notification: len=${pairingCode?.length}, pairingPort=$pairingPort, connectPort=$connectPort")

        if (pairingCode.isNullOrBlank() || pairingPort == 0 || connectPort == 0) {
            Log.w(TAG, "Invalid pairing payload: code=$pairingCode, pairingPort=$pairingPort, connectPort=$connectPort")
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val failureNotification = NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.provisioning_failed_title))
                .setContentText(context.getString(R.string.toast_open_settings_failed))
                .setAutoCancel(true)
                .build()
            notificationManager.notify(AdbPairingNotificationService.NOTIFICATION_ID, failureNotification)
            return
        }

        val appContext = context.applicationContext

        // Show "Configuring Device Owner..." in notification while processing
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val inProgressNotification = NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.provisioning_in_progress_title))
            .setContentText(context.getString(R.string.provisioning_in_progress_desc))
            .setOngoing(true)
            .build()
        notificationManager.notify(AdbPairingNotificationService.NOTIFICATION_ID, inProgressNotification)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = DeviceOwnerPromoter.pairAndPromote(
                    context = appContext,
                    pairingPort = pairingPort,
                    connectPort = connectPort,
                    pairingCode = pairingCode
                )

                when (result) {
                    is PromotionResult.Success -> {
                        Log.i(TAG, "Device Owner successfully provisioned via Notification!")
                        AdbPairingNotificationService.stop(appContext)
                        val successNotification = NotificationCompat.Builder(appContext, AdbPairingNotificationService.CHANNEL_ID)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle(appContext.getString(R.string.provisioning_success_title))
                            .setContentText(appContext.getString(R.string.provisioning_success_desc))
                            .setAutoCancel(true)
                            .build()
                        notificationManager.notify(AdbPairingNotificationService.NOTIFICATION_ID, successNotification)

                        // Notify active UI (ProvisioningWizardActivity / MainActivity)
                        val successIntent = Intent(ACTION_PROVISIONING_RESULT).apply {
                            setPackage(appContext.packageName)
                            putExtra(EXTRA_SUCCESS, true)
                        }
                        appContext.sendBroadcast(successIntent)
                    }
                    is PromotionResult.Failure -> {
                        Log.e(TAG, "Device Owner provisioning failed: ${result.message}")

                        val accounts = AccountAuditor.getActiveAccounts(appContext)
                        val isAccountsIssue = accounts.isNotEmpty() && (
                            result.message.contains("accounts", ignoreCase = true) ||
                            result.message.contains("users", ignoreCase = true) ||
                            !AccountAuditor.isReadyForProvisioning(appContext)
                        )

                        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        } else {
                            PendingIntent.FLAG_UPDATE_CURRENT
                        }

                        val wizardIntent = Intent(appContext, ProvisioningWizardActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(ProvisioningWizardActivity.EXTRA_FOCUS_ACCOUNTS, true)
                        }
                        val wizardPendingIntent = PendingIntent.getActivity(
                            appContext,
                            1001,
                            wizardIntent,
                            pendingFlags
                        )

                        val failureNotification = if (isAccountsIssue) {
                            val summaryList = AccountAuditor.getAccountSummaryList(appContext)
                            val accountsFormatted = summaryList.joinToString("\n• ", prefix = "• ")
                            val title = appContext.getString(R.string.provisioning_failed_accounts_title)
                            val shortDesc = appContext.getString(R.string.provisioning_failed_accounts_desc, accounts.size)
                            val actionText = appContext.getString(R.string.provisioning_failed_accounts_action)
                            val fullBigText = "$shortDesc\n\n$accountsFormatted\n\n$actionText"

                            val openAccountsIntent = Intent(Settings.ACTION_SYNC_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            val openAccountsPendingIntent = PendingIntent.getActivity(
                                appContext,
                                1002,
                                openAccountsIntent,
                                pendingFlags
                            )

                            NotificationCompat.Builder(appContext, AdbPairingNotificationService.CHANNEL_ID)
                                .setSmallIcon(R.mipmap.ic_launcher)
                                .setContentTitle(title)
                                .setContentText(shortDesc)
                                .setStyle(NotificationCompat.BigTextStyle().bigText(fullBigText))
                                .setContentIntent(wizardPendingIntent)
                                .addAction(
                                    R.drawable.ic_person,
                                    appContext.getString(R.string.provisioning_btn_open_accounts),
                                    openAccountsPendingIntent
                                )
                                .setAutoCancel(true)
                                .build()
                        } else {
                            NotificationCompat.Builder(appContext, AdbPairingNotificationService.CHANNEL_ID)
                                .setSmallIcon(R.mipmap.ic_launcher)
                                .setContentTitle(appContext.getString(R.string.provisioning_failed_title))
                                .setContentText(result.message)
                                .setContentIntent(wizardPendingIntent)
                                .setAutoCancel(true)
                                .build()
                        }

                        notificationManager.notify(AdbPairingNotificationService.NOTIFICATION_ID, failureNotification)

                        val failIntent = Intent(ACTION_PROVISIONING_RESULT).apply {
                            setPackage(appContext.packageName)
                            putExtra(EXTRA_SUCCESS, false)
                            putExtra(EXTRA_ERROR_MESSAGE, result.message)
                        }
                        appContext.sendBroadcast(failIntent)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_PairReceiver"
        const val EXTRA_PAIRING_CODE = "extra_pairing_code"
        const val EXTRA_PAIRING_PORT = "extra_pairing_port"
        const val EXTRA_CONNECT_PORT = "extra_connect_port"

        const val ACTION_PROVISIONING_RESULT = "com.drmohammed.antiporn.ACTION_PROVISIONING_RESULT"
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
    }
}
