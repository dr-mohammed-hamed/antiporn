package com.drmohammed.antiporn.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.drmohammed.antiporn.data.local.db.entity.InAppNotificationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Modern Jetpack Room DAO for In-App Notifications.
 * Exposes both suspend queries for one-shot operations and reactive [Flow] streams
 * for real-time UI synchronization without polling.
 */
@Dao
interface InAppNotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(notification: InAppNotificationEntity): Long

    @Query("SELECT * FROM in_app_notifications ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getAllNotifications(limit: Int = 200): List<InAppNotificationEntity>

    @Query("SELECT * FROM in_app_notifications ORDER BY timestamp DESC LIMIT :limit")
    fun observeAllNotifications(limit: Int = 200): Flow<List<InAppNotificationEntity>>

    @Query("SELECT COUNT(*) FROM in_app_notifications WHERE is_read = 0")
    suspend fun getUnreadCount(): Int

    @Query("SELECT COUNT(*) FROM in_app_notifications WHERE is_read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM in_app_notifications WHERE dedup_key = :dedupKey LIMIT 1")
    suspend fun getByDedupKey(dedupKey: String): InAppNotificationEntity?

    @Query("SELECT * FROM in_app_notifications WHERE dedup_key = :dedupKey LIMIT 1")
    fun getByDedupKeyBlocking(dedupKey: String): InAppNotificationEntity?

    @Query("UPDATE in_app_notifications SET is_read = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long): Int

    @Query("UPDATE in_app_notifications SET is_read = 1 WHERE is_read = 0")
    suspend fun markAllAsRead(): Int

    @Query("DELETE FROM in_app_notifications WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM in_app_notifications")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM in_app_notifications")
    suspend fun getTotalCount(): Int

    @Query("DELETE FROM in_app_notifications WHERE timestamp < :olderThanMs")
    suspend fun deleteOlderThan(olderThanMs: Long): Int

    @Query("DELETE FROM in_app_notifications WHERE id IN (SELECT id FROM in_app_notifications ORDER BY timestamp ASC, id ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int): Int

    @Transaction
    suspend fun pruneOldNotifications(maxKeep: Int = 200, olderThanMs: Long = 0L): Int {
        var pruned = 0
        if (olderThanMs > 0L) {
            pruned += deleteOlderThan(olderThanMs)
        }
        if (maxKeep > 0) {
            val total = getTotalCount()
            if (total > maxKeep) {
                val excess = total - maxKeep
                pruned += deleteOldest(excess)
            }
        }
        return pruned
    }
}
