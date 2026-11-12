package com.drmohammed.antiporn.telemetry

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.services.NetworkWatchdog
import com.drmohammed.antiporn.services.NetworkWatchdog.Companion.NetworkStateSnapshot
import com.drmohammed.antiporn.ui.home.HomeViewModel
import com.drmohammed.antiporn.ui.settings.DiagnosticLogViewerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests covering [DiagnosticEventDto], [SafeDpmGateway], [CrashReport], and [AiTelemetryReporter].
 */
class TelemetryAndGatewayTest {

    @Test
    fun testDiagnosticEventDtoFormatting() {
        val timestamp = 1730577600000L // 2024-11-02 20:00:00 UTC approx
        val event = DiagnosticEventDto(
            id = 1L,
            timestamp = timestamp,
            tag = "DnsShield",
            actionName = "setGlobalPrivateDnsModeSpecifiedHost",
            executionTimeMs = 42L,
            isSuccess = true,
            errorDetails = null,
            contextMetadata = "host=adguard"
        )

        val formattedTime = event.formattedTime()
        assertTrue("Formatted time should not be empty", formattedTime.isNotBlank())

        val mdRow = event.toMarkdownTableRow()
        assertTrue("Markdown row must start and end with pipe", mdRow.startsWith("|") && mdRow.endsWith("|"))
        assertTrue("Markdown row must contain tag", mdRow.contains("`DnsShield`"))
        assertTrue("Markdown row must contain actionName", mdRow.contains("`setGlobalPrivateDnsModeSpecifiedHost`"))
        assertTrue("Markdown row must contain execution time", mdRow.contains("`42ms`"))
        assertTrue("Markdown row must contain success badge", mdRow.contains("[OK]"))
    }

    @Test
    fun testDiagnosticEventDtoFailureRowFormatting() {
        val event = DiagnosticEventDto(
            id = 2L,
            timestamp = System.currentTimeMillis(),
            tag = "AppSuspension",
            actionName = "setPackagesSuspended",
            executionTimeMs = 15L,
            isSuccess = false,
            errorDetails = "SecurityException: Admin not active",
            contextMetadata = "pkg=com.sample"
        )

        val mdRow = event.toMarkdownTableRow()
        assertTrue("Markdown row must contain failure badge", mdRow.contains("[FAIL]"))
        assertTrue("Markdown row must contain error details", mdRow.contains("SecurityException: Admin not active"))
    }

    @Test
    fun testDiagnosticEventDtoSanitization() {
        val event = DiagnosticEventDto(
            id = 3L,
            timestamp = System.currentTimeMillis(),
            tag = "Tag|With`Pipe\nAndLineBreak",
            actionName = "Action|With`Backtick\nAndLineBreak",
            executionTimeMs = 10L,
            isSuccess = true,
            errorDetails = "Err|Details`With\nBreak",
            contextMetadata = "Meta|Data`With\nBreak"
        )

        val mdRow = event.toMarkdownTableRow()
        // Ensure pipe, backtick, newline inside content are replaced
        assertFalse(mdRow.contains("Tag|With"))
        assertFalse(mdRow.contains("Action|With"))
        assertFalse(mdRow.contains("\n"))
        assertTrue(mdRow.contains("Tag/With'Pipe AndLineBreak"))
        assertTrue(mdRow.contains("Action/With'Backtick AndLineBreak"))
        assertTrue(mdRow.contains("Err/Details'With Break"))
        assertTrue(mdRow.contains("Meta/Data'With Break"))
    }

    @Test
    fun testDpmExecutionResultSuccess() {
        val result: DpmExecutionResult<Int> = DpmExecutionResult.Success(
            data = 42,
            executionTimeMs = 12L,
            actionName = "testAction"
        )

        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
        assertEquals(42, result.getOrNull())
        assertEquals(42, result.getOrThrow())
        assertEquals(12L, result.executionTimeMs)
        assertEquals("testAction", result.actionName)
    }

    @Test
    fun testDpmExecutionResultFailure() {
        val exception = SecurityException("Permission denied")
        val result: DpmExecutionResult<Int> = DpmExecutionResult.Failure(
            errorType = DpmErrorType.SECURITY_EXCEPTION,
            message = "SecurityException: Permission denied",
            executionTimeMs = 5L,
            actionName = "restrictedAction",
            cause = exception
        )

        assertFalse(result.isSuccess)
        assertTrue(result.isFailure)
        assertNull(result.getOrNull())

        try {
            result.getOrThrow()
            assertTrue("Expected getOrThrow to fail", false)
        } catch (e: SecurityException) {
            assertEquals("Permission denied", e.message)
        }
    }

    @Test
    fun testCrashReportFormatting() {
        val report = CrashReport(
            timestampMs = 1730577600000L,
            timestampIso = "2026-11-02T20:30:00Z",
            threadName = "main",
            threadId = 1L,
            exceptionClass = "java.lang.NullPointerException",
            exceptionMessage = "Attempt to invoke virtual method on a null object reference",
            stackTrace = "java.lang.NullPointerException\n\tat com.drmohammed.antiporn.MainActivity.test(MainActivity.kt:42)",
            batteryPercent = 88,
            availableMemoryMb = 1200L,
            totalMemoryMb = 4096L,
            isDeviceOwner = true,
            isAdminActive = true,
            appVersionName = "1.6",
            appVersionCode = 7L,
            manufacturer = "Google",
            model = "Pixel 8",
            sdkInt = 34,
            androidVersion = "14"
        )

        val text = report.toReadableText()
        assertTrue(text.contains("💥 ANTIPORN UNCAUGHT CRASH REPORT"))
        assertTrue(text.contains("Google Pixel 8"))
        assertTrue(text.contains("Battery Level: 88%"))
        assertTrue(text.contains("Device Owner Active: true"))
        assertTrue(text.contains("java.lang.NullPointerException"))

        val jsonStr = report.toJsonString()
        assertTrue(jsonStr.contains("\"batteryPercent\": 88"))
        assertTrue(jsonStr.contains("\"isDeviceOwner\": true"))
        assertTrue(jsonStr.contains("\"manufacturer\": \"Google\""))
    }

    @Test
    fun testBlackBoxRecorderTags() {
        assertEquals("CURFEW", BlackBoxRecorder.TAG_CURFEW)
        assertEquals("CLOUD_SYNC", BlackBoxRecorder.TAG_CLOUD_SYNC)
        assertEquals("IDENTITY", BlackBoxRecorder.TAG_IDENTITY)
        assertEquals("TIME", BlackBoxRecorder.TAG_TIME)
        assertEquals("DPM", BlackBoxRecorder.TAG_DPM)
        assertEquals("GATE", BlackBoxRecorder.TAG_GATE)
        assertEquals("CRASH", BlackBoxRecorder.TAG_CRASH)
        assertEquals("NETWORK", BlackBoxRecorder.TAG_NETWORK)
        assertEquals("ALL", BlackBoxRecorder.CATEGORY_ALL)
    }

    @Test
    fun testNetworkWatchdog_nullCapabilitiesFallback() {
        assertEquals("OTHER", NetworkWatchdog.resolveTransportLabel(null))
        val snapshot = NetworkWatchdog.buildSnapshot(null)
        assertEquals(
            NetworkStateSnapshot(
                transport = "OTHER",
                isValidated = false,
                isCaptivePortal = false
            ),
            snapshot
        )
    }

    @Test
    fun testNetworkWatchdog_classifyCapabilityTransition_suppressesIdenticalSnapshots() {
        val initial = NetworkStateSnapshot(
            transport = "WIFI",
            isValidated = true,
            isCaptivePortal = false
        )
        val duplicate = NetworkStateSnapshot(
            transport = "WIFI",
            isValidated = true,
            isCaptivePortal = false
        )

        assertNull(
            "Initial non-captive snapshot should not emit a duplicate transition event",
            NetworkWatchdog.classifyCapabilityTransition(null, initial, "net100")
        )
        assertNull(
            "Identical consecutive snapshots must be suppressed to avoid flapping spam",
            NetworkWatchdog.classifyCapabilityTransition(initial, duplicate, "net100")
        )
    }

    @Test
    fun testNetworkWatchdog_classifyCapabilityTransition_emitsAllMeaningfulTransitions() {
        val unvalidatedWifi = NetworkStateSnapshot("WIFI", isValidated = false, isCaptivePortal = false)
        val validatedWifi = NetworkStateSnapshot("WIFI", isValidated = true, isCaptivePortal = false)
        val captiveWifi = NetworkStateSnapshot("WIFI", isValidated = false, isCaptivePortal = true)
        val validatedCell = NetworkStateSnapshot("CELLULAR", isValidated = true, isCaptivePortal = false)
        val vpnSnapshot = NetworkStateSnapshot("VPN", isValidated = true, isCaptivePortal = false)

        // Initial captive portal
        val initialCaptive = NetworkWatchdog.classifyCapabilityTransition(null, captiveWifi, "net1")
        assertNotNull(initialCaptive)
        assertEquals("NET_CAPTIVE_PORTAL", initialCaptive!!.action)
        assertFalse(initialCaptive.isSuccess)

        // Transition to captive portal from unvalidated
        val toCaptive = NetworkWatchdog.classifyCapabilityTransition(unvalidatedWifi, captiveWifi, "net1")
        assertNotNull(toCaptive)
        assertEquals("NET_CAPTIVE_PORTAL", toCaptive!!.action)
        assertFalse(toCaptive.isSuccess)

        // Unvalidated -> Validated
        val toValidated = NetworkWatchdog.classifyCapabilityTransition(unvalidatedWifi, validatedWifi, "net1")
        assertNotNull(toValidated)
        assertEquals("NET_VALIDATED", toValidated!!.action)
        assertTrue(toValidated.isSuccess)
        assertNull(toValidated.error)

        // Validated -> Unvalidated
        val toUnvalidated = NetworkWatchdog.classifyCapabilityTransition(validatedWifi, unvalidatedWifi, "net1")
        assertNotNull(toUnvalidated)
        assertEquals("NET_UNVALIDATED", toUnvalidated!!.action)
        assertFalse(toUnvalidated.isSuccess)
        assertNotNull(toUnvalidated.error)

        // Transport change WIFI -> CELLULAR
        val transportChanged = NetworkWatchdog.classifyCapabilityTransition(validatedWifi, validatedCell, "net2")
        assertNotNull(transportChanged)
        assertEquals("NET_TRANSPORT_CHANGED", transportChanged!!.action)
        assertTrue(transportChanged.isSuccess)
        assertTrue(transportChanged.details?.contains("WIFI->CELLULAR") == true)

        // Transport change CELLULAR -> VPN
        val toVpn = NetworkWatchdog.classifyCapabilityTransition(validatedCell, vpnSnapshot, "net3")
        assertNotNull(toVpn)
        assertEquals("NET_TRANSPORT_CHANGED", toVpn!!.action)
        assertTrue(toVpn.details?.contains("CELLULAR->VPN") == true)
    }

    @Test
    fun testBlackBoxRecorder_healthyShieldCheckTimestampReadingWithoutRoom() {
        val prefsMap = mutableMapOf<String, InMemorySharedPreferences>()
        val fakeContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                val key = name ?: "default"
                return prefsMap.getOrPut(key) { InMemorySharedPreferences() }
            }
        }

        // Before any check is persisted, timestamp defaults to 0L without touching Room DB
        assertEquals(0L, BlackBoxRecorder.getLastHealthyShieldCheckTimestamp(fakeContext))

        // Reading persisted timestamp from BlackBoxRecorder health prefs (no Room DB involved)
        val persistedTimestamp = 1_760_000_123_000L
        fakeContext.getSharedPreferences("antiporn_blackbox_health_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_healthy_shield_check_ms", persistedTimestamp)
            .commit()

        assertEquals(
            persistedTimestamp,
            BlackBoxRecorder.getLastHealthyShieldCheckTimestamp(fakeContext)
        )
    }

    @Test
    fun testAiTelemetryReporter_splitVpnPolicyVsLiveStateFormatting() {
        val daytimeScheduled = AiTelemetryReporter.formatVpnStatusDisplay(
            isVpnBlackholePolicyEnabled = true,
            isInstantInternetKillPolicyEnabled = false,
            liveVpnStateSummary = "INACTIVE"
        )
        assertTrue(daytimeScheduled.scheduledBlackholePolicyLabel.contains("ENABLED"))
        assertTrue(daytimeScheduled.scheduledBlackholePolicyLabel.contains("Curfew"))
        assertTrue(daytimeScheduled.instantInternetKillPolicyLabel.contains("OFF"))
        assertTrue(daytimeScheduled.liveTunnelStateLabel.contains("INACTIVE"))
        assertFalse(daytimeScheduled.liveTunnelStateLabel.contains("ACTIVE ("))

        val daytimeDnsVpn = AiTelemetryReporter.formatVpnStatusDisplay(
            isVpnBlackholePolicyEnabled = true,
            isInstantInternetKillPolicyEnabled = false,
            liveVpnStateSummary = "ACTIVE (DNS_FILTERING_ONLY)"
        )
        assertTrue(daytimeDnsVpn.liveTunnelStateLabel.contains("ACTIVE (DNS_FILTERING_ONLY)"))

        val instantKillActive = AiTelemetryReporter.formatVpnStatusDisplay(
            isVpnBlackholePolicyEnabled = false,
            isInstantInternetKillPolicyEnabled = true,
            liveVpnStateSummary = "ACTIVE (CURFEW_BLACKHOLE)"
        )
        assertTrue(instantKillActive.scheduledBlackholePolicyLabel.contains("DISABLED"))
        assertTrue(instantKillActive.instantInternetKillPolicyLabel.contains("ENABLED"))
        assertTrue(instantKillActive.liveTunnelStateLabel.contains("ACTIVE (CURFEW_BLACKHOLE)"))
    }

    @Test
    fun testAiTelemetryReporter_lastHealthyCheckAndEventDetailsFormatting() {
        assertEquals("Not recorded yet", AiTelemetryReporter.formatLastHealthyVerification(0L))
        assertEquals("Not recorded yet", AiTelemetryReporter.formatLastHealthyVerification(-5L))

        val ts = 1730577600000L
        assertEquals(
            AiTelemetryReporter.formatReportTimestamp(ts),
            AiTelemetryReporter.formatLastHealthyVerification(ts)
        )

        val bothEvent = DiagnosticEventDto(
            timestamp = ts,
            tag = BlackBoxRecorder.TAG_NETWORK,
            actionName = "NET_UNVALIDATED",
            executionTimeMs = 0L,
            isSuccess = false,
            errorDetails = "Connected without validation",
            contextMetadata = "transport=WIFI|net=1"
        )
        val formattedBoth = AiTelemetryReporter.formatEventDetails(bothEvent)
        assertTrue(formattedBoth.contains("Connected without validation"))
        assertTrue(formattedBoth.contains("transport=WIFI/net=1"))
        assertFalse(formattedBoth.contains("|"))
    }

    @Test
    fun testAiTelemetryReporter_redactSensitiveIdentifiers_masksRawIds() {
        val raw = "uid=secretUserUid12345 wardUid=ward_abcdef_9876 path=/policies/targetUid999999/status"
        val redacted = AiTelemetryReporter.redactSensitiveIdentifiers(raw)
        assertFalse(redacted.contains("secretUserUid12345"))
        assertFalse(redacted.contains("ward_abcdef_9876"))
        assertFalse(redacted.contains("targetUid999999"))
        assertTrue(redacted.contains(AiTelemetryReporter.maskIdentifier("secretUserUid12345")))
        assertTrue(redacted.contains(AiTelemetryReporter.maskIdentifier("ward_abcdef_9876")))

        val event = DiagnosticEventDto(
            timestamp = 1730577600000L,
            tag = BlackBoxRecorder.TAG_NETWORK,
            actionName = "POLICY_SYNC_ERROR",
            executionTimeMs = 12L,
            isSuccess = false,
            errorDetails = "Failed reading uid=rawUidValue9999",
            contextMetadata = "wardUid=rawWardValue8888"
        )
        val formatted = AiTelemetryReporter.formatEventDetails(event)
        assertFalse(formatted.contains("rawUidValue9999"))
        assertFalse(formatted.contains("rawWardValue8888"))
    }

    @Test
    fun testHomeViewModel_coalesceGuardRules() {
        // First non-enforcing refresh must run
        assertFalse(
            HomeViewModel.shouldSkipCoalescedRefresh(
                isEnforcing = false,
                force = false,
                isEnforcementOperationActive = false,
                lastRefreshWasEnforcing = false,
                hasPreviousNonEnforcing = false,
                elapsedSinceLastMs = 0L
            )
        )
        // Duplicate non-enforcing refresh within 1000ms must be coalesced
        assertTrue(
            HomeViewModel.shouldSkipCoalescedRefresh(
                isEnforcing = false,
                force = false,
                isEnforcementOperationActive = false,
                lastRefreshWasEnforcing = false,
                hasPreviousNonEnforcing = true,
                elapsedSinceLastMs = 250L
            )
        )
        // Non-enforcing refresh after 1000ms must run
        assertFalse(
            HomeViewModel.shouldSkipCoalescedRefresh(
                isEnforcing = false,
                force = false,
                isEnforcementOperationActive = false,
                lastRefreshWasEnforcing = false,
                hasPreviousNonEnforcing = true,
                elapsedSinceLastMs = 1000L
            )
        )
        // Enforcing refresh must NEVER be skipped even at 0ms
        assertFalse(
            HomeViewModel.shouldSkipCoalescedRefresh(
                isEnforcing = true,
                force = false,
                isEnforcementOperationActive = true,
                lastRefreshWasEnforcing = false,
                hasPreviousNonEnforcing = true,
                elapsedSinceLastMs = 5L
            )
        )
        // Forced refresh (policy change / post-enforcement) must NEVER be skipped
        assertFalse(
            HomeViewModel.shouldSkipCoalescedRefresh(
                isEnforcing = false,
                force = true,
                isEnforcementOperationActive = false,
                lastRefreshWasEnforcing = false,
                hasPreviousNonEnforcing = true,
                elapsedSinceLastMs = 5L
            )
        )
        // Active enforcement must preserve isEnforcing=true across forced observePolicy refreshes
        assertTrue(HomeViewModel.resolveEffectiveEnforcing(requestedEnforcing = false, isEnforcementOperationActive = true))
        assertTrue(HomeViewModel.resolveEffectiveEnforcing(requestedEnforcing = true, isEnforcementOperationActive = false))
        assertFalse(HomeViewModel.resolveEffectiveEnforcing(requestedEnforcing = false, isEnforcementOperationActive = false))
    }

    @Test
    fun testDiagnosticLogViewer_networkChipMapping() {
        assertEquals(BlackBoxRecorder.TAG_NETWORK, DiagnosticLogViewerActivity.mapChipIdToTag(R.id.chipFilterNetwork))
        assertEquals(R.id.chipFilterNetwork, DiagnosticLogViewerActivity.mapTagToChipId(BlackBoxRecorder.TAG_NETWORK))
        assertNull(DiagnosticLogViewerActivity.mapChipIdToTag(R.id.chipFilterAll))
    }

    @Test
    fun testTelemetrySanitizationAndMaskingContracts() {
        assertEquals("None", AiTelemetryReporter.maskIdentifier(null))
        assertEquals("None", AiTelemetryReporter.maskIdentifier("   "))
        assertEquals("***", AiTelemetryReporter.maskIdentifier("abc123"))
        assertEquals("gua***789", AiTelemetryReporter.maskIdentifier("guardian_uid_789"))

        val sanitizedCell = AiTelemetryReporter.sanitizeMarkdownCell("line1\n|`cmd`\r\nend")
        assertFalse("Pipes must be stripped from Markdown cells", sanitizedCell.contains("|"))
        assertFalse("Backticks must be escaped in Markdown cells", sanitizedCell.contains("`"))
        assertFalse("Newlines must be flattened in Markdown cells", sanitizedCell.contains("\n") || sanitizedCell.contains("\r"))

        val sanitizedBlock = AiTelemetryReporter.sanitizeCodeBlock("stack\n```\ninjected")
        assertFalse("Triple backticks inside crash block must be broken", sanitizedBlock.contains("```"))
    }

    @Test
    fun testCopyReportToClipboard_returnsFalseWhenClipboardServiceMissing() {
        val fakeContext = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
        assertFalse(
            "Missing ClipboardManager must cleanly report false instead of succeeding silently",
            AiTelemetryReporter.copyReportToClipboard(fakeContext, preGeneratedReport = "diagnostic-sample")
        )
    }

    @Test
    fun testDpmExecutionResult_contractPreservesSuccessAndFailurePayloads() {
        val emptySuccess: DpmExecutionResult<Array<String>> = DpmExecutionResult.Success(
            data = emptyArray(),
            executionTimeMs = 12L,
            actionName = "setPackagesSuspendedBlocking"
        )
        assertTrue(emptySuccess.isSuccess)
        assertEquals(0, emptySuccess.getOrNull()?.size)

        val partialFailure: DpmExecutionResult<Array<String>> = DpmExecutionResult.Success(
            data = arrayOf("com.reddit.frontpage"),
            executionTimeMs = 15L,
            actionName = "setPackagesSuspendedBlocking"
        )
        assertTrue(partialFailure.isSuccess)
        assertTrue(partialFailure.getOrNull()?.contains("com.reddit.frontpage") == true)

        val failedExec: DpmExecutionResult<Array<String>> = DpmExecutionResult.Failure(
            errorType = DpmErrorType.NOT_DEVICE_OWNER,
            message = "Missing Device Owner",
            executionTimeMs = 2L,
            actionName = "setPackagesSuspendedBlocking"
        )
        assertFalse(failedExec.isSuccess)
        assertNull(failedExec.getOrNull())
    }

    @Test
    fun testSafeDpmPackageBridge_contractsAndMissingDevicePolicyService() {
        data class RecordedCall(
            val tag: String,
            val actionName: String,
            val metadata: String?,
            val requireDeviceOwner: Boolean,
            val recordSuccess: Boolean
        )

        val recorded = mutableListOf<RecordedCall>()
        val recordingBridge = object : SafeDpmPackageBridge {
            override suspend fun <T> execute(
                context: Context,
                tag: String,
                actionName: String,
                metadata: String?,
                requireDeviceOwner: Boolean,
                recordSuccess: Boolean,
                action: (dpm: android.app.admin.DevicePolicyManager, admin: android.content.ComponentName) -> T
            ): DpmExecutionResult<T> = executeBlocking(
                context, tag, actionName, metadata, requireDeviceOwner, recordSuccess, action
            )

            override fun <T> executeBlocking(
                context: Context,
                tag: String,
                actionName: String,
                metadata: String?,
                requireDeviceOwner: Boolean,
                recordSuccess: Boolean,
                action: (dpm: android.app.admin.DevicePolicyManager, admin: android.content.ComponentName) -> T
            ): DpmExecutionResult<T> {
                recorded.add(RecordedCall(tag, actionName, metadata, requireDeviceOwner, recordSuccess))
                return DpmExecutionResult.Failure(
                    errorType = DpmErrorType.NOT_DEVICE_OWNER,
                    message = "Test stub",
                    executionTimeMs = 1L,
                    actionName = actionName
                )
            }
        }

        val fakeCtx = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.drmohammed.antiporn"
            override fun getSystemService(name: String): Any? = null
        }

        val suspendRes = recordingBridge.setPackagesSuspendedBlocking(
            fakeCtx, arrayOf("com.a", "com.b"), true
        )
        assertFalse(suspendRes.isSuccess)
        assertEquals(
            RecordedCall(BlackBoxRecorder.TAG_DPM, "setPackagesSuspendedBlocking", "suspended=true, count=2", true, true),
            recorded.last()
        )

        val checkRes = recordingBridge.isPackageSuspendedBlocking(fakeCtx, "com.a")
        assertFalse(checkRes.isSuccess)
        assertEquals(
            RecordedCall(BlackBoxRecorder.TAG_DPM, "isPackageSuspendedBlocking", "pkg=com.a", true, false),
            recorded.last()
        )

        val msgRes = recordingBridge.setPackageSuspendedWithMessageBlocking(fakeCtx, "com.a", "Curfew")
        assertFalse(msgRes.isSuccess)
        assertEquals(
            RecordedCall(BlackBoxRecorder.TAG_DPM, "setPackageSuspendedWithMessageBlocking", "pkg=com.a", true, true),
            recorded.last()
        )

        val hideRes = recordingBridge.setApplicationHiddenBlocking(fakeCtx, "com.a", true)
        assertFalse(hideRes.isSuccess)
        assertEquals(
            RecordedCall(BlackBoxRecorder.TAG_DPM, "setApplicationHiddenBlocking", "pkg=com.a, hidden=true", true, true),
            recorded.last()
        )

        val uninstallRes = recordingBridge.setUninstallBlockedBlocking(fakeCtx, "com.a", true)
        assertFalse(uninstallRes.isSuccess)
        assertEquals(
            RecordedCall(BlackBoxRecorder.TAG_DPM, "setUninstallBlockedBlocking", "pkg=com.a, blocked=true", true, true),
            recorded.last()
        )

        kotlinx.coroutines.runBlocking {
            val suspendAsync = recordingBridge.setPackagesSuspended(fakeCtx, arrayOf("com.a"), false)
            assertFalse(suspendAsync.isSuccess)
            assertEquals(
                RecordedCall(BlackBoxRecorder.TAG_DPM, "setPackagesSuspended", "suspended=false, count=1", true, true),
                recorded.last()
            )
        }

        // Real SafeDpmGateway (implementing SafeDpmPackageBridge) must fail closed across blocking & suspend methods when DPM is absent
        assertFalse(SafeDpmGateway.isAdminActive(fakeCtx))
        assertFalse(SafeDpmGateway.isDeviceOwner(fakeCtx))
        assertFalse(SafeDpmGateway.setPackagesSuspendedBlocking(fakeCtx, arrayOf("com.a"), true).isSuccess)
        assertFalse(SafeDpmGateway.isPackageSuspendedBlocking(fakeCtx, "com.a").isSuccess)
        assertFalse(SafeDpmGateway.setPackageSuspendedWithMessageBlocking(fakeCtx, "com.a", "Curfew").isSuccess)
        assertFalse(SafeDpmGateway.setApplicationHiddenBlocking(fakeCtx, "com.a", true).isSuccess)
        assertFalse(SafeDpmGateway.setUninstallBlockedBlocking(fakeCtx, "com.a", true).isSuccess)
        kotlinx.coroutines.runBlocking {
            assertFalse(SafeDpmGateway.setPackagesSuspended(fakeCtx, arrayOf("com.a"), true).isSuccess)
            assertFalse(SafeDpmGateway.isPackageSuspended(fakeCtx, "com.a").isSuccess)
            assertFalse(SafeDpmGateway.setApplicationHidden(fakeCtx, "com.a", true).isSuccess)
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, v: MutableSet<String>?) = apply { if (key != null) pending[key] = v?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) { values.clear(); clearAll = false }
                for ((k, v) in pending) {
                    if (v == null) values.remove(k) else values[k] = v
                }
                pending.clear()
            }
        }
    }
}
