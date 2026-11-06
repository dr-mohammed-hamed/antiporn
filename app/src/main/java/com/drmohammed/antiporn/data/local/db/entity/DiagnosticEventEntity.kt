package com.drmohammed.antiporn.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.drmohammed.antiporn.telemetry.DiagnosticEventDto

/**
 * Entity representing an in-app diagnostic or telemetry event.
 * Verbatim SQLite DDL:
 * CREATE TABLE IF NOT EXISTS diagnostic_events (
 *     id INTEGER PRIMARY KEY AUTOINCREMENT,
 *     timestamp INTEGER NOT NULL,
 *     tag TEXT NOT NULL,
 *     action_name TEXT NOT NULL,
 *     execution_time_ms INTEGER NOT NULL,
 *     is_success INTEGER NOT NULL,
 *     error_details TEXT,
 *     context_metadata TEXT
 * );
 * CREATE INDEX IF NOT EXISTS idx_diag_timestamp ON diagnostic_events (timestamp);
 * CREATE INDEX IF NOT EXISTS idx_diag_tag ON diagnostic_events (tag);
 * CREATE INDEX IF NOT EXISTS idx_diag_tag_timestamp ON diagnostic_events (tag, timestamp DESC);
 */
@Entity(
    tableName = "diagnostic_events",
    indices = [
        Index(value = ["timestamp"], name = "idx_diag_timestamp"),
        Index(value = ["tag"], name = "idx_diag_tag"),
        Index(
            value = ["tag", "timestamp"],
            orders = [Index.Order.ASC, Index.Order.DESC],
            name = "idx_diag_tag_timestamp"
        )
    ]
)
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "tag")
    val tag: String,

    @ColumnInfo(name = "action_name")
    val actionName: String,

    @ColumnInfo(name = "execution_time_ms")
    val executionTimeMs: Long,

    @ColumnInfo(name = "is_success")
    val isSuccess: Boolean,

    @ColumnInfo(name = "error_details")
    val errorDetails: String? = null,

    @ColumnInfo(name = "context_metadata")
    val contextMetadata: String? = null
) {
    fun toDto(): DiagnosticEventDto = DiagnosticEventDto(
        id = id,
        timestamp = timestamp,
        tag = tag,
        actionName = actionName,
        executionTimeMs = executionTimeMs,
        isSuccess = isSuccess,
        errorDetails = errorDetails,
        contextMetadata = contextMetadata
    )

    companion object {
        fun fromDto(dto: DiagnosticEventDto): DiagnosticEventEntity = DiagnosticEventEntity(
            id = dto.id,
            timestamp = dto.timestamp,
            tag = dto.tag,
            actionName = dto.actionName,
            executionTimeMs = dto.executionTimeMs,
            isSuccess = dto.isSuccess,
            errorDetails = dto.errorDetails,
            contextMetadata = dto.contextMetadata
        )
    }
}
