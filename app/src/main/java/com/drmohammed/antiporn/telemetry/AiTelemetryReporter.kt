package com.drmohammed.antiporn.telemetry

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI-Targeted Telemetry & Diagnostic Report Generator.
 * Compiles a rich, structured Markdown diagnostic report combining:
 * 1. Hardware, Android OS, and battery metrics.
 * 2. Enterprise Device Owner (DO) and Device Admin (DA) status.
 * 3. Latest fatal crash dump (if any).
 * 4. Recent 50 system and DPM operations with precise execution latencies and error logs.
 * 5. Pre-crafted prompt for instant one-click analysis by AI models.
 */
object AiTelemetryReporter {

    private const val TAG = "AntiPorn_AiReporter"

    /**
     * Generates a complete, structured Markdown diagnostic report.
     */
    fun generateMarkdownReport(context: Context): String {
        val appContext = context.applicationContext
        val now = TrustedTimeProvider.currentTimeMillis()
        val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss (z)", Locale.US).format(Date(now))

        // Device & Environment Info
        val env = collectEnvironmentInfo(appContext)

        // Read Latest Crash Dump if present
        val latestCrash = getLatestCrashDump(appContext)

        // Read Recent Diagnostic Events from SQLite
        val recentEvents = try {
            AntiPornDatabase.getInstance(appContext).getRecentDiagnosticEvents(50)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching diagnostic events for AI report", e)
            emptyList()
        }

        val totalEventsCount = try {
            AntiPornDatabase.getInstance(appContext).getDiagnosticEventsCount()
        } catch (e: Exception) {
            Log.w(TAG, "Error querying diagnostic events count", e)
            -1L
        }

        val sb = StringBuilder()

        // 1. Report Header
        sb.appendLine("# 🛡️ AntiPorn Guard — In-App Diagnostic & Telemetry Report")
        sb.appendLine("> Generated on: `$formattedDate` | App Version: `${env.appVersionName} (${env.appVersionCode})`")
        sb.appendLine()

        // 2. Environment Summary
        sb.appendLine("## 📱 1. Device & Android Enterprise Environment")
        sb.appendLine("| Property | Value |")
        sb.appendLine("| :--- | :--- |")
        sb.appendLine("| **Device Model** | `${env.manufacturer} ${env.model} (${env.device})` |")
        sb.appendLine("| **Android OS** | `Android ${env.androidVersion} (API ${env.sdkInt})` |")
        sb.appendLine("| **Battery Level** | `${env.batteryPercent}%` |")
        sb.appendLine("| **RAM (Available / Total)** | `${env.availableMemoryMb} MB / ${env.totalMemoryMb} MB` |")
        sb.appendLine("| **Device Owner (DO) Active** | `${if (env.isDeviceOwner) "✅ YES" else "❌ NO"}` |")
        sb.appendLine("| **Device Admin (DA) Active** | `${if (env.isAdminActive) "✅ YES" else "❌ NO"}` |")
        sb.appendLine("| **Total Logged Events** | `${if (totalEventsCount >= 0) totalEventsCount else "Unknown"}` |")
        sb.appendLine()

        // 3. Crash Status
        sb.appendLine("## 💥 2. Latest Crash Incident")
        if (!latestCrash.isNullOrBlank()) {
            sb.appendLine("```text")
            sb.appendLine(latestCrash.trim())
            sb.appendLine("```")
        } else {
            sb.appendLine("✅ **No fatal crashes recorded on this device.**")
        }
        sb.appendLine()

        // 4. Recent Operations Table
        sb.appendLine("## ⏱️ 3. Recent 50 Operations & DPM Execution Latencies")
        if (recentEvents.isNotEmpty()) {
            sb.appendLine("| Timestamp | Tag | Action | Latency | Status | Error Details | Context Metadata |")
            sb.appendLine("| :--- | :--- | :--- | :---: | :---: | :--- | :--- |")
            for (event in recentEvents) {
                sb.appendLine(event.toMarkdownTableRow())
            }
        } else {
            sb.appendLine("ℹ️ *No diagnostic events recorded yet.*")
        }
        sb.appendLine()

        // 5. AI Prompt & Diagnosis Guidance
        sb.appendLine("---")
        sb.appendLine("## 🤖 4. AI Diagnosis Instructions (تعليمات التحليل للذكاء الاصطناعي)")
        sb.appendLine("""
Please analyze the telemetry log above and provide an actionable diagnosis:
1. **Button Responsiveness & Latency:** Identify any operations with latencies exceeding 100ms or binder deadlocks.
2. **DPM Security & Permissions:** Check for any `SecurityException`, `NOT_DEVICE_OWNER`, or permission denials.
3. **Crash Root Cause:** If a crash stacktrace is present, diagnose the exact failure line and suggest a surgical fix.
4. **Actionable Recommendations:** Recommend concise architectural or code adjustments.
""".trimIndent())

        return sb.toString()
    }

    /**
     * Reads the latest plain-text crash dump file from internal storage.
     */
    fun getLatestCrashDump(context: Context): String? {
        return try {
            val file = File(context.filesDir, GlobalCrashHandler.DUMP_FILE_TXT)
            if (file.exists() && file.length() > 0) {
                file.readText(Charsets.UTF_8)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Error reading latest crash dump", e)
            null
        }
    }

    /**
     * Deletes the latest crash dump file from internal storage.
     */
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

    /**
     * Copies the full generated Markdown report directly to the system Clipboard.
     */
    fun copyReportToClipboard(context: Context, preGeneratedReport: String? = null): Boolean {
        return try {
            val report = preGeneratedReport ?: generateMarkdownReport(context)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("AntiPorn Telemetry Report", report)
            clipboard?.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying telemetry report to clipboard", e)
            false
        }
    }

    private fun collectEnvironmentInfo(context: Context): EnvironmentInfo {
        // Query Battery
        val batteryLevel = try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "Error querying battery level", e)
            -1
        }

        // Query Memory
        var availMemMb = -1L
        var totalMemMb = -1L
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            availMemMb = memInfo.availMem / (1024 * 1024)
            totalMemMb = memInfo.totalMem / (1024 * 1024)
        } catch (e: Exception) {
            Log.w(TAG, "Error querying memory info", e)
        }

        // Query App Version
        var verName = "unknown"
        var verCode = -1L
        try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            verName = pInfo.versionName ?: "unknown"
            verCode = PackageInfoCompat.getLongVersionCode(pInfo)
        } catch (e: Exception) {
            Log.w(TAG, "Error querying app version info", e)
        }

        // Query Device Owner & Admin Status
        var isDeviceOwner = false
        var isAdminActive = false
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (dpm != null) {
                isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
                val adminComponent = ComponentName(context, AdminReceiver::class.java)
                isAdminActive = dpm.isAdminActive(adminComponent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying DPM status", e)
        }

        return EnvironmentInfo(
            batteryPercent = batteryLevel,
            availableMemoryMb = availMemMb,
            totalMemoryMb = totalMemMb,
            isDeviceOwner = isDeviceOwner,
            isAdminActive = isAdminActive,
            appVersionName = verName,
            appVersionCode = verCode,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            sdkInt = Build.VERSION.SDK_INT,
            androidVersion = Build.VERSION.RELEASE
        )
    }

    private data class EnvironmentInfo(
        val batteryPercent: Int,
        val availableMemoryMb: Long,
        val totalMemoryMb: Long,
        val isDeviceOwner: Boolean,
        val isAdminActive: Boolean,
        val appVersionName: String,
        val appVersionCode: Long,
        val manufacturer: String,
        val model: String,
        val device: String,
        val sdkInt: Int,
        val androidVersion: String
    )
}
