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
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.STATIC_WHITELIST, policy.customCurfewWhitelistPackages)
        assertTrue(policy.curfewVpnBypassPackages.isEmpty())
        assertTrue(policy.protectedAppPackages.isEmpty())
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_OFF, policy.browserFilterMode)
        assertTrue(policy.customBlockedUrls.isEmpty())
        assertTrue(policy.customAllowedUrls.isEmpty())
        assertTrue(policy.isSelfControlAllowed)
        assertFalse(policy.isDateTimeLocked)
        assertEquals(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST, policy.dnsHost)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.getDefaultFallbackTimeZone(), policy.timeZoneId)
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
            isSelfControlAllowed = false,
            isDateTimeLocked = true,
            customBlacklistPackages = setOf("com.bad.app1", "com.bad.app2"),
            customCurfewWhitelistPackages = setOf("com.work.app1"),
            curfewVpnBypassPackages = setOf("com.bypass.app1"),
            protectedAppPackages = setOf("com.vital.app1", "com.vital.app2"),
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST,
            customBlockedUrls = setOf("badsite.com", "gambling.org"),
            customAllowedUrls = setOf("safe.org"),
            dnsHost = "custom.dns.nextdns.io",
            timeZoneId = "Africa/Cairo",
            lastHeartbeat = 1790000050000L,
            breakoutStatus = DevicePolicyBundle.BREAKOUT_STATUS_PENDING,
            breakoutRequestedAt = 1790000000000L,
            breakoutUnlockAt = 1790086400000L,
            guardianPolicySnapshot = "{\"version\":40}",
            lastSelfModifiedAt = 1790000010000L,
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
        assertEquals(original.isSelfControlAllowed, restored.isSelfControlAllowed)
        assertEquals(original.isDateTimeLocked, restored.isDateTimeLocked)
        assertEquals(original.customBlacklistPackages, restored.customBlacklistPackages)
        assertEquals(original.customCurfewWhitelistPackages, restored.customCurfewWhitelistPackages)
        assertEquals(original.curfewVpnBypassPackages, restored.curfewVpnBypassPackages)
        assertEquals(original.protectedAppPackages, restored.protectedAppPackages)
        assertEquals(original.browserFilterMode, restored.browserFilterMode)
        assertEquals(original.customBlockedUrls, restored.customBlockedUrls)
        assertEquals(original.customAllowedUrls, restored.customAllowedUrls)
        assertEquals(original.dnsHost, restored.dnsHost)
        assertEquals(original.timeZoneId, restored.timeZoneId)
        assertEquals(original.lastHeartbeat, restored.lastHeartbeat)
        assertEquals(original.breakoutStatus, restored.breakoutStatus)
        assertEquals(original.breakoutRequestedAt, restored.breakoutRequestedAt)
        assertEquals(original.breakoutUnlockAt, restored.breakoutUnlockAt)
        assertEquals(original.guardianPolicySnapshot, restored.guardianPolicySnapshot)
        assertEquals(original.lastSelfModifiedAt, restored.lastSelfModifiedAt)
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
            isSelfControlAllowed = false,
            isDateTimeLocked = true,
            customBlacklistPackages = setOf("pkg.a"),
            customCurfewWhitelistPackages = setOf("pkg.b"),
            curfewVpnBypassPackages = setOf("pkg.vpn.bypass"),
            protectedAppPackages = setOf("pkg.c"),
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY,
            customBlockedUrls = setOf("blocked1.com", "blocked2.com"),
            customAllowedUrls = setOf("allowed1.com"),
            dnsHost = "custom.dns.nextdns.io",
            timeZoneId = "Asia/Dubai",
            lastHeartbeat = 1234567890L,
            breakoutStatus = DevicePolicyBundle.BREAKOUT_STATUS_COMPLETED,
            breakoutRequestedAt = 1234560000L,
            breakoutUnlockAt = 1234567800L,
            guardianPolicySnapshot = "{\"version\":4}",
            lastSelfModifiedAt = 1234560500L,
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
        assertEquals(original.isSelfControlAllowed, restored.isSelfControlAllowed)
        assertEquals(original.isDateTimeLocked, restored.isDateTimeLocked)
        assertEquals(original.customBlacklistPackages, restored.customBlacklistPackages)
        assertEquals(original.customCurfewWhitelistPackages, restored.customCurfewWhitelistPackages)
        assertEquals(original.curfewVpnBypassPackages, restored.curfewVpnBypassPackages)
        assertEquals(original.protectedAppPackages, restored.protectedAppPackages)
        assertEquals(original.browserFilterMode, restored.browserFilterMode)
        assertEquals(original.customBlockedUrls, restored.customBlockedUrls)
        assertEquals(original.customAllowedUrls, restored.customAllowedUrls)
        assertEquals(original.dnsHost, restored.dnsHost)
        assertEquals(original.timeZoneId, restored.timeZoneId)
        assertEquals(original.lastHeartbeat, restored.lastHeartbeat)
        assertEquals(original.breakoutStatus, restored.breakoutStatus)
        assertEquals(original.breakoutRequestedAt, restored.breakoutRequestedAt)
        assertEquals(original.breakoutUnlockAt, restored.breakoutUnlockAt)
        assertEquals(original.guardianPolicySnapshot, restored.guardianPolicySnapshot)
        assertEquals(original.lastSelfModifiedAt, restored.lastSelfModifiedAt)
        assertEquals(original.version, restored.version)
        assertEquals(original.updatedAt, restored.updatedAt)
    }

    @Test
    fun testLegacyPolicyWithoutTimeZone_fallsBackToCairo() {
        val legacyJson = """
            {
                "curfewStartHour": 20,
                "curfewStartMinute": 0,
                "version": 1
            }
        """.trimIndent()
        val parsed = DevicePolicyBundle.fromJson(legacyJson)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID, parsed.timeZoneId)
        assertFalse(parsed.isDateTimeLocked)
        assertTrue(parsed.isSelfControlAllowed)

        val legacyMap = mapOf<String, Any?>(
            "curfewStartHour" to 20,
            "curfewStartMinute" to 0
        )
        val fromMap = DevicePolicyBundle.fromMap(legacyMap)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID, fromMap.timeZoneId)
        assertFalse(fromMap.isDateTimeLocked)
        assertTrue(fromMap.isSelfControlAllowed)

        val emptyTzPolicy = DevicePolicyBundle(timeZoneId = "")
        val reconciled = DevicePolicyBundle.reconcileLegacyV1(emptyTzPolicy)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID, reconciled.timeZoneId)
    }

    @Test
    fun testCurfewWhitelist_intentionalEmptyVsMissing() {
        val missingJson = """
            {
                "version": 1
            }
        """.trimIndent()
        val parsedMissing = DevicePolicyBundle.fromJson(missingJson)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.STATIC_WHITELIST, parsedMissing.customCurfewWhitelistPackages)

        val explicitEmptyJson = """
            {
                "version": 1,
                "customCurfewWhitelistPackages": []
            }
        """.trimIndent()
        val parsedEmpty = DevicePolicyBundle.fromJson(explicitEmptyJson)
        assertTrue(parsedEmpty.customCurfewWhitelistPackages.isEmpty())

        val missingMap = emptyMap<String, Any?>()
        val parsedMissingMap = DevicePolicyBundle.fromMap(missingMap)
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.STATIC_WHITELIST, parsedMissingMap.customCurfewWhitelistPackages)

        val explicitEmptyMap = mapOf<String, Any?>("customCurfewWhitelistPackages" to emptyList<String>())
        val parsedEmptyMap = DevicePolicyBundle.fromMap(explicitEmptyMap)
        assertTrue(parsedEmptyMap.customCurfewWhitelistPackages.isEmpty())
    }

    @Test
    fun testLegacyPayloadWithoutBrowserFilter_defaultsSafely() {
        val legacyJson = """
            {
                "curfewStartHour": 20,
                "version": 1
            }
        """.trimIndent()
        val parsed = DevicePolicyBundle.fromJson(legacyJson)
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_OFF, parsed.browserFilterMode)
        assertTrue(parsed.customBlockedUrls.isEmpty())
        assertTrue(parsed.customAllowedUrls.isEmpty())

        val legacyMap = emptyMap<String, Any?>()
        val parsedMap = DevicePolicyBundle.fromMap(legacyMap)
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_OFF, parsedMap.browserFilterMode)
        assertTrue(parsedMap.customBlockedUrls.isEmpty())
        assertTrue(parsedMap.customAllowedUrls.isEmpty())
    }

    @Test
    fun testBrowserFilterMode_coercesOutOfBoundsValues() {
        val jsonTooHigh = """{"browserFilterMode": 99}"""
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY, DevicePolicyBundle.fromJson(jsonTooHigh).browserFilterMode)

        val jsonNegative = """{"browserFilterMode": -5}"""
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_OFF, DevicePolicyBundle.fromJson(jsonNegative).browserFilterMode)

        val mapTooHigh = mapOf("browserFilterMode" to 50)
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY, DevicePolicyBundle.fromMap(mapTooHigh).browserFilterMode)

        val mapNegative = mapOf("browserFilterMode" to -10)
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_OFF, DevicePolicyBundle.fromMap(mapNegative).browserFilterMode)

        val mapString = mapOf("browserFilterMode" to "1")
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST, DevicePolicyBundle.fromMap(mapString).browserFilterMode)
    }

    @Test
    fun testBrowserFilterUrls_handlesNullAndMalformedEntriesSafely() {
        val jsonWithNullEntries = """
            {
                "browserFilterMode": 1,
                "customBlockedUrls": ["site1.com", null, "", "   ", "site2.com"],
                "customAllowedUrls": [null, "allowed.com"]
            }
        """.trimIndent()
        val parsed = DevicePolicyBundle.fromJson(jsonWithNullEntries)
        assertEquals(DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST, parsed.browserFilterMode)
        assertEquals(setOf("site1.com", "site2.com"), parsed.customBlockedUrls)
        assertEquals(setOf("allowed.com"), parsed.customAllowedUrls)
    }

    @Test
    fun testSelfControlAllowed_deserializationEdgeCases() {
        val jsonExplicitFalse = """{"isSelfControlAllowed": false}"""
        assertFalse(DevicePolicyBundle.fromJson(jsonExplicitFalse).isSelfControlAllowed)

        val jsonExplicitTrue = """{"isSelfControlAllowed": true}"""
        assertTrue(DevicePolicyBundle.fromJson(jsonExplicitTrue).isSelfControlAllowed)

        val mapExplicitFalse = mapOf("isSelfControlAllowed" to false)
        assertFalse(DevicePolicyBundle.fromMap(mapExplicitFalse).isSelfControlAllowed)

        val mapExplicitTrue = mapOf("isSelfControlAllowed" to true)
        assertTrue(DevicePolicyBundle.fromMap(mapExplicitTrue).isSelfControlAllowed)
    }

    @Test
    fun testGuardianPolicyConstruction_ensuresSupervisionInvariants() {
        val baseDefault = DevicePolicyBundle.defaultPolicy()
        assertFalse(baseDefault.isSupervised)
        org.junit.Assert.assertNull(baseDefault.guardianUid)

        val myUid = "guardian_uid_123"
        val myName = "Guardian Device"
        val configuredPolicy = baseDefault.copy(
            guardianUid = baseDefault.guardianUid?.takeIf { it.isNotBlank() } ?: myUid,
            guardianName = baseDefault.guardianName?.takeIf { it.isNotBlank() } ?: myName,
            isSupervised = true
        )

        assertTrue(configuredPolicy.isSupervised)
        assertEquals("guardian_uid_123", configuredPolicy.guardianUid)
        assertEquals("Guardian Device", configuredPolicy.guardianName)

        val serializedMap = configuredPolicy.toMap()
        assertEquals(true, serializedMap["isSupervised"])
        assertEquals("guardian_uid_123", serializedMap["guardianUid"])
        assertEquals("Guardian Device", serializedMap["guardianName"])
    }
}
