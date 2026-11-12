package com.drmohammed.antiporn.telemetry

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.drmohammed.antiporn.R
import java.io.File

/**
 * Handles crash dump storage retrieval and diagnostic report export/sharing/clipboard delivery.
 */
internal object TelemetryExportHandler {

    private const val TAG = "AntiPorn_AiReporter"

    fun getLatestCrashDump(context: Context): String? {
        return try {
            val file = File(context.filesDir, GlobalCrashHandler.DUMP_FILE_TXT)
            if (file.exists() && file.length() > 0) {
                file.readText(Charsets.UTF_8)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading latest crash dump", e)
            null
        }
    }

    fun clearLatestCrashDump(context: Context): Boolean {
        return try {
            val file = File(context.filesDir, GlobalCrashHandler.DUMP_FILE_TXT)
            val jsonFile = File(context.filesDir, GlobalCrashHandler.DUMP_FILE_JSON)
            val deletedTxt = if (file.exists()) file.delete() else true
            val deletedJson = if (jsonFile.exists()) jsonFile.delete() else true
            deletedTxt && deletedJson
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing latest crash dump", e)
            false
        }
    }

    fun exportReportToFile(context: Context, reportText: String): File {
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        try {
            reportsDir.listFiles()?.filter {
                it.name.startsWith("AntiPorn_Diagnostic_Report") && it.isFile
            }?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed cleaning previous diagnostic report cache", e)
        }

        val reportFile = File(reportsDir, "AntiPorn_Diagnostic_Report.txt")
        reportFile.writeText(reportText, Charsets.UTF_8)
        return reportFile
    }

    fun createShareFileIntent(context: Context, reportFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, reportFile)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.telemetry_share_subject))
            clipData = ClipData.newRawUri("AntiPorn Diagnostic Report", contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return Intent.createChooser(shareIntent, context.getString(R.string.share_telemetry_chooser_title)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun copyReportToClipboard(context: Context, reportText: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            val clip = ClipData.newPlainText("AntiPorn Telemetry Report", reportText)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying telemetry report to clipboard", e)
            false
        }
    }
}
