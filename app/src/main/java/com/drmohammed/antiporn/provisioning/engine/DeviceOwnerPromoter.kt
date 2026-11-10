package com.drmohammed.antiporn.provisioning.engine

import android.accounts.AccountManager
import android.content.ComponentName
import android.content.Context
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.R
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.FileKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber
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

            Timber.tag(TAG).i("Pairing via SPAKE2 TLS to $LOCALHOST:$pairingPort with code length ${pairingCode.length}...")
            AdbClient.pairTls(LOCALHOST, pairingPort, pairingCode.trim(), keyProvider)
            Timber.tag(TAG).i("Pairing handshake successful! Waiting 500ms for adbd key persistence...")
            delay(500)
            Timber.tag(TAG).i("Connecting to main connectPort: $connectPort...")

            val adminComponent = ComponentName(appContext, AdminReceiver::class.java).flattenToShortString()

            var lastException: Exception? = null
            var result: PromotionResult? = null

            for (attempt in 1..2) {
                try {
                    AdbClient.connectTls(LOCALHOST, connectPort, keyProvider).use { client ->
                        result = executeZeroAccountPromotion(appContext, client, adminComponent, 0)
                    }
                    break
                } catch (e: Exception) {
                    lastException = e
                    Timber.tag(TAG).w("Connect attempt $attempt failed: ${e.message}")
                    if (attempt < 2) {
                        delay(500)
                    }
                }
            }

            result ?: throw (lastException ?: IllegalStateException("Failed to connect over TLS"))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed during pairAndPromote")
            PromotionResult.Failure(e.message ?: "Unknown error during wireless promotion", e)
        } finally {
            promotionMutex.unlock()
        }
    }

    suspend fun promoteIfAlreadyPaired(
        context: Context,
        connectPort: Int
    ): PromotionResult = withContext(Dispatchers.IO) {
        if (!promotionMutex.tryLock()) {
            return@withContext PromotionResult.Failure("Promotion already in progress.")
        }
        val appContext = context.applicationContext
        try {
            val keyFile = File(appContext.filesDir, "antiporn_adb.key")
            if (!keyFile.exists()) {
                return@withContext PromotionResult.Failure("Device has not been paired yet.")
            }
            val keyProvider = FileKeyProvider(keyFile)
            val adminComponent = ComponentName(appContext, AdminReceiver::class.java).flattenToShortString()

            AdbClient.connectTls(LOCALHOST, connectPort, keyProvider).use { client ->
                executeZeroAccountPromotion(appContext, client, adminComponent, 0)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed during promoteIfAlreadyPaired")
            PromotionResult.Failure(e.message ?: "Failed connecting to already paired port", e)
        } finally {
            promotionMutex.unlock()
        }
    }

    /**
     * Executes the zero-account bypass promotion sequence based on Android SDK level.
     */
    suspend fun executeZeroAccountPromotion(
        context: Context,
        client: AdbClient,
        adminComponent: String,
        userId: Int = 0
    ): PromotionResult {
        val appContext = context.applicationContext
        try {
            client.shell("pm grant ${appContext.packageName} android.permission.WRITE_SECURE_SETTINGS")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed granting WRITE_SECURE_SETTINGS permission via ADB")
        }
        try {
            client.shell("appops set ${appContext.packageName} ACCESS_RESTRICTED_SETTINGS allow")
            Timber.tag(TAG).i("Granted ACCESS_RESTRICTED_SETTINGS allow via ADB")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed setting ACCESS_RESTRICTED_SETTINGS via ADB")
        }

        val sdkVerStr = client.shell("getprop ro.build.version.sdk").stdoutText.trim()
        val sdkVersion = sdkVerStr.toIntOrNull() ?: android.os.Build.VERSION.SDK_INT
        Timber.tag(TAG).i("Executing zero-account promotion on Android SDK $sdkVersion for user $userId")

        val result = if (sdkVersion >= 31) {
            val primaryResult = executeSetupBypassPromotion(context, client, adminComponent, userId)
            if (primaryResult is PromotionResult.Success) {
                disableWirelessAdbSilently(client)
                primaryResult
            } else {
                Timber.tag(TAG).w("Setup bypass failed: ${(primaryResult as PromotionResult.Failure).message}. Attempting fallback to package freeze...")
                val fallbackResult = executePackageFreezePromotion(context, client, adminComponent, userId)
                if (fallbackResult is PromotionResult.Success) {
                    disableWirelessAdbSilently(client)
                    fallbackResult
                } else {
                    fallbackResult
                }
            }
        } else {
            val legacyResult = executePackageFreezePromotion(context, client, adminComponent, userId)
            if (legacyResult is PromotionResult.Success) {
                disableWirelessAdbSilently(client)
            }
            legacyResult
        }

        if (result is PromotionResult.Success) {
            com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator.enableSilently(context)
        }
        return result
    }

    suspend fun executeZeroAccountPromotion(
        client: AdbClient,
        adminComponent: String,
        userId: Int = 0
    ): PromotionResult {
        val sdkVerStr = client.shell("getprop ro.build.version.sdk").stdoutText.trim()
        val sdkVersion = sdkVerStr.toIntOrNull() ?: android.os.Build.VERSION.SDK_INT
        return if (sdkVersion >= 31) {
            val primaryResult = executeSetupBypassPromotion(null, client, adminComponent, userId)
            if (primaryResult is PromotionResult.Success) {
                disableWirelessAdbSilently(client)
                primaryResult
            } else {
                Timber.tag(TAG).w("Setup bypass failed without context. Attempting fallback to package freeze...")
                val fallbackResult = executePackageFreezePromotion(null, client, adminComponent, userId)
                if (fallbackResult is PromotionResult.Success) {
                    disableWirelessAdbSilently(client)
                    fallbackResult
                } else {
                    fallbackResult
                }
            }
        } else {
            val result = executePackageFreezePromotion(null, client, adminComponent, userId)
            if (result is PromotionResult.Success) {
                disableWirelessAdbSilently(client)
            }
            result
        }
    }

    private suspend fun disableWirelessAdbSilently(client: AdbClient) {
        try {
            Timber.tag(TAG).i("Disabling adb_wifi_enabled for security cleanup (leaving adb_enabled intact)...")
            client.shell("settings put global adb_wifi_enabled 0")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed disabling adb_wifi_enabled after promotion")
        }
    }

    private suspend fun executeSetupBypassPromotion(
        context: Context?,
        client: AdbClient,
        adminComponent: String,
        userId: Int
    ): PromotionResult {
        val dexPath = "/data/local/tmp/antiporn_helper.dex"

        // Push embedded dex helper to /data/local/tmp
        client.shell("echo '$HELPER_DEX_BASE64' | base64 -d > $dexPath")

        var isBypassed = false
        try {
            // 1. Temporarily clear user_setup_complete
            val setupRes = client.shell("settings --user $userId put secure user_setup_complete 0")
            isBypassed = true

            if (setupRes.stdoutText.contains("SecurityException", ignoreCase = true) ||
                setupRes.stderrText.contains("SecurityException", ignoreCase = true) ||
                setupRes.stderrText.contains("Permission Denial", ignoreCase = true)) {
                val errorMsg = context?.getString(R.string.provisioning_error_xiaomi_security_settings)
                    ?: setupRes.stderrText.ifBlank { setupRes.stdoutText }
                return PromotionResult.Failure(errorMsg)
            }

            // 2. Refresh DevicePolicyManagerService cache via our helper
            client.shell("CLASSPATH=$dexPath app_process /system/bin SetupStateHelper $userId")

            // 3. Dispatch set-device-owner command
            var cmd = "dpm set-device-owner --user $userId $adminComponent"
            var doResult = client.shell(cmd)
            var output = (doResult.stdoutText + "\n" + doResult.stderrText).trim()

            if (output.contains("unknown option: --user", ignoreCase = true) ||
                output.contains("illegal option", ignoreCase = true)) {
                cmd = "dpm set-device-owner $adminComponent"
                doResult = client.shell(cmd)
                output = (doResult.stdoutText + "\n" + doResult.stderrText).trim()
            }

            Timber.tag(TAG).i("DPM bypass execution output: $output")

            val isSuccess = (output.contains("Success", ignoreCase = true) || output.contains("device owner set", ignoreCase = true)) &&
                !output.contains("error", ignoreCase = true) && !output.contains("exception", ignoreCase = true)

            return if (isSuccess) {
                PromotionResult.Success
            } else {
                PromotionResult.Failure(formatFriendlyError(context, output))
            }
        } finally {
            // 4. Guaranteed Restoration in finally
            if (isBypassed) {
                try {
                    client.shell("settings --user $userId put secure user_setup_complete 1")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to restore user_setup_complete in finally")
                }
                try {
                    client.shell("CLASSPATH=$dexPath app_process /system/bin SetupStateHelper $userId")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to refresh SetupStateHelper in finally")
                }
            }
            try {
                client.shell("rm -f $dexPath")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to remove helper dex in finally")
            }
        }
    }

    private suspend fun executePackageFreezePromotion(
        context: Context?,
        client: AdbClient,
        adminComponent: String,
        userId: Int
    ): PromotionResult {
        val dumpsys = client.shell("dumpsys account").stdoutText
        val regex = "Account \\{name=.*?, type=(.*?)\\}".toRegex()
        val accountTypes = mutableSetOf<String>()
        dumpsys.lines().forEach { line ->
            regex.find(line)?.let { accountTypes.add(it.groupValues[1]) }
        }

        val authenticatorMap = context?.let { ctx ->
            try {
                AccountManager.get(ctx.applicationContext).authenticatorTypes
                    .associate { it.type to it.packageName }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to retrieve authenticatorTypes from AccountManager")
                emptyMap()
            }
        } ?: emptyMap()

        val packagesToDisable = mutableSetOf<String>()
        accountTypes.forEach { type ->
            authenticatorMap[type]?.let { pkg ->
                packagesToDisable.add(pkg)
            } ?: run {
                when {
                    type.contains("google", ignoreCase = true) -> packagesToDisable.add("com.google.android.gms")
                    type.contains("whatsapp", ignoreCase = true) -> packagesToDisable.add("com.whatsapp")
                    type.contains("telegram", ignoreCase = true) -> packagesToDisable.add("org.telegram.messenger")
                    type.contains("samsung", ignoreCase = true) -> packagesToDisable.add("com.osp.app.signin")
                    type.contains("xiaomi", ignoreCase = true) -> packagesToDisable.add("com.xiaomi.account")
                }
            }
        }
        packagesToDisable.add("com.google.android.gms")

        try {
            for (pkg in packagesToDisable) {
                client.shell("pm disable-user --user $userId $pkg")
            }
            delay(1000)

            var cmd = "dpm set-device-owner --user $userId $adminComponent"
            var doResult = client.shell(cmd)
            var output = (doResult.stdoutText + "\n" + doResult.stderrText).trim()

            if (output.contains("unknown option: --user", ignoreCase = true) ||
                output.contains("illegal option", ignoreCase = true)) {
                cmd = "dpm set-device-owner $adminComponent"
                doResult = client.shell(cmd)
                output = (doResult.stdoutText + "\n" + doResult.stderrText).trim()
            }

            Timber.tag(TAG).i("DPM legacy execution output: $output")

            val isSuccess = (output.contains("Success", ignoreCase = true) || output.contains("device owner set", ignoreCase = true)) &&
                !output.contains("error", ignoreCase = true) && !output.contains("exception", ignoreCase = true)

            return if (isSuccess) {
                PromotionResult.Success
            } else {
                PromotionResult.Failure(formatFriendlyError(context, output))
            }
        } finally {
            for (pkg in packagesToDisable) {
                try {
                    client.shell("pm enable --user $userId $pkg")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to re-enable package $pkg in finally")
                }
            }
            try {
                client.shell("pm enable --user $userId com.google.android.gms")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to re-enable com.google.android.gms in finally")
            }
        }
    }

    private fun formatFriendlyError(context: Context?, output: String): String {
        if (context == null) return output
        val lower = output.lowercase()
        return when {
            lower.contains("already several users") || lower.contains("user 150") || lower.contains("user 95") ->
                context.getString(R.string.provisioning_error_samsung_secure_folder)
            lower.contains("precondition 99") ->
                context.getString(R.string.provisioning_error_realme_precondition)
            lower.contains("already") && lower.contains("owner") ->
                context.getString(R.string.provisioning_error_already_device_owner)
            lower.contains("securityexception") || lower.contains("permission denial") ->
                context.getString(R.string.provisioning_error_xiaomi_security_settings)
            else -> output
        }
    }
}
