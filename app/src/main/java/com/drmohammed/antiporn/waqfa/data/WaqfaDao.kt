package com.drmohammed.antiporn.waqfa.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * واجهة الوصول للبيانات (DAO) لجدول [waqfa_sessions].
 * عمليات CRUD مستقلة تحافظ على سقف حجم الكود ونقاء المعمارية.
 */
@Dao
interface WaqfaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WaqfaSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSessionBlocking(session: WaqfaSessionEntity): Long

    @Update
    suspend fun updateSession(session: WaqfaSessionEntity)

    @Update
    fun updateSessionBlocking(session: WaqfaSessionEntity)

    @Query("SELECT * FROM waqfa_sessions WHERE package_name = :packageName AND is_active = 1 ORDER BY started_at_trusted_ms DESC LIMIT 1")
    suspend fun getActiveSession(packageName: String): WaqfaSessionEntity?

    @Query("SELECT * FROM waqfa_sessions WHERE package_name = :packageName AND is_active = 1 ORDER BY started_at_trusted_ms DESC LIMIT 1")
    fun getActiveSessionBlocking(packageName: String): WaqfaSessionEntity?

    @Query("SELECT * FROM waqfa_sessions WHERE is_active = 1")
    suspend fun getAllActiveSessions(): List<WaqfaSessionEntity>

    @Query("SELECT * FROM waqfa_sessions WHERE is_active = 1")
    fun getAllActiveSessionsBlocking(): List<WaqfaSessionEntity>

    @Query("UPDATE waqfa_sessions SET is_active = 0, actual_end_at_trusted_ms = :actualEndMs, end_action_applied = :action WHERE id = :id AND is_active = 1")
    suspend fun markSessionCompleted(id: Long, actualEndMs: Long, action: String)

    @Query("UPDATE waqfa_sessions SET is_active = 0, actual_end_at_trusted_ms = :actualEndMs, end_action_applied = :action WHERE id = :id AND is_active = 1")
    fun markSessionCompletedBlocking(id: Long, actualEndMs: Long, action: String)

    @Query("UPDATE waqfa_sessions SET is_active = 0, actual_end_at_trusted_ms = :actualEndMs, end_action_applied = :action WHERE package_name = :packageName AND is_active = 1")
    suspend fun markActiveSessionsCompletedForPackage(packageName: String, actualEndMs: Long, action: String)

    @Query("UPDATE waqfa_sessions SET is_active = 0, actual_end_at_trusted_ms = :actualEndMs, end_action_applied = :action WHERE is_active = 1")
    suspend fun markAllActiveSessionsCompleted(actualEndMs: Long, action: String)

    @Transaction
    suspend fun replaceActiveSessionAtomic(
        packageName: String,
        actualEndMs: Long,
        closeAction: String,
        newSession: WaqfaSessionEntity
    ): Long {
        markActiveSessionsCompletedForPackage(packageName, actualEndMs, closeAction)
        return insertSession(newSession)
    }

    @Query("SELECT * FROM waqfa_sessions ORDER BY started_at_trusted_ms DESC LIMIT :limit")
    fun getRecentSessions(limit: Int): Flow<List<WaqfaSessionEntity>>

    @Query("SELECT * FROM waqfa_sessions ORDER BY started_at_trusted_ms DESC")
    fun getAllSessionsFlow(): Flow<List<WaqfaSessionEntity>>

    @Query("SELECT COUNT(*) FROM waqfa_sessions")
    suspend fun getTotalSessionCount(): Int

    @Query("DELETE FROM waqfa_sessions WHERE started_at_trusted_ms < :cutoffTimeMs AND is_active = 0")
    suspend fun deleteSessionsOlderThan(cutoffTimeMs: Long): Int
}
