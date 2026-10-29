package com.drmohammed.antiporn.ota.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.ota.installer.OtaPackageInstaller
import com.drmohammed.antiporn.ota.notification.OtaNotificationManager

/**
 * BroadcastReceiver listening for PackageInstaller session status callbacks.
 *
 * Handles:
 * - [PackageInstaller.STATUS_SUCCESS]: Self-update applied successfully (process replacement occurs).
 * - [PackageInstaller.STATUS_PENDING_USER_ACTION]: Non-Device Owner fallback mode, prompting user confirmation.
 * - Other failure statuses: Logging error codes and messages, notifying internal listeners.
 */
class OtaInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AntiPorn_OtaReceiver"

        const val ACTION_OTA_INSTALL_RESULT = "com.drmohammed.antiporn.ACTION_OTA_INSTALL_RESULT"
        const val EXTRA_RESULT_STATUS = "com.drmohammed.antiporn.EXTRA_RESULT_STATUS"
        const val EXTRA_RESULT_MESSAGE = "com.drmohammed.antiporn.EXTRA_RESULT_MESSAGE"

        /**
         * Optional listener interface for reactive observation by UI or workers.
         */
        interface InstallStatusListener {
            fun onInstallSuccess()
            fun onPendingUserAction()
            fun onInstallFailure(status: Int, message: String?)
        }

        @Volatile
        var statusListener: InstallStatusListener? = null
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        if (intent.action != OtaPackageInstaller.ACTION_OTA_INSTALL_STATUS) {
            return
        }

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "OTA Self-update installed successfully")
                OtaNotificationManager.cancelNotification(context)
                val installedVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
                } catch (e: Exception) {
                    "1.0"
                }
                OtaNotificationManager.showSuccessNotification(context, installedVersion)
                statusListener?.onInstallSuccess()
                notifyInternalResult(context, status, message)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG, "User confirmation required (fallback mode)")
                statusListener?.onPendingUserAction()

                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }

                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                } else {
                    Log.e(TAG, "STATUS_PENDING_USER_ACTION received but Intent.EXTRA_INTENT is null")
                }
                notifyInternalResult(context, status, message)
            }
            else -> {
                Log.e(TAG, "OTA installation failed with status $status: $message")
                statusListener?.onInstallFailure(status, message)
                notifyInternalResult(context, status, message)
            }
        }
    }

    private fun notifyInternalResult(context: Context, status: Int, message: String?) {
        try {
            val resultIntent = Intent(ACTION_OTA_INSTALL_RESULT).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_RESULT_STATUS, status)
                putExtra(EXTRA_RESULT_MESSAGE, message.orEmpty())
            }
            context.sendBroadcast(resultIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast internal OTA result: ${e.message}")
        }
    }
}
