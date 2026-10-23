package com.drmohammed.antiporn.data

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePolicyBundleTest {

    @Test
    fun testDefaultPolicyBundle_hasExpectedDefaults() {
        val policy = DevicePolicyBundle.defaultPolicy()
        assertEquals(20, policy.curfewStartHour)
        assertEquals(0, policy.curfewStartMinute)
        assertEquals(7, policy.curfewEndHour)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED, policy.isVpnBlackholeEnabled)
        assertTrue(policy.isVpnBlackholeEnabled)
        assertTrue(policy.isIncognitoBlocked)
        assertTrue(policy.lockNewInstallsUntilApproved)
        assertTrue(policy.customBlacklistPackages.isEmpty())
        assertTrue(policy.customCurfewWhitelistPackages.isEmpty())
        assertEquals(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST, policy.dnsHost)
        assertEquals(1L, policy.version)
    }

    @Test
    fun testJsonSerialization_roundTripPreservesAllFields() {
        val original = DevicePolicyBundle(
            curfewStartHour = 22,
            curfewStartMinute = 15,
            curfewEndHour = 6,
            curfewEndMinute = 30,
            isVpnBlackholeEnabled = true,
            isIncognitoBlocked = true,
            lockNewInstallsUntilApproved = true,
            customBlacklistPackages = setOf("com.bad.app1", "com.bad.app2"),
            customCurfewWhitelistPackages = setOf("com.work.app1"),
            dnsHost = "custom.dns.nextdns.io",
            version = 42L,
            updatedAt = 1790000000000L
        )

        val jsonStr = original.toJson()
        val restored = DevicePolicyBundle.fromJson(jsonStr)

        assertEquals(original.curfewStartHour, restored.curfewStartHour)
        assertEquals(original.curfewStartMinute, restored.curfewStartMinute)
        assertEquals(original.curfewEndHour, restored.curfewEndHour)
        assertEquals(original.curfewEndMinute, restored.curfewEndMinute)
        assertEquals(original.isVpnBlackholeEnabled, restored.isVpnBlackholeEnabled)
        assertEquals(original.isIncognitoBlocked, restored.isIncognitoBlocked)
        assertEquals(original.lockNewInstallsUntilApproved, restored.lockNewInstallsUntilApproved)
        assertEquals(original.customBlacklistPackages, restored.customBlacklistPackages)
        assertEquals(original.customCurfewWhitelistPackages, restored.customCurfewWhitelistPackages)
        assertEquals(original.dnsHost, restored.dnsHost)
        assertEquals(original.version, restored.version)
        assertEquals(original.updatedAt, restored.updatedAt)
    }

    @Test
    fun testMapSerialization_roundTripPreservesAllFields() {
        val original = DevicePolicyBundle(
            curfewStartHour = 21,
            curfewStartMinute = 0,
            curfewEndHour = 8,
            curfewEndMinute = 0,
            isVpnBlackholeEnabled = false,
            isIncognitoBlocked = false,
            lockNewInstallsUntilApproved = true,
            customBlacklistPackages = setOf("pkg.a"),
            customCurfewWhitelistPackages = setOf("pkg.b"),
            dnsHost = "custom.dns.nextdns.io",
            version = 5L,
            updatedAt = 123456789L
        )

        val map = original.toMap()
        val restored = DevicePolicyBundle.fromMap(map)

        assertEquals(original.curfewStartHour, restored.curfewStartHour)
        assertEquals(original.curfewStartMinute, restored.curfewStartMinute)
        assertEquals(original.curfewEndHour, restored.curfewEndHour)
        assertEquals(original.curfewEndMinute, restored.curfewEndMinute)
        assertEquals(original.isVpnBlackholeEnabled, restored.isVpnBlackholeEnabled)
        assertEquals(original.isIncognitoBlocked, restored.isIncognitoBlocked)
        assertEquals(original.lockNewInstallsUntilApproved, restored.lockNewInstallsUntilApproved)
        assertEquals(original.customBlacklistPackages, restored.customBlacklistPackages)
        assertEquals(original.customCurfewWhitelistPackages, restored.customCurfewWhitelistPackages)
        assertEquals(original.dnsHost, restored.dnsHost)
        assertEquals(original.version, restored.version)
        assertEquals(original.updatedAt, restored.updatedAt)
    }

    @Test
    fun testLegacyJsonAndMap_withoutDnsHost_defaultsToLegacyNextDnsHost() {
        val legacyJson = """
            {
                "curfewStartHour": 20,
                "curfewStartMinute": 30,
                "curfewEndHour": 7,
                "curfewEndMinute": 0,
                "isVpnBlackholeEnabled": false,
                "isIncognitoBlocked": true,
                "lockNewInstallsUntilApproved": true,
                "version": 1
            }
        """.trimIndent()
        val fromJson = DevicePolicyBundle.fromJson(legacyJson)
        assertEquals(DevicePolicyBundle.LEGACY_DEFAULT_NEXTDNS_HOST, fromJson.dnsHost)

        val legacyMap = mapOf(
            "curfewStartHour" to 20,
            "version" to 1L
        )
        val fromMap = DevicePolicyBundle.fromMap(legacyMap)
        assertEquals(DevicePolicyBundle.LEGACY_DEFAULT_NEXTDNS_HOST, fromMap.dnsHost)
    }

    @Test
    fun testFromJson_handlesCorruptStringGracefully() {
        val restored = DevicePolicyBundle.fromJson("invalid json string {[{")
        assertNotNull(restored)
        assertEquals(20, restored.curfewStartHour)
        assertEquals(0, restored.curfewStartMinute)
    }

    @Test
    fun testPartnerEntity_rolesAndHelpers() {
        val guardian = PartnerEntity(
            id = "uid_123",
            pairingCode = "ABC123",
            displayName = "My Guardian",
            role = PartnerEntity.ROLE_GUARDIAN
        )
        assertTrue(guardian.isGuardian)
        assertFalse(guardian.isWard)

        val ward = PartnerEntity(
            id = "uid_456",
            pairingCode = "XYZ789",
            displayName = "My Ward",
            role = PartnerEntity.ROLE_WARD
        )
        assertFalse(ward.isGuardian)
        assertTrue(ward.isWard)
    }

    @Test
    fun testPendingApprovalEntity_statusHelpers() {
        val pending = PendingApprovalEntity(
            packageName = "com.test.app",
            appName = "Test App",
            status = PendingApprovalEntity.STATUS_PENDING
        )
        assertTrue(pending.isPending)
        assertFalse(pending.isApproved)
        assertFalse(pending.isRejected)

        val approved = pending.copy(status = PendingApprovalEntity.STATUS_APPROVED)
        assertFalse(approved.isPending)
        assertTrue(approved.isApproved)
        assertFalse(approved.isRejected)

        val rejected = pending.copy(status = PendingApprovalEntity.STATUS_REJECTED)
        assertFalse(rejected.isPending)
        assertFalse(rejected.isApproved)
        assertTrue(rejected.isRejected)
    }

    @Test
    fun testSupervisedPolicy_jsonAndMapSerialization() {
        val policy = DevicePolicyBundle(
            guardianUid = "guardian_abc123",
            guardianName = "Father Phone",
            isSupervised = true,
            version = 10L
        )
        assertTrue(policy.isSupervised)
        assertEquals("guardian_abc123", policy.guardianUid)
        assertEquals("Father Phone", policy.guardianName)

        val json = policy.toJson()
        val fromJson = DevicePolicyBundle.fromJson(json)
        assertTrue(fromJson.isSupervised)
        assertEquals(policy.guardianUid, fromJson.guardianUid)
        assertEquals(policy.guardianName, fromJson.guardianName)

        val map = policy.toMap()
        val fromMap = DevicePolicyBundle.fromMap(map)
        assertTrue(fromMap.isSupervised)
        assertEquals(policy.guardianUid, fromMap.guardianUid)
        assertEquals(policy.guardianName, fromMap.guardianName)
    }

    @Test
    fun testUnsupervisedPolicy_defaultsToFalseAndNull() {
        val policy = DevicePolicyBundle.defaultPolicy()
        assertFalse(policy.isSupervised)
        org.junit.Assert.assertNull(policy.guardianUid)
        org.junit.Assert.assertNull(policy.guardianName)
    }

    @Test
    fun testGuardianPartner_representation() {
        val guardian = PartnerEntity(
            id = "guard_999",
            pairingCode = "LOCKED",
            displayName = "Supervising Guardian",
            role = PartnerEntity.ROLE_GUARDIAN
        )
        assertTrue(guardian.isGuardian)
        assertFalse(guardian.isWard)
        assertEquals("LOCKED", guardian.pairingCode)
        assertEquals("Supervising Guardian", guardian.displayName)
    }
}
