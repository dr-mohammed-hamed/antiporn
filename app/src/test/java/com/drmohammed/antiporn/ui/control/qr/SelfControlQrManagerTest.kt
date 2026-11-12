package com.drmohammed.antiporn.ui.control.qr

import android.content.SharedPreferences
import com.google.zxing.BarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelfControlQrManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private var currentTime: Long = 1_000_000_000L
    private lateinit var manager: SelfControlQrManager

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        currentTime = 1_000_000_000L
        manager = SelfControlQrManager(fakePrefs, timeProvider = { currentTime }, trustProvider = { true })
    }

    @Test
    fun testInitialState_defaultsAreSafe() {
        assertFalse(manager.isQrLockEnabled())
        assertFalse(manager.hasToken())
        assertEquals(null, manager.getSavedToken())
        assertEquals(null, manager.getSavedTokenHash())
        assertTrue(manager.isEmergencyDelayEnabled())
        assertEquals(SelfControlQrManager.DEFAULT_EMERGENCY_DELAY_HOURS, manager.getEmergencyDelayHours())
        assertFalse(manager.isEmergencyDelayRequested())
        assertEquals(0L, manager.getEmergencyDelayRemainingMs())
        assertFalse(manager.isEmergencyDelayActiveAndElapsed())
    }

    @Test
    fun testGenerateAndSaveNewToken_generatesValidHexAndHash() {
        val token = manager.generateAndSaveNewToken()
        assertNotNull(token)
        assertEquals(64, token.length) // 32 random bytes as hex = 64 characters
        assertTrue(manager.hasToken())
        assertEquals(token, manager.getSavedToken())

        val hash = manager.getSavedTokenHash()
        assertNotNull(hash)
        assertEquals(64, hash!!.length)

        // Verifying correct token succeeds
        assertTrue(manager.verifyToken(token))
        // Verifying with whitespace padding succeeds
        assertTrue(manager.verifyToken("  $token  \n"))
    }

    @Test
    fun testVerifyToken_failsOnInvalidTokens() {
        val token = manager.generateAndSaveNewToken()

        assertFalse(manager.verifyToken(""))
        assertFalse(manager.verifyToken("   "))
        assertFalse(manager.verifyToken("wrong_token"))
        val invertedLast = if (token.last() == '0') '1' else '0'
        assertFalse(manager.verifyToken(token.dropLast(1) + invertedLast))
    }

    @Test
    fun testEmergencyDelay_lifecycleAndCountdown() {
        manager.setEmergencyDelayEnabled(true, delayHours = 24)
        assertEquals(24, manager.getEmergencyDelayHours())

        // Request delay at t = 1_000_000_000L
        manager.requestEmergencyDelay()
        assertTrue(manager.isEmergencyDelayRequested())
        assertEquals(currentTime, manager.getEmergencyDelayRequestedAt())

        val expectedTotalMs = 24L * 3600L * 1000L
        assertEquals(expectedTotalMs, manager.getEmergencyDelayRemainingMs())
        assertFalse(manager.isEmergencyDelayActiveAndElapsed())

        // Advance 12 hours
        currentTime += 12L * 3600L * 1000L
        assertEquals(12L * 3600L * 1000L, manager.getEmergencyDelayRemainingMs())
        assertFalse(manager.isEmergencyDelayActiveAndElapsed())

        // Advance another 12 hours (24h total)
        currentTime += 12L * 3600L * 1000L
        assertEquals(0L, manager.getEmergencyDelayRemainingMs())
        assertTrue(manager.isEmergencyDelayActiveAndElapsed())

        // Advance past 24h
        currentTime += 3600L * 1000L
        assertEquals(0L, manager.getEmergencyDelayRemainingMs())
        assertTrue(manager.isEmergencyDelayActiveAndElapsed())

        // Cancel emergency request
        manager.cancelEmergencyDelay()
        assertFalse(manager.isEmergencyDelayRequested())
        assertEquals(0L, manager.getEmergencyDelayRemainingMs())
        assertFalse(manager.isEmergencyDelayActiveAndElapsed())
    }

    @Test
    fun testReset_clearsAllState() {
        manager.generateAndSaveNewToken()
        manager.setQrLockEnabled(true)
        manager.requestEmergencyDelay()

        assertTrue(manager.isQrLockEnabled())
        assertTrue(manager.hasToken())
        assertTrue(manager.isEmergencyDelayRequested())

        manager.reset()

        assertFalse(manager.isQrLockEnabled())
        assertFalse(manager.hasToken())
        assertFalse(manager.isEmergencyDelayRequested())
    }

    @Test
    fun testQrMatrixGeneration_createsValidBarcodeMatrix() {
        val matrix = manager.generateQrMatrix("test_token_12345", 256)
        assertNotNull(matrix)
        assertTrue(matrix.width >= 21) // Minimum QR size is 21x21 modules
        assertTrue(matrix.height >= 21)
    }

    /**
     * In-memory fake SharedPreferences for testing without Android context.
     */
    private class FakeSharedPreferences : SharedPreferences {
        private val storage = mutableMapOf<String, Any>()

        override fun getAll(): Map<String, *> = storage.toMap()
        override fun getString(key: String?, defValue: String?): String? = storage[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            @Suppress("UNCHECKED_CAST") (storage[key] as? Set<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = storage[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = storage[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = storage[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = storage[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = storage.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(storage)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val storage: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearPending = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value == null) pending[key] = null else pending[key] = value
                }
                return this
            }

            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values == null) pending[key] = null else pending[key] = values
                }
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearPending = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearPending) {
                    storage.clear()
                    clearPending = false
                }
                for ((k, v) in pending) {
                    if (v == null) {
                        storage.remove(k)
                    } else {
                        storage[k] = v
                    }
                }
                pending.clear()
            }
        }
    }
    @Test
    fun testEmergencyDelay_offlineUntrusted_rejectsElapsed() {
        val untrustedManager = SelfControlQrManager(
            fakePrefs,
            timeProvider = { currentTime },
            trustProvider = { false }
        )
        assertFalse(untrustedManager.requestEmergencyDelay())
        assertEquals(0L, untrustedManager.getEmergencyDelayRequestedAt())
        currentTime += 30L * 3600L * 1000L // 30 hours elapsed
        assertEquals(0L, untrustedManager.getEmergencyDelayRemainingMs())
        // Anti-tamper: Must return false because time is untrusted (offline clock spoof)
        assertFalse(untrustedManager.isEmergencyDelayActiveAndElapsed())
    }

    @Test
    fun testEmergencyDelayHours_nonPositiveValues_clampedToAtLeastOne() {
        val manager = SelfControlQrManager(
            fakePrefs,
            timeProvider = { currentTime },
            trustProvider = { true }
        )
        fakePrefs.edit().putInt(SelfControlQrManager.KEY_EMERGENCY_DELAY_HOURS, 0).apply()
        assertEquals(1, manager.getEmergencyDelayHours())

        fakePrefs.edit().putInt(SelfControlQrManager.KEY_EMERGENCY_DELAY_HOURS, -5).apply()
        assertEquals(1, manager.getEmergencyDelayHours())

        manager.setEmergencyDelayEnabled(true, 0)
        assertEquals(1, manager.getEmergencyDelayHours())
    }

    @Test
    fun testEmergencyDelay_onlineRequestThenOfflineThenElapsedExpiry() {
        var trusted = true
        val offlineCapableManager = SelfControlQrManager(
            fakePrefs,
            timeProvider = { currentTime },
            trustProvider = { trusted }
        )
        offlineCapableManager.setEmergencyDelayEnabled(true, 2)

        assertTrue("Online emergency request must succeed", offlineCapableManager.requestEmergencyDelay())
        assertTrue(offlineCapableManager.isEmergencyDelayRequested())

        // Device goes offline while 3 hours elapse (> 2h delay)
        trusted = false
        currentTime += 3 * 3600L * 1000L
        assertFalse(
            "Emergency delay expiry must fail closed while trusted time is unavailable",
            offlineCapableManager.isEmergencyDelayActiveAndElapsed()
        )

        // Device regains trusted sync
        trusted = true
        assertEquals(0L, offlineCapableManager.getEmergencyDelayRemainingMs())
        assertTrue(
            "Emergency delay must report elapsed once trusted time returns and delay has passed",
            offlineCapableManager.isEmergencyDelayActiveAndElapsed()
        )
    }
}