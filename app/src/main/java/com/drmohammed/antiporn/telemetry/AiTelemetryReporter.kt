package com.drmohammed.antiporn.telemetry

import android.app.ActivityManager
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
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
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

        // Active Policy & Account State
        val currentPolicy = try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(appContext).getCurrentPolicy()
        } catch (e: Exception) {
            null
        }

        val cachedUid = try {
            com.drmohammed.antiporn.data.repository.AccountabilityRepository.getInstance(appContext).getCachedUid()
        } catch (e: Exception) {
            "Unknown"
        }

        val isEffectiveBlock = try {
            com.drmohammed.antiporn.curfew.CurfewEnforcer.isEffectiveBlockActive(appContext)
        } catch (e: Exception) {
            false
        }

        // Read Latest Crash Dump if present
        val latestCrash = getLatestCrashDump(appContext)

        // Read Categorized Diagnostic Events from Room Database
        val diagDao = HisnRoomDatabase.getInstance(appContext).diagnosticEventDao()
        val cloudEvents = try {
            (diagDao.getDiagnosticEventsBlocking(30, BlackBoxRecorder.TAG_CLOUD_SYNC) +
                diagDao.getDiagnosticEventsBlocking(15, BlackBoxRecorder.TAG_IDENTITY) +
                diagDao.getDiagnosticEventsBlocking(20, BlackBoxRecorder.TAG_PAIRING)).map { it.toDto() }
        } catch (e: Exception) {
            emptyList()
        }.sortedByDescending { it.timestamp }

        val curfewEvents = try {
            diagDao.getDiagnosticEventsBlocking(40, BlackBoxRecorder.TAG_CURFEW).map { it.toDto() }
        } catch (e: Exception) {
            emptyList()
        }

        val dpmEvents = try {
            diagDao.getDiagnosticEventsBlocking(30, BlackBoxRecorder.TAG_DPM).map { it.toDto() }
        } catch (e: Exception) {
            emptyList()
        }

        val totalEventsCount = try {
            diagDao.getEventsCountBlocking()
        } catch (e: Exception) {
            Log.w(TAG, "Error querying diagnostic events count", e)
            -1L
        }

        val sb = StringBuilder()

        // 1. Report Header
        sb.appendLine("# 🛡️ AntiPorn Guard — Black Box Diagnostic Report")
        sb.appendLine("> Generated on: `$formattedDate` | App Version: `${env.appVersionName} (${env.appVersionCode})`")
        sb.appendLine()

        // 2. Active Protection & Policy State
        sb.appendLine("## ⚙️ 1. Active Protection & Policy State (حالة السياسة والحظر)")
        if (currentPolicy != null) {
            sb.appendLine("| Protection Property | Current Value |")
            sb.appendLine("| :--- | :--- |")
            sb.appendLine("| **Current Policy Version** | `v${currentPolicy.version}` |")
            sb.appendLine("| **Effective Block Active Now** | `${if (isEffectiveBlock) "🔴 LOCKED / ACTIVE" else "🟢 NORMAL / DAYTIME"}` |")
            sb.appendLine("| **Curfew Feature Master Switch** | `${if (currentPolicy.isCurfewEnabled) "✅ ENABLED" else "❌ DISABLED"}` |")
            sb.appendLine("| **Curfew Schedule (Weekday)** | `${String.format(Locale.US, "%02d:%02d", currentPolicy.curfewStartHour, currentPolicy.curfewStartMinute)} ➔ ${String.format(Locale.US, "%02d:%02d", currentPolicy.curfewEndHour, currentPolicy.curfewEndMinute)}` |")
            sb.appendLine("| **Curfew Schedule (Weekend)** | `${if (currentPolicy.isWeekendCurfewEnabled) "✅ ACTIVE" else "❌ OFF"} (${String.format(Locale.US, "%02d:%02d", currentPolicy.weekendCurfewStartHour, currentPolicy.weekendCurfewStartMinute)} ➔ ${String.format(Locale.US, "%02d:%02d", currentPolicy.weekendCurfewEndHour, currentPolicy.weekendCurfewEndMinute)})` |")
            sb.appendLine("| **Policy Timezone** | `${currentPolicy.timeZoneId}` |")
            sb.appendLine("| **Supervised by Guardian** | `${if (currentPolicy.isSupervised) "✅ YES" else "❌ NO (Standalone)"}` |")
            sb.appendLine("| **Guardian Identity** | `${currentPolicy.guardianName ?: "None"} (${currentPolicy.guardianUid ?: "None"})` |")
            sb.appendLine("| **Self-Control Allowed** | `${if (currentPolicy.isSelfControlAllowed) "✅ YES" else "❌ LOCKED BY GUARDIAN"}` |")
            sb.appendLine("| **VPN Blackhole Internet Kill** | `${if (currentPolicy.isVpnBlackholeEnabled) "✅ ACTIVE" else "❌ DISABLED"}` |")
            sb.appendLine("| **Instant Internet Kill** | `${if (currentPolicy.isInstantInternetKillEnabled) "🔴 ACTIVE" else "⚪ OFF"}` |")
            sb.appendLine("| **Active DNS Host** | `${currentPolicy.dnsHost}` |")
            sb.appendLine("| **Local Device UID** | `$cachedUid` |")
        } else {
            sb.appendLine("⚠️ *Unable to load current device policy from SQLite.*")
        }
        sb.appendLine()

        // 3. Environment Summary
        sb.appendLine("## 📱 2. Device & Android Enterprise Environment")
        sb.appendLine("| Property | Value |")
        sb.appendLine("| :--- | :--- |")
        sb.appendLine("| **Device Model** | `${env.manufacturer} ${env.model} (${env.device})` |")
        sb.appendLine("| **Android OS** | `Android ${env.androidVersion} (API ${env.sdkInt})` |")
        sb.appendLine("| **Battery Level** | `${env.batteryPercent}%` |")
        sb.appendLine("| **RAM (Available / Total)** | `${env.availableMemoryMb} MB / ${env.totalMemoryMb} MB` |")
        sb.appendLine("| **Device Owner (DO) Active** | `${if (env.isDeviceOwner) "✅ YES" else "❌ NO"}` |")
        sb.appendLine("| **Device Admin (DA) Active** | `${if (env.isAdminActive) "✅ YES" else "❌ NO"}` |")
        sb.appendLine("| **Total Logged Events in Black Box** | `${if (totalEventsCount >= 0) totalEventsCount else "Unknown"}` |")
        sb.appendLine()

        // 4. Crash Status
        sb.appendLine("## 💥 3. Latest Crash Incident")
        if (!latestCrash.isNullOrBlank()) {
            sb.appendLine("```text")
            sb.appendLine(latestCrash.trim())
            sb.appendLine("```")
        } else {
            sb.appendLine("✅ **No fatal crashes recorded on this device.**")
        }
        sb.appendLine()

        // 5. Cloud Sync, Pairing & Identity Timeline
        sb.appendLine("## 🔄 4. Cloud Sync, Pairing & Identity Timeline (آخر عمليات المزامنة والاقتران والهويات)")
        if (cloudEvents.isNotEmpty()) {
            sb.appendLine("| Timestamp | Action | Status | Metadata / Error |")
            sb.appendLine("| :--- | :--- | :---: | :--- |")
            for (event in cloudEvents.take(25)) {
                val statusEmoji = if (event.isSuccess) "✅ OK" else "❌ FAIL"
                val errOrMeta = event.errorDetails?.takeIf { it.isNotBlank() } ?: (event.contextMetadata ?: "-")
                sb.appendLine("| `${event.formattedTime()}` | `${event.actionName}` | $statusEmoji | ${errOrMeta.replace("|", "/")} |")
            }
        } else {
            sb.appendLine("ℹ️ *No cloud synchronization events recorded yet.*")
        }
        sb.appendLine()

        // 6. Curfew, Nap & Alarm Lifecycle Events
        sb.appendLine("## 🌙 5. Curfew & Alarm Lifecycle Events (أحداث الحظر الليلي والمنبهات)")
        if (curfewEvents.isNotEmpty()) {
            sb.appendLine("| Timestamp | Action | Status | Details / Error |")
            sb.appendLine("| :--- | :--- | :---: | :--- |")
            for (event in curfewEvents.take(25)) {
                val statusEmoji = if (event.isSuccess) "✅ OK" else "❌ FAIL"
                val errOrMeta = event.errorDetails?.takeIf { it.isNotBlank() } ?: (event.contextMetadata ?: "-")
                sb.appendLine("| `${event.formattedTime()}` | `${event.actionName}` | $statusEmoji | ${errOrMeta.replace("|", "/")} |")
            }
        } else {
            sb.appendLine("ℹ️ *No curfew lifecycle events recorded yet.*")
        }
        sb.appendLine()

        // 7. System DPM Operations
        sb.appendLine("## ⚙️ 6. System & DPM Operations (أوامر حماية النظام)")
        if (dpmEvents.isNotEmpty()) {
            sb.appendLine("| Timestamp | Action | Latency | Status | Error Details |")
            sb.appendLine("| :--- | :--- | :---: | :---: | :--- |")
            for (event in dpmEvents.take(25)) {
                val statusEmoji = if (event.isSuccess) "✅ OK" else "❌ FAIL"
                val err = event.errorDetails?.replace("|", "/") ?: "-"
                sb.appendLine("| `${event.formattedTime()}` | `${event.actionName}` | `${event.executionTimeMs}ms` | $statusEmoji | $err |")
            }
        } else {
            sb.appendLine("ℹ️ *No DPM operations recorded yet.*")
        }
        sb.appendLine()

        // 8. AI Prompt & Diagnosis Guidance
        sb.appendLine("---")
        sb.appendLine("## 🤖 7. AI Diagnosis Instructions (تعليمات التحليل للذكاء الاصطناعي)")
        sb.appendLine("""
Please analyze the telemetry and black-box log above and provide an actionable diagnosis:
1. **Curfew & Alarm Timing:** Verify if the scheduled curfew start/end matches the observed behavior and identify any alarm skips or drift.
2. **Cloud Sync & Monotonic Guards:** Check for rejected stale policies, UID mismatches, or missing acknowledgments.
3. **Permissions & Security:** Identify any DPM failures, SecurityExceptions, or unexpected state transitions.
4. **Actionable Recommendations:** Provide a surgical diagnosis and concrete code/configuration recommendations.
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
        val isDeviceOwner = SafeDpmGateway.isDeviceOwner(context)
        val isAdminActive = SafeDpmGateway.isAdminActive(context)

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
