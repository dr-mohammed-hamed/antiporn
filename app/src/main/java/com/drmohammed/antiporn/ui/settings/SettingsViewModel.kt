package com.drmohammed.antiporn.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.ai.service.ScreenAiProjectionService
import com.drmohammed.antiporn.cloud.auth.GoogleAuthManager
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.ota.model.OtaVersionInfo
import com.drmohammed.antiporn.ota.repository.OtaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * ViewModel for SettingsFragment.
 * Manages Screen AI status, OTA updates, Device Owner state, and Google account authentication.
 */
class SettingsViewModel(
    application: Application,
    private val otaRepository: OtaRepository = OtaRepository.getInstance(application),
    private val googleAuthManager: GoogleAuthManager = GoogleAuthManager.getInstance(application),
    private val accountabilityRepo: AccountabilityRepository = AccountabilityRepository.getInstance(application),
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadAppVersion()
        observeOtaUpdates()
        observePolicy()
        startCountdownTicker()
        refreshAll()
    }

    private fun startCountdownTicker() {
        viewModelScope.launch {
            while (true) {
                if (_uiState.value.isDeactivationPending) {
                    val state = _uiState.value
                    if (state.deactivationUnlockAt != null) {
                        val app = getApplication<Application>()
                        val now = TrustedTimeProvider.currentTimeMillis(app)
                        val remaining = (state.deactivationUnlockAt - now).coerceAtLeast(0L)
                        if (remaining != state.remainingDeactivationMs) {
                            _uiState.update { it.copy(remainingDeactivationMs = remaining) }
                        }
                    }
                    kotlinx.coroutines.delay(1000L)
                } else {
                    _uiState.first { it.isDeactivationPending }
                }
            }
        }
    }

    private fun observePolicy() {
        viewModelScope.launch {
            policyRepository.currentPolicyFlow.collect { policy ->
                val app = getApplication<Application>()
                val isDo = com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(app)
                val isPending = policy.deactivationStatus == DevicePolicyBundle.DEACTIVATION_STATUS_PENDING && policy.deactivationUnlockAt != null
                val now = TrustedTimeProvider.currentTimeMillis(app)
                val remaining = if (isPending) {
                    ((policy.deactivationUnlockAt ?: 0L) - now).coerceAtLeast(0L)
                } else 0L

                _uiState.update { current ->
                    current.copy(
                        isDeviceOwner = isDo,
                        isSupervised = policy.isSupervised,
                        isSelfControlAllowed = policy.isSelfControlAllowed,
                        isDeactivationPending = isPending,
                        deactivationUnlockAt = policy.deactivationUnlockAt,
                        remainingDeactivationMs = remaining
                    )
                }
            }
        }
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
            Timber.tag(TAG).w(e, "Error resolving app version")
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
        val isDo = com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(app)
        val policy = policyRepository.getCurrentPolicy()
        val isPending = policy.deactivationStatus == DevicePolicyBundle.DEACTIVATION_STATUS_PENDING && policy.deactivationUnlockAt != null
        val now = TrustedTimeProvider.currentTimeMillis(app)
        val remaining = if (isPending) {
            ((policy.deactivationUnlockAt ?: 0L) - now).coerceAtLeast(0L)
        } else 0L

        _uiState.update {
            it.copy(
                isDeviceOwner = isDo,
                isSupervised = policy.isSupervised,
                isSelfControlAllowed = policy.isSelfControlAllowed,
                isDeactivationPending = isPending,
                deactivationUnlockAt = policy.deactivationUnlockAt,
                remainingDeactivationMs = remaining
            )
        }
    }

    fun requestDeactivation(hasQr: Boolean, onResult: (Result<Long>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = policyRepository.requestDeviceOwnerDeactivation(hasQr)
            withContext(Dispatchers.Main) {
                refreshDeviceOwnerState()
                onResult(result)
            }
        }
    }

    fun cancelDeactivation(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = policyRepository.cancelDeviceOwnerDeactivation()
            withContext(Dispatchers.Main) {
                refreshDeviceOwnerState()
                onResult(result)
            }
        }
    }

    fun executeDeactivation(onResult: (Result<Unit>) -> Unit) {
        if (_uiState.value.isDeactivating) return
        _uiState.update { it.copy(isDeactivating = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                policyRepository.executeDeviceOwnerTeardown()
            } finally {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isDeactivating = false) }
                }
            }
            withContext(Dispatchers.Main) {
                refreshDeviceOwnerState()
                onResult(result)
            }
        }
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
