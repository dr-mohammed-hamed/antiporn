package com.drmohammed.antiporn.curfew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Calendar
import java.util.TimeZone

class NapTest {

    private fun napCalendar(hourOfDay: Int, minute: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(CurfewConfig.DEFAULT_TIMEZONE_ID)).apply {
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun napConfig_constants() {
        assertEquals("antiporn_nap_prefs", NapConfig.PREFS_NAME)
        assertEquals("key_nap_start_elapsed", NapConfig.KEY_NAP_START_ELAPSED)
        assertEquals("key_nap_end_elapsed", NapConfig.KEY_NAP_END_ELAPSED)
        assertEquals("key_nap_end_wall_ms", NapConfig.KEY_NAP_END_WALL_MS)
        assertEquals("key_nap_active", NapConfig.KEY_NAP_ACTIVE)
        assertEquals(3_600_000L, NapConfig.NAP_DURATION_MS)
        assertEquals(3_600_000L, NapConfig.EXTEND_STEP_MS)
        assertEquals("com.drmohammed.antiporn.ACTION_NAP_END", NapConfig.ACTION_NAP_END)
        assertEquals(3003, NapConfig.REQUEST_CODE_NAP_END)
        assertEquals(3003, NapConfig.REQUEST_CODE_NAP_END_RTC)
        assertEquals(3004, NapConfig.REQUEST_CODE_NAP_END_ELAPSED)
    }

    @Test
    fun start_setsExactly60min() {
        val elapsedNow = 1_000_000L
        val wallNow = 2_000_000L
        val (endElapsed, endWall) = NapController.computeStartEnds(elapsedNow, wallNow)
        assertEquals(NapConfig.NAP_DURATION_MS, endElapsed - elapsedNow)
        assertEquals(NapConfig.NAP_DURATION_MS, endWall - wallNow)

        val resolved = NapController.resolveStartEnds(0L, 0L, false, elapsedNow, wallNow)
        assertEquals(endElapsed, resolved.first)
        assertEquals(endWall, resolved.second)
    }

    @Test
    fun start_whenActive_returnsCurrentWithoutReset() {
        val (freshEndElapsed, freshEndWall) = NapController.computeStartEnds(10_000L, 20_000L)
        val resolved = NapController.resolveStartEnds(freshEndElapsed, freshEndWall, true, 11_000L, 21_000L)
        assertEquals(freshEndElapsed, resolved.first)
        assertEquals(freshEndWall, resolved.second)
    }

    @Test
    fun extend_adds60minUnlimited_twiceEqualsPlus120() {
        val (base0Elapsed, base0Wall) = NapController.computeStartEnds(0L, 0L)
        val (base1Elapsed, base1Wall) = NapController.computeExtendedEnds(base0Elapsed, base0Wall)
        assertEquals(NapConfig.EXTEND_STEP_MS, base1Elapsed - base0Elapsed)
        assertEquals(NapConfig.EXTEND_STEP_MS, base1Wall - base0Wall)

        val (base2Elapsed, base2Wall) = NapController.computeExtendedEnds(base1Elapsed, base1Wall)
        assertEquals(2L * NapConfig.EXTEND_STEP_MS, base2Elapsed - base0Elapsed)
        assertEquals(2L * NapConfig.EXTEND_STEP_MS, base2Wall - base0Wall)

        val resolved = NapController.resolveExtendedEnds(base0Elapsed, base0Wall, true, 1_000L, 1_000L)
        assertEquals(base0Elapsed + NapConfig.EXTEND_STEP_MS, resolved.first)
        assertEquals(base0Wall + NapConfig.EXTEND_STEP_MS, resolved.second)
    }

    @Test
    fun extend_whenInactive_startsFresh() {
        val resolved = NapController.resolveExtendedEnds(5_000L, 6_000L, false, 100_000L, 200_000L)
        assertEquals(100_000L + NapConfig.NAP_DURATION_MS, resolved.first)
        assertEquals(200_000L + NapConfig.NAP_DURATION_MS, resolved.second)
    }

    @Test
    fun overlap_napExpired_nightActive_effectiveBlockStaysTrue() {
        val napActiveAt21 = NapController.computeActive(
            elapsed = 21_000L, endElapsed = 21_000L,
            wall = 31_000L, endWall = 31_000L, flagActive = true
        )
        assertFalse("Nap itself must be expired at its end", napActiveAt21)

        val nightActiveAt21 = CurfewEnforcer.isCurfewActive(napCalendar(21, 0))
        assertTrue("21:00 is inside night curfew 20:30-07:00", nightActiveAt21)

        val effectiveBlock = napActiveAt21 || nightActiveAt21
        assertTrue("Effective block must stay locked across nap/night overlap", effectiveBlock)
    }

    @Test
    fun reboot_elapsedReset_wallFallback_staysActive() {
        assertTrue(
            "Wall clock must keep nap alive when elapsed data is unusable after reboot",
            NapController.computeActive(0L, 0L, 1_000L, 3_600_000L, true)
        )
        assertTrue(
            "Wall clock must keep nap alive when elapsed expired but wall still inside window",
            NapController.computeActive(5_000_000L, 3_600_000L, 1_000_000L, 7_200_000L, true)
        )
        assertTrue(
            "Elapsed clock alone must keep nap alive when wall was tampered backwards",
            NapController.computeActive(1_000L, 3_600_000L, 9_000_000L, 7_200_000L, true)
        )
    }

    @Test
    fun expiry_bothClocksExpired_returnsFalseAndAutoClears() {
        assertFalse(
            NapController.computeActive(5_000_000L, 3_600_000L, 8_000_000L, 7_200_000L, true)
        )
        assertFalse(
            NapController.computeActive(1_000L, 3_600_000L, 1_000L, 7_200_000L, false)
        )
        assertTrue(
            NapController.shouldAutoClear(5_000_000L, 3_600_000L, 8_000_000L, 7_200_000L, true)
        )
        assertFalse(
            NapController.shouldAutoClear(1_000L, 3_600_000L, 1_000L, 7_200_000L, true)
        )
        assertEquals(0L, NapController.computeRemaining(5_000_000L, 3_600_000L, 8_000_000L, 7_200_000L, true))
        assertEquals(0L, NapController.computeRemaining(1_000L, 3_600_000L, 1_000L, 7_200_000L, false))
    }

    @Test
    fun remaining_returnsMaxOfBothClocks() {
        assertEquals(
            5_000L,
            NapController.computeRemaining(1_000L, 2_000L, 1_000L, 6_000L, true)
        )
        assertEquals(
            4_000L,
            NapController.computeRemaining(1_000L, 5_000L, 1_000L, 2_000L, true)
        )
    }

    @Test
    fun daytime_expiry_releases() {
        val nightAt14 = CurfewEnforcer.isCurfewActive(napCalendar(14, 0))
        assertFalse("14:00 must be outside night curfew", nightAt14)
        val napExpired = NapController.computeActive(5_000L, 5_000L, 6_000L, 6_000L, true)
        assertFalse(napExpired)
        assertFalse("Daytime with expired nap must release", napExpired || nightAt14)

        val napActiveDaytime = NapController.computeActive(1_000L, 3_600_000L, 1_000L, 3_600_000L, true)
        assertTrue("Active nap must lock even in daytime", napActiveDaytime || nightAt14)
    }

    @Test
    fun noReduceApiExists() {
        val publicNames = NapController::class.java.methods.map { it.name.lowercase() }
        assertTrue(publicNames.contains("startnap"))
        assertTrue(publicNames.contains("extendnap"))
        assertTrue(publicNames.contains("isnapactive"))
        assertTrue(publicNames.contains("remainingms"))
        assertTrue(publicNames.contains("getendelapsed"))
        assertTrue(publicNames.contains("getendwall"))

        for (name in publicNames) {
            assertFalse("Forbidden reduce API must not exist: $name", "cancelnap" in name)
            assertFalse("Forbidden shorten API must not exist: $name", "shorten" in name)
            assertFalse("Forbidden set-end API must not exist: $name", "setend" in name)
            assertFalse("clearNap must stay private: $name", name == "clearnap")
        }

        val declaredClear = NapController::class.java.declaredMethods.firstOrNull { it.name == "clearNap" }
        assertTrue("Private clearNap must exist for expiry cleanup", declaredClear != null)
        assertTrue("clearNap must be private", Modifier.isPrivate(declaredClear!!.modifiers))
    }
}
