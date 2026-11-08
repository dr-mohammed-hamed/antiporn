package com.drmohammed.antiporn.waqfa.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.waqfa.model.WaqfaSession
import com.drmohammed.antiporn.waqfa.model.WaqfaTimeoutReactionType

/**
 * منفذ استراتيجيات رد الفعل عند انتهاء وقت جلسة «وَقْفَة».
 */
object WaqfaReactionExecutor {

    private const val TAG = "AntiPorn_WaqfaReaction"

    fun kickToHomeScreen(context: Context) {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(homeIntent)
            Log.i(TAG, "Sent user to Home Screen")
        } catch (e: Exception) {
            Log.e(TAG, "Failed redirecting to home screen", e)
        }
    }

    suspend fun executeTimeout(
        context: Context,
        session: WaqfaSession,
        reactionType: WaqfaTimeoutReactionType,
        cooldownMinutes: Int
    ) {
        Log.i(TAG, "Executing timeout reaction ${reactionType.name} for ${session.packageName}")
        val appContext = context.applicationContext

        when (reactionType) {
            WaqfaTimeoutReactionType.EXIT_ONLY -> {
                kickToHomeScreen(appContext)
            }

            WaqfaTimeoutReactionType.EXIT_AND_FREEZE -> {
                kickToHomeScreen(appContext)

                // 1. تجميد التطبيق فورياً على مستوى نظام التشغيل عبر SafeDpmGateway
                if (SafeDpmGateway.isDeviceOwner(appContext)) {
                    try {
                        SafeDpmGateway.setPackagesSuspended(appContext, arrayOf(session.packageName), true)
                        Log.i(TAG, "Suspended package ${session.packageName} for $cooldownMinutes min cooldown")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error suspending package ${session.packageName}", e)
                    }
                } else {
                    Log.w(TAG, "Device Owner permission missing, skipping setPackagesSuspended")
                }

                // 2. تسجيل حالة التهدئة وحساب وقت الانتهاء الموثوق
                val frozen = WaqfaCooldownStore.recordCooldown(
                    context = appContext,
                    packageName = session.packageName,
                    appName = session.appName,
                    cooldownMinutes = cooldownMinutes
                )

                // 3. جدولة منبه دقيق لفك التجميد
                WaqfaAlarmScheduler.scheduleCooldownEndAlarm(
                    context = appContext,
                    packageName = session.packageName,
                    appName = session.appName,
                    triggerWallMs = frozen.freezeEndWallMs
                )

                // 4. إظهار إشعار توضيحي فوري للمستخدم
                WaqfaNotificationHelper.postCooldownStartedNotification(
                    context = appContext,
                    appName = session.appName,
                    cooldownMinutes = cooldownMinutes
                )
            }

            WaqfaTimeoutReactionType.NOTIFY_ONLY -> {
                WaqfaNotificationHelper.postSessionTimeoutNotification(
                    context = appContext,
                    appName = session.appName,
                    durationMinutes = session.durationMinutes
                )
            }
        }
    }

    suspend fun liftCooldown(context: Context, packageName: String, appName: String) {
        val appContext = context.applicationContext
        Log.i(TAG, "Lifting cooldown for $packageName")

        // 1. فك التجميد عبر SafeDpmGateway
        if (SafeDpmGateway.isDeviceOwner(appContext)) {
            try {
                SafeDpmGateway.setPackagesSuspended(appContext, arrayOf(packageName), false)
                Log.i(TAG, "Lifted package suspension for $packageName via SafeDpmGateway")
            } catch (e: Exception) {
                Log.e(TAG, "Error unsuspending package $packageName", e)
            }
        }

        // 2. مسح السجل من مخزن التهدئة
        WaqfaCooldownStore.clearCooldown(appContext, packageName)

        // 3. إلغاء أي منبه متبقٍ
        WaqfaAlarmScheduler.cancelCooldownEndAlarm(appContext, packageName)

        // 4. إشعار فك التجميد
        WaqfaNotificationHelper.postCooldownEndedNotification(appContext, appName)
    }
}
