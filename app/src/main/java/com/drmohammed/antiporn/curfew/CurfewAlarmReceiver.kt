package com.drmohammed.antiporn.curfew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Internal non-exported BroadcastReceiver for scheduled exact alarms:
 * Night curfew start/end, snooze completion, and nap duration expirations.
 * Explicitly unexported to eliminate external Intent spoofing and unauthorized curfew overrides.
 */
class CurfewAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        Log.i(TAG, "CurfewAlarmReceiver triggered with action: $action")
        BlackBoxRecorder.recordCurfew(
            context,
            "ALARM_ACTION_RECEIVED",
            isSuccess = true,
            details = "action=$action"
        )

        val appContext = context.applicationContext
        val pendingResult = goAsync()

        try {
            bgExecutor.execute {
                try {
                    // Reject unauthenticated external snooze broadcasts to maintain Zero-Bypass security
                    if (action == CurfewConfig.ACTION_CURFEW_SNOOZE) {
                        Log.w(TAG, "Rejected external ACTION_CURFEW_SNOOZE broadcast: unauthenticated bypass vector")
                        return@execute
                    }
                    if (action == CurfewConfig.ACTION_CURFEW_SNOOZE_END) {
                        CurfewEnforcer.clearSnooze(appContext)
                        CurfewEnforcer.syncBlockState(appContext)
                        CurfewScheduler.scheduleNextAlarms(appContext)
                        return@execute
                    }
                    if (action == NapConfig.ACTION_NAP_END) {
                        // Nap end: merge block state (curfew OR nap), keep night alarms scheduled,
                        // then reschedule nap alarm if still active (extended), else cancel.
                        CurfewEnforcer.syncBlockState(appContext)
                        CurfewScheduler.scheduleNextAlarms(appContext)
                        rescheduleNapIfNeeded(appContext)
                        if (!NapController.isNapActive(appContext)) {
                            ProtectionNotificationCoordinator.postNapEndedNotification(appContext)
                        }
                        return@execute
                    }

                    // Curfew Start / End actions:
                    CurfewEnforcer.syncBlockState(appContext)
                    CurfewScheduler.scheduleNextAlarms(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing curfew alarm action: $action", e)
                } finally {
                    pendingResult.finish()
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.e(TAG, "Executor rejected curfew task for action: $action", e)
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "AntiPorn_CurfewReceiver"
        private val bgExecutor = Executors.newSingleThreadExecutor()

        const val ACTION_CURFEW_START = "com.drmohammed.antiporn.ACTION_CURFEW_START"
        const val ACTION_CURFEW_END = "com.drmohammed.antiporn.ACTION_CURFEW_END"

        private fun rescheduleNapIfNeeded(appContext: Context) {
            try {
                if (NapController.isNapActive(appContext)) {
                    val napEndWall = NapController.getEndWall(appContext)
                    if (napEndWall > TrustedTimeProvider.currentTimeMillis(appContext)) {
                        CurfewScheduler.scheduleNapEndAlarm(appContext, napEndWall)
                    } else {
                        CurfewScheduler.cancelNapEndAlarm(appContext)
                    }
                } else {
                    CurfewScheduler.cancelNapEndAlarm(appContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling nap alarm", e)
            }
        }
    }
}
