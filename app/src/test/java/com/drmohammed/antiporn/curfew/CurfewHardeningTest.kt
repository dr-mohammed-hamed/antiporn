package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Regression tests for Thursday same-day curfew windows, partial-release package tracking,
 * and Nap veto invariants over curfew snooze requests.
 */
class CurfewHardeningTest {

    private fun createCalendar(hourOfDay: Int, minute: Int, dayOfWeek: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(CurfewConfig.DEFAULT_TIMEZONE_ID)).apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun testCurfewScheduleExt_thursdaySameDayWeekendEndTimeAndActiveEnd() {
        // Wednesday crosses midnight (20:00-07:00) AND Thursday has a same-day weekend window (14:00-17:00)
        val thursdayEndTimesWithWedSpill = CurfewScheduleExt.getCurfewEndTimesForDay(
            dayOfWeek = Calendar.THURSDAY,
            startHour = 20,
            startMinute = 0,
            endHour = 7,
            endMinute = 0,
            isWeekendCurfewEnabled = true,
            weekendStartHour = 14,
            weekendStartMinute = 0,
            weekendEndHour = 17,
            weekendEndMinute = 0
        )
        assertEquals(listOf(7 to 0, 17 to 0), thursdayEndTimesWithWedSpill)

        val thursdayAfternoon = createCalendar(15, 0, Calendar.THURSDAY)
        assertEquals(
            17 to 0,
            CurfewScheduleExt.resolveActiveCurfewEnd(
                calendar = thursdayAfternoon,
                startHour = 20,
                startMinute = 0,
                endHour = 7,
                endMinute = 0,
                isWeekendCurfewEnabled = true,
                weekendStartHour = 14,
                weekendStartMinute = 0,
                weekendEndHour = 17,
                weekendEndMinute = 0
            )
        )

        // Same-day weekday window (09:00-12:00) -> Thursday morning has no Wednesday spill, only weekend 17:00
        val thursdayEndTimesNoSpill = CurfewScheduleExt.getCurfewEndTimesForDay(
            dayOfWeek = Calendar.THURSDAY,
            startHour = 9,
            startMinute = 0,
            endHour = 12,
            endMinute = 0,
            isWeekendCurfewEnabled = true,
            weekendStartHour = 14,
            weekendStartMinute = 0,
            weekendEndHour = 17,
            weekendEndMinute = 0
        )
        assertEquals(listOf(17 to 0), thursdayEndTimesNoSpill)
    }

    @Test
    fun testCurfewVpnDnsBridge_localVpnAndStandaloneConsistency() {
        assertTrue(
            CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC,
                globalMode = "opportunistic",
                globalHost = null
            )
        )
        // Reject DoT hostname in Settings.Global even if DPM reports opportunistic (C3 split-brain fix)
        assertFalse(
            CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC,
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com"
            )
        )
        assertTrue(
            CurfewVpnDnsBridge.isStandaloneDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
                dpmHost = "family.cloudflare-dns.com",
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com",
                targetHost = "family.cloudflare-dns.com"
            )
        )
        assertFalse(
            CurfewVpnDnsBridge.isStandaloneDnsStateConsistent(
                dpmMode = android.app.admin.DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
                dpmHost = "dns.google",
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com",
                targetHost = "family.cloudflare-dns.com"
            )
        )
    }

    @Test
    fun testCurfewScheduleExt_thursdaySameDayWindowAndEqualBounds() {
        val thursdayMidday = createCalendar(15, 0, Calendar.THURSDAY)
        assertFalse(
            "Thursday daytime must not activate a same-day weekday window (14:00-16:00) before evening weekend start",
            CurfewEnforcer.isScheduleWindowActive(
                calendar = thursdayMidday,
                startHour = 14,
                startMinute = 0,
                endHour = 16,
                endMinute = 0,
                isWeekendCurfewEnabled = true,
                weekendStartHour = 21,
                weekendStartMinute = 0,
                weekendEndHour = 9,
                weekendEndMinute = 0
            )
        )
        assertFalse(
            "Degenerate equal start/end window (20:00-20:00) must evaluate to inactive",
            CurfewScheduleExt.isMinuteInWindow(minuteOfDay = 20 * 60, startMinute = 20 * 60, endMinute = 20 * 60)
        )
    }

    @Test
    fun testCurfewConfig_removeTrackedSuspendedPackagesPreservesFailedUnsuspends() {
        val prefs = InMemoryPrefs()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getApplicationContext(): Context = this
        }
        CurfewConfig.addTrackedSuspendedPackages(context, listOf("com.one", "com.two", "com.three"))
        CurfewConfig.removeTrackedSuspendedPackages(context, listOf("com.one", "com.three"))
        assertEquals(setOf("com.two"), CurfewConfig.getTrackedSuspendedPackages(context))
    }

    @Test
    fun testCurfewEnforcer_snoozeRejectedWhileNapIsActive() {
        val prefsMap = mutableMapOf<String, InMemoryPrefs>()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                prefsMap.getOrPut(name ?: "default") { InMemoryPrefs() }
            override fun getApplicationContext(): Context = this
        }
        val origElapsed = NapController.elapsedProvider
        val origWall = NapController.wallProvider
        val origBoot = NapController.bootCountProvider
        try {
            NapController.elapsedProvider = { 10_000L }
            NapController.wallProvider = { 1_000_000L }
            NapController.bootCountProvider = { 1 }

            NapController.startNap(context)
            assertTrue(NapController.isNapActive(context))
            assertFalse(
                "Curfew snooze must be rejected while Nap session is active",
                CurfewEnforcer.snoozeCurfew(context, minutes = 15)
            )
            val curfewPrefs = prefsMap[CurfewConfig.PREFS_NAME]
            assertEquals(
                "Snooze end timestamp must not be written when Nap vetoes snooze",
                0L,
                curfewPrefs?.getLong(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL, 0L) ?: 0L
            )
        } finally {
            NapController.elapsedProvider = origElapsed
            NapController.wallProvider = origWall
            NapController.bootCountProvider = origBoot
        }
    }

    @Test
    fun testDaytimeHighRiskRelease_respectsGuardianApprovalAndRejectsAutoDecisions() {
        val highRiskPkg = "org.telegram.messenger"

        // Guardian-approved high-risk app can be released during daytime unless custom-blacklisted
        assertTrue(CurfewLifecycleHandler.isApprovalEligibleForRelease(highRiskPkg, isApproved = true, decidedBy = "guardian_uid_123"))
        assertFalse(
            CurfewLifecycleHandler.isHighRiskOrBlacklistedForRelease(
                pkg = highRiskPkg,
                approvedPackages = setOf(highRiskPkg),
                customBlacklist = emptySet()
            )
        )

        // Approved high-risk apps (including baseline/auto-approved) are eligible for daytime release unless custom-blacklisted
        assertTrue(CurfewLifecycleHandler.isApprovalEligibleForRelease(highRiskPkg, isApproved = true, decidedBy = "guardian_uid_123"))
        assertTrue(CurfewLifecycleHandler.isApprovalEligibleForRelease(highRiskPkg, isApproved = true, decidedBy = "INITIAL_DEVICE_BASELINE"))
        assertTrue(CurfewLifecycleHandler.isApprovalEligibleForRelease(highRiskPkg, isApproved = true, decidedBy = "AUTO_APPROVED_UNLOCKED"))
        assertFalse(CurfewLifecycleHandler.isApprovalEligibleForRelease(highRiskPkg, isApproved = false, decidedBy = "guardian_uid_123"))

        // Non-high-risk approved apps remain eligible even with null decidedBy
        assertTrue(CurfewLifecycleHandler.isApprovalEligibleForRelease("com.whatsapp", isApproved = true, decidedBy = null))

        // Unapproved high-risk app or explicitly custom-blacklisted app stays suspended
        assertTrue(
            CurfewLifecycleHandler.isHighRiskOrBlacklistedForRelease(
                pkg = highRiskPkg,
                approvedPackages = emptySet(),
                customBlacklist = emptySet()
            )
        )
        assertTrue(
            CurfewLifecycleHandler.isHighRiskOrBlacklistedForRelease(
                pkg = highRiskPkg,
                approvedPackages = setOf(highRiskPkg),
                customBlacklist = setOf(highRiskPkg)
            )
        )
    }

    private class InMemoryPrefs : SharedPreferences {
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
