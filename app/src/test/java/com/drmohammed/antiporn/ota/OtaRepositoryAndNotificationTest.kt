package com.drmohammed.antiporn.ota

import android.content.Context
import android.content.ContextWrapper
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.ota.model.OtaVersionInfo
import com.drmohammed.antiporn.ota.notification.OtaNotificationManager
import com.drmohammed.antiporn.ota.repository.OtaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testPurgeOtaCache_cleansObsoleteApksAndTmpFiles() {
        val cacheDir = tempFolder.newFolder("cache")
        val otaDir = File(cacheDir, "ota").apply { mkdirs() }

        val apk20 = File(otaDir, "antiporn_update_20.apk").apply { writeText("dummy apk 20") }
        val apk21 = File(otaDir, "antiporn_update_21.apk").apply { writeText("dummy apk 21") }
        val tmpFile = File(otaDir, "antiporn_update_22.apk.tmp").apply { writeText("dummy tmp") }
        val notesFile = File(otaDir, "notes.txt").apply { writeText("keep me") }

        val fakeContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cacheDir
        }

        val otaRepo = OtaRepository(fakeContext)
        val deletedCount = otaRepo.purgeOtaCache(fakeContext)

        assertEquals(3, deletedCount)
        assertFalse(apk20.exists())
        assertFalse(apk21.exists())
        assertFalse(tmpFile.exists())
        assertTrue(notesFile.exists())
    }

    @Test
    fun testPurgeOtaCache_withExcludeVersionCode_preservesTargetApk() {
        val cacheDir = tempFolder.newFolder("cache_exclude")
        val otaDir = File(cacheDir, "ota").apply { mkdirs() }

        val apk20 = File(otaDir, "antiporn_update_20.apk").apply { writeText("dummy apk 20") }
        val apk21 = File(otaDir, "antiporn_update_21.apk").apply { writeText("dummy apk 21") }
        val tmp21 = File(otaDir, "antiporn_update_21.apk.tmp").apply { writeText("dummy tmp 21") }
        val tmpFile = File(otaDir, "download.tmp").apply { writeText("dummy tmp") }

        val fakeContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cacheDir
        }

        val otaRepo = OtaRepository(fakeContext)
        val deletedCount = otaRepo.purgeOtaCache(fakeContext, excludeVersionCode = 21L)

        assertEquals(2, deletedCount)
        assertFalse(apk20.exists())
        assertTrue(apk21.exists())
        assertTrue(tmp21.exists())
        assertFalse(tmpFile.exists())
    }

    @Test
    fun testPurgeOtaCache_whenOtaDirDoesNotExist_returnsZero() {
        val emptyCacheDir = tempFolder.newFolder("empty_cache")
        val fakeContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = emptyCacheDir
        }

        val otaRepo = OtaRepository(fakeContext)
        val deletedCount = otaRepo.purgeOtaCache(fakeContext)

        assertEquals(0, deletedCount)
    }
}
