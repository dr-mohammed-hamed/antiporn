package com.drmohammed.antiporn.ota.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.ota.receiver.OtaInstallReceiver
import java.io.File
import java.io.FileNotFoundException

/**
 * Manages PackageInstaller sessions for seamless OTA updates.
 *
 * Utilizes Android's PackageInstaller session API to stream local verified APK binaries.
 * On Device Owner installations, updates are committed silently without prompting.
 * On non-Device Owner setups, Android delegates user confirmation through
 * [OtaInstallReceiver] via [PackageInstaller.STATUS_PENDING_USER_ACTION].
 */
object OtaPackageInstaller {

    private const val TAG = "AntiPorn_OtaInstaller"
    const val ACTION_OTA_INSTALL_STATUS = "com.drmohammed.antiporn.ACTION_OTA_INSTALL_STATUS"
    private const val BUFFER_SIZE = 64 * 1024 // 64KB buffer

    /**
     * Streams the given APK file into a new PackageInstaller session and commits it.
     *
     * @param context Application or component context.
     * @param apkFile The verified APK binary file on disk.
     * @return [Result] wrapping the created session ID on success, or an exception on failure.
     */
    fun installApk(context: Context, apkFile: File): Result<Int> {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            val error = FileNotFoundException("APK file does not exist or is empty: ${apkFile.absolutePath}")
            Log.e(TAG, error.message.orEmpty())
            return Result.failure(error)
        }

        val packageManager = context.packageManager
        val packageInstaller = packageManager.packageInstaller

        var session: PackageInstaller.Session? = null
        var sessionId = -1
        var isCommitted = false

        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            val fileLength = apkFile.length()
            apkFile.inputStream().use { inputStream ->
                session.openWrite("ota_package_stream", 0, fileLength).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }
                    session.fsync(out)
                }
            }

            val intent = Intent(context, OtaInstallReceiver::class.java).apply {
                action = ACTION_OTA_INSTALL_STATUS
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            )

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                flags
            )

            session.commit(pendingIntent.intentSender)
            isCommitted = true
            session.close()

            Log.i(TAG, "OTA PackageInstaller session $sessionId committed successfully for ${context.packageName}")
            Result.success(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute PackageInstaller session (sessionId=$sessionId)", e)
            if (!isCommitted) {
                try {
                    session?.abandon()
                } catch (abandonEx: Exception) {
                    Log.w(TAG, "Failed to abandon session $sessionId: ${abandonEx.message}")
                }
            }
            Result.failure(e)
        }
    }
}
