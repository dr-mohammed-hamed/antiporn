package com.drmohammed.antiporn.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing an application installation pending approval from a guardian.
 * Verbatim SQLite DDL:
 * CREATE TABLE IF NOT EXISTS pending_approvals (
 *     package_name TEXT PRIMARY KEY,
 *     app_name TEXT NOT NULL,
 *     requested_at INTEGER NOT NULL,
 *     status TEXT NOT NULL,
 *     decided_by TEXT,
 *     decided_at INTEGER,
 *     is_synced INTEGER NOT NULL DEFAULT 0
 * );
 * CREATE INDEX IF NOT EXISTS idx_pending_approvals_status ON pending_approvals (status);
 */
@Entity(
    tableName = "pending_approvals",
    indices = [
        Index(value = ["status"], name = "idx_pending_approvals_status")
    ]
)
data class PendingApprovalEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "app_name")
    val appName: String,

    @ColumnInfo(name = "requested_at")
    val requestedAt: Long = try {
        com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis()
    } catch (_: Throwable) {
        System.currentTimeMillis()
    },

    @ColumnInfo(name = "status")
    val status: String = STATUS_PENDING,

    @ColumnInfo(name = "decided_by")
    val decidedBy: String? = null,

    @ColumnInfo(name = "decided_at")
    val decidedAt: Long? = null,

    @ColumnInfo(name = "is_synced", defaultValue = "0")
    val isCloudSynced: Boolean = false
) {
    val isPending: Boolean get() = status == STATUS_PENDING || status == STATUS_FALLBACK_HIDDEN
    val isApproved: Boolean get() = status == STATUS_APPROVED
    val isRejected: Boolean get() = status == STATUS_REJECTED
    val isFallbackHidden: Boolean get() = status == STATUS_FALLBACK_HIDDEN

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_FALLBACK_HIDDEN = "FALLBACK_HIDDEN"
    }
}
