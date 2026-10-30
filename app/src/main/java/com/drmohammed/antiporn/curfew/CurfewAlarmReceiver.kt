package com.drmohammed.antiporn.curfew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Receives scheduled exact alarms for night curfew activation and release,
 * as well as time/timezone changes and device reboot events.
 * Executes asynchronously off the main thread to prevent ANR.
 */
class CurfewAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        Log.i(TAG, "CurfewAlarmReceiver triggered with action: $action")


        val appContext = context.applicationContext

        // CRITICAL(R6): Synchronously persist trusted time state on the main thread before OS kills process
        if (action == Intent.ACTION_SHUTDOWN || action == "android.intent.action.QUICKBOOT_POWEROFF") {
            Log.i(TAG, "Device shutdown broadcast received. Persisting trusted time synchronously on main thread.")
            TrustedTimeProvider.persistOnShutdown(appContext)
            return
        }

        val pendingResult = goAsync()
        // appContext already resolved above

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
                    // General path (START/END/BOOT/TIME_SET/...): merged block state is safe
                    // because syncBlockState = curfew OR nap.
                    CurfewEnforcer.syncBlockState(appContext)
                    CurfewScheduler.scheduleNextAlarms(appContext)

                    if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                        rescheduleNapIfNeeded(appContext)
                        rescheduleSnoozeIfNeeded(appContext)
                    }

                    if (action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED) {
                        Log.i(TAG, "Time/Timezone change detected; re-synchronizing trusted time and policies immediately")
                        TrustedTimeProvider.syncNowAsync(appContext)
                        ProtectionEngine.applyAllAsync(appContext)
                        ProtectionNotificationCoordinator.syncProtectionNotification(appContext)
                    }
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

        private fun rescheduleSnoozeIfNeeded(appContext: Context) {
            try {
                if (CurfewEnforcer.isSnoozeActive(appContext)) {
                    val snoozeEnd = CurfewEnforcer.getSnoozeEndWall(appContext)
                    if (snoozeEnd > TrustedTimeProvider.currentTimeMillis(appContext)) {
                        CurfewScheduler.scheduleCurfewSnoozeAlarm(appContext, snoozeEnd)
                    } else {
                        CurfewEnforcer.clearSnooze(appContext)
                    }
                } else {
                    CurfewScheduler.cancelCurfewSnoozeAlarm(appContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling snooze alarm", e)
            }
        }
    }
}
