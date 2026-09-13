package com.drmohammed.antiporn.curfew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import java.util.concurrent.Executors

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

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        bgExecutor.execute {
            try {
                // Regardless of whether START, END, BOOT, or TIME_SET was received,
                // evaluate ground-truth state via syncCurfewState to prevent broadcast spoofing.
                CurfewEnforcer.syncCurfewState(appContext)
                CurfewScheduler.scheduleNextAlarms(appContext)

                if (action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED) {
                    Log.i(TAG, "Time/Timezone change detected; re-enforcing system policies immediately")
                    ProtectionEngine.applyAllAsync(appContext)
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
        private val bgExecutor = Executors.newSingleThreadExecutor()

        const val ACTION_CURFEW_START = "com.drmohammed.antiporn.ACTION_CURFEW_START"
        const val ACTION_CURFEW_END = "com.drmohammed.antiporn.ACTION_CURFEW_END"
    }
}
