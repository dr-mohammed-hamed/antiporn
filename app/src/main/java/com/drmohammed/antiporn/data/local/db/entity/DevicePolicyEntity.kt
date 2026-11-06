package com.drmohammed.antiporn.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.drmohammed.antiporn.core.model.DevicePolicyBundle

/**
 * Entity representing the locally cached active device policy.
 * Verbatim SQLite DDL:
 * CREATE TABLE IF NOT EXISTS device_policies (
 *     id INTEGER PRIMARY KEY,
 *     policy_json TEXT NOT NULL,
 *     version INTEGER NOT NULL,
 *     updated_at INTEGER NOT NULL
 * )
 */
@Entity(tableName = "device_policies")
data class DevicePolicyEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = 1,

    @ColumnInfo(name = "policy_json")
    val policyJson: String,

    @ColumnInfo(name = "version")
    val version: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
) {
    fun toBundle(): DevicePolicyBundle {
        return DevicePolicyBundle.fromJson(policyJson)
    }

    companion object {
        fun fromBundle(bundle: DevicePolicyBundle): DevicePolicyEntity {
            return DevicePolicyEntity(
                id = 1,
                policyJson = bundle.toJson(),
                version = bundle.version,
                updatedAt = bundle.updatedAt
            )
        }
    }
}
