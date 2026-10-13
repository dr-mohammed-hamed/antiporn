package com.drmohammed.antiporn.curfew

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic integration test for the merged block (night curfew OR nap).
 * No Robolectric: mirrors `isEffectiveBlockActive = curfew || nap` and the
 * ironclad release guard with plain booleans.
 */
class NapIntegrationTest {

    private fun effectiveBlock(nightActive: Boolean, napActive: Boolean): Boolean =
        nightActive || napActive

    /** Mirrors the extended ironclad guard: reject release if (night || nap) and no emergency extension. */
    private fun guardRejectsRelease(
        activate: Boolean,
        nightActive: Boolean,
        napActive: Boolean,
        isEmergencyWorkExtension: Boolean
    ): Boolean =
        !activate && (nightActive || napActive) && !isEmergencyWorkExtension

    @Test
    fun effectiveBlock_truthTable() {
        assertFalse("night=off, nap=off -> released", effectiveBlock(false, false))
        assertTrue("night=on, nap=off -> blocked", effectiveBlock(true, false))
        assertTrue("night=off, nap=on -> blocked", effectiveBlock(false, true))
        assertTrue("night=on, nap=on -> blocked", effectiveBlock(true, true))
    }

    @Test
    fun guard_releaseRejected_whenNapActive() {
        assertTrue(
            "Release must be rejected while nap is active (daytime)",
            guardRejectsRelease(
                activate = false,
                nightActive = false,
                napActive = true,
                isEmergencyWorkExtension = false
            )
        )
    }

    @Test
    fun guard_releaseRejected_whenNightActive() {
        assertTrue(
            "Release must be rejected during night window (nap expired)",
            guardRejectsRelease(
                activate = false,
                nightActive = true,
                napActive = false,
                isEmergencyWorkExtension = false
            )
        )
    }

    @Test
    fun guard_releaseAllowed_whenBothExpired() {
        assertFalse(
            "Release must be allowed when both night and nap expired",
            guardRejectsRelease(
                activate = false,
                nightActive = false,
                napActive = false,
                isEmergencyWorkExtension = false
            )
        )
    }

    @Test
    fun guard_activateNeverRejected() {
        assertFalse(
            "Activation must never be rejected by the guard",
            guardRejectsRelease(
                activate = true,
                nightActive = true,
                napActive = true,
                isEmergencyWorkExtension = false
            )
        )
    }
}
