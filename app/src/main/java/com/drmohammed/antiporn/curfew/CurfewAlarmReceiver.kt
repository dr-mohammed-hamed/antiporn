package com.drmohammed.antiporn.curfew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
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
        com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordCurfew(
            context,
            "ALARM_ACTION_RECEIVED",
            isSuccess = true,
            details = "action=$action"
        )

        val appContext = context.applicationContext

        // CRITICAL(R6): Synchronously persist trusted time state on the main thread before OS kills process
        if (action == Intent.ACTION_SHUTDOWN || action == "android.intent.action.QUICKBOOT_POWEROFF") {
            Log.i(TAG, "Device shutdown broadcast received. Persisting trusted time synchronously on main thread.")
            TrustedTimeProvider.persistOnShutdown(appContext)
            return
        }

        val pendingResult = goAsync()
        executeAction(appContext, action, pendingResult)
    }

    companion object {
        private const val TAG = "AntiPorn_CurfewReceiver"
        private val bgExecutor = Executors.newSingleThreadExecutor()

        const val ACTION_CURFEW_PRE_WARNING = "com.drmohammed.antiporn.ACTION_CURFEW_PRE_WARNING"
        const val ACTION_CURFEW_START = "com.drmohammed.antiporn.ACTION_CURFEW_START"
        const val ACTION_CURFEW_END = "com.drmohammed.antiporn.ACTION_CURFEW_END"

        fun executeAction(appContext: Context, action: String, pendingResult: PendingResult) {
            try {
                bgExecutor.execute {
                    try {
                        // Reject unauthenticated external snooze broadcasts to maintain Zero-Bypass security
                        if (action == CurfewConfig.ACTION_CURFEW_SNOOZE) {
                            Log.w(TAG, "Rejected external ACTION_CURFEW_SNOOZE broadcast: unauthenticated bypass vector")
                            return@execute
                        }

                        if (action == ACTION_CURFEW_PRE_WARNING) {
                            try {
                                val cal = CurfewConfig.getCurfewCalendar(appContext)
                                val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
                                val (startH, startM) = CurfewScheduler.getCurfewStartTimeForDay(appContext, dayOfWeek)
                                val timeFormatted = String.format(java.util.Locale.US, "%02d:%02d", startH, startM)
                                val yyyyMMdd = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(TrustedTimeProvider.currentTimeMillis(appContext)))

                                AppNotificationManager.getInstance(appContext).postEventNotification(
                                    category = NotificationCategory.CURFEW,
                                    title = appContext.getString(R.string.notif_curfew_pre_warn_title),
                                    message = appContext.getString(R.string.notif_curfew_pre_warn_msg, timeFormatted),
                                    dedupKey = "curfew_pre_warn_$yyyyMMdd",
                                    cooldownMs = 60 * 60 * 1000L,
                                    actionType = NotificationActionType.OPEN_CURFEW,
                                    systemNotificationId = NotificationRegistry.EVENT_CURFEW_PRE_WARNING_ID,
                                    channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                                    postSystemNotification = true,
                                    importance = 2
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Error posting pre-curfew warning notification", e)
                            }
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

                        if (action == ACTION_CURFEW_START) {
                            try {
                                val yyyyMMdd = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(TrustedTimeProvider.currentTimeMillis(appContext)))
                                AppNotificationManager.getInstance(appContext).postEventNotification(
                                    category = NotificationCategory.CURFEW,
                                    title = appContext.getString(R.string.notif_curfew_start_title),
                                    message = appContext.getString(R.string.notif_curfew_start_msg),
                                    dedupKey = "curfew_start_$yyyyMMdd",
                                    cooldownMs = 60 * 60 * 1000L,
                                    actionType = NotificationActionType.OPEN_CURFEW,
                                    systemNotificationId = NotificationRegistry.EVENT_CURFEW_STARTED_ID,
                                    channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                                    postSystemNotification = true,
                                    importance = 2
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Error posting curfew started notification", e)
                            }
                        }

                        if (action == ACTION_CURFEW_END) {
                            try {
                                val yyyyMMdd = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(TrustedTimeProvider.currentTimeMillis(appContext)))
                                AppNotificationManager.getInstance(appContext).postEventNotification(
                                    category = NotificationCategory.CURFEW,
                                    title = appContext.getString(R.string.notif_curfew_end_title),
                                    message = appContext.getString(R.string.notif_curfew_end_msg),
                                    dedupKey = "curfew_end_$yyyyMMdd",
                                    cooldownMs = 60 * 60 * 1000L,
                                    actionType = NotificationActionType.NONE,
                                    systemNotificationId = NotificationRegistry.EVENT_CURFEW_ENDED_ID,
                                    channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                                    postSystemNotification = true,
                                    importance = 1
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Error posting curfew ended notification", e)
                            }
                        }

                        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                            rescheduleNapIfNeeded(appContext)
                            rescheduleSnoozeIfNeeded(appContext)
                            CurfewEnforcer.repairStuckSuspendedApps(appContext)
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
