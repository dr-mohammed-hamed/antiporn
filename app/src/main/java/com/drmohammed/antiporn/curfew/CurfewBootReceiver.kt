package com.drmohammed.antiporn.curfew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manifest-declared public BroadcastReceiver for OS system lifecycle events.
 * Handles device boot, package replacement, timezone/time changes, and device shutdown.
 * Strictly isolated from internal alarm triggers to prevent external bypass vectors.
 */
class CurfewBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        Log.i(TAG, "CurfewBootReceiver triggered with action: $action")
        BlackBoxRecorder.recordCurfew(
            context,
            "SYSTEM_LIFECYCLE_ACTION_RECEIVED",
            isSuccess = true,
            details = "action=$action"
        )

        val appContext = context.applicationContext

        // Synchronously persist trusted time state on the main thread before OS kills process
        if (action == Intent.ACTION_SHUTDOWN || action == "android.intent.action.QUICKBOOT_POWEROFF") {
            Log.i(TAG, "Device shutdown broadcast received. Persisting trusted time synchronously on main thread.")
            TrustedTimeProvider.persistOnShutdown(appContext)
            return
        }

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "Processing system lifecycle broadcast: $action")

                CurfewEnforcer.syncBlockState(appContext)
                CurfewScheduler.scheduleNextAlarms(appContext)

                if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    rescheduleNapIfNeeded(appContext)
                    rescheduleSnoozeIfNeeded(appContext)
                    CurfewEnforcer.repairStuckSuspendedApps(appContext)
                    ZeroTrustForegroundWatcher.syncWatcherState(appContext)
                    com.drmohammed.antiporn.waqfa.engine.WaqfaBootHandler.onBoot(appContext)
                }

                if (action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED) {
                    Log.i(TAG, "Time/Timezone change detected; re-synchronizing trusted time and policies immediately")
                    TrustedTimeProvider.syncNowAsync(appContext)
                    ProtectionEngine.applyAllAsync(appContext)
                    ProtectionNotificationCoordinator.syncProtectionNotification(appContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing system lifecycle action: $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_BootReceiver"

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
