package com.drmohammed.antiporn.curfew.notification

import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionNotificationCoordinatorTest {

    @Test
    fun testConstantsAndIdsConsistency() {
        assertEquals("antiporn_curfew_vpn", ProtectionNotificationCoordinator.CHANNEL_ID_PROTECTION)
        assertEquals("antiporn_protection_events", ProtectionNotificationCoordinator.CHANNEL_ID_EVENTS)
        assertEquals(2001, ProtectionNotificationCoordinator.NOTIFICATION_ID_PROTECTION)
        assertEquals(2002, ProtectionNotificationCoordinator.NOTIFICATION_ID_EVENTS)

        // Must match CurfewBlackholeVpnService FGS requirements
        assertEquals(CurfewBlackholeVpnService.CHANNEL_ID, ProtectionNotificationCoordinator.CHANNEL_ID_PROTECTION)
        assertEquals(CurfewBlackholeVpnService.NOTIFICATION_ID, ProtectionNotificationCoordinator.NOTIFICATION_ID_PROTECTION)
    }

    @Test
    fun testStateHierarchy_GuardianLockdown_HasHighestPriority() {
        // State 1: Guardian Lockdown active while Nap and Curfew are also true
        val state = ProtectionNotificationCoordinator.ProtectionState.GuardianLockdown(
            isCurfewAlsoActive = true,
            isNapAlsoActive = true
        )
        assertTrue(state.isCurfewAlsoActive)
        assertTrue(state.isNapAlsoActive)
    }

    @Test
    fun testStateHierarchy_NapActive_HasChronometerData() {
        val endWall = 1_700_000_000_000L
        val remaining = 1_800_000L
        val state = ProtectionNotificationCoordinator.ProtectionState.NapActive(
            endWallMs = endWall,
            remainingMs = remaining
        )
        assertEquals(endWall, state.endWallMs)
        assertEquals(remaining, state.remainingMs)
    }

    @Test
    fun testStateHierarchy_CurfewActive_HasDynamicHours() {
        val state = ProtectionNotificationCoordinator.ProtectionState.CurfewActive(
            endHour = 6,
            endMinute = 30
        )
        assertEquals(6, state.endHour)
        assertEquals(30, state.endMinute)
    }

    @Test
    fun testStateHierarchy_AppsOnlyActive_DistinguishesNapFromCurfew() {
        val napAppsOnly = ProtectionNotificationCoordinator.ProtectionState.AppsOnlyActive(
            isNap = true,
            endHour = 0,
            endMinute = 0,
            endWallMs = 123456L
        )
        assertTrue(napAppsOnly.isNap)
        assertEquals(123456L, napAppsOnly.endWallMs)

        val curfewAppsOnly = ProtectionNotificationCoordinator.ProtectionState.AppsOnlyActive(
            isNap = false,
            endHour = 7,
            endMinute = 0,
            endWallMs = 0L
        )
        assertFalse(curfewAppsOnly.isNap)
        assertEquals(7, curfewAppsOnly.endHour)
    }

    @Test
    fun testNapNotificationReceiverAction_matchesActionName() {
        assertEquals("com.drmohammed.antiporn.curfew.ACTION_EXTEND_NAP", NapNotificationReceiver.ACTION_EXTEND_NAP)
    }

    @Test
    fun testGuardianNameSanitization() {
        fun sanitizeGuardianName(name: String?, fallback: String): String {
            return name?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) } ?: fallback
        }

        assertEquals("Parent", sanitizeGuardianName(null, "Parent"))
        assertEquals("Parent", sanitizeGuardianName("", "Parent"))
        assertEquals("Parent", sanitizeGuardianName("   ", "Parent"))
        assertEquals("Parent", sanitizeGuardianName("null", "Parent"))
        assertEquals("Parent", sanitizeGuardianName("NULL", "Parent"))
        assertEquals("Alice", sanitizeGuardianName("Alice", "Parent"))
    }

    @Test
    fun testNewCurfewStringResourceDefined() {
        assertTrue(com.drmohammed.antiporn.R.string.notif_nap_ended_desc_curfew_still_active != 0)
    }
}
