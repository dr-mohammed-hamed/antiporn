package com.drmohammed.antiporn.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppPolicyRegistryTest {

    @Before
    fun setUp() {
        AppPolicyRegistry.clearCache()
    }

    @Test
    fun testSelfProtectionAndPuregram_immunityCheck() {
        assertTrue(AppPolicyRegistry.isImmune("com.drmohammed.antiporn"))
        assertTrue(AppPolicyRegistry.isExemptFromCurfew("com.drmohammed.antiporn"))

        // Puregram must NOT be immune or permanently exempt (closed loophole)
        assertFalse(AppPolicyRegistry.isImmune("org.puregram.messenger"))
        assertFalse(AppPolicyRegistry.isPermanentlyBlacklisted("org.puregram.messenger"))
        assertFalse("Puregram is not exempt from curfew", AppPolicyRegistry.isExemptFromCurfew("org.puregram.messenger"))
    }

    @Test
    fun testPermanentBlacklist_strictHierarchy() {
        val highRiskPackages = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.zhiliaoapp.musically",
            "org.torproject.torbrowser",
            "org.mozilla.firefox",
            "com.duckduckgo.mobile.android",
            "chat.ometv"
        )

        for (pkg in highRiskPackages) {
            assertTrue("Expected isHighRisk to be true for $pkg", AppPolicyRegistry.isHighRisk(pkg))
            assertTrue("Expected isPermanentlyBlacklisted to be true for $pkg", AppPolicyRegistry.isPermanentlyBlacklisted(pkg))
            assertFalse("Expected isExemptFromCurfew to be false for $pkg", AppPolicyRegistry.isExemptFromCurfew(pkg))
        }

        assertTrue("Blacklisted packages must not be empty", AppPolicyRegistry.getBlacklistedPackages().isNotEmpty())
        assertTrue("Blacklisted packages must contain Telegram", AppPolicyRegistry.getBlacklistedPackages().contains("org.telegram.messenger"))
    }

    @Test
    fun testHighRiskAppClassification() {
        val highRiskPackages = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.zhiliaoapp.musically",
            "org.torproject.torbrowser",
            "org.mozilla.firefox",
            "com.duckduckgo.mobile.android",
            "chat.ometv"
        )

        for (pkg in highRiskPackages) {
            assertTrue("Expected isHighRisk to be true for $pkg", AppPolicyRegistry.isHighRisk(pkg))
        }

        // Immune apps must not be high-risk
        assertFalse("Self-protection must not be high-risk", AppPolicyRegistry.isHighRisk("com.drmohammed.antiporn"))
        assertFalse("Puregram must not be high-risk", AppPolicyRegistry.isHighRisk("org.puregram.messenger"))

        // Regular non-blacklisted apps must not be high-risk
        assertFalse("Regular calculator app must not be high-risk", AppPolicyRegistry.isHighRisk("com.google.android.calculator"))
    }

    @Test
    fun testNewPipeWildcard_matchesOfficialDebugAndForks() {
        val official = "org.schabi.newpipe"
        val debug = "org.schabi.newpipe.debug"
        val nightly = "org.schabi.newpipe.nightly"
        val tubular = "org.polymorphicshade.tubular"

        assertTrue("Official NewPipe must be exempt", AppPolicyRegistry.isExemptFromCurfew(official))
        assertTrue("Debug NewPipe must be exempt via wildcard", AppPolicyRegistry.isExemptFromCurfew(debug))
        assertTrue("Nightly NewPipe must be exempt via wildcard", AppPolicyRegistry.isExemptFromCurfew(nightly))
        assertTrue("Tubular must be exempt", AppPolicyRegistry.isExemptFromCurfew(tubular))

        assertFalse(AppPolicyRegistry.isPermanentlyBlacklisted(debug))
        assertEquals(AppClassification.CURFEW_EXEMPT_MEDIA, AppPolicyRegistry.classify(debug))
    }

    @Test
    fun testBusinessWildcard_drTechAppsNotHardcodedExempt() {
        val daftar = "com.dr.tech.daftar_albahr"
        val hafeez = "com.dr.tech.hafeez_pos_micro_erp"
        val newApp = "com.dr.tech.future_micro_pos"

        assertFalse("dr.tech apps must not have hardcoded curfew exemptions", AppPolicyRegistry.isExemptFromCurfew(daftar))
        assertFalse("dr.tech apps must not have hardcoded curfew exemptions", AppPolicyRegistry.isExemptFromCurfew(hafeez))
        assertFalse("dr.tech apps must not have hardcoded curfew exemptions", AppPolicyRegistry.isExemptFromCurfew(newApp))
    }

    @Test
    fun testRestrictedBrowsers() {
        assertTrue(AppPolicyRegistry.isRestrictedBrowser("com.android.chrome"))
        assertTrue(AppPolicyRegistry.isRestrictedBrowser("com.brave.browser"))
        assertTrue(AppPolicyRegistry.isRestrictedBrowser("com.microsoft.emmx"))
        assertFalse(AppPolicyRegistry.isRestrictedBrowser("org.mozilla.firefox")) // Firefox is blacklisted, not restricted
    }

    @Test
    fun testAppStores_andSearchLoophole() {
        assertTrue(AppPolicyRegistry.isOemAppStore("com.heytap.market"))
        assertTrue(AppPolicyRegistry.isAppStore("com.android.vending"))
        assertTrue(AppPolicyRegistry.isSearchLoophole("com.google.android.apps.searchlite"))
    }

    @Test
    fun testGemini_isBlockedDuringCurfew() {
        val geminiPkg = "com.google.android.apps.bard"
        assertEquals(AppClassification.DEFAULT_CURFEW_TARGET, AppPolicyRegistry.classify(geminiPkg))
        assertFalse("Gemini must NOT be exempt from night curfew", AppPolicyRegistry.isExemptFromCurfew(geminiPkg))
        assertFalse("Gemini must NOT be in static whitelist", AppPolicyRegistry.getCurfewStaticWhitelist().contains(geminiPkg))
    }

    @Test
    fun testTickTick_isExemptFromNightCurfew() {
        val official = "com.ticktick.task"
        val variant = "com.ticktick.task.lite"

        assertEquals(AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, AppPolicyRegistry.classify(official))
        assertTrue("Official TickTick must be exempt from curfew", AppPolicyRegistry.isExemptFromCurfew(official))
        assertTrue("Official TickTick must be in curfew static whitelist", AppPolicyRegistry.getCurfewStaticWhitelist().contains(official))
        assertFalse("TickTick must NOT be permanently blacklisted", AppPolicyRegistry.isPermanentlyBlacklisted(official))

        assertEquals(AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, AppPolicyRegistry.classify(variant))
        assertTrue("TickTick variants must be exempt via wildcard", AppPolicyRegistry.isExemptFromCurfew(variant))
    }

    @Test
    fun testMxPlayer_isExemptFromNightCurfew() {
        val free = "com.mxtech.videoplayer.ad"
        val pro = "com.mxtech.videoplayer.pro"
        val beta = "com.mxtech.videoplayer.beta"

        // Free version
        assertEquals(AppClassification.CURFEW_EXEMPT_MEDIA, AppPolicyRegistry.classify(free))
        assertTrue("MX Player Free must be exempt from curfew", AppPolicyRegistry.isExemptFromCurfew(free))
        assertTrue("MX Player Free must be in curfew static whitelist", AppPolicyRegistry.getCurfewStaticWhitelist().contains(free))
        assertFalse("MX Player must NOT be permanently blacklisted", AppPolicyRegistry.isPermanentlyBlacklisted(free))

        // Pro version
        assertEquals(AppClassification.CURFEW_EXEMPT_MEDIA, AppPolicyRegistry.classify(pro))
        assertTrue("MX Player Pro must be exempt from curfew", AppPolicyRegistry.isExemptFromCurfew(pro))
        assertTrue("MX Player Pro must be in curfew static whitelist", AppPolicyRegistry.getCurfewStaticWhitelist().contains(pro))
        assertFalse("MX Player Pro must NOT be permanently blacklisted", AppPolicyRegistry.isPermanentlyBlacklisted(pro))

        // Beta / wildcard variant
        assertEquals(AppClassification.CURFEW_EXEMPT_MEDIA, AppPolicyRegistry.classify(beta))
        assertTrue("MX Player Beta must be exempt via wildcard", AppPolicyRegistry.isExemptFromCurfew(beta))
        assertFalse("MX Player Beta must NOT be permanently blacklisted", AppPolicyRegistry.isPermanentlyBlacklisted(beta))
    }
}
