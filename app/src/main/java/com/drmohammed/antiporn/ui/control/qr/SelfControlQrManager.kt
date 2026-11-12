package com.drmohammed.antiporn.ui.control.qr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.EnumMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
    private val decryptToken: (String) -> String? = { it }
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
        { token -> encryptWithKeystore(token) },
        { stored -> decryptWithKeystore(stored) }
    )

    companion object {
        private const val TAG = "AntiPorn_QrManager"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "hisn_self_control_qr_token_v1"
        private const val ENCRYPTED_TOKEN_PREFIX = "enc:v1:"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private val keystoreLock = Any()

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

        private fun getOrCreateSecretKey(): SecretKey? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }

        private fun encryptWithKeystore(plaintext: String): String? = synchronized(keystoreLock) {
            try {
                val secretKey = getOrCreateSecretKey() ?: return null
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
                val ivB64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
                val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
                "$ENCRYPTED_TOKEN_PREFIX$ivB64:$ctB64"
            } catch (e: Exception) {
                Log.e(TAG, "Failed encrypting QR lock token via AndroidKeyStore", e)
                null
            }
        }

        private fun decryptWithKeystore(stored: String): String? = synchronized(keystoreLock) {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
                if (!stored.startsWith(ENCRYPTED_TOKEN_PREFIX)) return null
                val payload = stored.removePrefix(ENCRYPTED_TOKEN_PREFIX)
                val parts = payload.split(':')
                if (parts.size != 2) return null
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey ?: return null
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Failed decrypting QR lock token via AndroidKeyStore", e)
                null
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
     * Retrieves the decrypted token when encrypted via KeyStore (migrating legacy plaintext if needed).
     */
    fun getSavedToken(): String? {
        val raw = prefs.getString(KEY_SAVED_TOKEN, null) ?: return null
        if (raw.startsWith(ENCRYPTED_TOKEN_PREFIX)) {
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
        return !getSavedTokenHash().isNullOrBlank() || !getSavedToken().isNullOrBlank()
    }

    /**
     * Generates a cryptographically secure random token, computes its SHA-256 hash,
     * stores encrypted token (when KeyStore is available) and SHA-256 hash in preferences,
     * and returns the raw token.
     */
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
        return prefs.getInt(KEY_EMERGENCY_DELAY_HOURS, DEFAULT_EMERGENCY_DELAY_HOURS).coerceAtLeast(1)
    }

    /**
     * Initiates an emergency unlock request by recording the current trusted timestamp.
     * Fails closed (returns false) if network-verified trusted time is unavailable.
     */
    fun requestEmergencyDelay(): Boolean {
        if (!trustProvider()) {
            safeLogWarn("Rejecting emergency delay request: trusted network time unavailable")
            return false
        }
        val now = getCurrentTimeMsOrNull() ?: run {
            safeLogWarn("Rejecting emergency delay request: current trusted time unavailable")
            return false
        }
        prefs.edit().putLong(KEY_EMERGENCY_DELAY_REQUESTED_AT, now).apply()
        return true
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
        if (!trustProvider()) return durationMs
        val now = getCurrentTimeMsOrNull() ?: return durationMs
        val targetTimeMs = requestedAt + durationMs
        val remaining = targetTimeMs - now

        return if (remaining > 0L) remaining else 0L
    }

    /**
     * Returns true if an emergency delay was initiated AND the required waiting time has fully elapsed.
     */
    fun isEmergencyDelayActiveAndElapsed(): Boolean {
        val requestedAt = getEmergencyDelayRequestedAt()
        if (requestedAt <= 0L) return false

        // Strict Anti-Tamper: Emergency delay expiration cannot be validated without trusted time
        if (!trustProvider()) return false
        if (getCurrentTimeMsOrNull() == null) return false

        return getEmergencyDelayRemainingMs() <= 0L
    }

    /**
     * Clears all stored QR lock data and resets preferences to initial state.
     */
    fun reset() {
        prefs.edit().clear().apply()
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
