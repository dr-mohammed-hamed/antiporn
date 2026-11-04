package com.drmohammed.antiporn.ui.home

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.curfew.NapController
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.ui.ProtectionUiState
import com.drmohammed.antiporn.ui.shields.ActiveShieldsEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for HomeFragment hero shield dashboard and 1-hour nap orchestration.
 * Emits immutable [HomeUiState] flows and observes system policy updates.
 */
class HomeViewModel(
    application: Application,
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var napTickerJob: Job? = null

    init {
        observePolicy()
        tickNap()
        refreshProtectionState(isEnforcing = false)
    }

    private fun observePolicy() {
        viewModelScope.launch {
            policyRepository.currentPolicyFlow.collect {
                refreshProtectionState(isEnforcing = false)
            }
        }
    }

    private fun startNapTicker() {
        napTickerJob?.cancel()
        napTickerJob = viewModelScope.launch {
            while (isActive) {
                tickNap()
                val remaining = NapController.remainingMs(getApplication())
                if (remaining <= 0L) {
                    stopNapTicker()
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun stopNapTicker() {
        napTickerJob?.cancel()
        napTickerJob = null
    }

    /**
     * Ticks nap countdown and updates night curfew / usage status.
     * Automatically engages or terminates the 1-second countdown job based on nap activity.
     */
    fun tickNap() {
        val app = getApplication<Application>()
        val remaining = NapController.remainingMs(app)
        val isNap = remaining > 0L
        val isCurfew = CurfewEnforcer.isCurfewActive(app)
        val hasUsage = CurfewForegroundWatcher.hasUsageStatsPermission(app)

        _uiState.update { current ->
            current.copy(
                napRemainingMs = remaining,
                isNapActive = isNap,
                isCurfewActive = isCurfew,
                hasUsageAccess = hasUsage
            )
        }

        if (isNap && napTickerJob == null) {
            startNapTicker()
        } else if (!isNap && napTickerJob != null) {
            stopNapTicker()
        }
    }

    /**
     * Evaluates active shields and updates [ProtectionUiState].
     */
    fun refreshProtectionState(isEnforcing: Boolean = false) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val isDo = ProtectionEngine.isDeviceOwner(app)
            val shields = ProtectionEngine.evaluateSystemStatus(app)

            val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = ProtectionEngine.getAdminComponent(app)
            val currentDnsHost = if (dpm != null && isDo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    dpm.getGlobalPrivateDnsHost(admin)
                } catch (e: Exception) {
                    null
                }
            } else null

            // Also check active shields evaluator for diagnostic completeness
            val activeShields = ActiveShieldsEvaluator.evaluateActiveShields(app)
            Log.d(TAG, "Evaluated ${activeShields.size} active shields (isDo=$isDo)")

            val state = ProtectionUiState.Success(
                isDeviceOwner = isDo,
                shields = shields,
                currentDnsHost = currentDnsHost,
                isEnforcing = isEnforcing
            )

            _uiState.update { current ->
                current.copy(
                    protectionState = state,
                    isApplying = isEnforcing
                )
            }
        }
    }

    /**
     * Toggles/enforces system-wide protection shields.
     */
    fun toggleProtection(onResult: ((Boolean, String?) -> Unit)? = null) {
        if (_uiState.value.isApplying) {
            Log.d(TAG, "toggleProtection: protection enforcement already in progress, ignoring duplicate request.")
            return
        }
        val app = getApplication<Application>()
        _uiState.update { it.copy(isApplying = true) }
        refreshProtectionState(isEnforcing = true)

        ProtectionEngine.applyAllAsync(app) { results ->
            val allSuccess = results.all { it.success }
            val errorMsg = results.firstOrNull { !it.success }?.message
            refreshProtectionState(isEnforcing = false)
            _uiState.update { it.copy(isApplying = false) }
            onResult?.invoke(allSuccess, errorMsg)
        }
    }

    /**
     * Starts a 1-Hour Nap mode if not currently in nighttime curfew.
     */
    fun startNap(onComplete: ((Boolean) -> Unit)? = null) {
        val app = getApplication<Application>()
        if (CurfewEnforcer.isCurfewActive(app)) {
            onComplete?.invoke(false)
            return
        }

        NapController.startNap(app)
        CurfewScheduler.scheduleNapEndAlarm(app, NapController.getEndWall(app))
        CurfewEnforcer.syncBlockStateAsync(app) {
            tickNap()
            onComplete?.invoke(true)
        }
    }

    /**
     * Extends an active nap mode by an additional hour.
     */
    fun extendNap(onComplete: ((Boolean) -> Unit)? = null) {
        val app = getApplication<Application>()
        if (!NapController.isNapActive(app)) {
            onComplete?.invoke(false)
            return
        }

        NapController.extendNap(app)
        CurfewScheduler.scheduleNapEndAlarm(app, NapController.getEndWall(app))
        CurfewEnforcer.syncBlockStateAsync(app) {
            tickNap()
            onComplete?.invoke(true)
        }
    }

    /**
     * Cancel nap operation.
     * Note: NapController enforces a strict fail-closed security invariant (no public cancel API).
     */
    fun cancelNap() {
        Log.i(TAG, "cancelNap requested: Nap is tamper-proof and expires automatically.")
        tickNap()
    }

    override fun onCleared() {
        stopNapTicker()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AntiPorn_HomeVM"
    }
}
