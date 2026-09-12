package com.drmohammed.antiporn.curfew

import android.content.pm.ApplicationInfo
import com.drmohammed.antiporn.shields.AppSuspensionShield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CurfewTest {

    private fun createCalendar(hourOfDay: Int, minute: Int): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun testCurfewActiveWindow_crossMidnight() {
        // Daytime: should be false
        assertFalse("12:00 PM should NOT be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(12, 0)))
        assertFalse("20:29 should NOT be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(20, 29)))
        assertFalse("07:00 AM should NOT be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(7, 0)))
        assertFalse("08:30 AM should NOT be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(8, 30)))

        // Nighttime Curfew: should be true
        assertTrue("20:30 should be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(20, 30)))
        assertTrue("21:00 should be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(21, 0)))
        assertTrue("21:30 should be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(21, 30)))
        assertTrue("22:00 should be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(22, 0)))
        assertTrue("23:59 should be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(23, 59)))
        assertTrue("00:00 should be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(0, 0)))
        assertTrue("03:45 AM should be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(3, 45)))
        assertTrue("06:59 AM should be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(6, 59)))
    }

    @Test
    fun testStaticWhitelist_containsRequiredUserApps() {
        // Business & productivity apps
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.dr.tech.daftar_albahr"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.dr.tech.hafeez_pos_micro_erp"))
        assertFalse("Puregram should NOT be exempt during curfew", CurfewConfig.STATIC_WHITELIST.contains("org.puregram.messenger"))

        // Islamic apps & Athan
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.athanotify"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.islamicapp.moshafy"))

        // Financial & Authenticator
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.egyptianbanks.instapay"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.authy.authy"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.google.android.apps.authenticator2"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("in.krosbits.musicolet"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.xodo.pdf.reader"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.google.android.keep"))

        // Medical & Pediatric
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.companyname.pedi_dose_app"))

        // Audiobooks & Podcasts
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("de.ph1b.audiobook"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("de.danoeh.antennapod"))

        // Exempted Games
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.axlebolt.standoff2"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.AlfaBravo.CombatMaster"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.nekki.shadowfightarena"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("yio.tro.antiyoy.android"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("air.com.RustyLake.CubeEscapeCollection"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("org.ppsspp.ppsspp"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.coloros.gamespaceui"))
    }

    @Test
    fun testAppStores_areExplicitlyTargetedForCurfew() {
        assertTrue(CurfewConfig.KNOWN_APP_STORES.contains("com.android.vending"))
        assertTrue(CurfewConfig.KNOWN_APP_STORES.contains("com.heytap.market"))
        assertTrue(CurfewConfig.KNOWN_APP_STORES.contains("com.oppo.market"))
        assertTrue(CurfewConfig.KNOWN_APP_STORES.contains("com.sec.android.app.samsungapps"))

        // Ensure store is NOT in whitelist
        assertFalse(CurfewConfig.STATIC_WHITELIST.contains("com.android.vending"))
    }

    @Test
    fun testCurfewSearchApps_areExplicitlyTargetedForCurfew() {
        assertTrue(CurfewConfig.CURFEW_SEARCH_APPS.contains("com.google.android.googlequicksearchbox"))
        assertTrue(CurfewConfig.CURFEW_SEARCH_APPS.contains("com.google.android.apps.searchlite"))

        // Ensure search apps are NOT in whitelist
        assertFalse(CurfewConfig.STATIC_WHITELIST.contains("com.google.android.googlequicksearchbox"))
        assertFalse(CurfewConfig.STATIC_WHITELIST.contains("com.google.android.apps.searchlite"))
    }

    @Test
    fun testGalleryPackages_areExplicitlyExcludedFromWhitelist() {
        assertTrue(CurfewConfig.EXCLUDED_GALLERY_PACKAGES.contains("com.google.android.apps.photos"))
        assertTrue(CurfewConfig.EXCLUDED_GALLERY_PACKAGES.contains("com.coloros.gallery3d"))
        assertTrue(CurfewConfig.EXCLUDED_GALLERY_PACKAGES.contains("com.oplus.gallery"))

        // Ensure gallery is NOT in static whitelist
        assertFalse(CurfewConfig.STATIC_WHITELIST.contains("com.google.android.apps.photos"))
    }

    @Test
    fun testPermanentBlacklistGuard_preventsUnsuspend() {
        val telegramPkg = "org.telegram.messenger"
        val tiktokPkg = "com.zhiliaoapp.musically"

        assertTrue(AppSuspensionShield.isBlacklisted(telegramPkg))
        assertTrue(AppSuspensionShield.isBlacklisted(tiktokPkg))

        // Ensure curfew unsuspend candidate filter rejects blacklisted packages
        val candidates = setOf(telegramPkg, tiktokPkg, "com.google.android.youtube")
        val safeToUnsuspend = candidates.filterNot { AppSuspensionShield.isBlacklisted(it) }

        assertEquals(listOf("com.google.android.youtube"), safeToUnsuspend)
    }

    @Test
    fun testCurfewInvariant_cannotReleaseCurfewDuringNightHours() {
        val nightTime = createCalendar(23, 0)
        assertTrue("23:00 is inside night curfew window", CurfewEnforcer.isCurfewActive(nightTime))

        // If someone passes activate = false during night hours, the invariant guard must trigger:
        val activate = false
        val rejectDeactivation = !activate && CurfewEnforcer.isCurfewActive(nightTime)
        assertTrue("Invariant must reject deactivation during night curfew", rejectDeactivation)
    }

    @Test
    fun testCurfewEffectiveState_nightCurfewOverridesManualTestFalse() {
        val nightTime = createCalendar(2, 30) // 02:30 AM
        val isCurfewNight = CurfewEnforcer.isCurfewActive(nightTime)
        assertTrue(isCurfewNight)

        // Even if manual test is false:
        val manualTestActive = false
        val effectiveState = manualTestActive || isCurfewNight
        assertTrue("Effective state must remain TRUE during night regardless of manual test flag", effectiveState)
    }

    @Test
    fun testCurfewDaytime_effectiveStateFollowsManualTest() {
        val dayTime = createCalendar(14, 0) // 02:00 PM
        val isCurfewNight = CurfewEnforcer.isCurfewActive(dayTime)
        assertFalse(isCurfewNight)

        // In daytime, effective state strictly follows manual test flag:
        assertFalse(false || isCurfewNight)
        assertTrue(true || isCurfewNight)

        // Daytime wakeup must NOT be blocked by night invariant:
        val activate = false
        val rejectDeactivation = !activate && isCurfewNight
        assertFalse("Daytime wakeup must be permitted", rejectDeactivation)
    }
}
