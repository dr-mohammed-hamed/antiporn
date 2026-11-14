package com.drmohammed.antiporn.ui.control.qr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.EnumMap

/**
 * Manager handling QR-Code lock settings, cryptographically secure token generation,
 * SHA-256 hash storage and verification, QR bitmap rendering via ZXing,
 * and emergency delay timer logic.
 */
class SelfControlQrManager internal constructor(
    private val prefs: SharedPreferences,
    private val timeProvider: () -> Long = {
        try {
            TrustedTimeProvider.currentTimeMillis()
        } catch (e: Throwable) {
            Log.e(TAG, "TrustedTimeProvider unavailable", e)
            0L
        }
    },
    private val trustProvider: () -> Boolean = {
        try {
            TrustedTimeProvider.isTrusted()
        } catch (e: Throwable) {
            Log.e(TAG, "TrustedTimeProvider trust status check failed", e)
            false
        }
    },
    private val encryptToken: (String) -> String? = { it },
    private val decryptToken: (String) -> String? = { it },
    private val monotonicTimeProvider: () -> Long = {
        android.os.SystemClock.elapsedRealtime()
    }
) {

    private constructor(context: Context) : this(
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        {
            try {
                TrustedTimeProvider.currentTimeMillis(context.applicationContext ?: context)
            } catch (e: Throwable) {
                Log.e(TAG, "TrustedTimeProvider unavailable", e)
                0L
            }
        },
        {
            try {
                val appContext = context.applicationContext ?: context
                TrustedTimeProvider.initialize(appContext)
                TrustedTimeProvider.isTrusted()
            } catch (e: Throwable) {
                Log.e(TAG, "TrustedTimeProvider trust status check failed", e)
                false
            }
        },
        { token -> SelfControlQrCrypto.encrypt(token) },
        { stored -> SelfControlQrCrypto.decrypt(stored) },
        { android.os.SystemClock.elapsedRealtime() }
    )

    companion object {
        private const val TAG = "AntiPorn_QrManager"

        const val PREFS_NAME = "self_control_qr_prefs"
        const val KEY_QR_LOCK_ENABLED = "qr_lock_enabled"
        const val KEY_SAVED_TOKEN = "saved_token"
        const val KEY_SAVED_TOKEN_HASH = "saved_token_hash"
        const val KEY_EMERGENCY_DELAY_ENABLED = "emergency_delay_enabled"
        const val KEY_EMERGENCY_DELAY_HOURS = "emergency_delay_hours"
        const val KEY_EMERGENCY_DELAY_REQUESTED_AT = "emergency_delay_requested_at"
        const val KEY_EMERGENCY_DELAY_REQUESTED_ELAPSED_RT = "emergency_delay_requested_elapsed_rt"
        const val KEY_EMERGENCY_DELAY_IS_OFFLINE_MONOTONIC = "emergency_delay_is_offline_monotonic"

        const val DEFAULT_EMERGENCY_DELAY_HOURS = 24

        @Volatile
        private var instance: SelfControlQrManager? = null

        fun getInstance(context: Context): SelfControlQrManager {
            return instance ?: synchronized(this) {
                instance ?: SelfControlQrManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun isQrLockEnabled(): Boolean = prefs.getBoolean(KEY_QR_LOCK_ENABLED, false)

    fun setQrLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QR_LOCK_ENABLED, enabled).apply()
    }

    /**
     * Retrieves the decrypted token when encrypted via KeyStore (migrating legacy plaintext if needed).
     */
    fun getSavedToken(): String? {
        val raw = prefs.getString(KEY_SAVED_TOKEN, null) ?: return null
        if (raw.startsWith(SelfControlQrCrypto.ENCRYPTED_TOKEN_PREFIX)) {
            return decryptToken(raw)
        }
        val encrypted = encryptToken(raw)
        val tokenHash = prefs.getString(KEY_SAVED_TOKEN_HASH, null)
            ?.takeIf { it.isNotBlank() }
            ?: computeSha256(raw)
        if (!encrypted.isNullOrBlank() && encrypted != raw) {
            prefs.edit()
                .putString(KEY_SAVED_TOKEN, encrypted)
                .putString(KEY_SAVED_TOKEN_HASH, tokenHash)
                .apply()
        }
        return raw
    }

    fun getSavedTokenHash(): String? = prefs.getString(KEY_SAVED_TOKEN_HASH, null)

    fun hasToken(): Boolean = !getSavedTokenHash().isNullOrBlank() || !getSavedToken().isNullOrBlank()

    fun generateAndSaveNewToken(): String {
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val token = randomBytes.joinToString("") { "%02x".format(it) }
        val tokenHash = computeSha256(token)

        val encryptedToken = encryptToken(token)
        val editor = prefs.edit().putString(KEY_SAVED_TOKEN_HASH, tokenHash)
        if (!encryptedToken.isNullOrBlank()) {
            editor.putString(KEY_SAVED_TOKEN, encryptedToken)
        } else {
            editor.remove(KEY_SAVED_TOKEN)
        }
        editor.apply()

        return token
    }

    /**
     * Verifies whether [scannedContent] matches the active lock token
     * using constant-time comparison to prevent timing attacks.
     */
    fun verifyToken(scannedContent: String): Boolean {
        val trimmed = scannedContent.trim()
        if (trimmed.isEmpty()) return false

        val storedHash = getSavedTokenHash()
        val storedToken = getSavedToken()

        if (storedHash.isNullOrEmpty() && storedToken.isNullOrEmpty()) {
            return false
        }

        val inputHash = computeSha256(trimmed)

        val hashMatches = storedHash?.let {
            MessageDigest.isEqual(
                it.toByteArray(Charsets.UTF_8),
                inputHash.toByteArray(Charsets.UTF_8)
            )
        } ?: false

        val tokenMatches = storedToken?.let {
            MessageDigest.isEqual(
                it.toByteArray(Charsets.UTF_8),
                trimmed.toByteArray(Charsets.UTF_8)
            )
        } ?: false

        return hashMatches || tokenMatches
    }

    /**
     * Generates a square QR code [com.google.zxing.common.BitMatrix] containing [text] with UTF-8 encoding and minimal 1-block margin.
     */
    fun generateQrMatrix(text: String, sizePx: Int = 512): com.google.zxing.common.BitMatrix {
        return QrBitmapGenerator.generateQrMatrix(text, sizePx)
    }

    fun generateQrBitmap(text: String, sizePx: Int = 512): Bitmap {
        return QrBitmapGenerator.generateQrBitmap(text, sizePx)
    }

    // --- Emergency Delay Logic ---

    /**
     * Checks if emergency delay mechanism is enabled as a fallback for lost QR codes.
     */
    fun isEmergencyDelayEnabled(): Boolean {
        return prefs.getBoolean(KEY_EMERGENCY_DELAY_ENABLED, true)
    }

    /**
     * Configures the emergency delay feature and duration in hours.
     */
    fun setEmergencyDelayEnabled(enabled: Boolean, delayHours: Int = DEFAULT_EMERGENCY_DELAY_HOURS) {
        prefs.edit()
            .putBoolean(KEY_EMERGENCY_DELAY_ENABLED, enabled)
            .putInt(KEY_EMERGENCY_DELAY_HOURS, delayHours.coerceAtLeast(1))
            .apply()
    }

    /**
     * Returns configured delay duration in hours.
     */
    fun getEmergencyDelayHours(): Int {
        return prefs.getInt(KEY_EMERGENCY_DELAY_HOURS, DEFAULT_EMERGENCY_DELAY_HOURS).coerceAtLeast(1)
    }

    /**
     * Initiates an emergency unlock request by recording current trusted timestamp or monotonic fallback.
     */
    fun requestEmergencyDelay(): Boolean {
        val editor = prefs.edit()
        val elapsedRt = getMonotonicElapsedRealtimeOrNull()

        if (trustProvider()) {
            val now = getCurrentTimeMsOrNull()
            if (now != null) {
                editor.putLong(KEY_EMERGENCY_DELAY_REQUESTED_AT, now)
                if (elapsedRt != null && elapsedRt > 0L) {
                    editor.putLong(KEY_EMERGENCY_DELAY_REQUESTED_ELAPSED_RT, elapsedRt)
                }
                editor.putBoolean(KEY_EMERGENCY_DELAY_IS_OFFLINE_MONOTONIC, false)
                editor.apply()
                return true
            }
        }

        // Offline / Curfew Fallback: Record monotonic elapsedRealtime
        if (elapsedRt != null && elapsedRt > 0L) {
            val fallbackWallTime = System.currentTimeMillis()
            editor.putLong(KEY_EMERGENCY_DELAY_REQUESTED_AT, fallbackWallTime)
            editor.putLong(KEY_EMERGENCY_DELAY_REQUESTED_ELAPSED_RT, elapsedRt)
            editor.putBoolean(KEY_EMERGENCY_DELAY_IS_OFFLINE_MONOTONIC, true)
            editor.apply()
            return true
        }

        safeLogWarn("Rejecting emergency delay request: both trusted time and monotonic clock unavailable")
        return false
    }

    /**
     * Cancels any pending emergency unlock request.
     */
    fun cancelEmergencyDelay() {
        prefs.edit()
            .remove(KEY_EMERGENCY_DELAY_REQUESTED_AT)
            .remove(KEY_EMERGENCY_DELAY_REQUESTED_ELAPSED_RT)
            .remove(KEY_EMERGENCY_DELAY_IS_OFFLINE_MONOTONIC)
            .apply()
    }

    /**
     * Completes emergency unlock: disables QR lock, removes token and resets emergency state.
     */
    fun completeEmergencyUnlock() {
        prefs.edit()
            .putBoolean(KEY_QR_LOCK_ENABLED, false)
            .remove(KEY_EMERGENCY_DELAY_REQUESTED_AT)
            .remove(KEY_EMERGENCY_DELAY_REQUESTED_ELAPSED_RT)
            .remove(KEY_EMERGENCY_DELAY_IS_OFFLINE_MONOTONIC)
            .remove(KEY_SAVED_TOKEN)
            .remove(KEY_SAVED_TOKEN_HASH)
            .apply()
    }

    /**
     * Returns the timestamp (ms) when emergency delay was initiated, or 0 if not requested.
     */
    fun getEmergencyDelayRequestedAt(): Long {
        return prefs.getLong(KEY_EMERGENCY_DELAY_REQUESTED_AT, 0L)
    }

    /**
     * Checks whether an emergency delay request is currently in flight.
     */
    fun isEmergencyDelayRequested(): Boolean {
        return getEmergencyDelayRequestedAt() > 0L
    }

    /**
     * Calculates the remaining milliseconds until emergency access is granted.
     * Returns 0 if no delay was requested or if the delay has already elapsed.
     */
    fun getEmergencyDelayRemainingMs(): Long {
        val requestedAt = getEmergencyDelayRequestedAt()
        if (requestedAt <= 0L) return 0L

        val durationMs = getEmergencyDelayHours().toLong() * 3600L * 1000L
        val isOfflineMonotonic = prefs.getBoolean(KEY_EMERGENCY_DELAY_IS_OFFLINE_MONOTONIC, false)

        if (trustProvider()) {
            val now = getCurrentTimeMsOrNull()
            if (now != null && !isOfflineMonotonic) {
                val targetTimeMs = requestedAt + durationMs
                val remaining = targetTimeMs - now
                return if (remaining > 0L) remaining else 0L
            }
        }

        val requestedElapsed = prefs.getLong(KEY_EMERGENCY_DELAY_REQUESTED_ELAPSED_RT, 0L)
        val currentElapsed = getMonotonicElapsedRealtimeOrNull()
        if (requestedElapsed > 0L && currentElapsed != null && currentElapsed >= requestedElapsed) {
            val elapsedSinceRequest = currentElapsed - requestedElapsed
            val remaining = durationMs - elapsedSinceRequest
            return if (remaining > 0L) remaining else 0L
        }

        return durationMs
    }

    /**
     * Returns true if an emergency delay was initiated AND the required waiting time has fully elapsed.
     */
    fun isEmergencyDelayActiveAndElapsed(): Boolean {
        val requestedAt = getEmergencyDelayRequestedAt()
        if (requestedAt <= 0L) return false

        val remainingMs = getEmergencyDelayRemainingMs()
        if (remainingMs > 0L) return false

        val isOfflineMonotonic = prefs.getBoolean(KEY_EMERGENCY_DELAY_IS_OFFLINE_MONOTONIC, false)
        val requestedElapsed = prefs.getLong(KEY_EMERGENCY_DELAY_REQUESTED_ELAPSED_RT, 0L)
        val currentElapsed = getMonotonicElapsedRealtimeOrNull()
        val isMonotonicValid = requestedElapsed > 0L && currentElapsed != null && currentElapsed >= requestedElapsed

        return if (isOfflineMonotonic) {
            isMonotonicValid
        } else {
            val isTrusted = trustProvider() && getCurrentTimeMsOrNull() != null
            isTrusted || isMonotonicValid
        }
    }

    /**
     * Clears all stored QR lock data and resets preferences to initial state.
     */
    fun reset() {
        prefs.edit().clear().apply()
    }

    private fun getMonotonicElapsedRealtimeOrNull(): Long? {
        return try {
            monotonicTimeProvider().takeIf { it >= 0L }
        } catch (e: Throwable) {
            safeLogError("Injected monotonicTimeProvider failed", e)
            null
        }
    }

    private fun getCurrentTimeMsOrNull(): Long? {
        return try {
            timeProvider().takeIf { it > 0L }
        } catch (e: Throwable) {
            safeLogError("Injected timeProvider failed", e)
            null
        }
    }

    private fun safeLogWarn(message: String) {
        try {
            Log.w(TAG, message)
        } catch (e: RuntimeException) {
            if (e.message?.contains("not mocked") != true) throw e
        }
    }

    private fun safeLogError(message: String, throwable: Throwable) {
        try {
            Log.e(TAG, message, throwable)
        } catch (e: RuntimeException) {
            if (e.message?.contains("not mocked") != true) throw e
        }
    }

    private fun computeSha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
