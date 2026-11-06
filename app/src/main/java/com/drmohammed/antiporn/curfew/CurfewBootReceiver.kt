package com.drmohammed.antiporn.curfew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider

/**
 * Handles OS-level system lifecycle broadcasts (BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, SHUTDOWN).
 * Declared as exported="true" for system events, while internal curfew alarms remain protected in
 * CurfewAlarmReceiver (exported="false").
 */
class CurfewBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        Log.i(TAG, "CurfewBootReceiver triggered with action: $action")

        val appContext = context.applicationContext

        // CRITICAL(R6): Synchronously persist trusted time state on the main thread before OS kills process
        if (action == Intent.ACTION_SHUTDOWN || action == "android.intent.action.QUICKBOOT_POWEROFF") {
            Log.i(TAG, "Device shutdown broadcast received. Persisting trusted time synchronously on main thread.")
            TrustedTimeProvider.persistOnShutdown(appContext)
            return
        }

        val pendingResult = goAsync()
        CurfewAlarmReceiver.executeAction(appContext, action, pendingResult)
    }

    companion object {
        private const val TAG = "AntiPorn_CurfewBoot"
    }
}
