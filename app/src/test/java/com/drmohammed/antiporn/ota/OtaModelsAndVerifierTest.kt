package com.drmohammed.antiporn.ota

import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.ota.model.OtaVersionInfo
import com.drmohammed.antiporn.ota.security.OtaIntegrityVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class OtaModelsAndVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testOtaVersionInfo_fromJson_fullValidSchema() {
        val sampleJson = """
            {
              "versionCode": 42,
              "versionName": "2.4.0",
              "minSupportedVersion": 10,
              "apkFileName": "antiporn-v2.4.0.apk",
              "downloadUrl": "https://example-releases.r2.dev/antiporn-v2.4.0.apk",
              "fileSizeBytes": 28901750,
              "sha256CertificateChecksum": "Cxi7DKn3ddcJXYIuDNXdlKN84EuKu8Bf0L0SkSPCOMk",
              "sha256ApkHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "releaseNotes": "Bug fixes and stability improvements",
              "releaseDate": "2026-10-29"
            }
        """.trimIndent()

        val info = OtaVersionInfo.fromJson(sampleJson)
        assertEquals(42L, info.versionCode)
        assertEquals("2.4.0", info.versionName)
        assertEquals(10L, info.minSupportedVersion)
        assertEquals("antiporn-v2.4.0.apk", info.apkFileName)
        assertEquals("https://example-releases.r2.dev/antiporn-v2.4.0.apk", info.downloadUrl)
        assertEquals(28901750L, info.fileSizeBytes)
        assertEquals("Cxi7DKn3ddcJXYIuDNXdlKN84EuKu8Bf0L0SkSPCOMk", info.sha256CertificateChecksum)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", info.sha256ApkHash)
        assertEquals("Bug fixes and stability improvements", info.releaseNotes)
        assertEquals("2026-10-29", info.releaseDate)

        assertTrue(info.isNewerThan(41L))
        assertFalse(info.isNewerThan(42L))
        assertFalse(info.isNewerThan(43L))

        assertTrue(info.isMandatoryFor(9L))
        assertFalse(info.isMandatoryFor(10L))
        assertFalse(info.isMandatoryFor(15L))
    }

    @Test
    fun testOtaVersionInfo_fromJson_missingAndNullFields() {
        val minimalJson = """
            {
              "versionCode": 5,
              "versionName": "1.0",
              "downloadUrl": "https://example.com/app.apk",
              "sha256ApkHash": null,
              "releaseNotes": ""
            }
        """.trimIndent()

        val info = OtaVersionInfo.fromJson(minimalJson)
        assertEquals(5L, info.versionCode)
        assertEquals("1.0", info.versionName)
        assertEquals(0L, info.minSupportedVersion)
        assertEquals("", info.apkFileName)
        assertEquals("https://example.com/app.apk", info.downloadUrl)
        assertEquals(0L, info.fileSizeBytes)
        assertNull(info.sha256CertificateChecksum)
        assertNull(info.sha256ApkHash)
        assertNull(info.releaseNotes)
        assertNull(info.releaseDate)
    }

    @Test
    fun testOtaVersionInfo_roundTripSerialization() {
        val original = OtaVersionInfo(
            versionCode = 100L,
            versionName = "3.0.0",
            minSupportedVersion = 50L,
            apkFileName = "release.apk",
            downloadUrl = "https://r2.dev/release.apk",
            fileSizeBytes = 1234567L,
            sha256CertificateChecksum = "CERT_HASH",
            sha256ApkHash = "APK_HASH",
            releaseNotes = "Major overhaul",
            releaseDate = "2026-11-01"
        )

        val jsonStr = original.toJson()
        val parsed = OtaVersionInfo.fromJson(jsonStr)
        assertEquals(original, parsed)
    }

    @Test
    fun testOtaUpdateState_hierarchy() {
        val info = OtaVersionInfo(1, "1.0", 1, "test.apk", "https://test.com", 1000)
        val file = File("test.apk")

        val states: List<OtaUpdateState> = listOf(
            OtaUpdateState.Idle,
            OtaUpdateState.Checking,
            OtaUpdateState.NoUpdateAvailable(1L, "1.0"),
            OtaUpdateState.UpdateAvailable(info),
            OtaUpdateState.Downloading(50, 500L, 1000L),
            OtaUpdateState.Verifying,
            OtaUpdateState.ReadyToInstall(file, info),
            OtaUpdateState.Installing,
            OtaUpdateState.Error("Network failure", RuntimeException("Timeout"))
        )

        assertEquals(9, states.size)
        assertTrue(states[0] is OtaUpdateState.Idle)
        assertTrue(states[4] is OtaUpdateState.Downloading)
        assertEquals(50, (states[4] as OtaUpdateState.Downloading).progressPercent)
        assertTrue(states[7] is OtaUpdateState.Installing)
    }

    @Test
    fun testComputeSha256() {
        val testFile = tempFolder.newFile("test_data.bin")
        val content = "AntiPorn Secure OTA Integrity Verifier 2026"
        testFile.writeText(content)

        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val computedHash = OtaIntegrityVerifier.computeSha256(testFile)
        assertEquals(expectedHash, computedHash)

        // Non-existent file returns empty string
        val nonExistent = File(tempFolder.root, "does_not_exist.bin")
        assertEquals("", OtaIntegrityVerifier.computeSha256(nonExistent))
    }
}
