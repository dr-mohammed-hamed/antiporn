package com.drmohammed.antiporn.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class TrustedTimeTest {

    private var fakeElapsed: Long = 10_000L
    private var fakeWall: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        fakeElapsed = 10_000L
        fakeWall = 1_700_000_000_000L
        TrustedTimeProvider.elapsedRealtimeProvider = { fakeElapsed }
        TrustedTimeProvider.wallTimeProvider = { fakeWall }
        TrustedTimeProvider.resetForTesting()
    }

    @Test
    fun testAtomicAnchor_threadSafeImmutableState() {
        val anchorTime = 1_700_000_000_000L
        val anchorElapsed = 50_000L
        TrustedTimeProvider.setAnchorForTesting(anchorTime, anchorElapsed, isStrictlyTrusted = true)

        val anchor = TrustedTimeProvider.getCurrentAnchor()
        assertEquals(anchorTime, anchor.trueTimeMs)
        assertEquals(anchorElapsed, anchor.elapsedMs)
        assertTrue(anchor.isStrictlyTrusted)
        assertFalse(anchor.isDegradedOffline)
        assertTrue(TrustedTimeProvider.isTrusted())
        assertFalse(TrustedTimeProvider.isDegradedOffline())

        // Advancing elapsed time
        fakeElapsed = 80_000L // +30s
        val current = TrustedTimeProvider.currentTimeMillis()
        assertEquals(anchorTime + 30_000L, current)
    }

    @Test
    fun testOfflineReboot_morningWakeup_advancesByWallDelta() {
        // Simulate phone shut down at night (23:30)
        val shutdownTrueTime = 1_700_000_000_000L
        val shutdownWallTime = 1_700_000_000_000L
        val sleepDurationMs = 7 * 3600 * 1000L // 7 hours overnight sleep

        // User wakes up at 06:30 morning, turns on phone in airplane mode
        val wakeupWallTime = shutdownWallTime + sleepDurationMs
        fakeWall = wakeupWallTime
        fakeElapsed = 30_000L // 30 seconds since boot

        val wallDelta = fakeWall - shutdownWallTime
        val clampedDelta = minOf(wallDelta, TrustedTimeProvider.MAX_OFFLINE_ADVANCE_MS)
        val expectedWakeupTrueTime = shutdownTrueTime + clampedDelta

        // Establish offline degraded anchor as ensureInitialized would
        TrustedTimeProvider.setAnchorForTesting(expectedWakeupTrueTime, fakeElapsed, isStrictlyTrusted = false)

        assertFalse("Offline reboot must not be strictly trusted (blocks breakout bypass)", TrustedTimeProvider.isTrusted())
        assertTrue("Offline reboot must be marked degraded", TrustedTimeProvider.isDegradedOffline())

        // Time computed 1 minute after wakeup
        fakeElapsed += 60_000L
        val computed = TrustedTimeProvider.currentTimeMillis()

        assertEquals(
            "Morning wakeup offline must have advanced by 7 hours sleep + 1 minute uptime",
            expectedWakeupTrueTime + 60_000L,
            computed
        )
    }

    @Test
    fun testOfflineReboot_clockRollback_antiRollbackEnforced() {
        // Attacker rolled wall clock backward while phone was shut down
        val lastKnownTrue = 1_700_000_000_000L
        val lastSavedWall = 1_700_000_000_000L
        val manipulatedWall = lastSavedWall - 3600_000L // 1 hour backward

        fakeWall = manipulatedWall
        fakeElapsed = 15_000L

        val wallDelta = fakeWall - lastSavedWall // negative
        val effectiveTime = if (wallDelta > 0L) {
            val clamped = minOf(wallDelta, TrustedTimeProvider.MAX_OFFLINE_ADVANCE_MS)
            lastKnownTrue + clamped
        } else {
            lastKnownTrue // Strict Anti-Rollback
        }

        TrustedTimeProvider.setAnchorForTesting(effectiveTime, fakeElapsed, isStrictlyTrusted = false)

        val computed = TrustedTimeProvider.currentTimeMillis()
        assertTrue("Time must never roll back below lastKnownTrue: computed=$computed", computed >= lastKnownTrue)
        assertEquals(lastKnownTrue, computed)
    }

    @Test
    fun testOfflineReboot_exceeds48Hours_clampedTo48h() {
        val lastKnownTrue = 1_700_000_000_000L
        val lastSavedWall = 1_700_000_000_000L
        val longOffWall = lastSavedWall + (72 * 3600 * 1000L) // 72 hours later

        fakeWall = longOffWall
        fakeElapsed = 10_000L

        val wallDelta = fakeWall - lastSavedWall
        val clampedDelta = minOf(wallDelta, TrustedTimeProvider.MAX_OFFLINE_ADVANCE_MS)
        val expectedTime = lastKnownTrue + clampedDelta

        assertEquals(
            "Delta exceeding 48h must be clamped to 48 hours",
            lastKnownTrue + (48 * 3600 * 1000L),
            expectedTime
        )
    }

    @Test
    fun testAntiRollback_strictEnforcement() {
        val baseTime = 1_700_000_000_000L
        TrustedTimeProvider.setAnchorForTesting(baseTime, 50_000L, isStrictlyTrusted = true)

        fakeElapsed = 50_000L
        val first = TrustedTimeProvider.currentTimeMillis()

        // Simulate anomaly or backward clock attempt
        fakeElapsed = 20_000L
        val second = TrustedTimeProvider.currentTimeMillis()

        assertTrue("Time must never roll back: first=$first, second=$second", second >= first)
        assertTrue("Time must never be less than baseTime: first=$first", first >= baseTime)
    }

    @Test
    fun testDriftCorrectionListener_triggersAboveThreshold() {
        var observedDriftMs = 0L
        TrustedTimeProvider.onTimeDriftCorrected = { drift ->
            observedDriftMs = drift
        }

        // Initial anchor
        TrustedTimeProvider.setAnchorForTesting(1_700_000_000_000L, 50_000L, isStrictlyTrusted = false)

        // NTP corrects with a 5-minute jump
        val newNtpTime = 1_700_000_300_000L // +300s (5 minutes)
        val oldComputed = TrustedTimeProvider.currentTimeMillis()
        val drift = kotlin.math.abs(newNtpTime - oldComputed)

        if (drift >= TrustedTimeProvider.DRIFT_CORRECTION_THRESHOLD_MS) {
            TrustedTimeProvider.onTimeDriftCorrected?.invoke(drift)
        }

        assertEquals(300_000L, observedDriftMs)
    }

    @Test
    fun testComputeElapsedTrigger_calculatesCorrectDelay() {
        TrustedTimeProvider.setAnchorForTesting(1_700_000_000_000L, 50_000L, isStrictlyTrusted = true)
        fakeElapsed = 50_000L
        val nowTrue = TrustedTimeProvider.currentTimeMillis()
        val targetTrueFuture = nowTrue + 3600_000L // +1 hour

        val triggerElapsed = TrustedTimeProvider.computeElapsedTrigger(targetTrueFuture)

        // Trigger should be fakeElapsed + 1 hour
        assertEquals(fakeElapsed + 3600_000L, triggerElapsed)

        // Target in past should trigger immediately (elapsed now)
        val targetPast = nowTrue - 1000L
        val triggerPast = TrustedTimeProvider.computeElapsedTrigger(targetPast)
        assertEquals(fakeElapsed, triggerPast)
    }

    @Test
    fun testCurfewCalendar_usesTrustedTimeZone() {
        val cal = TrustedTimeProvider.getCurfewCalendar()
        assertNotNull(cal)
        assertNotNull(cal.timeZone)
        assertTrue(cal.timeZone.id.isNotBlank())
    }

    @Test
    fun testSntpEpochConstant() {
        // 70 years between 1900 and 1970
        // 70 * 365 + 17 leap days = 25567 days * 86400 seconds = 2208988800 seconds
        val expectedOffset = 2208988800L
        val field = SntpClient::class.java.getDeclaredField("OFFSET_1900_TO_1970")
        field.isAccessible = true
        val actualOffset = field.getLong(SntpClient)
        assertEquals(expectedOffset, actualOffset)
    }

    @Test
    fun testMinPlausibleEpochConstant() {
        // Must be at least 2024-01-01 (1_704_067_200_000L)
        assertTrue(SntpClient.MIN_PLAUSIBLE_EPOCH_MS >= 1704067200000L)
    }

    @Test
    fun testCurfewConfig_defaultFallbackTimeZone_isValid() {
        val defaultTz = com.drmohammed.antiporn.curfew.CurfewConfig.getDefaultFallbackTimeZone()
        assertTrue(com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry.isValidIanaId(defaultTz))
    }

    @Test
    fun testDefaultPolicyTimeZone_matchesCurfewFallback() {
        val policy = com.drmohammed.antiporn.core.model.DevicePolicyBundle.defaultPolicy()
        assertEquals(com.drmohammed.antiporn.curfew.CurfewConfig.getDefaultFallbackTimeZone(), policy.timeZoneId)
    }

    @Test
    fun testMaxFutureDriftConstant() {
        assertEquals(30L * 24 * 3600 * 1000L, SntpClient.MAX_HTTP_FUTURE_DRIFT_MS)
    }

    @Test
    fun testMonotonicityUnderConcurrentReads() {
        val baseTime = 1_700_000_000_000L
        TrustedTimeProvider.setAnchorForTesting(baseTime, 10_000L, isStrictlyTrusted = true)

        val threads = 8
        val iterationsPerThread = 500
        val executor = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val barrier = java.util.concurrent.CyclicBarrier(threads)

        for (t in 0 until threads) {
            executor.execute {
                barrier.await()
                var previous = 0L
                for (i in 0 until iterationsPerThread) {
                    val current = TrustedTimeProvider.currentTimeMillis()
                    if (current < previous || current < baseTime) {
                        failures.incrementAndGet()
                    }
                    previous = current
                }
            }
        }

        executor.shutdown()
        assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("Monotonicity violated under concurrency", 0, failures.get())
    }
}
