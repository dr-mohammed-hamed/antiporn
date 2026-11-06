package com.drmohammed.antiporn.curfew.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.curfew.NapController
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles action clicks from the Nap Notification (e.g., extend +60 mins)
 * directly from the status bar without requiring the user to open MainActivity.
 */
class NapNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action == ACTION_EXTEND_NAP) {
            val now = SystemClock.elapsedRealtime()
            val last = lastExtendClickMs.get()
            if (now - last < DEBOUNCE_MS) {
                Log.w(TAG, "Debounced rapid nap extend click from notification")
                return
            }
            if (!lastExtendClickMs.compareAndSet(last, now)) {
                Log.w(TAG, "Debounced concurrent nap extend click from notification")
                return
            }

            val appContext = context.applicationContext
            if (!NapController.isNapActive(appContext)) {
                Log.w(TAG, "Attempted to extend nap when nap is not active; syncing notification")
                ProtectionNotificationCoordinator.syncProtectionNotification(appContext)
                return
            }

            Log.i(TAG, "Received ACTION_EXTEND_NAP from notification action button")

            val pendingResult = goAsync()
            try {
                NapController.extendNap(appContext)
                CurfewScheduler.scheduleNapEndAlarm(appContext, NapController.getEndWall(appContext))
                CurfewEnforcer.syncBlockStateAsync(appContext) {
                    try {
                        ProtectionNotificationCoordinator.syncProtectionNotification(appContext)
                        Toast.makeText(appContext, R.string.toast_nap_extended, Toast.LENGTH_SHORT).show()
                    } finally {
                        pendingResult.finish()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extending nap in receiver", e)
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_NapReceiver"
        private const val DEBOUNCE_MS = 1000L

        private val lastExtendClickMs = AtomicLong(0L)

        const val ACTION_EXTEND_NAP = "com.drmohammed.antiporn.curfew.ACTION_EXTEND_NAP"
    }
}
