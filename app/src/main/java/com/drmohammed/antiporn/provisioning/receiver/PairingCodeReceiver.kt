package com.drmohammed.antiporn.provisioning.receiver

import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.PromotionResult
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService
import com.drmohammed.antiporn.provisioning.ui.ProvisioningWizardActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * BroadcastReceiver triggered when the user enters the pairing code
 * into the interactive Heads-Up notification.
 *
 * Implements:
 * 1. Smart Input Sanitization (Arabic/Indic digits to English, non-digits stripped).
 * 2. ConnectPort race-condition safety (waits up to 5s if mDNS resolution is trailing).
 * 3. Auto-Return to MainActivity via BAL (Background Activity Launch) exemption.
 */
class PairingCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val rawCode = remoteInput?.getCharSequence(EXTRA_PAIRING_CODE)?.toString()
        val pairingCode = sanitizePairingCode(rawCode)

        val pairingPort = intent.getIntExtra(EXTRA_PAIRING_PORT, 0).takeIf { it > 0 }
            ?: AdbPairingNotificationService.capturedPairingPort
        var connectPort = intent.getIntExtra(EXTRA_CONNECT_PORT, 0).takeIf { it > 0 }
            ?: AdbPairingNotificationService.capturedConnectPort

        Timber.tag(TAG).i("Received pairing code from notification: len=${pairingCode.length}, pairingPort=$pairingPort, connectPort=$connectPort")

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        // Dismiss the Heads-Up input prompt immediately
        notificationManager.cancel(AdbPairingNotificationService.PAIRING_PROMPT_NOTIFICATION_ID)

        if (pairingCode.length != 6 || pairingPort == 0) {
            Timber.tag(TAG).w("Invalid pairing payload: code=$pairingCode, pairingPort=$pairingPort")
            val failureNotification = NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID_GUIDE)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.provisioning_failed_title))
                .setContentText(context.getString(R.string.provisioning_input_label))
                .setAutoCancel(true)
                .build()
            notificationManager.notify(AdbPairingNotificationService.NOTIFICATION_ID, failureNotification)
            return
        }

        val appContext = context.applicationContext

        // Show "Configuring Device Owner..." in guide notification while processing
        val inProgressNotification = NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID_GUIDE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.provisioning_in_progress_title))
            .setContentText(context.getString(R.string.provisioning_in_progress_desc))
            .setOngoing(true)
            .build()
        notificationManager.notify(AdbPairingNotificationService.NOTIFICATION_ID, inProgressNotification)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ConnectPort trailing resolution guard
                if (connectPort == 0) {
                    Timber.tag(TAG).i("Connect port is 0, waiting up to 5s for mDNS resolution...")
                    withTimeoutOrNull(5000) {
                        while (AdbPairingNotificationService.capturedConnectPort == 0) {
                            delay(200)
                        }
                        connectPort = AdbPairingNotificationService.capturedConnectPort
                    }
                }

                if (connectPort == 0) {
                    notifyFailure(appContext, notificationManager, appContext.getString(R.string.toast_provisioning_failed, "Connect port discovery timed out"))
                    return@launch
                }

                val result = DeviceOwnerPromoter.pairAndPromote(
                    context = appContext,
                    pairingPort = pairingPort,
                    connectPort = connectPort,
                    pairingCode = pairingCode
                )

                when (result) {
                    is PromotionResult.Success -> {
                        Timber.tag(TAG).i("Device Owner successfully provisioned via Notification!")
                        AdbPairingNotificationService.stop(appContext)

                        // 1. Prepare target intent for MainActivity
                        val mainIntent = Intent(appContext, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }

                        val optionsBundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            ActivityOptions.makeBasic().apply {
                                @Suppress("DEPRECATION")
                                setPendingIntentBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                                )
                            }.toBundle()
                        } else {
                            null
                        }

                        // 2. Auto-Return: Jump directly to MainActivity exploiting BAL grace period
                        try {
                            appContext.startActivity(mainIntent, optionsBundle)
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Direct launch of MainActivity failed: ${e.message}")
                        }

                        // 3. Fallback Success Notification
                        val mainPendingIntent = PendingIntent.getActivity(
                            appContext,
                            1002,
                            mainIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            optionsBundle
                        )

                        val successNotification = NotificationCompat.Builder(appContext, AdbPairingNotificationService.CHANNEL_ID_GUIDE)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle(appContext.getString(R.string.provisioning_success_title))
                            .setContentText(appContext.getString(R.string.provisioning_success_desc))
                            .setContentIntent(mainPendingIntent)
                            .setAutoCancel(true)
                            .build()
                        notificationManager.notify(AdbPairingNotificationService.NOTIFICATION_ID, successNotification)

                        // 4. Notify active UI if ProvisioningWizardActivity is open
                        val successIntent = Intent(ACTION_PROVISIONING_RESULT).apply {
                            setPackage(appContext.packageName)
                            putExtra(EXTRA_SUCCESS, true)
                        }
                        appContext.sendBroadcast(successIntent)
                    }
                    is PromotionResult.Failure -> {
                        Timber.tag(TAG).e("Device Owner provisioning failed: ${result.message}")
                        notifyFailure(appContext, notificationManager, result.message)
                    }
                }
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Unexpected error in PairingCodeReceiver")
                notifyFailure(appContext, notificationManager, e.message ?: "Unexpected error during pairing")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun notifyFailure(appContext: Context, notificationManager: NotificationManager, errorMessage: String) {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val wizardIntent = Intent(appContext, ProvisioningWizardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val wizardPendingIntent = PendingIntent.getActivity(appContext, 1001, wizardIntent, pendingFlags)

        val retryPrompt = appContext.getString(R.string.provisioning_tap_to_retry)
        val fullBigText = "$errorMessage\n\n$retryPrompt"

        val failureNotification = NotificationCompat.Builder(appContext, AdbPairingNotificationService.CHANNEL_ID_GUIDE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(appContext.getString(R.string.provisioning_failed_title))
            .setContentText(retryPrompt)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullBigText))
            .setContentIntent(wizardPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(AdbPairingNotificationService.NOTIFICATION_ID, failureNotification)

        val failIntent = Intent(ACTION_PROVISIONING_RESULT).apply {
            setPackage(appContext.packageName)
            putExtra(EXTRA_SUCCESS, false)
            putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        }
        appContext.sendBroadcast(failIntent)
    }

    private fun sanitizePairingCode(raw: String?): String = Companion.sanitizePairingCode(raw)

    companion object {
        private const val TAG = "AntiPorn_PairReceiver"
        const val EXTRA_PAIRING_CODE = "extra_pairing_code"
        const val EXTRA_PAIRING_PORT = "extra_pairing_port"
        const val EXTRA_CONNECT_PORT = "extra_connect_port"

        const val ACTION_PROVISIONING_RESULT = "com.drmohammed.antiporn.ACTION_PROVISIONING_RESULT"
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"

        /**
         * Sanitizes pairing input by stripping non-digits and converting Arabic/Indic numerals to standard ASCII digits.
         */
        fun sanitizePairingCode(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            val sb = StringBuilder()
            for (ch in raw) {
                when (ch) {
                    in '0'..'9' -> sb.append(ch)
                    in '٠'..'٩' -> sb.append((ch - '٠' + '0'.code).toChar())
                    in '۰'..'۹' -> sb.append((ch - '۰' + '0'.code).toChar())
                }
            }
            return sb.toString()
        }
    }
}
