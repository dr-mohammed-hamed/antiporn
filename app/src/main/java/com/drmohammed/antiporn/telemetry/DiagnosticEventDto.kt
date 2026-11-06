package com.drmohammed.antiporn.telemetry

import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data Transfer Object representing an in-app diagnostic or telemetry event.
 * Stored in SQLite WAL database and used by [AiTelemetryReporter] to diagnose system performance.
 */
data class DiagnosticEventDto(
    val id: Long = 0L,
    val timestamp: Long,
    val tag: String,
    val actionName: String,
    val executionTimeMs: Long,
    val isSuccess: Boolean,
    val errorDetails: String? = null,
    val contextMetadata: String? = null
) {
    /**
     * Formats timestamp into human-readable ISO-like local date string (e.g. "2026-11-02 20:30:15.123").
     */
    fun formattedTime(): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            sdf.format(Date(timestamp))
        } catch (e: Exception) {
            Timber.w(e, "Failed to format timestamp")
            timestamp.toString()
        }
    }

    /**
     * Returns a compact Markdown table row representation.
     */
    fun toMarkdownTableRow(): String {
        val statusEmoji = if (isSuccess) "✅ OK" else "❌ FAIL"
        val cleanTag = tag.replace("|", "/").replace("`", "'").replace("\n", " ")
        val cleanAction = actionName.replace("|", "/").replace("`", "'").replace("\n", " ")
        val errorText = errorDetails?.replace("|", "/")?.replace("`", "'")?.replace("\n", " ") ?: "-"
        val metadataText = contextMetadata?.replace("|", "/")?.replace("`", "'")?.replace("\n", " ") ?: "-"
        return "| `${formattedTime()}` | `$cleanTag` | `$cleanAction` | `${executionTimeMs}ms` | $statusEmoji | $errorText | $metadataText |"
    }
}
