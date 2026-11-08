package com.drmohammed.antiporn.data

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.policy.AppClassification
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.data.model.InstalledAppDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInventoryAndZeroTrustTest {

    @Test
    fun testInstalledAppDto_serializationAndDeserialization() {
        val dto = InstalledAppDto(
            packageName = "com.example.testapp",
            appName = "Test App",
            isSystem = false,
            isSuspended = true,
            isCurfewWhitelisted = false,
            isProtected = true,
            updatedAt = 1234567890L
        )

        val map = dto.toMap()
        assertEquals("com.example.testapp", map["packageName"])
        assertEquals("Test App", map["appName"])
        assertEquals(false, map["isSystem"])
        assertEquals(true, map["isSuspended"])
        assertEquals(false, map["isCurfewWhitelisted"])
        assertEquals(true, map["isProtected"])
        assertEquals(1234567890L, map["updatedAt"])

        val reconstructed = InstalledAppDto.fromMap(map)
        assertEquals(dto.packageName, reconstructed.packageName)
        assertEquals(dto.appName, reconstructed.appName)
        assertEquals(dto.isSystem, reconstructed.isSystem)
        assertEquals(dto.isSuspended, reconstructed.isSuspended)
        assertEquals(dto.isCurfewWhitelisted, reconstructed.isCurfewWhitelisted)
        assertEquals(dto.isProtected, reconstructed.isProtected)
        assertEquals(dto.updatedAt, reconstructed.updatedAt)
    }

    @Test
    fun testZeroTrustExemption_doesNotExemptGamesOrMedia() {
        // Games and audio players might be exempt from curfew, but MUST NEVER be immune from Zero-Trust!
        val gamesAndMedia = listOf(
            "com.chillingo.robberybobfree.android.row",
            "org.videolan.vlc",
            "com.spotify.music",
            "com.supercell.clashofclans",
            "org.schabi.newpipe",
            "com.facebook.katana",
            "com.instagram.android"
        )

        val immunePackages = AppPolicyRegistry.getImmunePackages()
        val systemCriticalPackages = AppPolicyRegistry.getSystemCriticalPackages()

        for (pkg in gamesAndMedia) {
            val isImmune = immunePackages.contains(pkg) || systemCriticalPackages.contains(pkg)
            assertFalse("Package $pkg must NOT be exempt from Zero-Trust installation freeze!", isImmune)
        }
    }

    @Test
    fun testZeroTrustExemption_protectsCoreSystemFrameworks() {
        val systemPackages = listOf(
            "android",
            "com.android.systemui",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.settings",
            "com.google.android.packageinstaller"
        )

        val systemCriticalPackages = AppPolicyRegistry.getSystemCriticalPackages()
        for (pkg in systemPackages) {
            assertTrue("System critical package $pkg must be protected", systemCriticalPackages.contains(pkg))
        }

        val immunePackages = AppPolicyRegistry.getImmunePackages()
        assertTrue("AntiPorn must be immune", immunePackages.contains("com.drmohammed.antiporn"))
    }

    @Test
    fun testDevicePolicyBundle_supportsCustomBlacklistAndProtectedApps() {
        val bundle = DevicePolicyBundle(
            customBlacklistPackages = setOf("com.distracting.game", "com.social.app"),
            protectedAppPackages = setOf("com.drmohammed.antiporn", "com.important.workapp"),
            customCurfewWhitelistPackages = setOf("com.work.chat")
        )

        val map = bundle.toMap()
        @Suppress("UNCHECKED_CAST")
        val blacklistList = map["customBlacklistPackages"] as List<String>
        @Suppress("UNCHECKED_CAST")
        val protectedList = map["protectedAppPackages"] as List<String>
        @Suppress("UNCHECKED_CAST")
        val whitelistList = map["customCurfewWhitelistPackages"] as List<String>

        assertTrue(blacklistList.contains("com.distracting.game"))
        assertTrue(protectedList.contains("com.important.workapp"))
        assertTrue(whitelistList.contains("com.work.chat"))

        val restored = DevicePolicyBundle.fromMap(map)
        assertEquals(bundle.customBlacklistPackages, restored.customBlacklistPackages)
        assertEquals(bundle.protectedAppPackages, restored.protectedAppPackages)
        assertEquals(bundle.customCurfewWhitelistPackages, restored.customCurfewWhitelistPackages)
    }

    @Test
    fun testPendingApprovalEntity_fallbackHiddenIsTreatedAsPending() {
        val pendingEntity = com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity(
            packageName = "com.uncooperative.app",
            appName = "Uncooperative App",
            status = com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity.STATUS_FALLBACK_HIDDEN
        )

        assertTrue(pendingEntity.isPending)
        assertTrue(pendingEntity.isFallbackHidden)
        assertFalse(pendingEntity.isApproved)
        assertFalse(pendingEntity.isRejected)
    }

    @Test
    fun testDevicePolicyBundle_unfreezeRemovesFromCustomBlacklist() {
        val initialPolicy = DevicePolicyBundle(
            customBlacklistPackages = setOf("com.google.android.apps.bard", "com.distracting.app"),
            customCurfewWhitelistPackages = emptySet()
        )

        // When supervisor unfreezes "com.google.android.apps.bard"
        val updatedBlacklist = initialPolicy.customBlacklistPackages - "com.google.android.apps.bard"
        val updatedPolicy = initialPolicy.copy(
            customBlacklistPackages = updatedBlacklist
        )

        assertFalse("Gemini must be removed from custom blacklist", updatedPolicy.customBlacklistPackages.contains("com.google.android.apps.bard"))
        assertTrue("Other apps must remain in custom blacklist", updatedPolicy.customBlacklistPackages.contains("com.distracting.app"))

        val removedFromBlacklist = initialPolicy.customBlacklistPackages - updatedPolicy.customBlacklistPackages
        assertTrue("Removed set must contain Gemini", removedFromBlacklist.contains("com.google.android.apps.bard"))
    }
}