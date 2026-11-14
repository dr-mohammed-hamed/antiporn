package com.drmohammed.antiporn.waqfa.ui.gate

import android.app.Application
import timber.log.Timber
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.waqfa.engine.WaqfaSessionManager
import com.drmohammed.antiporn.waqfa.model.WaqfaConfig
import com.drmohammed.antiporn.waqfa.model.WaqfaIntentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WaqfaGateUiState(
    val packageName: String = "",
    val appName: String = "",
    val intentType: WaqfaIntentType = WaqfaIntentType.PURPOSEFUL,
    val reasonText: String = "",
    val selectedDurationMinutes: Int = 15,
    val availableDurations: List<Int> = listOf(10, 15, 30),
    val isReasonRequired: Boolean = false,
    val reasonError: String? = null,
    val isSubmitting: Boolean = false,
    val isSessionStarted: Boolean = false,
    val allowOpenSession: Boolean = false,
    val allowCustomDuration: Boolean = true,
    val isOpenSession: Boolean = false,
    val customDurationMinutes: Int? = null
)

class WaqfaGateViewModel(
    application: Application,
    private val sessionManager: WaqfaSessionManager = WaqfaSessionManager.getInstance(application),
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WaqfaGateUiState())
    val uiState: StateFlow<WaqfaGateUiState> = _uiState.asStateFlow()

    fun init(packageName: String, appName: String) {
        val config = try {
            policyRepository.getCurrentPolicy().waqfaConfig
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed reading policy for gate, falling back to cached config")
            sessionManager.getConfig()
        }

        val durations = if (config.quickDurationsMinutes.isNotEmpty()) {
            config.quickDurationsMinutes
        } else {
            WaqfaConfig.DEFAULT_DURATIONS
        }

        val defaultDuration = durations.getOrNull(1) ?: durations.firstOrNull() ?: 15

        _uiState.update {
            it.copy(
                packageName = packageName,
                appName = appName,
                availableDurations = durations,
                selectedDurationMinutes = defaultDuration,
                isReasonRequired = config.isReasonRequired,
                allowOpenSession = config.allowOpenSession,
                allowCustomDuration = config.allowCustomDuration,
                isOpenSession = false,
                customDurationMinutes = null
            )
        }
    }

    fun setIntentType(type: WaqfaIntentType) {
        _uiState.update { it.copy(intentType = type) }
    }

    fun setReasonText(text: String) {
        _uiState.update {
            it.copy(
                reasonText = text,
                reasonError = if (it.isReasonRequired && text.isNotBlank()) null else it.reasonError
            )
        }
    }

    fun setDurationMinutes(minutes: Int) {
        _uiState.update {
            it.copy(
                selectedDurationMinutes = minutes,
                isOpenSession = false
            )
        }
    }

    fun setOpenSession() {
        _uiState.update {
            it.copy(
                selectedDurationMinutes = 0,
                isOpenSession = true
            )
        }
    }

    fun setCustomDuration(minutes: Int) {
        val valid = minutes.coerceIn(1, 180)
        _uiState.update {
            it.copy(
                selectedDurationMinutes = valid,
                customDurationMinutes = valid,
                isOpenSession = false
            )
        }
    }

    fun submitSession(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.isSubmitting || state.packageName.isBlank()) return

        if (state.isReasonRequired && state.reasonText.trim().isBlank()) {
            val errorMsg = getApplication<Application>().getString(com.drmohammed.antiporn.R.string.waqfa_reason_required_error)
            _uiState.update { it.copy(reasonError = errorMsg) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, reasonError = null) }

        viewModelScope.launch {
            try {
                sessionManager.startSession(
                    packageName = state.packageName,
                    appName = state.appName,
                    intentType = state.intentType,
                    reasonOrFeeling = state.reasonText.trim(),
                    durationMinutes = state.selectedDurationMinutes
                )
                _uiState.update { it.copy(isSubmitting = false, isSessionStarted = true) }
                onSuccess()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed starting Waqfa session for %s", state.packageName)
                _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WaqfaGateVM"
    }
}
