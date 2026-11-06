package com.drmohammed.antiporn.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for pending app installation approvals.
 */
@Dao
interface PendingApprovalDao {

    @Query("SELECT * FROM pending_approvals ORDER BY requested_at DESC")
    fun getPendingApprovalsFlow(): Flow<List<PendingApprovalEntity>>

    @Query("SELECT * FROM pending_approvals ORDER BY requested_at DESC")
    suspend fun getPendingApprovals(): List<PendingApprovalEntity>

    @Query("SELECT * FROM pending_approvals ORDER BY requested_at DESC")
    fun getPendingApprovalsBlocking(): List<PendingApprovalEntity>

    @Query("SELECT * FROM pending_approvals WHERE is_synced = 0 ORDER BY requested_at ASC")
    suspend fun getUnsyncedApprovals(): List<PendingApprovalEntity>

    @Query("SELECT * FROM pending_approvals WHERE is_synced = 0 ORDER BY requested_at ASC")
    fun getUnsyncedApprovalsBlocking(): List<PendingApprovalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePendingApproval(approval: PendingApprovalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun savePendingApprovalBlocking(approval: PendingApprovalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePendingApprovals(approvals: List<PendingApprovalEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun savePendingApprovalsBlocking(approvals: List<PendingApprovalEntity>)

    @Query("""
        UPDATE pending_approvals 
        SET status = :status, decided_by = :decidedBy, decided_at = :decidedAt, is_synced = :isSynced 
        WHERE package_name = :packageName
    """)
    suspend fun updateApprovalStatus(
        packageName: String,
        status: String,
        decidedBy: String?,
        decidedAt: Long,
        isSynced: Int
    ): Int

    @Query("""
        UPDATE pending_approvals 
        SET status = :status, decided_by = :decidedBy, decided_at = :decidedAt, is_synced = :isSynced 
        WHERE package_name = :packageName
    """)
    fun updateApprovalStatusBlocking(
        packageName: String,
        status: String,
        decidedBy: String?,
        decidedAt: Long,
        isSynced: Int
    ): Int

    /**
     * Atomically updates the approval status, or inserts a new pending approval record
     * if the package does not exist locally yet (verbatim SQLiteOpenHelper upsert parity).
     */
    @Transaction
    fun upsertApprovalStatusBlocking(
        packageName: String,
        appName: String = packageName,
        status: String,
        decidedBy: String?,
        decidedAt: Long,
        isSynced: Int = 1
    ) {
        val affected = updateApprovalStatusBlocking(packageName, status, decidedBy, decidedAt, isSynced)
        if (affected == 0) {
            savePendingApprovalBlocking(
                PendingApprovalEntity(
                    packageName = packageName,
                    appName = appName,
                    requestedAt = decidedAt,
                    status = status,
                    decidedBy = decidedBy,
                    decidedAt = decidedAt,
                    isCloudSynced = isSynced == 1
                )
            )
        }
    }

    /**
     * Suspend version of atomic upsert.
     */
    @Transaction
    suspend fun upsertApprovalStatus(
        packageName: String,
        appName: String = packageName,
        status: String,
        decidedBy: String?,
        decidedAt: Long,
        isSynced: Int = 1
    ) {
        val affected = updateApprovalStatus(packageName, status, decidedBy, decidedAt, isSynced)
        if (affected == 0) {
            savePendingApproval(
                PendingApprovalEntity(
                    packageName = packageName,
                    appName = appName,
                    requestedAt = decidedAt,
                    status = status,
                    decidedBy = decidedBy,
                    decidedAt = decidedAt,
                    isCloudSynced = isSynced == 1
                )
            )
        }
    }

    @Query("UPDATE pending_approvals SET is_synced = :isSynced WHERE package_name = :packageName")
    suspend fun markApprovalCloudSynced(packageName: String, isSynced: Int): Int

    @Query("UPDATE pending_approvals SET is_synced = :isSynced WHERE package_name = :packageName")
    fun markApprovalCloudSyncedBlocking(packageName: String, isSynced: Int): Int

    @Query("DELETE FROM pending_approvals WHERE package_name = :packageName")
    suspend fun deletePendingApproval(packageName: String): Int

    @Query("DELETE FROM pending_approvals WHERE package_name = :packageName")
    fun deletePendingApprovalBlocking(packageName: String): Int
}
