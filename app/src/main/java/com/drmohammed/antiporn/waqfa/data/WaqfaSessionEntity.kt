package com.drmohammed.antiporn.waqfa.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.drmohammed.antiporn.waqfa.model.WaqfaIntentType
import com.drmohammed.antiporn.waqfa.model.WaqfaSession

/**
 * كيان Room لتخزين سجلات جلسات «وَقْفَة» في جدول [waqfa_sessions].
 * يُؤسس لبناء تقارير وتحليلات سلوكية دقيقة وموثوقة زمنياً.
 */
@Entity(
    tableName = "waqfa_sessions",
    indices = [
        Index(value = ["package_name"], name = "idx_waqfa_sessions_pkg"),
        Index(value = ["started_at_trusted_ms"], orders = [Index.Order.DESC], name = "idx_waqfa_sessions_start"),
        Index(value = ["package_name", "is_active"], name = "idx_waqfa_sessions_active")
    ]
)
data class WaqfaSessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "session_uuid")
    val sessionUuid: String,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "app_name")
    val appName: String,

    @ColumnInfo(name = "intent_type")
    val intentType: String,

    @ColumnInfo(name = "reason_or_feeling")
    val reasonOrFeeling: String?,

    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int,

    @ColumnInfo(name = "started_at_trusted_ms")
    val startedAtTrustedMs: Long,

    @ColumnInfo(name = "planned_end_at_trusted_ms")
    val plannedEndAtTrustedMs: Long,

    @ColumnInfo(name = "actual_end_at_trusted_ms")
    val actualEndAtTrustedMs: Long? = null,

    @ColumnInfo(name = "end_action_applied")
    val endActionApplied: String? = null,

    @ColumnInfo(name = "is_active", defaultValue = "1")
    val isActive: Int = 1
) {
    fun toDomain(): WaqfaSession {
        val parsedIntent = runCatching { WaqfaIntentType.valueOf(intentType) }
            .getOrDefault(WaqfaIntentType.PURPOSEFUL)

        return WaqfaSession(
            id = id,
            sessionId = sessionUuid,
            packageName = packageName,
            appName = appName,
            intentType = parsedIntent,
            reasonOrFeeling = reasonOrFeeling,
            durationMinutes = durationMinutes,
            startedAtTrustedMs = startedAtTrustedMs,
            plannedEndAtTrustedMs = plannedEndAtTrustedMs,
            actualEndAtTrustedMs = actualEndAtTrustedMs,
            endActionApplied = endActionApplied,
            isActive = isActive == 1
        )
    }

    companion object {
        fun fromDomain(session: WaqfaSession): WaqfaSessionEntity {
            return WaqfaSessionEntity(
                id = session.id,
                sessionUuid = session.sessionId,
                packageName = session.packageName,
                appName = session.appName,
                intentType = session.intentType.name,
                reasonOrFeeling = session.reasonOrFeeling,
                durationMinutes = session.durationMinutes,
                startedAtTrustedMs = session.startedAtTrustedMs,
                plannedEndAtTrustedMs = session.plannedEndAtTrustedMs,
                actualEndAtTrustedMs = session.actualEndAtTrustedMs,
                endActionApplied = session.endActionApplied,
                isActive = if (session.isActive) 1 else 0
            )
        }
    }
}
