package com.drmohammed.antiporn.provisioning.engine

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.FileKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Result sealed class for the Device Owner promotion workflow.
 */
sealed class PromotionResult {
    data object Success : PromotionResult()
    data class Failure(val message: String, val cause: Throwable? = null) : PromotionResult()
}

/**
 * Executes on-device Device Owner promotion via local wireless ADB.
 *
 * Sequence:
 * 1. Loads/generates persistent ADB RSA key pair in the app's private files directory.
 * 2. Executes SPAKE2 password-authenticated TLS 1.3 pairing with localhost (127.0.0.1:pairingPort).
 * 3. Connects over TLS to localhost (127.0.0.1:connectPort).
 * 4. Dispatches: `dpm set-device-owner com.drmohammed.antiporn/.AdminReceiver`
 * 5. Evaluates output for "Success".
 */
object DeviceOwnerPromoter {

    private const val TAG = "AntiPorn_Promoter"
    private const val LOCALHOST = "127.0.0.1"
    private val promotionMutex = Mutex()

    suspend fun pairAndPromote(
        context: Context,
        pairingPort: Int,
        connectPort: Int,
        pairingCode: String
    ): PromotionResult = withContext(Dispatchers.IO) {
        if (!promotionMutex.tryLock()) {
            return@withContext PromotionResult.Failure("Promotion already in progress.")
        }
        try {
            val appContext = context.applicationContext
            val keyFile = File(appContext.filesDir, "antiporn_adb.key")
            val keyProvider = FileKeyProvider(keyFile)

            Log.i(TAG, "Pairing via SPAKE2 TLS to $LOCALHOST:$pairingPort with code length ${pairingCode.length}...")
            AdbClient.pairTls(LOCALHOST, pairingPort, pairingCode.trim(), keyProvider)
            Log.i(TAG, "Pairing handshake successful! Waiting 500ms for adbd key persistence...")
            delay(500)
            Log.i(TAG, "Connecting to main connectPort: $connectPort...")

            val adminComponent = ComponentName(appContext, AdminReceiver::class.java).flattenToShortString()
            val command = "dpm set-device-owner $adminComponent"

            var lastException: Exception? = null
            var result: PromotionResult? = null

            for (attempt in 1..2) {
                try {
                    AdbClient.connectTls(LOCALHOST, connectPort, keyProvider).use { client ->
                        val shellResult = client.shell(command)
                        val output = (shellResult.stdoutText + "\n" + shellResult.stderrText).trim()
                        Log.i(TAG, "DPM command exitCode=${shellResult.exitCode}, output=$output")

                        result = if (output.contains("Success", ignoreCase = true)) {
                            PromotionResult.Success
                        } else {
                            PromotionResult.Failure(output)
                        }
                    }
                    break
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Connect attempt $attempt failed: ${e.message}")
                    if (attempt < 2) {
                        delay(500)
                    }
                }
            }

            result ?: throw (lastException ?: IllegalStateException("Failed to connect over TLS"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed during pairAndPromote", e)
            PromotionResult.Failure(e.message ?: "Unknown error during wireless promotion", e)
        } finally {
            promotionMutex.unlock()
        }
    }

    suspend fun promoteIfAlreadyPaired(
        context: Context,
        connectPort: Int
    ): PromotionResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            val keyFile = File(appContext.filesDir, "antiporn_adb.key")
            if (!keyFile.exists()) {
                return@withContext PromotionResult.Failure("Device has not been paired yet.")
            }
            val keyProvider = FileKeyProvider(keyFile)
            val adminComponent = ComponentName(appContext, AdminReceiver::class.java).flattenToShortString()
            val command = "dpm set-device-owner $adminComponent"

            AdbClient.connectTls(LOCALHOST, connectPort, keyProvider).use { client ->
                val result = client.shell(command)
                val output = (result.stdoutText + "\n" + result.stderrText).trim()
                Log.i(TAG, "DPM command (already paired) output: $output")

                if (output.contains("Success", ignoreCase = true)) {
                    PromotionResult.Success
                } else {
                    PromotionResult.Failure(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed during promoteIfAlreadyPaired", e)
            PromotionResult.Failure(e.message ?: "Failed connecting to already paired port", e)
        }
    }
}
