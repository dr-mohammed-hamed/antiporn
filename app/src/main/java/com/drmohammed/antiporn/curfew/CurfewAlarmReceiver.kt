package com.drmohammed.antiporn.curfew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Reject unauthenticated external snooze broadcasts to maintain Zero-Bypass security
                if (action == CurfewConfig.ACTION_CURFEW_SNOOZE) {
                    Log.w(TAG, "Rejected external ACTION_CURFEW_SNOOZE broadcast: unauthenticated bypass vector")
                    return@launch
                }
                if (action == CurfewConfig.ACTION_CURFEW_SNOOZE_END) {
                    CurfewEnforcer.clearSnooze(appContext)
                    CurfewEnforcer.syncBlockState(appContext)
                    CurfewScheduler.scheduleNextAlarms(appContext)
                    return@launch
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
                    return@launch
                }

                if (action == ACTION_CURFEW_PRE_WARNING) {
                    if (!CurfewConfig.isCurfewEnabled(appContext)) {
                        Log.i(TAG, "Ignoring pre-curfew warning: night curfew is disabled in policy")
                        CurfewScheduler.cancelCurfewAlarms(appContext)
                        return@launch
                    }
                    try {
                        val cal = CurfewConfig.getCurfewCalendar(appContext)
                        if (CurfewEnforcer.isCurfewActive(appContext, cal)) {
                            Log.i(TAG, "Skipping stale pre-curfew warning: curfew is already active")
                            return@launch
                        }
                        val nextStart = CurfewScheduler.getNextCurfewStart(appContext, cal)
                        val startH = nextStart.get(java.util.Calendar.HOUR_OF_DAY)
                        val startM = nextStart.get(java.util.Calendar.MINUTE)
                        val timeFormatted = String.format(java.util.Locale.US, "%02d:%02d", startH, startM)
                        val yyyyMMdd = formatTargetDateKey(appContext)

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
                    return@launch
                }

                // Curfew Start / End actions:
                CurfewEnforcer.syncBlockState(appContext)
                CurfewScheduler.scheduleNextAlarms(appContext)

                if (!CurfewConfig.isCurfewEnabled(appContext)) {
                    Log.i(TAG, "Night curfew is disabled in policy; skipping notification for $action")
                    return@launch
                }

                if (action == ACTION_CURFEW_START) {
                    try {
                        val yyyyMMdd = formatTargetDateKey(appContext)
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
                        val yyyyMMdd = formatTargetDateKey(appContext)
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
            } catch (e: Exception) {
                Log.e(TAG, "Error processing curfew alarm action: $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_CurfewReceiver"

        const val ACTION_CURFEW_PRE_WARNING = "com.drmohammed.antiporn.ACTION_CURFEW_PRE_WARNING"
        const val ACTION_CURFEW_START = "com.drmohammed.antiporn.ACTION_CURFEW_START"
        const val ACTION_CURFEW_END = "com.drmohammed.antiporn.ACTION_CURFEW_END"

        private fun formatTargetDateKey(appContext: Context): String {
            val tz = java.util.TimeZone.getTimeZone(CurfewConfig.getTargetTimeZoneId(appContext))
            return java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
                timeZone = tz
            }.format(java.util.Date(TrustedTimeProvider.currentTimeMillis(appContext)))
        }

        private fun rescheduleNapIfNeeded(appContext: Context) {
            try {
                if (NapController.isNapActive(appContext) && NapController.remainingMs(appContext) > 0L) {
                    CurfewScheduler.scheduleNapEndAlarm(appContext, NapController.getEndWall(appContext))
                } else {
                    CurfewScheduler.cancelNapEndAlarm(appContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling nap alarm", e)
            }
        }
    }
}
