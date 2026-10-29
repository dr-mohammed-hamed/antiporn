package com.drmohammed.antiporn.ota.model

import java.io.File

/**
 * Sealed class modeling all distinct states of the OTA update workflow.
 */
sealed class OtaUpdateState {
    /**
     * Initial idle state before an update check begins.
     */
    data object Idle : OtaUpdateState()

    /**
     * Actively communicating with the remote server to fetch latest version info.
     */
    data object Checking : OtaUpdateState()

    /**
     * Checked against remote version, and local application is already up to date.
     *
     * @property currentVersionCode Currently installed Android versionCode.
     * @property currentVersionName Currently installed Android versionName.
     */
    data class NoUpdateAvailable(
        val currentVersionCode: Long,
        val currentVersionName: String
    ) : OtaUpdateState()

    /**
     * Remote update is available and newer than local version.
     *
     * @property info Metadata of the new update.
     */
    data class UpdateAvailable(
        val info: OtaVersionInfo
    ) : OtaUpdateState()

    /**
     * APK file binary is downloading from remote repository.
     *
     * @property progressPercent Progress expressed as 0-100%, or -1 if total length is unknown.
     * @property downloadedBytes Bytes downloaded so far.
     * @property totalBytes Total file size in bytes (-1 if unknown).
     */
    data class Downloading(
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : OtaUpdateState()

    /**
     * Download completed; verifying package cryptographic hash and APK package integrity.
     */
    data object Verifying : OtaUpdateState()

    /**
     * Integrity verified; package is ready to be delivered to the package installer.
     *
     * @property apkFile The downloaded and verified APK file on local disk.
     * @property info The metadata corresponding to this update.
     */
    data class ReadyToInstall(
        val apkFile: File,
        val info: OtaVersionInfo
    ) : OtaUpdateState()

    /**
     * Package installation session is actively running.
     */
    data object Installing : OtaUpdateState()

    /**
     * An error occurred during checking, downloading, or verifying update.
     *
     * @property message Descriptive error message.
     * @property cause Optional underlying exception that caused the failure.
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : OtaUpdateState()
}
