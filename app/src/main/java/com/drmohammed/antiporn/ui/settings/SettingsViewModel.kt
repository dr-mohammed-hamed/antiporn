package com.drmohammed.antiporn.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.ai.service.ScreenAiProjectionService
import com.drmohammed.antiporn.cloud.auth.GoogleAuthManager
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.ota.model.OtaVersionInfo
import com.drmohammed.antiporn.ota.repository.OtaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for SettingsFragment.
 * Manages Screen AI status, OTA updates, Device Owner state, and Google account authentication.
 */
class SettingsViewModel(
    application: Application,
    private val otaRepository: OtaRepository = OtaRepository.getInstance(application),
    private val googleAuthManager: GoogleAuthManager = GoogleAuthManager.getInstance(application),
    private val accountabilityRepo: AccountabilityRepository = AccountabilityRepository.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadAppVersion()
        observeOtaUpdates()
        refreshAll()
    }

    private fun loadAppVersion() {
        val app = getApplication<Application>()
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, 0)
            }
            val code = PackageInfoCompat.getLongVersionCode(packageInfo)
            val name = packageInfo.versionName ?: "1.0"
            _uiState.update { it.copy(appVersionName = name, appVersionCode = code) }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving app version: ${e.message}")
        }
    }

    private fun observeOtaUpdates() {
        viewModelScope.launch {
            otaRepository.updateState.collect { otaState ->
                _uiState.update { it.copy(otaState = otaState) }
            }
        }
    }

    /**
     * Refreshes AI, Google Auth, Device Owner statuses, and Device Display Name in batch.
     */
    fun refreshAll() {
        refreshDisplayName()
        refreshAiState()
        refreshGoogleState()
        refreshDeviceOwnerState()
    }

    fun refreshDisplayName() {
        val name = accountabilityRepo.getMyDisplayName()
        _uiState.update { it.copy(myDisplayName = name) }
    }

    fun setMyDisplayName(name: String, onResult: ((Result<Unit>) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.setMyDisplayName(name)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    _uiState.update { it.copy(myDisplayName = name) }
                }
                onResult?.invoke(result)
            }
        }
    }

    fun refreshAiState() {
        val isRunning = ScreenAiProjectionService.isRunning
        val initError = ScreenAiProjectionService.initError
        val fps = ScreenAiProjectionService.currentFps
        val lastDetection = ScreenAiProjectionService.lastDetection
        val modelName = ScreenAiProjectionService.modelName

        _uiState.update { current ->
            current.copy(
                isAiRunning = isRunning,
                aiInitError = initError,
                aiFps = fps,
                aiLastDetectionLabel = lastDetection.label,
                aiLastDetectionConfidence = lastDetection.confidence,
                aiModelName = modelName
            )
        }
    }

    fun refreshGoogleState() {
        val isLinked = googleAuthManager.isLinkedWithGoogle()
        val email = googleAuthManager.getLinkedEmail()
        _uiState.update { current ->
            current.copy(
                isGoogleLinked = isLinked,
                googleAccountEmail = email
            )
        }
    }

    fun refreshDeviceOwnerState() {
        val app = getApplication<Application>()
        val isDo = ProtectionEngine.isDeviceOwner(app)
        _uiState.update { it.copy(isDeviceOwner = isDo) }
    }

    fun checkOtaUpdates() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            otaRepository.checkForUpdates(app)
        }
    }

    fun downloadAndInstallOta(info: OtaVersionInfo) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            otaRepository.downloadAndInstall(app, info, showNotification = true)
        }
    }

    fun setSharingTelemetry(isSharing: Boolean) {
        _uiState.update { it.copy(isSharingTelemetry = isSharing) }
    }

    companion object {
        private const val TAG = "AntiPorn_SettingsVM"
    }
}
