package com.drmohammed.antiporn.waqfa.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * مستقبل المنبهات الدقيقة غير المصدّر (Internal Unexported BroadcastReceiver) لنظام «وَقْفَة».
 * يستقبل أحداث انتهاء وقت الجلسة وفك التجميد بعد فترة التهدئة.
 */
class WaqfaAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        val packageName = intent.getStringExtra(WaqfaAlarmScheduler.EXTRA_PACKAGE_NAME) ?: return
        val appName = intent.getStringExtra(WaqfaAlarmScheduler.EXTRA_APP_NAME) ?: packageName

        Log.i(TAG, "WaqfaAlarmReceiver received action: $action for $packageName")
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    WaqfaAlarmScheduler.ACTION_WAQFA_SESSION_TIMEOUT -> {
                        val sessionId = intent.getLongExtra(WaqfaAlarmScheduler.EXTRA_SESSION_ID, 0L)
                        Log.i(TAG, "Processing session timeout for $packageName (sessionId: $sessionId)")
                        WaqfaSessionManager.getInstance(appContext).onSessionTimeout(packageName, sessionId)
                    }

                    WaqfaAlarmScheduler.ACTION_WAQFA_COOLDOWN_EXPIRED -> {
                        Log.i(TAG, "Processing cooldown expired for $packageName")
                        WaqfaReactionExecutor.liftCooldown(appContext, packageName, appName)
                    }

                    else -> {
                        Log.w(TAG, "Unrecognized action received: $action")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing alarm action $action for $packageName", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WaqfaAlarmRec"
    }
}
