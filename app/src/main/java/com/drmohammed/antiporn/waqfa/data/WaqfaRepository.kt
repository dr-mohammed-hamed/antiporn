package com.drmohammed.antiporn.waqfa.data

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.waqfa.model.WaqfaIntentType
import com.drmohammed.antiporn.waqfa.model.WaqfaSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * مستودع إدارة بيانات جلسات «وَقْفَة».
 * يمثل نقطة الحقيقة الواحدة لبيانات الجلسات بين قاعدة بيانات Room والمحرك.
 */
class WaqfaRepository(
    private val waqfaDao: WaqfaDao,
    private val context: Context
) {

    suspend fun startSession(
        packageName: String,
        appName: String,
        intentType: WaqfaIntentType,
        reasonOrFeeling: String?,
        durationMinutes: Int
    ): WaqfaSession = withContext(Dispatchers.IO) {
        val nowTrusted = TrustedTimeProvider.currentTimeMillis(context)
        val plannedEnd = nowTrusted + (durationMinutes * 60_000L)

        val session = WaqfaSession(
            sessionId = UUID.randomUUID().toString(),
            packageName = packageName,
            appName = appName,
            intentType = intentType,
            reasonOrFeeling = reasonOrFeeling?.trim()?.takeIf { it.isNotBlank() },
            durationMinutes = durationMinutes,
            startedAtTrustedMs = nowTrusted,
            plannedEndAtTrustedMs = plannedEnd,
            isActive = true
        )

        // استبدال الجلسة النشطة السابقة وإدراج الجديدة ذرياً داخل عملية Transaction واحدة
        val rowId = waqfaDao.replaceActiveSessionAtomic(
            packageName = packageName,
            actualEndMs = nowTrusted,
            closeAction = WaqfaSession.ACTION_USER_EARLY_EXIT,
            newSession = WaqfaSessionEntity.fromDomain(session)
        )
        Log.i(TAG, "Started new Waqfa session #$rowId for $packageName ($durationMinutes min, ${intentType.name})")
        session.copy(id = rowId)
    }

    suspend fun endSession(
        sessionId: Long,
        endAction: String
    ): Unit = withContext(Dispatchers.IO) {
        val nowTrusted = TrustedTimeProvider.currentTimeMillis(context)
        waqfaDao.markSessionCompleted(sessionId, nowTrusted, endAction)
        Log.i(TAG, "Ended Waqfa session #$sessionId with action: $endAction")
    }

    suspend fun endActiveSessionForPackage(
        packageName: String,
        endAction: String
    ): Unit = withContext(Dispatchers.IO) {
        val nowTrusted = TrustedTimeProvider.currentTimeMillis(context)
        waqfaDao.markActiveSessionsCompletedForPackage(packageName, nowTrusted, endAction)
        Log.i(TAG, "Ended active Waqfa sessions for $packageName with action: $endAction")
    }

    suspend fun getActiveSession(packageName: String): WaqfaSession? = withContext(Dispatchers.IO) {
        waqfaDao.getActiveSession(packageName)?.toDomain()
    }

    fun getActiveSessionBlocking(packageName: String): WaqfaSession? {
        return waqfaDao.getActiveSessionBlocking(packageName)?.toDomain()
    }

    suspend fun getAllActiveSessions(): List<WaqfaSession> = withContext(Dispatchers.IO) {
        waqfaDao.getAllActiveSessions().map { it.toDomain() }
    }

    fun getAllActiveSessionsBlocking(): List<WaqfaSession> {
        return waqfaDao.getAllActiveSessionsBlocking().map { it.toDomain() }
    }

    suspend fun endAllActiveSessions(endAction: String): Unit = withContext(Dispatchers.IO) {
        val nowTrusted = TrustedTimeProvider.currentTimeMillis(context)
        waqfaDao.markAllActiveSessionsCompleted(nowTrusted, endAction)
    }

    fun getRecentSessions(limit: Int = 50): Flow<List<WaqfaSession>> {
        return waqfaDao.getRecentSessions(limit).map { list ->
            list.map { it.toDomain() }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WaqfaRepo"

        @Volatile
        private var instance: WaqfaRepository? = null

        fun getInstance(context: Context): WaqfaRepository {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val db = HisnRoomDatabase.getInstance(context)
                    WaqfaRepository(db.waqfaDao(), context.applicationContext).also { instance = it }
                }
            }
        }
    }
}
