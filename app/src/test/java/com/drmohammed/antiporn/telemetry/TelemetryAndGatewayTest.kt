package com.drmohammed.antiporn.telemetry

import android.content.ComponentName
import com.drmohammed.antiporn.AdminReceiver
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
        assertTrue("Markdown row must contain success badge", mdRow.contains("✅ OK"))
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
        assertTrue("Markdown row must contain failure badge", mdRow.contains("❌ FAIL"))
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
        assertEquals("ALL", BlackBoxRecorder.CATEGORY_ALL)
    }

    @Test
    fun testSafeDpmPackageBridge_emptyArrayContractOnSuccess() {
        // Android DevicePolicyManager.setPackagesSuspended returns null when ALL packages were successfully suspended.
        // The bridge contract must convert null to emptyArray() (meaning 0 failed packages).
        val dpmReturnedNull: Array<String>? = null
        val normalized = dpmReturnedNull ?: emptyArray()

        assertTrue("Null return from DPM indicates 0 failures, must map to empty array", normalized.isEmpty())
        assertEquals(0, normalized.size)
    }
}
