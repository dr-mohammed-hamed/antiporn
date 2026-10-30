package com.drmohammed.antiporn.ui.control.qr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
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
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }
) {

    private constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        {
            try {
                TrustedTimeProvider.currentTimeMillis(context.applicationContext)
            } catch (_: Throwable) {
                System.currentTimeMillis()
            }
        }
    )

    companion object {
        const val PREFS_NAME = "self_control_qr_prefs"
        const val KEY_QR_LOCK_ENABLED = "qr_lock_enabled"
        const val KEY_SAVED_TOKEN = "saved_token"
        const val KEY_SAVED_TOKEN_HASH = "saved_token_hash"
        const val KEY_EMERGENCY_DELAY_ENABLED = "emergency_delay_enabled"
        const val KEY_EMERGENCY_DELAY_HOURS = "emergency_delay_hours"
        const val KEY_EMERGENCY_DELAY_REQUESTED_AT = "emergency_delay_requested_at"

        const val DEFAULT_EMERGENCY_DELAY_HOURS = 24

        @Volatile
        private var instance: SelfControlQrManager? = null

        fun getInstance(context: Context): SelfControlQrManager {
            return instance ?: synchronized(this) {
                instance ?: SelfControlQrManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Checks if the QR code barrier is currently enabled.
     */
    fun isQrLockEnabled(): Boolean {
        return prefs.getBoolean(KEY_QR_LOCK_ENABLED, false)
    }

    /**
     * Enables or disables the QR code barrier.
     */
    fun setQrLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QR_LOCK_ENABLED, enabled).apply()
    }

    /**
     * Retrieves the plaintext token (available during generation / dev phase).
     */
    fun getSavedToken(): String? {
        return prefs.getString(KEY_SAVED_TOKEN, null)
    }

    /**
     * Retrieves the stored SHA-256 hash of the lock token.
     */
    fun getSavedTokenHash(): String? {
        return prefs.getString(KEY_SAVED_TOKEN_HASH, null)
    }

    /**
     * Checks if a valid token or token hash is stored.
     */
    fun hasToken(): Boolean {
        return !getSavedToken().isNullOrBlank() || !getSavedTokenHash().isNullOrBlank()
    }

    /**
     * Generates a cryptographically secure random token, computes its SHA-256 hash,
     * stores both token and hash in preferences, and returns the raw token.
     */
    fun generateAndSaveNewToken(): String {
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val token = randomBytes.joinToString("") { "%02x".format(it) }
        val tokenHash = computeSha256(token)

        prefs.edit()
            .putString(KEY_SAVED_TOKEN, token)
            .putString(KEY_SAVED_TOKEN_HASH, tokenHash)
            .apply()

        return token
    }

    /**
     * Verifies whether [scannedContent] matches the active lock token
     * by comparing SHA-256 hashes (and direct token fallback if hash is missing)
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
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.MARGIN, 1)
        }
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    /**
     * Generates a square QR code [Bitmap] containing [text] with UTF-8 encoding and minimal 1-block margin.
     */
    fun generateQrBitmap(text: String, sizePx: Int = 512): Bitmap {
        val bitMatrix = generateQrMatrix(text, sizePx)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
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
        return prefs.getInt(KEY_EMERGENCY_DELAY_HOURS, DEFAULT_EMERGENCY_DELAY_HOURS)
    }

    /**
     * Initiates an emergency unlock request by recording the current trusted timestamp.
     */
    fun requestEmergencyDelay() {
        val now = getCurrentTimeMs()
        prefs.edit().putLong(KEY_EMERGENCY_DELAY_REQUESTED_AT, now).apply()
    }

    /**
     * Cancels any pending emergency unlock request.
     */
    fun cancelEmergencyDelay() {
        prefs.edit().remove(KEY_EMERGENCY_DELAY_REQUESTED_AT).apply()
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
        val targetTimeMs = requestedAt + durationMs
        val now = getCurrentTimeMs()
        val remaining = targetTimeMs - now

        return if (remaining > 0L) remaining else 0L
    }

    /**
     * Returns true if an emergency delay was initiated AND the required waiting time has fully elapsed.
     */
    fun isEmergencyDelayActiveAndElapsed(): Boolean {
        val requestedAt = getEmergencyDelayRequestedAt()
        if (requestedAt <= 0L) return false
        return getEmergencyDelayRemainingMs() <= 0L
    }

    /**
     * Clears all stored QR lock data and resets preferences to initial state.
     */
    fun reset() {
        prefs.edit().clear().apply()
    }

    private fun getCurrentTimeMs(): Long {
        return try {
            timeProvider()
        } catch (_: Throwable) {
            try {
                TrustedTimeProvider.currentTimeMillis()
            } catch (_: Throwable) {
                System.currentTimeMillis()
            }
        }
    }

    private fun computeSha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
