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
        val (inMemoryPrefs, fakeCtx) = createInMemoryPrefsContext()
        val shutdownTrueTime = 1_700_000_000_000L
        val shutdownWallTime = 1_700_000_000_000L
        val sleepDurationMs = 7 * 3600 * 1000L // 7 hours overnight sleep

        inMemoryPrefs.edit()
            .putBoolean(TrustedTimeProvider.KEY_HAS_EVER_SYNCED, true)
            .putLong(TrustedTimeProvider.KEY_LAST_KNOWN_TRUE_TIME, shutdownTrueTime)
            .putLong(TrustedTimeProvider.KEY_LAST_ELAPSED, 200_000L)
            .putLong(TrustedTimeProvider.KEY_LAST_SAVED_WALL_TIME, shutdownWallTime)
            .commit()

        TrustedTimeProvider.resetForTesting()

        // User wakes up 7 hours later after reboot (elapsed reset to 30_000L) in airplane mode
        fakeWall = shutdownWallTime + sleepDurationMs
        fakeElapsed = 30_000L

        TrustedTimeProvider.initialize(fakeCtx)

        assertFalse("Offline reboot must not be strictly trusted (blocks breakout bypass)", TrustedTimeProvider.isTrusted())
        assertTrue("Offline reboot must be marked degraded", TrustedTimeProvider.isDegradedOffline())

        // Time computed 1 minute after wakeup
        fakeElapsed += 60_000L
        val computed = TrustedTimeProvider.currentTimeMillis(fakeCtx)

        assertEquals(
            "Morning wakeup offline must have advanced by 7 hours sleep + 1 minute uptime",
            shutdownTrueTime + sleepDurationMs + 60_000L,
            computed
        )
    }

    @Test
    fun testOfflineReboot_clockRollback_antiRollbackEnforced() {
        val (inMemoryPrefs, fakeCtx) = createInMemoryPrefsContext()
        val lastKnownTrue = 1_700_000_000_000L
        val lastSavedWall = 1_700_000_000_000L

        inMemoryPrefs.edit()
            .putBoolean(TrustedTimeProvider.KEY_HAS_EVER_SYNCED, true)
            .putLong(TrustedTimeProvider.KEY_LAST_KNOWN_TRUE_TIME, lastKnownTrue)
            .putLong(TrustedTimeProvider.KEY_LAST_ELAPSED, 200_000L)
            .putLong(TrustedTimeProvider.KEY_LAST_SAVED_WALL_TIME, lastSavedWall)
            .commit()

        TrustedTimeProvider.resetForTesting()

        // Attacker rolled wall clock backward 1 hour while device was shut down
        fakeWall = lastSavedWall - 3600_000L
        fakeElapsed = 15_000L

        val computed = TrustedTimeProvider.currentTimeMillis(fakeCtx)
        assertTrue("Time must never roll back below lastKnownTrue: computed=$computed", computed >= lastKnownTrue)
        assertEquals(lastKnownTrue, computed)
    }

    private fun createInMemoryPrefsContext(): Pair<android.content.SharedPreferences, android.content.Context> {
        val prefsMap = mutableMapOf<String, Any?>()
        val inMemoryPrefs = object : android.content.SharedPreferences {
            override fun getAll(): MutableMap<String, *> = prefsMap.toMutableMap()
            override fun getString(key: String?, defValue: String?): String? = prefsMap[key] as? String ?: defValue
            @Suppress("UNCHECKED_CAST")
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
                (prefsMap[key] as? Set<String>)?.toMutableSet() ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = prefsMap[key] as? Int ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = prefsMap[key] as? Long ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = prefsMap[key] as? Float ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = prefsMap[key] as? Boolean ?: defValue
            override fun contains(key: String?): Boolean = prefsMap.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
                private val pending = mutableMapOf<String, Any?>()
                private val removed = mutableSetOf<String>()
                private var cleared = false
                override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                    if (key != null) { removed.remove(key); pending[key] = value }
                    return this
                }
                override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor {
                    if (key != null) { removed.remove(key); pending[key] = values?.toSet() }
                    return this
                }
                override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor {
                    if (key != null) { removed.remove(key); pending[key] = value }
                    return this
                }
                override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor {
                    if (key != null) { removed.remove(key); pending[key] = value }
                    return this
                }
                override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor {
                    if (key != null) { removed.remove(key); pending[key] = value }
                    return this
                }
                override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor {
                    if (key != null) { removed.remove(key); pending[key] = value }
                    return this
                }
                override fun remove(key: String?): android.content.SharedPreferences.Editor {
                    if (key != null) { pending.remove(key); removed.add(key) }
                    return this
                }
                override fun clear(): android.content.SharedPreferences.Editor {
                    cleared = true; pending.clear(); removed.clear()
                    return this
                }
                override fun commit(): Boolean { apply(); return true }
                override fun apply() {
                    if (cleared) prefsMap.clear()
                    removed.forEach { prefsMap.remove(it) }
                    prefsMap.putAll(pending)
                }
            }
            override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
        val fakeCtx = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = inMemoryPrefs
        }
        return inMemoryPrefs to fakeCtx
    }

    @Test
    fun testPreSyncPoisonedWallClock_doesNotSetHasEverSyncedOrLastKnown() {
        val (inMemoryPrefs, fakeCtx) = createInMemoryPrefsContext()

        TrustedTimeProvider.resetForTesting()
        fakeWall = 1_900_000_000_000L // Poisoned far-future untrusted wall clock
        fakeElapsed = 10_000L

        TrustedTimeProvider.currentTimeMillis(fakeCtx)
        fakeElapsed = 80_000L // Exceed DEBOUNCE_PERSIST_MS
        TrustedTimeProvider.currentTimeMillis(fakeCtx)
        TrustedTimeProvider.persistOnShutdown(fakeCtx)

        assertFalse("Pre-sync poisoned wall clock must not mark hasEverSynced", TrustedTimeProvider.hasEverSynced())
        assertFalse("Pre-sync state must remain untrusted", TrustedTimeProvider.isTrusted())
        assertEquals(0L, TrustedTimeProvider.getLastKnownTrueTime())
        assertEquals(0L, inMemoryPrefs.getLong(TrustedTimeProvider.KEY_LAST_KNOWN_TRUE_TIME, 0L))
        assertFalse(inMemoryPrefs.getBoolean(TrustedTimeProvider.KEY_HAS_EVER_SYNCED, false))
    }

    @Test
    fun testOfflineReboot_exceeds48Hours_clampedTo48h() {
        val (inMemoryPrefs, fakeCtx) = createInMemoryPrefsContext()
        val lastKnownTrue = 1_700_000_000_000L
        val lastSavedWall = 1_700_000_000_000L

        inMemoryPrefs.edit()
            .putBoolean(TrustedTimeProvider.KEY_HAS_EVER_SYNCED, true)
            .putLong(TrustedTimeProvider.KEY_LAST_KNOWN_TRUE_TIME, lastKnownTrue)
            .putLong(TrustedTimeProvider.KEY_LAST_ELAPSED, 200_000L)
            .putLong(TrustedTimeProvider.KEY_LAST_SAVED_WALL_TIME, lastSavedWall)
            .commit()

        TrustedTimeProvider.resetForTesting()

        fakeWall = lastSavedWall + (72L * 3600L * 1000L) // 72 hours later
        fakeElapsed = 10_000L

        val computed = TrustedTimeProvider.currentTimeMillis(fakeCtx)

        assertEquals(
            "Delta exceeding 48h must be clamped to 48 hours",
            lastKnownTrue + (48L * 3600L * 1000L),
            computed
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
        val (_, fakeCtx) = createInMemoryPrefsContext()
        var observedDriftMs = 0L
        TrustedTimeProvider.onTimeDriftCorrected = { drift ->
            observedDriftMs = drift
        }

        // Initialize cold boot untrusted wall anchor at elapsed=50_000L
        fakeElapsed = 50_000L
        fakeWall = 1_700_000_000_000L
        TrustedTimeProvider.initialize(fakeCtx)

        // Real syncNow() corrects with a 5-minute jump -> must invoke production drift listener
        val newNtpTime = 1_700_000_300_000L // +300s (5 minutes)
        fakeWall = newNtpTime
        TrustedTimeProvider.sntpRequester = {
            SntpResult(
                timeMs = newNtpTime,
                elapsedRealtimeMs = fakeElapsed,
                roundTripLatencyMs = 20L,
                source = "SNTP: test"
            )
        }
        assertTrue(TrustedTimeProvider.syncNow(fakeCtx, force = true))
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

    @Test
    fun testOfflineReboot_wallDeltaDoesNotPoisonLastKnownTrueTime() {
        val (inMemoryPrefs, fakeCtx) = createInMemoryPrefsContext()
        val verifiedSyncTime = 1_710_000_000_000L

        inMemoryPrefs.edit()
            .putBoolean(TrustedTimeProvider.KEY_HAS_EVER_SYNCED, true)
            .putLong(TrustedTimeProvider.KEY_LAST_KNOWN_TRUE_TIME, verifiedSyncTime)
            .putLong(TrustedTimeProvider.KEY_LAST_ELAPSED, 100_000L)
            .putLong(TrustedTimeProvider.KEY_LAST_SAVED_WALL_TIME, verifiedSyncTime)
            .commit()

        TrustedTimeProvider.resetForTesting()

        // Simulate offline reboot (elapsed rolled back below lastSavedElapsed) + 72h wall-clock advance
        fakeElapsed = 5_000L
        fakeWall = verifiedSyncTime + (72L * 3600L * 1000L)

        val computed = TrustedTimeProvider.currentTimeMillis(fakeCtx)
        assertTrue(TrustedTimeProvider.hasEverSynced())
        assertFalse("Offline reboot anchor must remain untrusted", TrustedTimeProvider.isTrusted())
        assertTrue(TrustedTimeProvider.isDegradedOffline())
        assertEquals(
            "Offline anchor must clamp wall-clock delta to MAX_OFFLINE_ADVANCE_MS",
            verifiedSyncTime + TrustedTimeProvider.MAX_OFFLINE_ADVANCE_MS,
            computed
        )
        assertEquals(
            "Unverified offline wallDelta must NOT poison lastKnownTrueTimeMs",
            verifiedSyncTime,
            TrustedTimeProvider.getLastKnownTrueTime()
        )

        // Advance elapsed past debounce threshold and persist on shutdown
        fakeElapsed = 80_000L
        TrustedTimeProvider.currentTimeMillis(fakeCtx)
        TrustedTimeProvider.persistOnShutdown(fakeCtx)

        assertEquals(
            "Debounced/shutdown persist while degraded offline must preserve last verified true time",
            verifiedSyncTime,
            TrustedTimeProvider.getLastKnownTrueTime()
        )
        assertEquals(
            verifiedSyncTime,
            inMemoryPrefs.getLong(TrustedTimeProvider.KEY_LAST_KNOWN_TRUE_TIME, 0L)
        )
    }

    @Test
    fun testSyncNow_enforcesAntiRollback_andAllowsQuorumLargeForwardAdvance() {
        val (inMemoryPrefs, fakeCtx) = createInMemoryPrefsContext()
        val initialSyncTime = 1_720_000_000_000L
        fakeElapsed = 50_000L
        fakeWall = initialSyncTime

        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.sntpRequester = {
            SntpResult(
                timeMs = initialSyncTime,
                elapsedRealtimeMs = fakeElapsed,
                roundTripLatencyMs = 20L,
                source = "SNTP: test1"
            )
        }
        assertTrue(TrustedTimeProvider.syncNow(fakeCtx, force = true))
        assertEquals(initialSyncTime, TrustedTimeProvider.currentTimeMillis(fakeCtx))
        assertTrue(TrustedTimeProvider.isTrusted())

        // Rollback attempt from compromised source must be rejected
        fakeElapsed = 60_000L
        TrustedTimeProvider.sntpRequester = {
            SntpResult(
                timeMs = initialSyncTime - 120_000L,
                elapsedRealtimeMs = fakeElapsed,
                roundTripLatencyMs = 15L,
                source = "SNTP: rogue"
            )
        }
        assertFalse(TrustedTimeProvider.syncNow(fakeCtx, force = true))

        // Unverified >30-day jump from a single source must be rejected
        val futureTime = initialSyncTime + (45L * 24L * 3600L * 1000L)
        TrustedTimeProvider.sntpRequester = {
            SntpResult(
                timeMs = futureTime,
                elapsedRealtimeMs = fakeElapsed,
                roundTripLatencyMs = 15L,
                source = "SNTP: unverified",
                isQuorumVerified = false
            )
        }
        assertFalse(TrustedTimeProvider.syncNow(fakeCtx, force = true))

        // Quorum-verified >30-day advance (genuine long offline recovery) must succeed
        TrustedTimeProvider.sntpRequester = {
            SntpResult(
                timeMs = futureTime,
                elapsedRealtimeMs = fakeElapsed,
                roundTripLatencyMs = 25L,
                source = "SNTP: quorum",
                isQuorumVerified = true
            )
        }
        assertTrue(TrustedTimeProvider.syncNow(fakeCtx, force = true))
        assertEquals(futureTime, TrustedTimeProvider.currentTimeMillis(fakeCtx))
        assertEquals(futureTime, inMemoryPrefs.getLong(TrustedTimeProvider.KEY_LAST_KNOWN_TRUE_TIME, 0L))
    }

    @Test
    fun testConsecutiveOfflineReboots_preservesWallBaselineAndMonotonicFloor() {
        val (inMemoryPrefs, fakeCtx) = createInMemoryPrefsContext()
        val syncTime = 1_720_000_000_000L
        fakeElapsed = 100_000L
        fakeWall = syncTime

        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.sntpRequester = {
            SntpResult(
                timeMs = syncTime,
                elapsedRealtimeMs = fakeElapsed,
                roundTripLatencyMs = 20L,
                source = "SNTP: test"
            )
        }
        assertTrue(TrustedTimeProvider.syncNow(fakeCtx, force = true))

        // Offline reboot #1: +6h sleep, run for 1h offline, then shut down
        TrustedTimeProvider.resetForTesting()
        fakeWall = syncTime + (6L * 3600L * 1000L)
        fakeElapsed = 10_000L
        assertEquals(syncTime + (6L * 3600L * 1000L), TrustedTimeProvider.currentTimeMillis(fakeCtx))
        fakeElapsed += 3600L * 1000L
        TrustedTimeProvider.persistOnShutdown(fakeCtx)

        // Wall baseline must remain anchored to last strictly trusted sync
        assertEquals(syncTime, inMemoryPrefs.getLong(TrustedTimeProvider.KEY_LAST_SAVED_WALL_TIME, 0L))

        // Offline reboot #2: wall rolled backwards — offline floor prevents regression below reboot #1 shutdown
        TrustedTimeProvider.resetForTesting()
        fakeWall = syncTime - 3600_000L
        fakeElapsed = 5_000L
        val reboot2Time = TrustedTimeProvider.currentTimeMillis(fakeCtx)
        assertTrue(
            "Reboot #2 must not regress below reboot #1 monotonic shutdown floor",
            reboot2Time >= syncTime + (7L * 3600L * 1000L)
        )
    }
}
