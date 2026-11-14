package com.drmohammed.antiporn.waqfa.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import timber.log.Timber
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.waqfa.data.WaqfaRepository
import com.drmohammed.antiporn.waqfa.model.WaqfaConfig
import com.drmohammed.antiporn.waqfa.model.WaqfaIntentType
import com.drmohammed.antiporn.waqfa.model.WaqfaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * مدير دورة حياة جلسات «وَقْفَة» في الذاكرة (Waqfa Session Manager).
 * يضمن تزامن الجلسة مع التنقل بين التطبيقات وإنهاء الجلسة فور مغادرة التطبيق إلى تطبيق آخر أو الشاشة الرئيسية.
 */
class WaqfaSessionManager(
    private val context: Context,
    private val repository: WaqfaRepository = WaqfaRepository.getInstance(context)
) {

    private val mutex = Mutex()
    private val managerScope = CoroutineScope(Dispatchers.IO)
    private var exitGraceJob: Job? = null

    @Volatile
    private var activeSession: WaqfaSession? = null

    @Volatile
    private var cachedConfig: WaqfaConfig = WaqfaConfig()

    init {
        // تحميل الإعدادات المبدئية من السياسة
        try {
            cachedConfig = PolicyRepository.getInstance(context).getCurrentPolicy().waqfaConfig
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed reading initial policy for Waqfa, using defaults")
        }
        // استعادة أي جلسة سارية غير منتهية من قاعدة البيانات عند إعادة إقلاع العملية
        try {
            val now = TrustedTimeProvider.currentTimeMillis(context)
            repository.getAllActiveSessionsBlocking().firstOrNull { !it.isExpired(now) }?.let { restored ->
                activeSession = restored
                WaqfaNotificationHelper.postOngoingSessionNotification(context, restored)
                if (!restored.isOpenSession) {
                    WaqfaAlarmScheduler.scheduleSessionTimeoutAlarm(context, restored)
                    WaqfaAlarmScheduler.schedulePreTimeoutWarningAlarm(context, restored)
                }
                Timber.tag(TAG).i("Restored active Waqfa session from DB for %s (isOpen=%b)", restored.packageName, restored.isOpenSession)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed restoring initial active Waqfa session from DB")
        }
    }

    fun updateConfig(config: WaqfaConfig) {
        cachedConfig = config
        Timber.tag(TAG).i("WaqfaConfig updated: isEnabled=%b, targets=%d", config.isEnabled, config.targetPackages.size)
        try {
            WaqfaForegroundWatcher.getInstance(context).notifyConfigChanged()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not notify WaqfaForegroundWatcher of config update")
        }
    }

    fun getConfig(): WaqfaConfig = cachedConfig

    suspend fun getActiveSession(): WaqfaSession? = mutex.withLock {
        activeSession
    }

    suspend fun hasActiveValidSession(packageName: String): Boolean = mutex.withLock {
        val session = activeSession ?: return@withLock false
        if (session.packageName != packageName) return@withLock false
        val now = TrustedTimeProvider.currentTimeMillis(context)
        !session.isExpired(now)
    }

    fun hasActiveValidSessionBlocking(packageName: String): Boolean {
        val currentSession = activeSession ?: return false
        if (currentSession.packageName != packageName) return false
        val now = TrustedTimeProvider.currentTimeMillis(context)
        val valid = !currentSession.isExpired(now)
        if (!valid && activeSession?.id == currentSession.id) {
            activeSession = null
        }
        return valid
    }

    suspend fun startSession(
        packageName: String,
        appName: String,
        intentType: WaqfaIntentType,
        reasonOrFeeling: String?,
        durationMinutes: Int
    ): WaqfaSession = mutex.withLock {
        exitGraceJob?.cancel()
        exitGraceJob = null

        // 1. إنشاء الجلسة في المستودع وقاعدة البيانات
        val newSession = repository.startSession(
            packageName = packageName,
            appName = appName,
            intentType = intentType,
            reasonOrFeeling = reasonOrFeeling,
            durationMinutes = durationMinutes
        )

        // 2. تحديث الجلسة في الذاكرة
        activeSession = newSession

        // 3. جدولة المنبهات فقط إذا لم تكن الجلسة مفتوحة
        if (!newSession.isOpenSession) {
            WaqfaAlarmScheduler.scheduleSessionTimeoutAlarm(context, newSession)
            WaqfaAlarmScheduler.schedulePreTimeoutWarningAlarm(context, newSession)
        }

        // 4. إطلاق إشعار العداد الحي (تصاعدي للمفتوحة أو تنازلي للمحددة)
        WaqfaNotificationHelper.postOngoingSessionNotification(context, newSession)

        Timber.tag(TAG).i("Active session registered for %s (%dm)", packageName, newSession.durationMinutes)
        newSession
    }

    private fun isHomeLauncher(packageName: String): Boolean {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val homeInfo = context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            homeInfo?.activityInfo?.packageName == packageName
        } catch (_: Exception) {
            false
        }
    }

    /**
     * يُستدعى فور رصد تغير التطبيق النشط في الواجهة.
     * يُلغي الجلسة إذا غادر المستخدم التطبيق إلى الشاشة الرئيسية (مهلة 10 ثوانٍ) أو تطبيق آخر (مهلة 2.5 ثانية).
     */
    fun onForegroundPackageChanged(newForegroundPackage: String) {
        if (newForegroundPackage.isBlank()) return
        if (newForegroundPackage == context.packageName) return

        val isLauncher = isHomeLauncher(newForegroundPackage)

        // إذا كان التطبيق نظامياً أو محصناً (مثل المكالمات الهاتفية) وليس المشغل، لا نقاطع الجلسة
        if (!isLauncher && CurfewAppResolver.isSystemCriticalOrImmune(context, newForegroundPackage)) {
            return
        }

        managerScope.launch {
            mutex.withLock {
                val current = activeSession
                if (current == null) {
                    exitGraceJob?.cancel()
                    exitGraceJob = null
                    return@withLock
                }

                if (current.packageName == newForegroundPackage) {
                    // المستخدم عاد للتطبيق المعتمد خلال فترة السماح
                    exitGraceJob?.cancel()
                    exitGraceJob = null
                    return@withLock
                }

                // عند الانتقال للشاشة الرئيسية نمنح مهلة 10 ثوانٍ، وعند الانتقال لتطبيق آخر 2500ms
                val graceDelayMs = if (isLauncher) 10_000L else 2500L
                exitGraceJob?.cancel()
                val sessionToTerminate = current
                exitGraceJob = managerScope.launch {
                    delay(graceDelayMs)
                    mutex.withLock {
                        val stillActive = activeSession
                        if (stillActive != null && stillActive.id == sessionToTerminate.id) {
                            Timber.tag(TAG).i("User exited %s -> switched to %s (isLauncher=%b). Grace period (%d ms) elapsed. Terminating Waqfa session.", stillActive.packageName, newForegroundPackage, isLauncher, graceDelayMs)
                            
                            // إلغاء الإشعارات والمنبهات
                            WaqfaNotificationHelper.cancelOngoingSessionNotification(context)
                            WaqfaNotificationHelper.cancelPreTimeoutWarningNotification(context)
                            WaqfaAlarmScheduler.cancelSessionTimeoutAlarm(context, stillActive.packageName)
                            WaqfaAlarmScheduler.cancelPreTimeoutWarningAlarm(context, stillActive.packageName)

                            repository.endSession(stillActive.id, WaqfaSession.ACTION_USER_EARLY_EXIT)
                            activeSession = null
                        }
                    }
                }
            }
        }
    }

    /**
     * يُستدعى من المنبه الدقيق فور انتهاء وقت الجلسة.
     */
    suspend fun onSessionTimeout(packageName: String, sessionId: Long) = mutex.withLock {
        exitGraceJob?.cancel()
        exitGraceJob = null

        val current = activeSession
        val targetSession = if (current != null && (sessionId == 0L || current.id == sessionId)) {
            current
        } else {
            repository.getActiveSession(packageName)
        }

        if (targetSession == null) {
            Timber.tag(TAG).d("No active session found for timeout on %s", packageName)
            return@withLock
        }

        if (targetSession.isOpenSession) {
            Timber.tag(TAG).d("Active session for %s is an open session. Spurious timeout ignored.", packageName)
            return@withLock
        }

        // إلغاء الإشعارات والمنبه التحذيري
        WaqfaNotificationHelper.cancelOngoingSessionNotification(context)
        WaqfaNotificationHelper.cancelPreTimeoutWarningNotification(context)
        WaqfaAlarmScheduler.cancelPreTimeoutWarningAlarm(context, packageName)

        val config = cachedConfig
        val reactionType = config.timeoutReactionType
        val cooldown = config.cooldownMinutes

        // تنفيذ استراتيجية رد الفعل (طرد، تجميد 3 دقائق، تنبيه)
        WaqfaReactionExecutor.executeTimeout(context, targetSession, reactionType, cooldown)

        // إنهاء الجلسة في قاعدة البيانات وتسجيل الإجراء المنفذ
        repository.endSession(targetSession.id, reactionType.name)

        if (activeSession?.id == targetSession.id) {
            activeSession = null
        }
    }

    suspend fun cancelActiveSession(endAction: String = WaqfaSession.ACTION_USER_EARLY_EXIT) = mutex.withLock {
        Timber.tag(TAG).i("Cancelling active session with action: %s", endAction)
        exitGraceJob?.cancel()
        exitGraceJob = null

        val current = activeSession ?: return@withLock
        WaqfaNotificationHelper.cancelOngoingSessionNotification(context)
        WaqfaNotificationHelper.cancelPreTimeoutWarningNotification(context)
        WaqfaAlarmScheduler.cancelSessionTimeoutAlarm(context, current.packageName)
        WaqfaAlarmScheduler.cancelPreTimeoutWarningAlarm(context, current.packageName)
        repository.endSession(current.id, endAction)
        activeSession = null
    }

    /**
     * تمديد الجلسة النشطة استثنائياً (+2 دقيقة) لمرة واحدة فقط لإنقاذ العمل والرسائل غير المحفوظة.
     */
    suspend fun extendActiveSession(extraMinutes: Int = 2): Boolean = mutex.withLock {
        val current = activeSession ?: return@withLock false
        if (current.isOpenSession) {
            Timber.tag(TAG).d("Session for %s is an open session. Extension not applicable.", current.packageName)
            return@withLock false
        }
        if (current.isExtended) {
            Timber.tag(TAG).w("Active session for %s is already extended. Refusing.", current.packageName)
            return@withLock false
        }
        val nowTrusted = TrustedTimeProvider.currentTimeMillis(context)
        if (current.isExpired(nowTrusted)) {
            Timber.tag(TAG).w("Active session for %s has already expired. Cannot extend.", current.packageName)
            return@withLock false
        }

        val newPlannedEnd = current.plannedEndAtTrustedMs + (extraMinutes * 60_000L)
        val newDuration = current.durationMinutes + extraMinutes
        val updated = current.copy(
            plannedEndAtTrustedMs = newPlannedEnd,
            durationMinutes = newDuration,
            isExtended = true
        )

        // Persist to database first before committing in-memory state
        try {
            repository.updateSessionPlannedEnd(updated.id, newPlannedEnd, newDuration)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed updating planned end in DB for session #${updated.id}")
            return@withLock false
        }

        activeSession = updated

        // إلغاء تنبيه ما قبل النهاية فور التمديد
        WaqfaNotificationHelper.cancelPreTimeoutWarningNotification(context)

        // إعادة جدولة المنبهات للتوقيت الجديد
        WaqfaAlarmScheduler.scheduleSessionTimeoutAlarm(context, updated)
        WaqfaAlarmScheduler.schedulePreTimeoutWarningAlarm(context, updated)

        // تحديث إشعار العداد التنازلي التفاعلي بالوقت الجديد
        WaqfaNotificationHelper.postOngoingSessionNotification(context, updated)

        Timber.tag(TAG).i("Extended active session for %s by %d min until wallMs=%d", updated.packageName, extraMinutes, newPlannedEnd)
        true
    }

    companion object {
        private const val TAG = "AntiPorn_WaqfaSessionMgr"

        @Volatile
        private var instance: WaqfaSessionManager? = null

        fun getInstance(context: Context): WaqfaSessionManager {
            return instance ?: synchronized(this) {
                instance ?: WaqfaSessionManager(context.applicationContext).also { instance = it }
            }
        }

        fun setInstance(mgr: WaqfaSessionManager?) {
            instance = mgr
        }
    }
}
