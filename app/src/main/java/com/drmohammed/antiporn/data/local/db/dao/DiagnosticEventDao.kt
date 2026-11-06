package com.drmohammed.antiporn.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drmohammed.antiporn.data.local.db.entity.DiagnosticEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for in-app diagnostic and Black Box telemetry events.
 */
@Dao
interface DiagnosticEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiagnosticEvent(event: DiagnosticEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDiagnosticEventBlocking(event: DiagnosticEventEntity): Long

    @Query("SELECT * FROM diagnostic_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEventsFlow(limit: Int = 50): Flow<List<DiagnosticEventEntity>>

    @Query("SELECT * FROM diagnostic_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 50): List<DiagnosticEventEntity>

    @Query("SELECT * FROM diagnostic_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEventsBlocking(limit: Int = 50): List<DiagnosticEventEntity>

    @Query("SELECT * FROM diagnostic_events WHERE (:tag IS NULL OR tag = :tag) ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getDiagnosticEvents(limit: Int = 200, tag: String? = null): List<DiagnosticEventEntity>

    @Query("SELECT * FROM diagnostic_events WHERE (:tag IS NULL OR tag = :tag) ORDER BY timestamp DESC LIMIT :limit")
    fun getDiagnosticEventsBlocking(limit: Int = 200, tag: String? = null): List<DiagnosticEventEntity>

    @Query("SELECT COUNT(*) FROM diagnostic_events")
    suspend fun getEventsCount(): Long

    @Query("SELECT COUNT(*) FROM diagnostic_events")
    fun getEventsCountBlocking(): Long

    @Query("DELETE FROM diagnostic_events WHERE id IN (SELECT id FROM diagnostic_events ORDER BY timestamp ASC, id ASC LIMIT :excess)")
    suspend fun deleteOldestExcess(excess: Int): Int

    @Query("DELETE FROM diagnostic_events WHERE id IN (SELECT id FROM diagnostic_events ORDER BY timestamp ASC, id ASC LIMIT :excess)")
    fun deleteOldestExcessBlocking(excess: Int): Int

    @Query("DELETE FROM diagnostic_events WHERE timestamp < :cutoffTimestamp")
    suspend fun pruneEventsOlderThan(cutoffTimestamp: Long): Int

    @Query("DELETE FROM diagnostic_events WHERE timestamp < :cutoffTimestamp")
    fun pruneEventsOlderThanBlocking(cutoffTimestamp: Long): Int

    @Query("DELETE FROM diagnostic_events WHERE id NOT IN (SELECT id FROM diagnostic_events ORDER BY timestamp DESC, id DESC LIMIT :maxEvents)")
    suspend fun enforceMaxEventsCap(maxEvents: Int): Int

    @Query("DELETE FROM diagnostic_events WHERE id NOT IN (SELECT id FROM diagnostic_events ORDER BY timestamp DESC, id DESC LIMIT :maxEvents)")
    fun enforceMaxEventsCapBlocking(maxEvents: Int): Int

    @Query("DELETE FROM diagnostic_events")
    suspend fun clearAllEvents(): Int

    @Query("DELETE FROM diagnostic_events")
    fun clearAllEventsBlocking(): Int
}
