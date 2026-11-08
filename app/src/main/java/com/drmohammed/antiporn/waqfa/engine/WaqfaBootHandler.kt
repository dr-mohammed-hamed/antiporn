package com.drmohammed.antiporn.waqfa.engine

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.waqfa.data.WaqfaRepository
import com.drmohammed.antiporn.waqfa.model.WaqfaSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * معالج استعادة حالة نظام «وَقْفَة» بعد إعادة تشغيل الهاتف (Boot Recovery).
 * يضمن فك تجميد التطبيقات التي انقضت مدة تهدئتها، وإعادة جدولة منبهات التهدئة المتبقية.
 */
object WaqfaBootHandler {

    private const val TAG = "AntiPorn_WaqfaBoot"

    suspend fun onBoot(context: Context): Unit = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        Log.i(TAG, "WaqfaBootHandler reconciling active cooldowns and sessions on boot...")

        try {
            val nowTrusted = TrustedTimeProvider.currentTimeMillis(appContext)

            // 1. فحص ومعالجة التطبيقات المجمدة في التهدئة
            val frozenList = WaqfaCooldownStore.getAllFrozenPackages(appContext)
            for (frozen in frozenList) {
                if (frozen.isExpired(nowTrusted)) {
                    Log.i(TAG, "Cooldown expired for ${frozen.packageName} during shutdown; lifting suspension immediately.")
                    WaqfaReactionExecutor.liftCooldown(appContext, frozen.packageName, frozen.appName)
                } else {
                    Log.i(TAG, "Cooldown still active for ${frozen.packageName}; rescheduling unfreeze alarm for remaining ${frozen.remainingMs(nowTrusted) / 1000}s.")
                    // تأكيد التجميد في DPM
                    if (SafeDpmGateway.isDeviceOwner(appContext)) {
                        SafeDpmGateway.setPackagesSuspended(appContext, arrayOf(frozen.packageName), true)
                    }
                    WaqfaAlarmScheduler.scheduleCooldownEndAlarm(
                        context = appContext,
                        packageName = frozen.packageName,
                        appName = frozen.appName,
                        triggerWallMs = frozen.freezeEndWallMs
                    )
                }
            }

            // 2. فحص الجلسات النشطة في قاعدة البيانات
            val repository = WaqfaRepository.getInstance(appContext)
            val activeSessions = repository.getAllActiveSessions()
            for (session in activeSessions) {
                if (session.isExpired(nowTrusted)) {
                    repository.endSession(session.id, WaqfaSession.ACTION_USER_EARLY_EXIT)
                } else {
                    // نظراً لأن قرار التصميم هو إنهاء الجلسة عند الخروج من التطبيق،
                    // فإن إعادة تشغيل الهاتف تخرج المستخدم حتماً للشاشة الرئيسية وتنهي الجلسة
                    repository.endSession(session.id, WaqfaSession.ACTION_USER_EARLY_EXIT)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in WaqfaBootHandler.onBoot", e)
        }
    }
}
