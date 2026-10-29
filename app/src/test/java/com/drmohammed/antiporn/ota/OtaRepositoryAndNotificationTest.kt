package com.drmohammed.antiporn.ota

import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.ota.model.OtaVersionInfo
import com.drmohammed.antiporn.ota.notification.OtaNotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OtaRepositoryAndNotificationTest {

    @Test
    fun testNotificationChannelId() {
        assertEquals("antiporn_ota_updates", OtaNotificationManager.CHANNEL_ID)
    }

    @Test
    fun testOtaUpdateState_instances() {
        val idle = OtaUpdateState.Idle
        assertEquals(OtaUpdateState.Idle, idle)

        val checking = OtaUpdateState.Checking
        assertEquals(OtaUpdateState.Checking, checking)

        val noUpdate = OtaUpdateState.NoUpdateAvailable(10L, "1.2.0")
        assertEquals(10L, noUpdate.currentVersionCode)
        assertEquals("1.2.0", noUpdate.currentVersionName)

        val info = OtaVersionInfo(
            versionCode = 11L,
            versionName = "1.2.1",
            minSupportedVersion = 5L,
            apkFileName = "app-release.apk",
            downloadUrl = "https://example.com/app.apk",
            fileSizeBytes = 50_000_000L
        )

        val updateAvailable = OtaUpdateState.UpdateAvailable(info)
        assertEquals(11L, updateAvailable.info.versionCode)

        val downloading = OtaUpdateState.Downloading(50, 25_000_000L, 50_000_000L)
        assertEquals(50, downloading.progressPercent)
        assertEquals(25_000_000L, downloading.downloadedBytes)

        val verifying = OtaUpdateState.Verifying
        assertEquals(OtaUpdateState.Verifying, verifying)

        val dummyFile = File("test.apk")
        val ready = OtaUpdateState.ReadyToInstall(dummyFile, info)
        assertEquals(dummyFile, ready.apkFile)

        val installing = OtaUpdateState.Installing
        assertEquals(OtaUpdateState.Installing, installing)

        val error = OtaUpdateState.Error("Failed", IllegalStateException("Disk full"))
        assertEquals("Failed", error.message)
        assertTrue(error.cause is IllegalStateException)
    }

    @Test
    fun testOtaVersionInfo_isNewerThan() {
        val info = OtaVersionInfo(
            versionCode = 15L,
            versionName = "1.5.0",
            minSupportedVersion = 10L,
            apkFileName = "release.apk",
            downloadUrl = "https://example.com/release.apk",
            fileSizeBytes = 12345L
        )

        assertTrue(info.isNewerThan(14L))
        assertFalse(info.isNewerThan(15L))
        assertFalse(info.isNewerThan(16L))

        assertTrue(info.isMandatoryFor(9L))
        assertFalse(info.isMandatoryFor(10L))
        assertFalse(info.isMandatoryFor(12L))
    }
}
