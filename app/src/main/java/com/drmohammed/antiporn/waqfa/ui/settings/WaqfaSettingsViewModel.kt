package com.drmohammed.antiporn.waqfa.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.accessibility.bus.ForegroundAppEventBus
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.waqfa.engine.WaqfaSessionManager
import com.drmohammed.antiporn.waqfa.model.WaqfaConfig
import com.drmohammed.antiporn.waqfa.model.WaqfaTimeoutReactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

enum class WaqfaEngineType {
    INSTANT_0MS,
    USAGE_STATS_FALLBACK,
    DORMANT
}

data class WaqfaSettingsUiState(
    val config: WaqfaConfig = WaqfaConfig(),
    val isRemote: Boolean = false,
    val wardId: String? = null,
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null,
    val engineType: WaqfaEngineType = WaqfaEngineType.DORMANT,
    val showPermissionDialog: Boolean = false
)

class WaqfaSettingsViewModel(
    application: Application,
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application),
    private val sessionManager: WaqfaSessionManager = WaqfaSessionManager.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WaqfaSettingsUiState())
    val uiState: StateFlow<WaqfaSettingsUiState> = _uiState.asStateFlow()

    private var initialPolicy: DevicePolicyBundle? = null

    fun initialize(isRemote: Boolean, wardId: String?, policyJson: String?) {
        val policy = if (!policyJson.isNullOrBlank()) {
            try {
                DevicePolicyBundle.fromJson(policyJson)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed parsing remote policyJson for Waqfa, falling back to local policy")
                policyRepository.getCurrentPolicy()
            }
        } else {
            policyRepository.getCurrentPolicy()
        }

        initialPolicy = policy
        _uiState.update {
            it.copy(
                config = policy.waqfaConfig,
                isRemote = isRemote,
                wardId = wardId
            )
        }

        if (!isRemote) {
            viewModelScope.launch {
                AccessibilitySelfActivator.enableSilently(getApplication())
                refreshEngineStatus()
            }
        }
    }

    fun refreshEngineStatus() {
        val app = getApplication<Application>()
        val a11yActive = ForegroundAppEventBus.getInstance(app).isAccessibilityActive.value ||
            AccessibilitySelfActivator.isServiceEnabledInSettings(app)

        val hasUsage = CurfewForegroundWatcher.hasUsageStatsPermission(app)

        val engine = when {
            a11yActive -> WaqfaEngineType.INSTANT_0MS
            hasUsage -> WaqfaEngineType.USAGE_STATS_FALLBACK
            else -> WaqfaEngineType.DORMANT
        }

        _uiState.update { it.copy(engineType = engine) }
    }

    fun setWaqfaEnabled(enabled: Boolean) {
        val state = _uiState.value
        _uiState.update { it.copy(config = it.config.copy(isEnabled = enabled)) }

        if (enabled && !state.isRemote) {
            val app = getApplication<Application>()
            val healed = AccessibilitySelfActivator.enableSilently(app)

            if (!healed && !AccessibilitySelfActivator.isServiceEnabledInSettings(app)) {
                _uiState.update { it.copy(showPermissionDialog = true) }
            }
            refreshEngineStatus()
        }
    }

    fun dismissPermissionDialog() {
        _uiState.update { it.copy(showPermissionDialog = false) }
    }

    fun setTargetPackages(packages: Set<String>) {
        _uiState.update { it.copy(config = it.config.copy(targetPackages = packages)) }
    }

    fun setReasonRequired(required: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(isReasonRequired = required)) }
    }

    fun setTimeoutReactionType(type: WaqfaTimeoutReactionType) {
        _uiState.update { it.copy(config = it.config.copy(timeoutReactionType = type)) }
    }

    fun setCooldownMinutes(minutes: Int) {
        val valid = minutes.coerceAtLeast(1)
        _uiState.update { it.copy(config = it.config.copy(cooldownMinutes = valid)) }
    }

    fun saveSettings(onComplete: (Boolean, DevicePolicyBundle?) -> Unit) {
        if (_uiState.value.isLoading) return
        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                if (state.isRemote) {
                    val base = initialPolicy ?: policyRepository.getCurrentPolicy()
                    val updated = base.copy(waqfaConfig = state.config)
                    val ward = state.wardId
                    val success = if (!ward.isNullOrBlank()) {
                        policyRepository.pushPolicyToWard(ward, updated)
                    } else {
                        true
                    }

                    _uiState.update { it.copy(isLoading = false, isSaved = success) }
                    onComplete(success, updated)
                } else {
                    val current = policyRepository.getCurrentPolicy()
                    val updatedPolicy = current.copy(waqfaConfig = state.config)
                    val success = policyRepository.saveAndEnforceSelfPolicy(updatedPolicy)

                    if (success) {
                        sessionManager.updateConfig(state.config)
                        _uiState.update { it.copy(isLoading = false, isSaved = true) }
                    } else {
                        val failedMsg = getApplication<Application>().getString(com.drmohammed.antiporn.R.string.waqfa_settings_save_failed_toast)
                        _uiState.update { it.copy(isLoading = false, errorMessage = failedMsg) }
                    }
                    onComplete(success, updatedPolicy)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error saving waqfa settings")
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
                onComplete(false, null)
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WaqfaSettingsVM"
    }
}
