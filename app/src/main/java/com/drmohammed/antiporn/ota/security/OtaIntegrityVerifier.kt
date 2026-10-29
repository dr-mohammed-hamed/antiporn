package com.drmohammed.antiporn.ota.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.ota.model.OtaVersionInfo
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Cryptographic and package integrity verifier for OTA APK updates.
 *
 * Guarantees that downloaded packages are complete, match expected SHA-256 hashes,
 * and represent valid installable Android APK packages for com.drmohammed.antiporn.
 */
object OtaIntegrityVerifier {

    private const val TAG = "OtaIntegrityVerifier"
    private const val BUFFER_SIZE = 64 * 1024 // 64KB chunk buffer

    /**
     * Computes the lowercase hexadecimal SHA-256 checksum of the specified file.
     * Returns an empty string if reading fails or file is invalid.
     */
    fun computeSha256(file: File): String {
        if (!file.exists() || !file.isFile || file.length() == 0L) {
            return ""
        }
        return try {
            FileInputStream(file).use { input ->
                computeSha256(input)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute SHA-256 for file: ${file.absolutePath}", e)
            ""
        }
    }

    /**
     * Computes the lowercase hexadecimal SHA-256 checksum of an [InputStream].
     */
    fun computeSha256(inputStream: InputStream): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute SHA-256 from input stream", e)
            ""
        }
    }

    /**
     * Verifies the full integrity and authenticity of a downloaded OTA APK.
     *
     * Validates:
     * a) File exists and is non-empty.
     * b) If [info.fileSizeBytes] > 0, exact file length matches [info.fileSizeBytes].
     * c) If [info.sha256ApkHash] is present, computed SHA-256 hash matches (case-insensitive).
     * d) Android [PackageManager.getPackageArchiveInfo] parses the archive successfully
     *    and confirms package name matches [context.packageName].
     *
     * Returns true only if all checks pass, otherwise false. Never throws exceptions.
     */
    fun verifyApkIntegrity(context: Context, apkFile: File, info: OtaVersionInfo): Boolean {
        return try {
            // Check a: File exists and is not empty
            if (!apkFile.exists() || apkFile.length() <= 0L) {
                Log.e(TAG, "Integrity check failed: APK file does not exist or is empty (${apkFile.absolutePath})")
                return false
            }

            // Check b: Exact byte size match if specified
            if (info.fileSizeBytes > 0L && apkFile.length() != info.fileSizeBytes) {
                Log.e(
                    TAG,
                    "Integrity check failed: Size mismatch. Expected ${info.fileSizeBytes} bytes, but found ${apkFile.length()} bytes"
                )
                return false
            }

            // Check c: Binary SHA-256 checksum match if specified
            if (!info.sha256ApkHash.isNullOrBlank()) {
                val computedHash = computeSha256(apkFile)
                if (!computedHash.equals(info.sha256ApkHash.trim(), ignoreCase = true)) {
                    Log.e(
                        TAG,
                        "Integrity check failed: SHA-256 mismatch. Expected ${info.sha256ApkHash}, computed $computedHash"
                    )
                    return false
                }
            }

            // Check d: Valid installable Android APK archive and package name match
            val packageManager = context.packageManager
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_ACTIVITIES
                )
            }

            if (packageInfo == null) {
                Log.e(TAG, "Integrity check failed: Package archive info is null. APK is corrupted or invalid.")
                return false
            }

            val archivePackageName = packageInfo.packageName
            if (archivePackageName.isNullOrBlank() || archivePackageName != context.packageName) {
                Log.e(
                    TAG,
                    "Integrity check failed: Package name mismatch. Expected '${context.packageName}', got '$archivePackageName'"
                )
                return false
            }

            Log.i(TAG, "APK integrity verification passed successfully for ${apkFile.name}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected exception during APK integrity verification", t)
            false
        }
    }
}
