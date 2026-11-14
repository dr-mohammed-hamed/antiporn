package com.drmohammed.antiporn.waqfa.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import android.widget.Toast
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.waqfa.model.WaqfaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * مستقبل المنبهات الدقيقة والأحداث التفاعلية غير المصدّر (Internal Unexported BroadcastReceiver) لنظام «وَقْفَة».
 * يستقبل أحداث انتهاء وقت الجلسة، والإنذار التحذيري (T-60s)، وفك التجميد، وأزرار الإنهاء والتمديد من الإشعار.
 */
class WaqfaAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        val packageName = intent.getStringExtra(WaqfaAlarmScheduler.EXTRA_PACKAGE_NAME) ?: return
        val appName = intent.getStringExtra(WaqfaAlarmScheduler.EXTRA_APP_NAME) ?: packageName

        Timber.tag(TAG).i("WaqfaAlarmReceiver received action: %s for %s", action, packageName)
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    WaqfaAlarmScheduler.ACTION_WAQFA_SESSION_TIMEOUT -> {
                        val sessionId = intent.getLongExtra(WaqfaAlarmScheduler.EXTRA_SESSION_ID, 0L)
                        Timber.tag(TAG).i("Processing session timeout for %s (sessionId: %d)", packageName, sessionId)
                        val active = WaqfaSessionManager.getInstance(appContext).getActiveSession()
                        if (active != null && active.isOpenSession) {
                            Timber.tag(TAG).d("Active session for %s is an open session. Ignoring spurious timeout alarm.", packageName)
                        } else {
                            WaqfaSessionManager.getInstance(appContext).onSessionTimeout(packageName, sessionId)
                        }
                    }

                    WaqfaAlarmScheduler.ACTION_WAQFA_COOLDOWN_EXPIRED -> {
                        Timber.tag(TAG).i("Processing cooldown expired for %s", packageName)
                        WaqfaReactionExecutor.liftCooldown(appContext, packageName, appName)
                    }

                    WaqfaAlarmScheduler.ACTION_WAQFA_SESSION_WARNING -> {
                        val sessionId = intent.getLongExtra(WaqfaAlarmScheduler.EXTRA_SESSION_ID, 0L)
                        Timber.tag(TAG).i("Processing session warning for %s (sessionId: %d)", packageName, sessionId)
                        val activeSession = WaqfaSessionManager.getInstance(appContext).getActiveSession()
                        val nowTrusted = TrustedTimeProvider.currentTimeMillis(appContext)
                        if (activeSession != null && (sessionId == 0L || activeSession.id == sessionId) && !activeSession.isExpired(nowTrusted)) {
                            WaqfaNotificationHelper.postPreTimeoutWarningNotification(appContext, activeSession)
                        }
                    }

                    WaqfaAlarmScheduler.ACTION_WAQFA_EARLY_EXIT -> {
                        Timber.tag(TAG).i("Processing early exit requested from notification for %s", packageName)
                        WaqfaSessionManager.getInstance(appContext).cancelActiveSession(WaqfaSession.ACTION_USER_EARLY_EXIT)
                        WaqfaReactionExecutor.kickToHomeScreen(appContext)
                    }

                    WaqfaAlarmScheduler.ACTION_WAQFA_EXTEND_SESSION -> {
                        Timber.tag(TAG).i("Processing emergency extension (+2m) for %s", packageName)
                        val success = WaqfaSessionManager.getInstance(appContext).extendActiveSession(2)
                        Handler(Looper.getMainLooper()).post {
                            val msgRes = if (success) R.string.waqfa_toast_extended_success else R.string.waqfa_toast_already_extended
                            Toast.makeText(appContext, appContext.getString(msgRes), Toast.LENGTH_SHORT).show()
                        }
                    }

                    else -> {
                        Timber.tag(TAG).w("Unrecognized action received: %s", action)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error executing alarm action %s for %s", action, packageName)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WaqfaAlarmRec"
    }
}
