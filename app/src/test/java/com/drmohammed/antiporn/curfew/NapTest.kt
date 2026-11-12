package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
        assertEquals("key_nap_boot_count", NapConfig.KEY_NAP_BOOT_COUNT)
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
        assertTrue("21:00 is inside night curfew 20:00-07:00", nightActiveAt21)

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

    @Test
    fun bootCountChangeDetectedEvenAfterUptimeExceedsPreRebootStartElapsed() {
        val prefs = InMemoryNapPrefs()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getApplicationContext(): Context = this
        }
        val origElapsed = NapController.elapsedProvider
        val origWall = NapController.wallProvider
        val origBoot = NapController.bootCountProvider
        try {
            var fakeElapsed = 10_000L
            var fakeWall = 1_000_000L
            var fakeBoot = 5
            NapController.elapsedProvider = { fakeElapsed }
            NapController.wallProvider = { fakeWall }
            NapController.bootCountProvider = { fakeBoot }

            NapController.startNap(context)
            assertTrue(NapController.isNapActive(context))

            // Simulate reboot where device stays up long enough that elapsedRealtime exceeds old startElapsed,
            // AND wall clock moved past the 60-minute expiry window.
            fakeBoot = 6
            fakeElapsed = 20_000L
            fakeWall = 1_000_000L + NapConfig.NAP_DURATION_MS + 5_000L

            assertFalse(
                "BOOT_COUNT increment must detect reboot and expire nap when wall clock passed endWall",
                NapController.isNapActive(context)
            )
            assertEquals(0L, NapController.remainingMs(context))
        } finally {
            NapController.elapsedProvider = origElapsed
            NapController.wallProvider = origWall
            NapController.bootCountProvider = origBoot
        }
    }

    private class InMemoryNapPrefs : SharedPreferences {
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
}
