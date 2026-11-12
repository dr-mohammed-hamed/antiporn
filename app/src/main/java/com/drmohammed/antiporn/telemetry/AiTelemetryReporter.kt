package com.drmohammed.antiporn.telemetry

import android.content.Context
import android.content.Intent
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI-Targeted Telemetry & Diagnostic Report Generator.
 * Compiles a structured, sanitized Markdown diagnostic report for offline/external analysis.
 */
object AiTelemetryReporter {

    private const val TAG = "AntiPorn_AiReporter"

    internal data class VpnStatusDisplay(
        val scheduledBlackholePolicyLabel: String,
        val instantInternetKillPolicyLabel: String,
        val liveTunnelStateLabel: String
    )

    internal fun formatReportTimestamp(timestampMs: Long): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss (z)", Locale.US).format(Date(timestampMs))
        } catch (e: Exception) {
            Log.w(TAG, "Error formatting report timestamp", e)
            timestampMs.toString()
        }
    }

    internal fun formatLastHealthyVerification(timestampMs: Long): String {
        return if (timestampMs <= 0L) {
            "Not recorded yet"
        } else {
            formatReportTimestamp(timestampMs)
        }
    }

    internal fun formatVpnStatusDisplay(
        isVpnBlackholePolicyEnabled: Boolean,
        isInstantInternetKillPolicyEnabled: Boolean,
        liveVpnStateSummary: String
    ): VpnStatusDisplay {
        val scheduledLabel = if (isVpnBlackholePolicyEnabled) {
            "[ENABLED] (Scheduled during Curfew)"
        } else {
            "[DISABLED]"
        }
        val instantKillLabel = if (isInstantInternetKillPolicyEnabled) {
            "[ENABLED] (Immediate)"
        } else {
            "[OFF]"
        }
        val liveLabel = if (liveVpnStateSummary.startsWith("ACTIVE")) {
            "[ON] $liveVpnStateSummary"
        } else {
            "[OFF] $liveVpnStateSummary"
        }
        return VpnStatusDisplay(
            scheduledBlackholePolicyLabel = scheduledLabel,
            instantInternetKillPolicyLabel = instantKillLabel,
            liveTunnelStateLabel = liveLabel
        )
    }

    internal fun sanitizeMarkdownCell(raw: String?): String {
        if (raw.isNullOrBlank()) return "-"
        return raw.trim()
            .replace("|", "/")
            .replace("`", "'")
            .replace("\r\n", " ")
            .replace("\r", " ")
            .replace("\n", " ")
    }

    internal fun sanitizeCodeBlock(raw: String): String {
        return raw.trim().replace("```", "` ` `")
    }

    private val SENSITIVE_ID_REGEX = Regex(
        """\b(uid|wardUid|targetUid|partnerUid|guardianUid|from|to|targets|code)\s*=\s*([^\s;|)]+)""",
        RegexOption.IGNORE_CASE
    )
    private val FIRESTORE_PATH_REGEX = Regex(
        """/(policies|users|pairings|uid_mappings)/([^\s,;|)'"\[\]/]+)"""
    )

    internal fun maskIdentifier(raw: String?): String {
        val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return "None"
        return if (clean.length <= 6) {
            "***"
        } else {
            "${clean.take(3)}***${clean.takeLast(3)}"
        }
    }

    internal fun redactSensitiveIdentifiers(raw: String): String {
        val afterKeyValues = SENSITIVE_ID_REGEX.replace(raw) { match ->
            val key = match.groupValues[1]
            val rawVal = match.groupValues[2]
            val trailingComma = if (rawVal.endsWith(",")) "," else ""
            val value = rawVal.trimEnd(',')
            val masked = if (key.equals("targets", ignoreCase = true) && value.contains(",")) {
                value.split(",").joinToString(",") { elem ->
                    val clean = elem.trim()
                    if (clean.isEmpty() || clean == "None" || clean.contains("***")) clean else maskIdentifier(clean)
                }
            } else {
                if (value == "None" || value.contains("***")) value else maskIdentifier(value)
            }
            "$key=$masked$trailingComma"
        }
        return FIRESTORE_PATH_REGEX.replace(afterKeyValues) { match ->
            val collection = match.groupValues[1]
            val value = match.groupValues[2]
            val masked = if (value == "None" || value.contains("***")) value else maskIdentifier(value)
            "/$collection/$masked"
        }
    }

    internal fun formatEventDetails(event: DiagnosticEventDto): String {
        val err = event.errorDetails?.trim()?.takeIf { it.isNotEmpty() }
        val meta = event.contextMetadata?.trim()?.takeIf { it.isNotEmpty() }
        val combined = when {
            err != null && meta != null -> "$err ($meta)"
            err != null -> err
            meta != null -> meta
            else -> "-"
        }
        return sanitizeMarkdownCell(redactSensitiveIdentifiers(combined))
    }

    /**
     * Generates a complete, structured Markdown diagnostic report.
     */
    fun generateMarkdownReport(context: Context): String {
        val appContext = context.applicationContext ?: context
        val now = TrustedTimeProvider.currentTimeMillis(appContext)
        val formattedDate = formatReportTimestamp(now)

        val env = TelemetryEnvironmentMapper.collect(appContext)

        val currentPolicy = try {
            PolicyRepository.getInstance(appContext).getCurrentPolicy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading current policy for telemetry report", e)
            null
        }

        val cachedUid = try {
            AccountabilityRepository.getInstance(appContext).getCachedUid()
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading cached UID for telemetry report", e)
            "READ_ERROR"
        }

        val isEffectiveBlock = try {
            CurfewEnforcer.isEffectiveBlockActive(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed checking isEffectiveBlockActive for telemetry report", e)
            false
        }

        val isVpnBlackholePolicy = try {
            CurfewConfig.isVpnBlackholeEnabled(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading VPN blackhole policy flag for telemetry report", e)
            currentPolicy?.isVpnBlackholeEnabled ?: false
        }

        val isInstantKillPolicy = try {
            CurfewConfig.isInstantInternetKillEnabled(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading Instant Internet Kill flag for telemetry report", e)
            currentPolicy?.isInstantInternetKillEnabled ?: false
        }

        val liveVpnState = try {
            CurfewBlackholeVpnService.getLiveStateSummary()
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading live VPN summary for telemetry report", e)
            "READ_ERROR"
        }

        val vpnStatus = formatVpnStatusDisplay(
            isVpnBlackholePolicyEnabled = isVpnBlackholePolicy,
            isInstantInternetKillPolicyEnabled = isInstantKillPolicy,
            liveVpnStateSummary = liveVpnState
        )

        // Evaluate live shield status BEFORE reading healthy check timestamp so this report reflects the fresh check
        var shieldsReadError = false
        val liveShields = try {
            ProtectionEngine.evaluateSystemStatus(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed evaluating live shields for telemetry report", e)
            shieldsReadError = true
            emptyMap()
        }

        val lastHealthyShieldCheckMs = try {
            BlackBoxRecorder.getLastHealthyShieldCheckTimestamp(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading last healthy shield check timestamp", e)
            0L
        }

        val latestCrash = getLatestCrashDump(appContext)

        BlackBoxRecorder.awaitPendingWritesBlocking()
        val diagDao = HisnRoomDatabase.getInstance(appContext).diagnosticEventDao()
        var cloudReadError = false
        val cloudEvents = try {
            (diagDao.getDiagnosticEventsBlocking(30, BlackBoxRecorder.TAG_CLOUD_SYNC) +
                diagDao.getDiagnosticEventsBlocking(15, BlackBoxRecorder.TAG_IDENTITY) +
                diagDao.getDiagnosticEventsBlocking(20, BlackBoxRecorder.TAG_PAIRING)).map { it.toDto() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading cloud telemetry events", e)
            cloudReadError = true
            emptyList()
        }.sortedByDescending { it.timestamp }

        val (curfewEvents, curfewReadError) = queryEventsSafely("curfew") {
            diagDao.getDiagnosticEventsBlocking(40, BlackBoxRecorder.TAG_CURFEW).map { it.toDto() }
        }
        val (dpmEvents, dpmReadError) = queryEventsSafely("dpm") {
            diagDao.getDiagnosticEventsBlocking(30, BlackBoxRecorder.TAG_DPM).map { it.toDto() }
        }
        val (shieldEvents, shieldEventsReadError) = queryEventsSafely("shield") {
            diagDao.getDiagnosticEventsBlocking(20, BlackBoxRecorder.TAG_SHIELD).map { it.toDto() }
        }
        var waqfaReadError = false
        val waqfaEvents = try {
            (diagDao.getDiagnosticEventsBlocking(30, BlackBoxRecorder.TAG_WAQFA) +
                diagDao.getDiagnosticEventsBlocking(15, BlackBoxRecorder.TAG_A11Y)).map { it.toDto() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading waqfa/accessibility telemetry events", e)
            waqfaReadError = true
            emptyList()
        }.sortedByDescending { it.timestamp }
        val (networkEvents, networkReadError) = queryEventsSafely("network") {
            diagDao.getDiagnosticEventsBlocking(25, BlackBoxRecorder.TAG_NETWORK).map { it.toDto() }
        }
        val (timeEvents, timeReadError) = queryEventsSafely("time") {
            diagDao.getDiagnosticEventsBlocking(20, BlackBoxRecorder.TAG_TIME).map { it.toDto() }
        }

        val totalEventsCount = try {
            diagDao.getEventsCountBlocking()
        } catch (e: Exception) {
            Log.w(TAG, "Error querying diagnostic events count", e)
            -1L
        }

        val sb = StringBuilder()

        // 1. Report Header
        sb.appendLine("# [SHIELD] AntiPorn Guard - Black Box Diagnostic Report")
        sb.appendLine("> Generated on: `${sanitizeMarkdownCell(formattedDate)}` | App Version: `${sanitizeMarkdownCell(env.appVersionName)} (${env.appVersionCode})`")
        sb.appendLine()

        // 2. Active Protection & Policy State
        sb.appendLine("## 1. Active Protection & Policy State (حالة السياسة والحظر)")
        if (currentPolicy != null) {
            sb.appendLine("| Protection Property | Current Value |")
            sb.appendLine("| :--- | :--- |")
            sb.appendLine("| **Current Policy Version** | `v${currentPolicy.version}` |")
            sb.appendLine("| **Effective Block Active Now** | `${if (isEffectiveBlock) "[ACTIVE] LOCKED / ACTIVE" else "[OK] NORMAL / DAYTIME"}` |")
            sb.appendLine("| **Curfew Feature Master Switch** | `${if (currentPolicy.isCurfewEnabled) "[OK] ENABLED" else "[OFF] DISABLED"}` |")
            sb.appendLine("| **Curfew Schedule (Weekday)** | `${String.format(Locale.US, "%02d:%02d", currentPolicy.curfewStartHour, currentPolicy.curfewStartMinute)} -> ${String.format(Locale.US, "%02d:%02d", currentPolicy.curfewEndHour, currentPolicy.curfewEndMinute)}` |")
            sb.appendLine("| **Curfew Schedule (Weekend)** | `${if (currentPolicy.isWeekendCurfewEnabled) "[OK] ACTIVE" else "[OFF] OFF"} (${String.format(Locale.US, "%02d:%02d", currentPolicy.weekendCurfewStartHour, currentPolicy.weekendCurfewStartMinute)} -> ${String.format(Locale.US, "%02d:%02d", currentPolicy.weekendCurfewEndHour, currentPolicy.weekendCurfewEndMinute)})` |")
            sb.appendLine("| **Policy Timezone** | `${sanitizeMarkdownCell(currentPolicy.timeZoneId)}` |")
            sb.appendLine("| **Supervised by Guardian** | `${if (currentPolicy.isSupervised) "[YES]" else "[NO] (Standalone)"}` |")
            sb.appendLine("| **Guardian Identity** | `${sanitizeMarkdownCell(maskIdentifier(currentPolicy.guardianName))} (${sanitizeMarkdownCell(maskIdentifier(currentPolicy.guardianUid))})` |")
            sb.appendLine("| **Self-Control Allowed** | `${if (currentPolicy.isSelfControlAllowed) "[YES]" else "[LOCKED] LOCKED BY GUARDIAN"}` |")
            sb.appendLine("| **VPN Blackhole Policy (Curfew)** | `${sanitizeMarkdownCell(vpnStatus.scheduledBlackholePolicyLabel)}` |")
            sb.appendLine("| **Instant Internet Kill Policy** | `${sanitizeMarkdownCell(vpnStatus.instantInternetKillPolicyLabel)}` |")
            sb.appendLine("| **Live VPN Service / Tunnel State** | `${sanitizeMarkdownCell(vpnStatus.liveTunnelStateLabel)}` |")
            sb.appendLine("| **Active DNS Host** | `${sanitizeMarkdownCell(currentPolicy.dnsHost)}` |")
            sb.appendLine("| **Local Device UID** | `${sanitizeMarkdownCell(if (cachedUid == "READ_ERROR") "READ_ERROR" else maskIdentifier(cachedUid))}` |")
        } else {
            sb.appendLine("[WARN] *Unable to load current device policy from SQLite (READ_ERROR).*")
            sb.appendLine("- **VPN Blackhole Policy (Curfew):** `${sanitizeMarkdownCell(vpnStatus.scheduledBlackholePolicyLabel)}`")
            sb.appendLine("- **Instant Internet Kill Policy:** `${sanitizeMarkdownCell(vpnStatus.instantInternetKillPolicyLabel)}`")
            sb.appendLine("- **Live VPN Service / Tunnel State:** `${sanitizeMarkdownCell(vpnStatus.liveTunnelStateLabel)}`")
        }
        sb.appendLine()

        // 2. Environment Summary
        sb.appendLine("## 2. Device & Android Enterprise Environment")
        sb.appendLine("| Property | Value |")
        sb.appendLine("| :--- | :--- |")
        sb.appendLine("| **Device Model** | `${sanitizeMarkdownCell("${env.manufacturer} ${env.model} (${env.device})")}` |")
        sb.appendLine("| **Android OS** | `Android ${sanitizeMarkdownCell(env.androidVersion)} (API ${env.sdkInt})` |")
        sb.appendLine("| **Battery Level** | `${env.batteryPercent}%` |")
        sb.appendLine("| **RAM (Available / Total)** | `${env.availableMemoryMb} MB / ${env.totalMemoryMb} MB` |")
        sb.appendLine("| **Device Owner (DO) Active** | `${if (env.isDeviceOwner) "[YES]" else "[NO]"}` |")
        sb.appendLine("| **Device Admin (DA) Active** | `${if (env.isAdminActive) "[YES]" else "[NO]"}` |")
        sb.appendLine("| **Total Logged Events in Black Box** | `${if (totalEventsCount >= 0) totalEventsCount else "READ_ERROR"}` |")
        sb.appendLine()

        // 3. Security Shields Invariants Audit
        sb.appendLine("## 3. Security Shields Invariants Audit (فحص سلامة الدروع الأمنية الستة)")
        sb.appendLine("- **Last Healthy Verification:** `${sanitizeMarkdownCell(formatLastHealthyVerification(lastHealthyShieldCheckMs))}`")
        if (shieldsReadError) {
            sb.appendLine("[WARN] *READ_ERROR while evaluating live shield statuses.*")
        }
        sb.appendLine()
        sb.appendLine("| Shield ID | Shield Name | Current Live Status | Ground-Truth Invariant Details / Reason |")
        sb.appendLine("| :--- | :--- | :---: | :--- |")
        for (shield in ProtectionEngine.shields) {
            val status = liveShields[shield.id]
            val statusDisplay = when (status) {
                is ShieldStatus.Active -> "[ACTIVE]"
                is ShieldStatus.NeedsEnforcement -> "[NEEDS ENFORCEMENT]"
                is ShieldStatus.Disabled -> "[DISABLED]"
                is ShieldStatus.Unsupported -> "[UNSUPPORTED]"
                null -> if (shieldsReadError) "[READ_ERROR]" else "[UNKNOWN]"
            }
            val details = when (status) {
                is ShieldStatus.Active -> status.detail
                is ShieldStatus.NeedsEnforcement -> status.reason
                is ShieldStatus.Disabled -> status.warning
                is ShieldStatus.Unsupported -> "API < ${status.minSdkRequired}"
                null -> if (shieldsReadError) "READ_ERROR" else "Not evaluated"
            }
            val shieldTitle = try {
                appContext.getString(shield.titleResId)
            } catch (e: Exception) {
                Log.w(TAG, "Error resolving title for shield ${shield.id}", e)
                shield.id
            }
            sb.appendLine("| `${sanitizeMarkdownCell(shield.id)}` | ${sanitizeMarkdownCell(shieldTitle)} | `$statusDisplay` | ${sanitizeMarkdownCell(redactSensitiveIdentifiers(details))} |")
        }
        sb.appendLine()

        if (shieldEventsReadError) {
            sb.appendLine("[WARN] *READ_ERROR querying shield status events from SQLite.*")
        } else if (shieldEvents.isNotEmpty()) {
            sb.appendLine("### Recent Shield Status & Failure Events (سجل الدروع المتعثرة)")
            sb.appendLine("| Timestamp | Shield | Status | Details / Failure Reason |")
            sb.appendLine("| :--- | :--- | :---: | :--- |")
            for (event in shieldEvents.take(15)) {
                val statusLabel = if (event.isSuccess) "[OK]" else "[FAIL]"
                val errOrMeta = formatEventDetails(event)
                sb.appendLine("| `${sanitizeMarkdownCell(event.formattedTime())}` | `${sanitizeMarkdownCell(event.actionName)}` | $statusLabel | $errOrMeta |")
            }
            sb.appendLine()
        }

        // 4. Crash Status
        sb.appendLine("## 4. Latest Crash Incident")
        if (!latestCrash.isNullOrBlank()) {
            sb.appendLine("```text")
            sb.appendLine(sanitizeCodeBlock(redactSensitiveIdentifiers(latestCrash)))
            sb.appendLine("```")
        } else {
            sb.appendLine("[OK] **No fatal crashes recorded on this device.**")
        }
        sb.appendLine()

        // 5..10 Categorized Timelines
        appendEventSection(
            sb = sb,
            title = "## 5. Cloud Sync, Pairing & Identity Timeline (آخر عمليات المزامنة والاقتران والهويات)",
            emptyMessage = "[INFO] *No cloud synchronization events recorded yet.*",
            readError = cloudReadError,
            events = cloudEvents.take(25)
        )
        appendEventSection(
            sb = sb,
            title = "## 6. Curfew & Alarm Lifecycle Events (أحداث الحظر الليلي والمنبهات)",
            emptyMessage = "[INFO] *No curfew lifecycle events recorded yet.*",
            readError = curfewReadError,
            events = curfewEvents.take(25)
        )

        sb.appendLine("## 7. System & DPM Operations (أوامر حماية النظام)")
        if (dpmReadError) {
            sb.appendLine("[WARN] *READ_ERROR querying DPM telemetry events.*")
        } else if (dpmEvents.isNotEmpty()) {
            sb.appendLine("| Timestamp | Action | Latency | Status | Error Details |")
            sb.appendLine("| :--- | :--- | :---: | :---: | :--- |")
            for (event in dpmEvents.take(25)) {
                val statusLabel = if (event.isSuccess) "[OK]" else "[FAIL]"
                val safeError = sanitizeMarkdownCell(event.errorDetails?.let { redactSensitiveIdentifiers(it) })
                sb.appendLine("| `${sanitizeMarkdownCell(event.formattedTime())}` | `${sanitizeMarkdownCell(event.actionName)}` | `${event.executionTimeMs}ms` | $statusLabel | $safeError |")
            }
        } else {
            sb.appendLine("[INFO] *No DPM operations recorded yet.*")
        }
        sb.appendLine()

        sb.appendLine("## 8. Waqfa & Accessibility Interceptions (سجل اعتراضات وقفة ومحرك الوصول اللحظي)")
        if (waqfaReadError) {
            sb.appendLine("[WARN] *READ_ERROR querying Waqfa/Accessibility telemetry events.*")
        } else if (waqfaEvents.isNotEmpty()) {
            sb.appendLine("| Timestamp | Action | Latency | Status | Details / Engine |")
            sb.appendLine("| :--- | :--- | :---: | :---: | :--- |")
            for (event in waqfaEvents.take(30)) {
                val statusLabel = if (event.isSuccess) "[OK]" else "[FAIL]"
                val latencyStr = if (event.executionTimeMs > 0L) "${event.executionTimeMs}ms" else "0ms"
                sb.appendLine("| `${sanitizeMarkdownCell(event.formattedTime())}` | `${sanitizeMarkdownCell(event.actionName)}` | `$latencyStr` | $statusLabel | ${formatEventDetails(event)} |")
            }
        } else {
            sb.appendLine("[INFO] *No Waqfa or Accessibility interception events recorded yet.*")
        }
        sb.appendLine()

        appendEventSection(
            sb = sb,
            title = "## 9. Network Connectivity & Transitions (أحداث الشبكة والاتصال)",
            emptyMessage = "[INFO] *No network connectivity transition events recorded yet.*",
            readError = networkReadError,
            events = networkEvents.take(25)
        )
        appendEventSection(
            sb = sb,
            title = "## 10. Time Integrity & SNTP Sync Events (سلامة التوقيت ومزامنة SNTP)",
            emptyMessage = "[INFO] *No SNTP or time integrity events recorded yet.*",
            readError = timeReadError,
            events = timeEvents.take(20)
        )

        // 11. AI Prompt & Diagnosis Guidance
        sb.appendLine("---")
        sb.appendLine("## 11. AI Diagnosis Instructions (تعليمات التحليل للذكاء الاصطناعي)")
        sb.appendLine(
            """
Please analyze the telemetry and black-box log above and provide an actionable diagnosis:
1. **Curfew & Alarm Timing:** Verify if the scheduled curfew start/end matches the observed behavior and identify any alarm skips or drift.
2. **Security Shields Audit:** Verify if any shield (DNS, VPN, Browser, Curfew, Tamper, AppSuspension) reported NEEDS ENFORCEMENT or DISABLED and check its ground truth reason.
3. **Cloud Sync & Monotonic Guards:** Check for rejected stale policies, UID mismatches, or missing acknowledgments.
4. **Permissions & Security:** Identify any DPM failures, SecurityExceptions, or unexpected state transitions.
5. **Waqfa & Accessibility Interceptions:** Check for interception latency spikes, engine transitions, or abnormal debounce rejections.
6. **Network & Time Integrity:** Inspect network connectivity transitions (`NET_UNVALIDATED`, `NET_CAPTIVE_PORTAL`, `NET_LOST`) and SNTP sync/drift events for connectivity blocks or time tampering.
7. **Actionable Recommendations:** Provide a surgical diagnosis and concrete code/configuration recommendations.
""".trimIndent()
        )

        return sb.toString()
    }

    private inline fun queryEventsSafely(
        categoryName: String,
        block: () -> List<DiagnosticEventDto>
    ): Pair<List<DiagnosticEventDto>, Boolean> {
        return try {
            block() to false
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading $categoryName telemetry events", e)
            emptyList<DiagnosticEventDto>() to true
        }
    }

    private fun appendEventSection(
        sb: StringBuilder,
        title: String,
        emptyMessage: String,
        readError: Boolean,
        events: List<DiagnosticEventDto>
    ) {
        sb.appendLine(title)
        if (readError) {
            sb.appendLine("[WARN] *READ_ERROR querying diagnostic events from SQLite.*")
        } else if (events.isNotEmpty()) {
            sb.appendLine("| Timestamp | Action | Status | Details / Error |")
            sb.appendLine("| :--- | :--- | :---: | :--- |")
            for (event in events) {
                val statusLabel = if (event.isSuccess) "[OK]" else "[FAIL]"
                sb.appendLine("| `${sanitizeMarkdownCell(event.formattedTime())}` | `${sanitizeMarkdownCell(event.actionName)}` | $statusLabel | ${formatEventDetails(event)} |")
            }
        } else {
            sb.appendLine(emptyMessage)
        }
        sb.appendLine()
    }

    fun getLatestCrashDump(context: Context): String? =
        TelemetryExportHandler.getLatestCrashDump(context)

    fun clearLatestCrashDump(context: Context): Boolean =
        TelemetryExportHandler.clearLatestCrashDump(context)

    fun exportReportToFile(context: Context, preGeneratedReport: String? = null): File {
        val reportText = preGeneratedReport ?: generateMarkdownReport(context)
        return TelemetryExportHandler.exportReportToFile(context, reportText)
    }

    fun createShareFileIntent(context: Context, reportFile: File): Intent =
        TelemetryExportHandler.createShareFileIntent(context, reportFile)

    fun copyReportToClipboard(context: Context, preGeneratedReport: String? = null): Boolean {
        val report = preGeneratedReport ?: generateMarkdownReport(context)
        return TelemetryExportHandler.copyReportToClipboard(context, report)
    }
}
