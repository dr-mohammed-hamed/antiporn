package com.drmohammed.antiporn.ota.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.R
import androidx.core.content.pm.PackageInfoCompat
import com.drmohammed.antiporn.ota.installer.OtaPackageInstaller
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.ota.model.OtaVersionInfo
import com.drmohammed.antiporn.ota.notification.OtaNotificationManager
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.ota.receiver.OtaInstallReceiver
import com.drmohammed.antiporn.ota.remote.OtaRemoteSource
import com.drmohammed.antiporn.ota.security.OtaIntegrityVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Central orchestrator and repository for Over-The-Air (OTA) updates.
 *
 * Coordinates version checking, background streaming downloads, cryptographic verification,
 * seamless package installer submission, user notifications, and reactive UI state publication.
 */
class OtaRepository(private val appContext: Context) {

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadMutex = Mutex()

    private val _updateState = MutableStateFlow<OtaUpdateState>(OtaUpdateState.Idle)
    val updateState: StateFlow<OtaUpdateState> = _updateState.asStateFlow()

    @Volatile
    private var lastTargetVersionName: String? = null

    init {
        // Register receiver status callback for completed or failed installations
        OtaInstallReceiver.statusListener = object : OtaInstallReceiver.Companion.InstallStatusListener {
            override fun onInstallSuccess() {
                Log.i(TAG, "OTA self-update installed successfully via receiver")
                OtaNotificationManager.cancelNotification(appContext)
                val targetVersion = lastTargetVersionName
                if (!targetVersion.isNullOrBlank()) {
                    OtaNotificationManager.showSuccessNotification(appContext, targetVersion)
                }
            }

            override fun onPendingUserAction() {
                Log.i(TAG, "OTA update requires user confirmation prompt")
            }

            override fun onInstallFailure(status: Int, message: String?) {
                Log.e(TAG, "OTA installation failed with status $status: $message")
                OtaNotificationManager.cancelNotification(appContext)
                _updateState.value = OtaUpdateState.Error(
                    appContext.getString(R.string.ota_error_install_failed, message ?: status.toString())
                )
            }
        }
    }

    /**
     * Checks remote server for available updates against the local application version.
     *
     * @param context Calling context to query PackageManager.
     * @return [Result] containing [OtaVersionInfo] if a newer version is available,
     *         null if already on the latest version, or failure exception.
     */
    suspend fun checkForUpdates(context: Context): Result<OtaVersionInfo?> = withContext(Dispatchers.IO) {
        _updateState.value = OtaUpdateState.Checking
        try {
            val remoteResult = OtaRemoteSource.fetchLatestVersion()
            val info = remoteResult.getOrThrow()

            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }

            val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            val currentVersionName = packageInfo.versionName ?: "1.0"

            if (info.isNewerThan(currentVersionCode)) {
                Log.i(TAG, "New OTA version found: ${info.versionName} (code: ${info.versionCode}) > current: $currentVersionCode")
                _updateState.value = OtaUpdateState.UpdateAvailable(info)
                Result.success(info)
            } else {
                Log.i(TAG, "App is up to date: local code $currentVersionCode, remote code ${info.versionCode}")
                _updateState.value = OtaUpdateState.NoUpdateAvailable(currentVersionCode, currentVersionName)
                Result.success(null)
            }
        } catch (ce: CancellationException) {
            _updateState.value = OtaUpdateState.Idle
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for OTA updates", e)
            _updateState.value = OtaUpdateState.Error(
                appContext.getString(R.string.ota_error_check_failed, e.message ?: ""),
                e
            )
            Result.failure(e)
        }
    }

    /**
     * Downloads, verifies, and installs the specified OTA update package.
     * Guaranteed thread-safe; concurrent triggers are dropped to avoid redundant I/O.
     *
     * @param context Calling context.
     * @param info Target version metadata.
     * @param showNotification Whether to display progress & status notifications in system tray.
     * @return [Result] indicating success or failure.
     */
    suspend fun downloadAndInstall(
        context: Context,
        info: OtaVersionInfo,
        showNotification: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!downloadMutex.tryLock()) {
            Log.w(TAG, "OTA download/installation is already in progress. Ignoring duplicate trigger.")
            return@withContext Result.success(Unit)
        }

        lastTargetVersionName = info.versionName

        try {
            val otaCacheDir = File(context.cacheDir, "ota").apply {
                if (!exists()) mkdirs()
            }
            val apkFile = File(otaCacheDir, "antiporn_update_${info.versionCode}.apk")

            Log.i(TAG, "Starting OTA package download from ${info.downloadUrl} to ${apkFile.absolutePath}")
            _updateState.value = OtaUpdateState.Downloading(0, 0L, info.fileSizeBytes)
            if (showNotification) {
                OtaNotificationManager.showProgressNotification(context, 0, info)
            }

            val downloadResult = OtaRemoteSource.downloadApk(info.downloadUrl, apkFile) { downloaded, total, percent ->
                _updateState.value = OtaUpdateState.Downloading(percent, downloaded, total)
                if (showNotification) {
                    OtaNotificationManager.showProgressNotification(context, percent, info)
                }
            }

            val downloadedFile = downloadResult.getOrThrow()

            // Verification phase
            _updateState.value = OtaUpdateState.Verifying
            Log.i(TAG, "Verifying downloaded APK integrity: ${downloadedFile.absolutePath}")

            val isVerified = OtaIntegrityVerifier.verifyApkIntegrity(context, downloadedFile, info)
            if (!isVerified) {
                if (downloadedFile.exists()) {
                    downloadedFile.delete()
                }
                val verifyError = IllegalStateException("APK integrity check failed (hash, size, or package mismatch)")
                Log.e(TAG, "Integrity verification rejected APK", verifyError)
                _updateState.value = OtaUpdateState.Error(
                    appContext.getString(R.string.ota_error_corrupted_package),
                    verifyError
                )
                if (showNotification) {
                    OtaNotificationManager.cancelNotification(context)
                }
                return@withContext Result.failure(verifyError)
            }

            // Installation phase
            _updateState.value = OtaUpdateState.Installing
            if (showNotification) {
                OtaNotificationManager.showInstallingNotification(context, info)
            }

            Log.i(TAG, "Integrity verified. Initiating package installation session...")
            val installResult = OtaPackageInstaller.installApk(context, downloadedFile)
            if (installResult.isSuccess) {
                Log.i(TAG, "PackageInstaller session successfully committed (sessionId=${installResult.getOrNull()})")
                Result.success(Unit)
            } else {
                val installEx = installResult.exceptionOrNull() ?: Exception("Package installation session failed")
                Log.e(TAG, "PackageInstaller installation failed to commit", installEx)
                _updateState.value = OtaUpdateState.Error(
                    appContext.getString(R.string.ota_error_install_failed, installEx.message ?: ""),
                    installEx
                )
                if (showNotification) {
                    OtaNotificationManager.cancelNotification(context)
                }
                try {
                    AppNotificationManager.getInstance(context).postEventNotification(
                        category = NotificationCategory.OTA,
                        title = context.getString(R.string.notif_ota_repo_fail_title),
                        message = context.getString(R.string.notif_ota_repo_fail_msg, info.versionName, installEx.message ?: ""),
                        dedupKey = "ota_install_failed_${info.versionCode}",
                        cooldownMs = 60 * 60 * 1000L,
                        actionType = NotificationActionType.NONE,
                        systemNotificationId = NotificationRegistry.EVENT_OTA_FAILED_ID,
                        channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                        postSystemNotification = true,
                        importance = 2
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error posting OTA failure notification", e)
                }
                Result.failure(installEx)
            }
        } catch (ce: CancellationException) {
            if (showNotification) {
                OtaNotificationManager.cancelNotification(context)
            }
            _updateState.value = OtaUpdateState.Idle
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during OTA download and install", e)
            _updateState.value = OtaUpdateState.Error(
                appContext.getString(R.string.ota_error_unexpected, e.message ?: ""),
                e
            )
            if (showNotification) {
                OtaNotificationManager.cancelNotification(context)
            }
            try {
                AppNotificationManager.getInstance(context).postEventNotification(
                    category = NotificationCategory.OTA,
                    title = context.getString(R.string.notif_ota_repo_download_fail_title),
                    message = context.getString(R.string.notif_ota_repo_download_fail_msg, info.versionName),
                    dedupKey = "ota_download_failed_${info.versionCode}",
                    cooldownMs = 60 * 60 * 1000L,
                    actionType = NotificationActionType.NONE,
                    systemNotificationId = NotificationRegistry.EVENT_OTA_FAILED_ID,
                    channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                    postSystemNotification = true,
                    importance = 2
                )
            } catch (ex: Exception) {
                Log.e(TAG, "Error posting OTA failure notification", ex)
            }
            Result.failure(e)
        } finally {
            downloadMutex.unlock()
        }
    }

    /**
     * Autonomous update workflow designed for scheduled or background triggers (Watchdog and FCM).
     * Automatically queries latest version metadata and executes silent download & install if newer.
     */
    suspend fun checkForUpdatesAndAutoInstall(context: Context): Result<Unit> {
        Log.i(TAG, "Autonomous background OTA check initiated")
        val checkResult = checkForUpdates(context)
        if (checkResult.isFailure) {
            val ex = checkResult.exceptionOrNull() ?: Exception("Failed to check for updates")
            return Result.failure(ex)
        }
        val info = checkResult.getOrNull()
        return if (info != null) {
            Log.i(TAG, "New OTA version ${info.versionName} detected in background. Executing auto-install...")
            downloadAndInstall(context, info, showNotification = true)
        } else {
            Log.d(TAG, "Autonomous OTA check finished: no newer updates available")
            Result.success(Unit)
        }
    }

    /**
     * Non-blocking trigger executing autonomous OTA check & download on the repository singleton scope.
     * Prevents short-lived service termination from killing background downloads.
     */
    fun checkForUpdatesAndAutoInstallAsync(context: Context) {
        repositoryScope.launch {
            try {
                checkForUpdatesAndAutoInstall(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error in checkForUpdatesAndAutoInstallAsync", e)
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_OtaRepo"

        @Volatile
        private var instance: OtaRepository? = null

        /**
         * Returns thread-safe singleton instance of [OtaRepository].
         */
        fun getInstance(context: Context): OtaRepository {
            return instance ?: synchronized(this) {
                instance ?: OtaRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
