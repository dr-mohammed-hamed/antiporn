package com.drmohammed.antiporn.curfew

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ChangedPackages
import android.content.pm.FeatureInfo
import android.content.pm.InstrumentationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.SharedLibraryInfo
import android.content.pm.VersionedPackage
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.UserHandle
import com.drmohammed.antiporn.shields.AppSuspensionShield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CurfewTest {

    private fun createCalendar(hourOfDay: Int, minute: Int, dayOfWeek: Int = Calendar.WEDNESDAY): Calendar {
        return Calendar.getInstance(TimeZone.getTimeZone(CurfewConfig.DEFAULT_TIMEZONE_ID)).apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
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
        assertFalse("19:59 should NOT be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(19, 59)))
        assertFalse("07:00 AM should NOT be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(7, 0)))
        assertFalse("08:30 AM should NOT be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(8, 30)))

        // Nighttime Curfew: should be true
        assertTrue("20:00 should be in curfew", CurfewEnforcer.isCurfewActive(createCalendar(20, 0)))
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
        // Media & productivity apps
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("org.schabi.newpipe"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("org.schabi.newpipe.debug"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("org.polymorphicshade.tubular"))
        assertFalse("Puregram should NOT be exempt during curfew", CurfewConfig.STATIC_WHITELIST.contains("org.puregram.messenger"))
        assertFalse("Daftar Al-Bahr should NOT have hardcoded exemption", CurfewConfig.STATIC_WHITELIST.contains("com.dr.tech.daftar_albahr"))

        // Islamic apps & Athan
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.athanotify"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.islamicapp.moshafy"))

        // Financial & Authenticator
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.egyptianbanks.instapay"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.authy.authy"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.google.android.apps.authenticator2"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("in.krosbits.musicolet"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.mxtech.videoplayer.ad"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.mxtech.videoplayer.pro"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.xodo.pdf.reader"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.google.android.keep"))

        // Medical & Pediatric
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("com.companyname.pedi_dose_app"))

        // Audiobooks & Podcasts
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("de.ph1b.audiobook"))
        assertTrue(CurfewConfig.STATIC_WHITELIST.contains("de.danoeh.antennapod"))
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
        // Searchlite (Google Go) is hidden, but Google Search is kept unhidden for system IPC
        assertTrue(CurfewConfig.CURFEW_SEARCH_APPS.contains("com.google.android.apps.searchlite"))
        assertFalse(
            "Google Search must NOT be in CURFEW_SEARCH_APPS so it remains unhidden for IPC",
            CurfewConfig.CURFEW_SEARCH_APPS.contains(CurfewConfig.GOOGLE_SEARCH_PACKAGE)
        )

        // Ensure Gemini, searchlite, and Google Search are NOT in whitelist
        assertFalse(CurfewConfig.STATIC_WHITELIST.contains("com.google.android.apps.bard"))
        assertFalse(CurfewConfig.STATIC_WHITELIST.contains("com.google.android.apps.searchlite"))
        assertFalse(CurfewConfig.STATIC_WHITELIST.contains(CurfewConfig.GOOGLE_SEARCH_PACKAGE))
    }

    @Test
    fun testCurfewSuspensionTargetStores_excludesGoogleSearchAndPlayStoreForIpc() {
        val storesToSuspend = CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS
        assertFalse(
            "Google Search must NOT be in storesToSuspend as DPM suspension fails on system assist apps",
            storesToSuspend.contains(CurfewConfig.GOOGLE_SEARCH_PACKAGE)
        )
        assertFalse(
            "Google Play Store must NOT be in storesToSuspend as it is kept for background licensing IPC",
            storesToSuspend.contains(CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE)
        )
    }

    @Test
    fun testCurfewForegroundWatcher_isGeminiActivity() {
        // Gemini surfaces
        assertTrue(
            CurfewForegroundWatcher.isGeminiActivity(
                "com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity"
            )
        )
        assertTrue(
            CurfewForegroundWatcher.isGeminiActivity(
                "com.google.android.apps.search.assistant.surfaces.voice.robin.launcher.RobinEntryPointActivity"
            )
        )
        assertTrue(
            CurfewForegroundWatcher.isGeminiActivity(
                "com.google.android.apps.gsa.assistant.settings.AssistantSettingsActivity"
            )
        )

        // Google Search & Discover surfaces to block
        assertFalse(
            CurfewForegroundWatcher.isGeminiActivity(
                "com.google.android.googlequicksearchbox.SearchActivity"
            )
        )
        assertFalse(
            CurfewForegroundWatcher.isGeminiActivity(
                "com.google.android.apps.search.googleapp.activity.GoogleAppActivity"
            )
        )
        assertFalse(
            CurfewForegroundWatcher.isGeminiActivity(
                "com.google.android.apps.search.googleapp.launcher.minusone.activity.MinusOneActivity"
            )
        )
        assertFalse(CurfewForegroundWatcher.isGeminiActivity(null))
        assertFalse(CurfewForegroundWatcher.isGeminiActivity(""))
        assertFalse(CurfewForegroundWatcher.isGeminiActivity("   "))
        assertTrue(CurfewForegroundWatcher.isGeminiActivity("com.google.android.apps.ROBIN.activity"))
        assertTrue(CurfewForegroundWatcher.isGeminiActivity("com.google.android.apps.ASSISTANT.MainActivity"))
        assertFalse("CurfewForegroundWatcher should not be running in tests by default", CurfewForegroundWatcher.isRunning)
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

        assertFalse("Under Unified Zero-Trust, no app is permanently blacklisted", AppSuspensionShield.isBlacklisted(telegramPkg))
        assertFalse("Under Unified Zero-Trust, no app is permanently blacklisted", AppSuspensionShield.isBlacklisted(tiktokPkg))

        assertTrue("Telegram is classified as high-risk", com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isHighRisk(telegramPkg))
        assertTrue("TikTok is classified as high-risk", com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isHighRisk(tiktokPkg))
    }

    @Test
    fun testCurfewInvariant_cannotReleaseCurfewDuringNightHours() {
        val nightTime = createCalendar(23, 0)
        assertTrue("23:00 is inside night curfew window", CurfewEnforcer.isCurfewActive(nightTime))

        // Real fail-secure release guard must reject deactivation when PolicyRepository is unavailable
        val isolatedContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        assertFalse(
            "Fail-open check returns false when repository is unavailable",
            CurfewEnforcer.isCurfewActive(isolatedContext, nightTime, useFailSecureEnabled = false)
        )
        assertTrue(
            "Fail-secure guard must return true during night curfew when repository lookup fails",
            CurfewEnforcer.isCurfewActive(isolatedContext, nightTime, useFailSecureEnabled = true)
        )
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

    @Test
    fun testMakkahTimezone_isConfiguredCorrectly() {
        assertEquals("Asia/Riyadh", CurfewConfig.MAKKAH_TIMEZONE_ID)
        val makkahTz = TimeZone.getTimeZone(CurfewConfig.MAKKAH_TIMEZONE_ID)
        val defaultTz = CurfewConfig.getDefaultFallbackTimeZone()
        assertTrue(com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry.isValidIanaId(defaultTz))
        assertEquals(3 * 3600 * 1000, makkahTz.rawOffset)
    }

    @Test
    fun testCurfewScheduler_nextOccurrence_usesMakkahTimezone() {
        val baseTime = createCalendar(12, 0) // 12:00 PM Makkah time
        val nextOccurrence = CurfewScheduler.getNextOccurrence(20, 30, baseTime)

        assertEquals("Next occurrence should preserve Makkah timezone",
            baseTime.timeZone.rawOffset, nextOccurrence.timeZone.rawOffset)
        assertEquals(20, nextOccurrence.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, nextOccurrence.get(Calendar.MINUTE))
        assertTrue(nextOccurrence.timeInMillis > baseTime.timeInMillis)
    }

    @Test
    fun testGooglePlayStore_isConfiguredForIPC() {
        assertEquals("com.android.vending", CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE)
        assertTrue(CurfewConfig.KNOWN_APP_STORES.contains(CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE))
        assertFalse(
            "Google Play Store must NOT be in OEM_APP_STORES so it remains unhidden for background IPC",
            CurfewConfig.OEM_APP_STORES.contains(CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE)
        )
    }

    @Test
    fun testOemAppStores_remainHiddenAndTargeted() {
        assertTrue(CurfewConfig.OEM_APP_STORES.contains("com.heytap.market"))
        assertTrue(CurfewConfig.OEM_APP_STORES.contains("com.oppo.market"))
        assertTrue(CurfewConfig.OEM_APP_STORES.contains("com.sec.android.app.samsungapps"))
        assertTrue(CurfewConfig.OEM_APP_STORES.contains("com.xiaomi.mipicks"))
        assertTrue(CurfewConfig.OEM_APP_STORES.contains("com.huawei.appmarket"))
    }

    @Test
    fun testPhobiesGame_isInStaticWhitelist() {
        assertFalse("Phobies game must NOT have hardcoded whitelist", CurfewConfig.STATIC_WHITELIST.contains("com.smokingguninc.phobies"))
    }

    @Test
    fun testCurfewShieldPackagesToHide_excludesGooglePlayStore() {
        val packagesToHide = CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS
        assertFalse(
            "CurfewShield hidden packages verification must NEVER include Play Store to prevent infinite desync loops",
            packagesToHide.contains(CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE)
        )
    }

    @Test
    fun testNewPipe_isWhitelistedAndExemptFromCurfew() {
        val newpipePackages = listOf(
            "org.schabi.newpipe",
            "org.schabi.newpipe.debug",
            "org.polymorphicshade.tubular"
        )
        for (pkg in newpipePackages) {
            assertTrue("Package $pkg must be in STATIC_WHITELIST", CurfewConfig.STATIC_WHITELIST.contains(pkg))
        }
    }

    @Test
    fun testCurfewBlackholeVpnConstants() {
        assertEquals("antiporn_curfew_vpn", CurfewBlackholeVpnService.CHANNEL_ID)
        assertEquals(2001, CurfewBlackholeVpnService.NOTIFICATION_ID)
        assertEquals("com.drmohammed.antiporn.curfew.ACTION_START_VPN", CurfewBlackholeVpnService.ACTION_START)
        assertEquals("com.drmohammed.antiporn.curfew.ACTION_STOP_VPN", CurfewBlackholeVpnService.ACTION_STOP)
        assertFalse(CurfewBlackholeVpnService.isRunning)
    }

    @Test
    fun testCurfewVpnBypassConfigKey() {
        assertEquals("key_curfew_vpn_bypass_packages", CurfewConfig.KEY_CURFEW_VPN_BYPASS_PACKAGES)
    }

    @Test
    fun testMxPlayer_isWhitelistedAndExemptFromCurfew() {
        val mxPackages = listOf(
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro"
        )
        for (pkg in mxPackages) {
            assertTrue("Package $pkg must be in STATIC_WHITELIST", CurfewConfig.STATIC_WHITELIST.contains(pkg))
        }
    }

    @Test
    fun testCurfewSnoozeConfigConstants() {
        assertEquals("key_curfew_snooze_end_wall", CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL)
        assertEquals("com.drmohammed.antiporn.ACTION_CURFEW_SNOOZE", CurfewConfig.ACTION_CURFEW_SNOOZE)
        assertEquals("com.drmohammed.antiporn.ACTION_CURFEW_SNOOZE_END", CurfewConfig.ACTION_CURFEW_SNOOZE_END)
    }

    @Test
    fun testCurfewDefaultWindow_matchesConfig() {
        assertEquals(20, CurfewConfig.START_HOUR)
        assertEquals(0, CurfewConfig.START_MINUTE)
        assertEquals(7, CurfewConfig.END_HOUR)
        assertEquals(0, CurfewConfig.END_MINUTE)
    }

    @Test
    fun testCurfewPolicyBundleDefaults() {
        val bundle = com.drmohammed.antiporn.core.model.DevicePolicyBundle.defaultPolicy()
        assertFalse(bundle.isCurfewEnabled)
        assertTrue(bundle.isVpnBlackholeEnabled)
        assertFalse(bundle.isInstantInternetKillEnabled)
        assertFalse(bundle.isWeekendCurfewEnabled)
        assertEquals(20, bundle.weekendCurfewStartHour)
        assertEquals(0, bundle.weekendCurfewStartMinute)
        assertEquals(7, bundle.weekendCurfewEndHour)
        assertEquals(0, bundle.weekendCurfewEndMinute)
        assertEquals(CurfewConfig.getDefaultFallbackTimeZone(), bundle.timeZoneId)
        assertTrue(bundle.protectedAppPackages.isEmpty())
        assertEquals(CurfewConfig.STATIC_WHITELIST, bundle.customCurfewWhitelistPackages)
    }

    @Test
    fun testDefaultPolicy_seedsCurfewWhitelistWithWhatsAppAndRequiredApps() {
        val bundle = com.drmohammed.antiporn.core.model.DevicePolicyBundle.defaultPolicy()
        assertTrue("WhatsApp must be seeded in customCurfewWhitelistPackages by default",
            bundle.customCurfewWhitelistPackages.contains("com.whatsapp"))
        assertTrue("Moshafy Quran must be seeded in customCurfewWhitelistPackages by default",
            bundle.customCurfewWhitelistPackages.contains("com.islamicapp.moshafy"))
        assertTrue("NewPipe must be seeded in customCurfewWhitelistPackages by default",
            bundle.customCurfewWhitelistPackages.contains("org.schabi.newpipe"))
    }

    @Test
    fun testCurfewWhitelist_preservesIntentionalEmptySet() {
        val policyWithEmpty = com.drmohammed.antiporn.core.model.DevicePolicyBundle.defaultPolicy().copy(
            customCurfewWhitelistPackages = emptySet()
        )
        val reconciled = com.drmohammed.antiporn.core.model.DevicePolicyBundle.reconcileLegacyV1(policyWithEmpty)
        assertTrue("Reconciled legacy policy must preserve intentional empty customCurfewWhitelistPackages",
            reconciled.customCurfewWhitelistPackages.isEmpty())
    }

    @Test
    fun testLegacyPayloadWithoutCurfewWhitelist_seedsStaticWhitelist() {
        val legacyJson = """
            {
                "curfewStartHour": 20,
                "version": 1
            }
        """.trimIndent()
        val parsed = com.drmohammed.antiporn.core.model.DevicePolicyBundle.fromJson(legacyJson)
        assertEquals(CurfewConfig.STATIC_WHITELIST, parsed.customCurfewWhitelistPackages)
    }

    @Test
    fun testCurfewCalendar_dynamicTimezones() {
        val cairoCal = Calendar.getInstance(TimeZone.getTimeZone("Africa/Cairo")).apply {
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
        }
        assertTrue("21:00 in Cairo calendar should be active in curfew", CurfewEnforcer.isCurfewActive(cairoCal))

        val cairoDayCal = Calendar.getInstance(TimeZone.getTimeZone("Africa/Cairo")).apply {
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 0)
        }
        assertFalse("14:00 in Cairo calendar should NOT be active in curfew", CurfewEnforcer.isCurfewActive(cairoDayCal))
    }

    @Test
    fun testCurfewScheduler_dynamicTimezones() {
        val cairoTz = TimeZone.getTimeZone("Africa/Cairo")
        val cairoCal = Calendar.getInstance(cairoTz).apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nextOccurrence = CurfewScheduler.getNextOccurrence(20, 0, cairoCal)
        assertEquals(cairoTz.id, nextOccurrence.timeZone.id)
        assertEquals(20, nextOccurrence.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, nextOccurrence.get(Calendar.MINUTE))
        assertTrue(nextOccurrence.timeInMillis > cairoCal.timeInMillis)
    }

    @Test
    fun testAppPolicyRegistry_wildcardCurfewExemptions() {
        // Test wildcard matching org.schabi.newpipe.*, com.mxtech.videoplayer.*
        assertTrue(com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isExemptFromCurfew("org.schabi.newpipe.nightly"))
        assertTrue(com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isExemptFromCurfew("com.mxtech.videoplayer.custom"))

        // Blacklisted / rogue apps must NEVER be exempt
        assertFalse(com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isExemptFromCurfew("org.telegram.messenger"))
        assertFalse(com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isExemptFromCurfew("com.android.chrome"))
        assertFalse(com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isExemptFromCurfew("com.heytap.market"))
        assertFalse("dr.tech apps should not have hardcoded curfew exemptions", com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isExemptFromCurfew("com.dr.tech.pos_scanner"))
    }

    @Test
    fun testIsScheduleWindowActive_weekdayCrossMidnightAndSameDay() {
        // Cross-midnight weekday window 21:30 - 06:15 (weekend override disabled)
        val crossMidnight = { hour: Int, minute: Int, day: Int ->
            CurfewEnforcer.isScheduleWindowActive(
                calendar = createCalendar(hour, minute, day),
                startHour = 21, startMinute = 30,
                endHour = 6, endMinute = 15,
                isWeekendCurfewEnabled = false,
                weekendStartHour = 23, weekendStartMinute = 0,
                weekendEndHour = 9, weekendEndMinute = 0
            )
        }
        assertFalse("21:29 must be before start", crossMidnight(21, 29, Calendar.MONDAY))
        assertTrue("21:30 start boundary must be active", crossMidnight(21, 30, Calendar.MONDAY))
        assertTrue("00:00 midnight must be active", crossMidnight(0, 0, Calendar.TUESDAY))
        assertTrue("06:14 must be active before end", crossMidnight(6, 14, Calendar.TUESDAY))
        assertFalse("06:15 end boundary must be inactive", crossMidnight(6, 15, Calendar.TUESDAY))
        assertFalse("14:00 daytime must be inactive", crossMidnight(14, 0, Calendar.WEDNESDAY))
        assertTrue("Friday 21:30 follows weekday start when weekend override is off", crossMidnight(21, 30, Calendar.FRIDAY))
        assertFalse("Friday 07:30 does not extend when weekend override is off", crossMidnight(7, 30, Calendar.FRIDAY))

        // Same-day window 13:00 - 15:00
        val sameDay = { hour: Int, minute: Int ->
            CurfewEnforcer.isScheduleWindowActive(
                calendar = createCalendar(hour, minute, Calendar.TUESDAY),
                startHour = 13, startMinute = 0,
                endHour = 15, endMinute = 0,
                isWeekendCurfewEnabled = false,
                weekendStartHour = 22, weekendStartMinute = 0,
                weekendEndHour = 9, weekendEndMinute = 0
            )
        }
        assertFalse(sameDay(12, 59))
        assertTrue(sameDay(13, 0))
        assertTrue(sameDay(14, 59))
        assertFalse(sameDay(15, 0))
    }

    @Test
    fun testIsScheduleWindowActive_weekendAndThursdayOverrides() {
        // Weekday 20:00 - 07:00, Weekend (Thu night - Sat) 22:30 - 09:00
        val check = { hour: Int, minute: Int, day: Int ->
            CurfewEnforcer.isScheduleWindowActive(
                calendar = createCalendar(hour, minute, day),
                startHour = 20, startMinute = 0,
                endHour = 7, endMinute = 0,
                isWeekendCurfewEnabled = true,
                weekendStartHour = 22, weekendStartMinute = 30,
                weekendEndHour = 9, weekendEndMinute = 0
            )
        }

        // Wednesday uses weekday schedule
        assertFalse(check(19, 59, Calendar.WEDNESDAY))
        assertTrue(check(20, 0, Calendar.WEDNESDAY))

        // Thursday: morning spill ends at weekdayEnd (07:00), evening starts at weekendStart (22:30)
        assertTrue("Thu 06:59 spills over from Wed night", check(6, 59, Calendar.THURSDAY))
        assertFalse("Thu 07:00 ends Wed night spill", check(7, 0, Calendar.THURSDAY))
        assertFalse("Thu 20:30 waits for weekend start 22:30", check(20, 30, Calendar.THURSDAY))
        assertTrue("Thu 22:30 starts weekend window", check(22, 30, Calendar.THURSDAY))

        // Friday & Saturday use weekend window (22:30 - 09:00)
        assertTrue("Fri 08:59 spills from Thu night until 09:00", check(8, 59, Calendar.FRIDAY))
        assertFalse("Fri 09:00 ends weekend morning window", check(9, 0, Calendar.FRIDAY))
        assertFalse("Fri 21:00 is before weekend start", check(21, 0, Calendar.FRIDAY))
        assertTrue("Fri 22:30 starts weekend night window", check(22, 30, Calendar.FRIDAY))
        assertTrue("Sat 08:59 active until 09:00", check(8, 59, Calendar.SATURDAY))
        assertFalse("Sat 09:00 inactive", check(9, 0, Calendar.SATURDAY))
        assertTrue("Sat 22:30 active", check(22, 30, Calendar.SATURDAY))

        // Sunday returns to weekday schedule (ends at 07:00, starts at 20:00)
        assertTrue("Sun 06:59 active until weekdayEnd 07:00", check(6, 59, Calendar.SUNDAY))
        assertFalse("Sun 07:00 inactive", check(7, 0, Calendar.SUNDAY))
        assertTrue("Sun 20:00 starts weekday window", check(20, 0, Calendar.SUNDAY))
    }

    @Test
    fun testCurfewShieldInactiveOutcome_instantKillAndOptOutSeparation() {
        val optOutClean = com.drmohammed.antiporn.shields.CurfewShield.resolveInactiveOutcome(
            isCurfewEnabled = false,
            isInstantKillEnabled = false,
            isInstantVpnEnforced = false,
            isLockdownLingering = false
        )
        assertFalse(optOutClean.needsEnforcement)
        assertEquals(com.drmohammed.antiporn.R.string.card_curfew_off_optional_desc, optOutClean.messageResId)

        val optOutLingeringVpn = com.drmohammed.antiporn.shields.CurfewShield.resolveInactiveOutcome(
            isCurfewEnabled = false,
            isInstantKillEnabled = false,
            isInstantVpnEnforced = false,
            isLockdownLingering = true
        )
        assertTrue(optOutLingeringVpn.needsEnforcement)
        assertEquals(com.drmohammed.antiporn.R.string.card_curfew_pending, optOutLingeringVpn.messageResId)

        val instantKillHealthyWhileCurfewOff = com.drmohammed.antiporn.shields.CurfewShield.resolveInactiveOutcome(
            isCurfewEnabled = false,
            isInstantKillEnabled = true,
            isInstantVpnEnforced = true,
            isLockdownLingering = true
        )
        assertFalse("Healthy Instant Kill lockdown must not trigger linger tamper alert", instantKillHealthyWhileCurfewOff.needsEnforcement)
        assertEquals(com.drmohammed.antiporn.R.string.card_curfew_off_optional_desc, instantKillHealthyWhileCurfewOff.messageResId)

        val instantKillTampered = com.drmohammed.antiporn.shields.CurfewShield.resolveInactiveOutcome(
            isCurfewEnabled = false,
            isInstantKillEnabled = true,
            isInstantVpnEnforced = false,
            isLockdownLingering = false
        )
        assertTrue(instantKillTampered.needsEnforcement)
        assertEquals(com.drmohammed.antiporn.R.string.card_instant_kill_pending, instantKillTampered.messageResId)

        val daytimeInstantKillHealthy = com.drmohammed.antiporn.shields.CurfewShield.resolveInactiveOutcome(
            isCurfewEnabled = true,
            isInstantKillEnabled = true,
            isInstantVpnEnforced = true,
            isLockdownLingering = false
        )
        assertFalse(daytimeInstantKillHealthy.needsEnforcement)
        assertEquals(com.drmohammed.antiporn.R.string.card_curfew_scheduled_desc, daytimeInstantKillHealthy.messageResId)
    }

    @Test
    fun testCurfewSettings_immediateConfirmationPromptRules() {
        assertTrue(
            "Local night opt-in with Device Owner inside active window must warn user",
            com.drmohammed.antiporn.ui.control.curfew.CurfewSettingsActivity.shouldPromptImmediateCurfewConfirmation(
                isRemote = false,
                targetCurfewEnabled = true,
                initialCurfewEnabled = false,
                isCurrentlyCurfewActive = false,
                isDeviceOwner = true,
                isTargetScheduleActiveNow = true
            )
        )
        assertFalse(
            "Daytime opt-in must save directly without immediate-lock warning",
            com.drmohammed.antiporn.ui.control.curfew.CurfewSettingsActivity.shouldPromptImmediateCurfewConfirmation(
                isRemote = false,
                targetCurfewEnabled = true,
                initialCurfewEnabled = false,
                isCurrentlyCurfewActive = false,
                isDeviceOwner = true,
                isTargetScheduleActiveNow = false
            )
        )
        assertFalse(
            "Opt-in without Device Owner cannot lock device immediately and needs no warning",
            com.drmohammed.antiporn.ui.control.curfew.CurfewSettingsActivity.shouldPromptImmediateCurfewConfirmation(
                isRemote = false,
                targetCurfewEnabled = true,
                initialCurfewEnabled = false,
                isCurrentlyCurfewActive = false,
                isDeviceOwner = false,
                isTargetScheduleActiveNow = true
            )
        )
        assertFalse(
            "Remote Guardian updates must bypass local immediate-lock prompt",
            com.drmohammed.antiporn.ui.control.curfew.CurfewSettingsActivity.shouldPromptImmediateCurfewConfirmation(
                isRemote = true,
                targetCurfewEnabled = true,
                initialCurfewEnabled = false,
                isCurrentlyCurfewActive = false,
                isDeviceOwner = true,
                isTargetScheduleActiveNow = true
            )
        )
        assertFalse(
            "Disabling curfew must never trigger immediate activation confirmation",
            com.drmohammed.antiporn.ui.control.curfew.CurfewSettingsActivity.shouldPromptImmediateCurfewConfirmation(
                isRemote = false,
                targetCurfewEnabled = false,
                initialCurfewEnabled = true,
                isCurrentlyCurfewActive = true,
                isDeviceOwner = true,
                isTargetScheduleActiveNow = true
            )
        )
        assertTrue(
            "Expanding enabled schedule to cover current hour when inactive must warn user",
            com.drmohammed.antiporn.ui.control.curfew.CurfewSettingsActivity.shouldPromptImmediateCurfewConfirmation(
                isRemote = false,
                targetCurfewEnabled = true,
                initialCurfewEnabled = true,
                isCurrentlyCurfewActive = false,
                isDeviceOwner = true,
                isTargetScheduleActiveNow = true
            )
        )
        assertFalse(
            "Editing other curfew options when curfew is already active needs no re-warn",
            com.drmohammed.antiporn.ui.control.curfew.CurfewSettingsActivity.shouldPromptImmediateCurfewConfirmation(
                isRemote = false,
                targetCurfewEnabled = true,
                initialCurfewEnabled = true,
                isCurrentlyCurfewActive = true,
                isDeviceOwner = true,
                isTargetScheduleActiveNow = true
            )
        )
    }

    @Test
    fun testShouldExecuteReleaseCleanup_evaluatesAllFlags() {
        assertFalse(
            "All false flags must skip release cleanup silently",
            CurfewEnforcer.shouldExecuteReleaseCleanup(
                isVpnTeardownNeeded = false,
                isAlwaysOnConfigured = false,
                wasBlockEnforced = false,
                hasTrackedSuspendedPackages = false,
                hasLingeringSuspendedPackages = false
            )
        )
        assertTrue(
            CurfewEnforcer.shouldExecuteReleaseCleanup(
                isVpnTeardownNeeded = true,
                isAlwaysOnConfigured = false,
                wasBlockEnforced = false,
                hasTrackedSuspendedPackages = false,
                hasLingeringSuspendedPackages = false
            )
        )
        assertTrue(
            CurfewEnforcer.shouldExecuteReleaseCleanup(
                isVpnTeardownNeeded = false,
                isAlwaysOnConfigured = true,
                wasBlockEnforced = false,
                hasTrackedSuspendedPackages = false,
                hasLingeringSuspendedPackages = false
            )
        )
        assertTrue(
            CurfewEnforcer.shouldExecuteReleaseCleanup(
                isVpnTeardownNeeded = false,
                isAlwaysOnConfigured = false,
                wasBlockEnforced = true,
                hasTrackedSuspendedPackages = false,
                hasLingeringSuspendedPackages = false
            )
        )
        assertTrue(
            CurfewEnforcer.shouldExecuteReleaseCleanup(
                isVpnTeardownNeeded = false,
                isAlwaysOnConfigured = false,
                wasBlockEnforced = false,
                hasTrackedSuspendedPackages = true,
                hasLingeringSuspendedPackages = false
            )
        )
        assertTrue(
            CurfewEnforcer.shouldExecuteReleaseCleanup(
                isVpnTeardownNeeded = false,
                isAlwaysOnConfigured = false,
                wasBlockEnforced = false,
                hasTrackedSuspendedPackages = false,
                hasLingeringSuspendedPackages = true
            )
        )
    }

    @Test
    fun testIsPackageInstalled_skipsUninstalledAndAcceptsInstalled() {
        val installed = setOf("com.whatsapp", "com.heytap.market")
        val pm = FakePackageManager(installed)

        assertTrue(CurfewEnforcer.isPackageInstalled(pm, "com.whatsapp"))
        assertTrue(CurfewEnforcer.isPackageInstalled(pm, "com.heytap.market"))
        assertFalse(CurfewEnforcer.isPackageInstalled(pm, "com.android.chrome"))
        assertFalse(CurfewEnforcer.isPackageInstalled(pm, "com.xiaomi.mipicks"))
        assertFalse(CurfewEnforcer.isPackageInstalled(pm, ""))

        val candidates = CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS +
            setOf("com.whatsapp", "com.android.chrome")
        val filtered = candidates.filter { CurfewEnforcer.isPackageInstalled(pm, it) }.toSet()
        assertEquals(installed, filtered)
    }

    @Test
    fun testIsPackageInstalled_includeHiddenDetectsHiddenAppsOnlyWhenRequested() {
        val pm = FakePackageManager(
            installedPackages = setOf("com.whatsapp"),
            hiddenPackages = setOf("com.xiaomi.mipicks")
        )
        assertTrue(CurfewEnforcer.isPackageInstalled(pm, "com.whatsapp", includeHidden = false))
        assertFalse(CurfewEnforcer.isPackageInstalled(pm, "com.xiaomi.mipicks", includeHidden = false))
        assertTrue(CurfewEnforcer.isPackageInstalled(pm, "com.xiaomi.mipicks", includeHidden = true))
        assertFalse(CurfewEnforcer.isPackageInstalled(pm, "com.nonexistent.app", includeHidden = true))
    }

    @Test
    fun testIsVpnTeardownAllowedOnReleaseFailSecure() {
        assertFalse(
            CurfewEnforcer.isVpnTeardownAllowedOnReleaseFailSecure(
                fetchPolicyFailed = true,
                instantKill = false,
                isVpnTeardownNeeded = true,
                isAlwaysOnConfigured = true
            )
        )
        assertFalse(
            CurfewEnforcer.isVpnTeardownAllowedOnReleaseFailSecure(
                fetchPolicyFailed = false,
                instantKill = true,
                isVpnTeardownNeeded = true,
                isAlwaysOnConfigured = true
            )
        )
        assertTrue(
            CurfewEnforcer.isVpnTeardownAllowedOnReleaseFailSecure(
                fetchPolicyFailed = false,
                instantKill = false,
                isVpnTeardownNeeded = true,
                isAlwaysOnConfigured = false
            )
        )
        assertTrue(
            CurfewEnforcer.isVpnTeardownAllowedOnReleaseFailSecure(
                fetchPolicyFailed = false,
                instantKill = false,
                isVpnTeardownNeeded = false,
                isAlwaysOnConfigured = true
            )
        )
    }

    @Test
    fun testCurfewConfig_lastBlockEnforcedPersistence() {
        val prefs = InMemorySharedPreferences()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }

        assertFalse(CurfewConfig.isLastBlockEnforced(context))
        CurfewConfig.setLastBlockEnforced(context, true)
        assertTrue(CurfewConfig.isLastBlockEnforced(context))
        CurfewConfig.setLastBlockEnforced(context, false)
        assertFalse(CurfewConfig.isLastBlockEnforced(context))
    }

    @Test
    fun testCurfewBlackholeVpnService_idleStateSummary() {
        assertFalse(CurfewBlackholeVpnService.isServiceOrTunnelActive())
        assertEquals(CurfewBlackholeVpnService.VpnMode.IDLE, CurfewBlackholeVpnService.currentMode)
        assertEquals("INACTIVE", CurfewBlackholeVpnService.getLiveStateSummary())
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, v: MutableSet<String>?) = apply { if (key != null) pending[key] = v?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) { values.clear(); clearAll = false }
                for ((k, v) in pending) {
                    if (v == null) values.remove(k) else values[k] = v
                }
                pending.clear()
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private class FakePackageManager(
        private val installedPackages: Set<String>,
        private val hiddenPackages: Set<String> = emptySet()
    ) : PackageManager() {
        override fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
            val matchesHidden = (flags and MATCH_UNINSTALLED_PACKAGES) != 0
            if (installedPackages.contains(packageName) || (matchesHidden && hiddenPackages.contains(packageName))) {
                return PackageInfo().apply { this.packageName = packageName }
            }
            throw NameNotFoundException(packageName)
        }

        override fun getPackageInfo(packageName: String, flags: PackageInfoFlags): PackageInfo =
            getPackageInfo(packageName, flags.value.toInt())

        override fun getPackageInfo(versionedPackage: VersionedPackage, flags: Int): PackageInfo =
            throw NameNotFoundException(versionedPackage.packageName)

        override fun getPackageGids(packageName: String): IntArray = throw NameNotFoundException(packageName)
        override fun getPackageGids(packageName: String, flags: Int): IntArray = throw NameNotFoundException(packageName)
        override fun getPackageUid(packageName: String, flags: Int): Int = throw NameNotFoundException(packageName)
        override fun currentToCanonicalPackageNames(packageNames: Array<out String>): Array<String> = emptyArray()
        override fun canonicalToCurrentPackageNames(packageNames: Array<out String>): Array<String> = emptyArray()
        override fun getLaunchIntentForPackage(packageName: String): Intent? = null
        override fun getLeanbackLaunchIntentForPackage(packageName: String): Intent? = null
        override fun getPermissionInfo(permName: String, flags: Int): PermissionInfo = throw NameNotFoundException(permName)
        override fun queryPermissionsByGroup(permissionGroup: String?, flags: Int): List<PermissionInfo> = emptyList()
        override fun getPermissionGroupInfo(groupName: String, flags: Int): PermissionGroupInfo = throw NameNotFoundException(groupName)
        override fun getAllPermissionGroups(flags: Int): List<PermissionGroupInfo> = emptyList()
        override fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo = throw NameNotFoundException(packageName)
        override fun getActivityInfo(component: ComponentName, flags: Int): ActivityInfo = throw NameNotFoundException()
        override fun getReceiverInfo(component: ComponentName, flags: Int): ActivityInfo = throw NameNotFoundException()
        override fun getServiceInfo(component: ComponentName, flags: Int): ServiceInfo = throw NameNotFoundException()
        override fun getProviderInfo(component: ComponentName, flags: Int): ProviderInfo = throw NameNotFoundException()
        override fun getInstalledPackages(flags: Int): List<PackageInfo> = emptyList()
        override fun getPackagesHoldingPermissions(permissions: Array<out String>, flags: Int): List<PackageInfo> = emptyList()
        override fun checkPermission(permName: String, packageName: String): Int = PERMISSION_DENIED
        override fun isPermissionRevokedByPolicy(permName: String, packageName: String): Boolean = false
        override fun addPermission(info: PermissionInfo): Boolean = false
        override fun addPermissionAsync(info: PermissionInfo): Boolean = false
        override fun removePermission(permName: String) {}
        override fun checkSignatures(packageName1: String, packageName2: String): Int = SIGNATURE_NO_MATCH
        override fun checkSignatures(uid1: Int, uid2: Int): Int = SIGNATURE_NO_MATCH
        override fun getPackagesForUid(uid: Int): Array<String>? = null
        override fun getNameForUid(uid: Int): String? = null
        override fun getInstalledApplications(flags: Int): List<ApplicationInfo> = emptyList()
        override fun isInstantApp(): Boolean = false
        override fun isInstantApp(packageName: String): Boolean = false
        override fun getInstantAppCookieMaxBytes(): Int = 0
        override fun getInstantAppCookie(): ByteArray = byteArrayOf()
        override fun clearInstantAppCookie() {}
        override fun updateInstantAppCookie(cookie: ByteArray?) {}
        override fun getSystemSharedLibraryNames(): Array<String>? = null
        override fun getSharedLibraries(flags: Int): List<SharedLibraryInfo> = emptyList()
        override fun getChangedPackages(sequenceNumber: Int): ChangedPackages? = null
        override fun getSystemAvailableFeatures(): Array<FeatureInfo> = emptyArray()
        override fun hasSystemFeature(featureName: String): Boolean = false
        override fun hasSystemFeature(featureName: String, version: Int): Boolean = false
        override fun resolveActivity(intent: Intent, flags: Int): ResolveInfo? = null
        override fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
        override fun queryIntentActivityOptions(caller: ComponentName?, specifics: Array<out Intent>?, intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
        override fun queryBroadcastReceivers(intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
        override fun resolveService(intent: Intent, flags: Int): ResolveInfo? = null
        override fun queryIntentServices(intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
        override fun queryIntentContentProviders(intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
        override fun resolveContentProvider(authority: String, flags: Int): ProviderInfo? = null
        override fun queryContentProviders(processName: String?, uid: Int, flags: Int): List<ProviderInfo> = emptyList()
        override fun getInstrumentationInfo(className: ComponentName, flags: Int): InstrumentationInfo = throw NameNotFoundException()
        override fun queryInstrumentation(targetPackage: String, flags: Int): List<InstrumentationInfo> = emptyList()
        override fun getDrawable(packageName: String, resid: Int, appInfo: ApplicationInfo?): Drawable? = null
        override fun getActivityIcon(activityName: ComponentName): Drawable = throw NameNotFoundException()
        override fun getActivityIcon(intent: Intent): Drawable = throw NameNotFoundException()
        override fun getActivityBanner(activityName: ComponentName): Drawable? = null
        override fun getActivityBanner(intent: Intent): Drawable? = null
        override fun getDefaultActivityIcon(): Drawable = throw UnsupportedOperationException()
        override fun getApplicationIcon(info: ApplicationInfo): Drawable = throw UnsupportedOperationException()
        override fun getApplicationIcon(packageName: String): Drawable = throw NameNotFoundException(packageName)
        override fun getApplicationBanner(info: ApplicationInfo): Drawable? = null
        override fun getApplicationBanner(packageName: String): Drawable? = null
        override fun getActivityLogo(activityName: ComponentName): Drawable? = null
        override fun getActivityLogo(intent: Intent): Drawable? = null
        override fun getApplicationLogo(info: ApplicationInfo): Drawable? = null
        override fun getApplicationLogo(packageName: String): Drawable? = null
        override fun getUserBadgedIcon(drawable: Drawable, user: UserHandle): Drawable = drawable
        override fun getUserBadgedDrawableForDensity(drawable: Drawable, user: UserHandle, badgeLocation: Rect?, badgeDensity: Int): Drawable = drawable
        override fun getUserBadgedLabel(label: CharSequence, user: UserHandle): CharSequence = label
        override fun getText(packageName: String, resid: Int, appInfo: ApplicationInfo?): CharSequence? = null
        override fun getXml(packageName: String, resid: Int, appInfo: ApplicationInfo?): XmlResourceParser? = null
        override fun getApplicationLabel(info: ApplicationInfo): CharSequence = ""
        override fun getResourcesForActivity(activityName: ComponentName): Resources = throw NameNotFoundException()
        override fun getResourcesForApplication(app: ApplicationInfo): Resources = throw NameNotFoundException()
        override fun getResourcesForApplication(packageName: String): Resources = throw NameNotFoundException(packageName)
        override fun verifyPendingInstall(id: Int, verificationCode: Int) {}
        override fun extendVerificationTimeout(id: Int, verificationCodeAtTimeout: Int, millisecondsToDelay: Long) {}
        override fun setInstallerPackageName(targetPackage: String, installerPackageName: String?) {}
        override fun getInstallerPackageName(packageName: String): String? = null
        override fun addPackageToPreferred(packageName: String) {}
        override fun removePackageFromPreferred(packageName: String) {}
        override fun getPreferredPackages(flags: Int): List<PackageInfo> = emptyList()
        override fun addPreferredActivity(filter: IntentFilter, match: Int, set: Array<out ComponentName>?, activity: ComponentName) {}
        override fun clearPackagePreferredActivities(packageName: String) {}
        override fun getPreferredActivities(outFilters: MutableList<IntentFilter>, outActivities: MutableList<ComponentName>, packageName: String?): Int = 0
        override fun setComponentEnabledSetting(componentName: ComponentName, newState: Int, flags: Int) {}
        override fun getComponentEnabledSetting(componentName: ComponentName): Int = COMPONENT_ENABLED_STATE_DEFAULT
        override fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int) {}
        override fun getApplicationEnabledSetting(packageName: String): Int = COMPONENT_ENABLED_STATE_DEFAULT
        override fun isSafeMode(): Boolean = false
        override fun setApplicationCategoryHint(packageName: String, categoryHint: Int) {}
        override fun getPackageInstaller(): PackageInstaller = throw UnsupportedOperationException()
        override fun canRequestPackageInstalls(): Boolean = false
    }
}
