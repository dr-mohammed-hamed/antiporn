package com.drmohammed.antiporn.waqfa.engine

import android.content.Context
import android.util.Log
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
            Log.w(TAG, "Failed reading initial policy for Waqfa, using defaults", e)
        }
        // استعادة أي جلسة سارية غير منتهية من قاعدة البيانات عند إعادة إقلاع العملية
        try {
            val now = TrustedTimeProvider.currentTimeMillis(context)
            repository.getAllActiveSessionsBlocking().firstOrNull { !it.isExpired(now) }?.let { restored ->
                activeSession = restored
                Log.i(TAG, "Restored active Waqfa session from DB for ${restored.packageName}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed restoring initial active Waqfa session from DB", e)
        }
    }

    fun updateConfig(config: WaqfaConfig) {
        cachedConfig = config
        Log.i(TAG, "WaqfaConfig updated: isEnabled=${config.isEnabled}, targets=${config.targetPackages.size}")
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

        // 3. جدولة منبه انتهاء الوقت الدقيق
        WaqfaAlarmScheduler.scheduleSessionTimeoutAlarm(context, newSession)

        Log.i(TAG, "Active session registered for $packageName (${newSession.durationMinutes}m)")
        newSession
    }

    /**
     * يُستدعى فور رصد تغير التطبيق النشط في الواجهة.
     * يُلغي الجلسة فوراً إذا غادر المستخدم التطبيق إلى الشاشة الرئيسية أو تطبيق آخر بعد فترة سماح (Debounce).
     */
    fun onForegroundPackageChanged(newForegroundPackage: String) {
        if (newForegroundPackage.isBlank()) return
        if (newForegroundPackage == context.packageName || CurfewAppResolver.isSystemCriticalOrImmune(context, newForegroundPackage)) {
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

                // عند الانتقال لتطبيق آخر غير محمي، ننتظر مهلة سماح 2500ms لمنع الإغلاق في التبديل السريع
                exitGraceJob?.cancel()
                val sessionToTerminate = current
                exitGraceJob = managerScope.launch {
                    delay(2500L)
                    mutex.withLock {
                        val stillActive = activeSession
                        if (stillActive != null && stillActive.id == sessionToTerminate.id) {
                            Log.i(TAG, "User exited ${stillActive.packageName} -> switched to $newForegroundPackage. Grace period elapsed. Terminating Waqfa session.")
                            repository.endSession(stillActive.id, WaqfaSession.ACTION_USER_EARLY_EXIT)
                            WaqfaAlarmScheduler.cancelSessionTimeoutAlarm(context, stillActive.packageName)
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
            Log.d(TAG, "No active session found for timeout on $packageName")
            return@withLock
        }

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
        exitGraceJob?.cancel()
        exitGraceJob = null

        val current = activeSession ?: return@withLock
        repository.endSession(current.id, endAction)
        WaqfaAlarmScheduler.cancelSessionTimeoutAlarm(context, current.packageName)
        activeSession = null
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
