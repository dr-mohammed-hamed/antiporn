package com.drmohammed.antiporn.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing an accountability partner (Guardian or Ward).
 * Verbatim SQLite DDL:
 * CREATE TABLE IF NOT EXISTS partners (
 *     id TEXT PRIMARY KEY,
 *     pairing_code TEXT NOT NULL,
 *     display_name TEXT NOT NULL,
 *     role TEXT NOT NULL,
 *     status TEXT NOT NULL,
 *     created_at INTEGER NOT NULL,
 *     last_sync_at INTEGER NOT NULL
 * );
 * CREATE INDEX IF NOT EXISTS idx_partners_role ON partners (role);
 */
@Entity(
    tableName = "partners",
    indices = [
        Index(value = ["role"], name = "idx_partners_role")
    ]
)
data class PartnerEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "pairing_code")
    val pairingCode: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "role")
    val role: String, // "GUARDIAN" (protects me) or "WARD" (I protect them)

    @ColumnInfo(name = "status")
    val status: String = STATUS_ACTIVE, // "ACTIVE", "PENDING", "REVOKED"

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long = System.currentTimeMillis()
) {
    val isGuardian: Boolean get() = role == ROLE_GUARDIAN
    val isWard: Boolean get() = role == ROLE_WARD

    companion object {
        const val ROLE_GUARDIAN = "GUARDIAN"
        const val ROLE_WARD = "WARD"

        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_REVOKED = "REVOKED"
    }
}
