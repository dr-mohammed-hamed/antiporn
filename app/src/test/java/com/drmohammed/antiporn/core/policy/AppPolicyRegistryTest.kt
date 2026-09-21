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
    fun testSelfProtectionAndPuregram_areAlwaysImmune() {
        assertTrue(AppPolicyRegistry.isImmune("com.drmohammed.antiporn"))
        assertTrue(AppPolicyRegistry.isImmune("org.puregram.messenger"))
        assertFalse(AppPolicyRegistry.isPermanentlyBlacklisted("org.puregram.messenger"))
        assertFalse("Puregram is not exempt from curfew", AppPolicyRegistry.isExemptFromCurfew("org.puregram.messenger"))
        assertTrue(AppPolicyRegistry.isExemptFromCurfew("com.drmohammed.antiporn"))
    }

    @Test
    fun testPermanentBlacklist_strictHierarchy() {
        // Telegram
        assertTrue(AppPolicyRegistry.isPermanentlyBlacklisted("org.telegram.messenger"))
        assertFalse(AppPolicyRegistry.isExemptFromCurfew("org.telegram.messenger"))

        // TikTok
        assertTrue(AppPolicyRegistry.isPermanentlyBlacklisted("com.zhiliaoapp.musically"))
        assertFalse(AppPolicyRegistry.isExemptFromCurfew("com.zhiliaoapp.musically"))

        // Proxy Browsers
        assertTrue(AppPolicyRegistry.isPermanentlyBlacklisted("org.torproject.torbrowser"))
        assertTrue(AppPolicyRegistry.isPermanentlyBlacklisted("org.mozilla.firefox"))
        assertTrue(AppPolicyRegistry.isPermanentlyBlacklisted("com.duckduckgo.mobile.android"))

        // Random Chat
        assertTrue(AppPolicyRegistry.isPermanentlyBlacklisted("chat.ometv"))
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
    fun testBusinessWildcard_matchesAllDrTechApps() {
        val daftar = "com.dr.tech.daftar_albahr"
        val hafeez = "com.dr.tech.hafeez_pos_micro_erp"
        val newApp = "com.dr.tech.future_micro_pos"

        assertTrue(AppPolicyRegistry.isExemptFromCurfew(daftar))
        assertTrue(AppPolicyRegistry.isExemptFromCurfew(hafeez))
        assertTrue("Any future com.dr.tech.* app must be auto-exempted via wildcard", AppPolicyRegistry.isExemptFromCurfew(newApp))
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
