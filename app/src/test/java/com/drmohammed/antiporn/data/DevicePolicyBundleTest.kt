package com.drmohammed.antiporn.data

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePolicyBundleTest {

    @Test
    fun testDefaultPolicy_hasExpectedSecurityDefaults() {
        val policy = DevicePolicyBundle.defaultPolicy()
        assertEquals(20, policy.curfewStartHour)
        assertEquals(0, policy.curfewStartMinute)
        assertEquals(7, policy.curfewEndHour)
        assertEquals(0, policy.curfewEndMinute)
        assertTrue(policy.isCurfewEnabled)
        assertFalse(policy.isInstantInternetKillEnabled)
        assertFalse(policy.isWeekendCurfewEnabled)
        assertEquals(20, policy.weekendCurfewStartHour)
        assertEquals(0, policy.weekendCurfewStartMinute)
        assertEquals(7, policy.weekendCurfewEndHour)
        assertEquals(0, policy.weekendCurfewEndMinute)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED, policy.isVpnBlackholeEnabled)
        assertTrue(policy.isVpnBlackholeEnabled)
        assertTrue(policy.isIncognitoBlocked)
        assertTrue(policy.lockNewInstallsUntilApproved)
        assertTrue(policy.customBlacklistPackages.isEmpty())
        assertTrue(policy.customCurfewWhitelistPackages.isEmpty())
        assertTrue(policy.protectedAppPackages.isEmpty())
        assertEquals(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST, policy.dnsHost)
        assertEquals(1L, policy.version)
    }

    @Test
    fun testSerializationAndDeserialization_viaMap() {
        val original = DevicePolicyBundle(
            curfewStartHour = 22,
            curfewStartMinute = 15,
            curfewEndHour = 6,
            curfewEndMinute = 30,
            isCurfewEnabled = true,
            isInstantInternetKillEnabled = true,
            isWeekendCurfewEnabled = true,
            weekendCurfewStartHour = 23,
            weekendCurfewStartMinute = 45,
            weekendCurfewEndHour = 9,
            weekendCurfewEndMinute = 15,
            isVpnBlackholeEnabled = true,
            isIncognitoBlocked = true,
            lockNewInstallsUntilApproved = true,
            customBlacklistPackages = setOf("com.bad.app1", "com.bad.app2"),
            customCurfewWhitelistPackages = setOf("com.work.app1"),
            protectedAppPackages = setOf("com.vital.app1", "com.vital.app2"),
            dnsHost = "custom.dns.nextdns.io",
            version = 42L,
            updatedAt = 1790000000000L
        )

        val map = original.toMap()
        val restored = DevicePolicyBundle.fromMap(map)

        assertEquals(original.curfewStartHour, restored.curfewStartHour)
        assertEquals(original.curfewStartMinute, restored.curfewStartMinute)
        assertEquals(original.curfewEndHour, restored.curfewEndHour)
        assertEquals(original.curfewEndMinute, restored.curfewEndMinute)
        assertEquals(original.isCurfewEnabled, restored.isCurfewEnabled)
        assertEquals(original.isInstantInternetKillEnabled, restored.isInstantInternetKillEnabled)
        assertEquals(original.isWeekendCurfewEnabled, restored.isWeekendCurfewEnabled)
        assertEquals(original.weekendCurfewStartHour, restored.weekendCurfewStartHour)
        assertEquals(original.weekendCurfewStartMinute, restored.weekendCurfewStartMinute)
        assertEquals(original.weekendCurfewEndHour, restored.weekendCurfewEndHour)
        assertEquals(original.weekendCurfewEndMinute, restored.weekendCurfewEndMinute)
        assertEquals(original.isVpnBlackholeEnabled, restored.isVpnBlackholeEnabled)
        assertEquals(original.isIncognitoBlocked, restored.isIncognitoBlocked)
        assertEquals(original.lockNewInstallsUntilApproved, restored.lockNewInstallsUntilApproved)
        assertEquals(original.customBlacklistPackages, restored.customBlacklistPackages)
        assertEquals(original.customCurfewWhitelistPackages, restored.customCurfewWhitelistPackages)
        assertEquals(original.protectedAppPackages, restored.protectedAppPackages)
        assertEquals(original.dnsHost, restored.dnsHost)
        assertEquals(original.version, restored.version)
        assertEquals(original.updatedAt, restored.updatedAt)
    }

    @Test
    fun testSerializationAndDeserialization_viaJson() {
        val original = DevicePolicyBundle(
            curfewStartHour = 21,
            curfewStartMinute = 0,
            curfewEndHour = 8,
            curfewEndMinute = 0,
            isCurfewEnabled = false,
            isInstantInternetKillEnabled = true,
            isWeekendCurfewEnabled = true,
            weekendCurfewStartHour = 22,
            weekendCurfewStartMinute = 30,
            weekendCurfewEndHour = 10,
            weekendCurfewEndMinute = 0,
            isVpnBlackholeEnabled = false,
            isIncognitoBlocked = false,
            lockNewInstallsUntilApproved = true,
            customBlacklistPackages = setOf("pkg.a"),
            customCurfewWhitelistPackages = setOf("pkg.b"),
            protectedAppPackages = setOf("pkg.c"),
            dnsHost = "custom.dns.nextdns.io",
            version = 5L,
            updatedAt = 123456789L
        )

        val json = original.toJson()
        val restored = DevicePolicyBundle.fromJson(json)

        assertEquals(original.curfewStartHour, restored.curfewStartHour)
        assertEquals(original.curfewStartMinute, restored.curfewStartMinute)
        assertEquals(original.curfewEndHour, restored.curfewEndHour)
        assertEquals(original.curfewEndMinute, restored.curfewEndMinute)
        assertEquals(original.isCurfewEnabled, restored.isCurfewEnabled)
        assertEquals(original.isInstantInternetKillEnabled, restored.isInstantInternetKillEnabled)
        assertEquals(original.isWeekendCurfewEnabled, restored.isWeekendCurfewEnabled)
        assertEquals(original.weekendCurfewStartHour, restored.weekendCurfewStartHour)
        assertEquals(original.weekendCurfewStartMinute, restored.weekendCurfewStartMinute)
        assertEquals(original.weekendCurfewEndHour, restored.weekendCurfewEndHour)
        assertEquals(original.weekendCurfewEndMinute, restored.weekendCurfewEndMinute)
        assertEquals(original.isVpnBlackholeEnabled, restored.isVpnBlackholeEnabled)
        assertEquals(original.isIncognitoBlocked, restored.isIncognitoBlocked)
        assertEquals(original.lockNewInstallsUntilApproved, restored.lockNewInstallsUntilApproved)
        assertEquals(original.customBlacklistPackages, restored.customBlacklistPackages)
        assertEquals(original.customCurfewWhitelistPackages, restored.customCurfewWhitelistPackages)
        assertEquals(original.protectedAppPackages, restored.protectedAppPackages)
        assertEquals(original.dnsHost, restored.dnsHost)
        assertEquals(original.version, restored.version)
        assertEquals(original.updatedAt, restored.updatedAt)
    }
}
