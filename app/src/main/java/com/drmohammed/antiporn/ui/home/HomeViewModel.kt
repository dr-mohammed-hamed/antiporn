package com.drmohammed.antiporn.ui.home

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.curfew.NapController
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ui.ProtectionUiState
import com.drmohammed.antiporn.ui.shields.ActiveShieldsEvaluator
import java.util.concurrent.atomic.AtomicLong
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

    private val refreshLock = Any()
    private var refreshJob: Job? = null
    private var hasNonEnforcingRefresh = false
    private var lastNonEnforcingRefreshMs = 0L
    private var lastRefreshWasEnforcing = false
    private var isEnforcementOperationActive = false
    private val pendingToggleCallbacks = mutableListOf<(Boolean, String?) -> Unit>()
    private val refreshGeneration = AtomicLong(0L)

    init {
        observePolicy()
        tickNap()
        refreshProtectionState(isEnforcing = false)
    }

    private fun observePolicy() {
        viewModelScope.launch {
            var lastObservedPolicy: DevicePolicyBundle? = null
            policyRepository.currentPolicyFlow.collect { policy ->
                val isPolicyChanged = lastObservedPolicy != null && lastObservedPolicy != policy
                lastObservedPolicy = policy
                tickNap()
                if (isPolicyChanged) {
                    refreshProtectionStateInternal(isEnforcing = false, force = true)
                } else {
                    refreshProtectionState(isEnforcing = false)
                }
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
        val isCurfew = isEnforcedCurfewActive(app)
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
     * Coalesces redundant back-to-back non-enforcing refreshes within [REFRESH_COALESCE_WINDOW_MS]
     * while always honoring enforcing refreshes and state-changing updates.
     */
    fun refreshProtectionState(isEnforcing: Boolean = false, force: Boolean = false) {
        refreshProtectionStateInternal(isEnforcing = isEnforcing, force = force)
    }

    private fun refreshProtectionStateInternal(isEnforcing: Boolean, force: Boolean) {
        val app = getApplication<Application>()
        val nowMs = System.nanoTime() / 1_000_000L
        val requestGen: Long
        val effectiveEnforcing: Boolean
        synchronized(refreshLock) {
            val elapsedSinceLast = nowMs - lastNonEnforcingRefreshMs
            if (shouldSkipCoalescedRefresh(
                    isEnforcing = isEnforcing,
                    force = force,
                    isEnforcementOperationActive = isEnforcementOperationActive,
                    lastRefreshWasEnforcing = lastRefreshWasEnforcing,
                    hasPreviousNonEnforcing = hasNonEnforcingRefresh,
                    elapsedSinceLastMs = elapsedSinceLast
                )
            ) {
                return
            }

            effectiveEnforcing = resolveEffectiveEnforcing(isEnforcing, isEnforcementOperationActive)
            if (!effectiveEnforcing) {
                hasNonEnforcingRefresh = true
                lastNonEnforcingRefreshMs = nowMs
            }
            lastRefreshWasEnforcing = effectiveEnforcing

            refreshJob?.cancel()
            requestGen = refreshGeneration.incrementAndGet()
            refreshJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val isDo = ProtectionEngine.isDeviceOwner(app)
                    val shields = ProtectionEngine.evaluateSystemStatus(app)

                    val currentDnsHost = com.drmohammed.antiporn.telemetry.SafeDpmGateway.getGlobalPrivateDnsHost(app).getOrNull()

                    // Reuse precomputed shield statuses to avoid redundant ProtectionEngine evaluation
                    val activeShields = ActiveShieldsEvaluator.evaluateActiveShields(
                        context = app,
                        precomputedStatuses = shields
                    )
                    Log.d(TAG, "Evaluated ${activeShields.size} active shields (isDo=$isDo)")

                    if (refreshGeneration.get() != requestGen) {
                        return@launch
                    }

                    val state = ProtectionUiState.Success(
                        isDeviceOwner = isDo,
                        shields = shields,
                        currentDnsHost = currentDnsHost,
                        isEnforcing = effectiveEnforcing
                    )

                    _uiState.update { current ->
                        if (refreshGeneration.get() != requestGen) {
                            current
                        } else {
                            current.copy(
                                protectionState = state,
                                isApplying = effectiveEnforcing
                            )
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed refreshing protection state", e)
                    if (refreshGeneration.get() == requestGen) {
                        val enforcingActive = synchronized(refreshLock) { isEnforcementOperationActive }
                        _uiState.update { current ->
                            current.copy(
                                protectionState = ProtectionUiState.Error(
                                    e.localizedMessage ?: app.getString(R.string.error_evaluating_protection)
                                ),
                                isApplying = enforcingActive
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Toggles/enforces system-wide protection shields.
     */
    fun toggleProtection(onResult: ((Boolean, String?) -> Unit)? = null) {
        synchronized(refreshLock) {
            if (_uiState.value.isApplying || isEnforcementOperationActive) {
                Log.d(TAG, "toggleProtection: protection enforcement already in progress, attaching callback to active run.")
                onResult?.let { pendingToggleCallbacks.add(it) }
                return
            }
            isEnforcementOperationActive = true
            onResult?.let { pendingToggleCallbacks.add(it) }
        }
        val app = getApplication<Application>()
        _uiState.update { it.copy(isApplying = true) }
        refreshProtectionState(isEnforcing = true)

        ProtectionEngine.applyAllAsync(app) { results ->
            val allSuccess = results.all { it.success }
            val errorMsg = results.firstOrNull { !it.success }?.message
            val callbacks = synchronized(refreshLock) {
                isEnforcementOperationActive = false
                pendingToggleCallbacks.toList().also { pendingToggleCallbacks.clear() }
            }
            _uiState.update { it.copy(isApplying = false) }
            refreshProtectionStateInternal(isEnforcing = false, force = true)
            callbacks.forEach { it.invoke(allSuccess, errorMsg) }
        }
    }

    /**
     * Starts a 1-Hour Nap mode if Device Owner is active and nighttime curfew is not active.
     */
    fun startNap(onComplete: ((Boolean) -> Unit)? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            if (!ProtectionEngine.isDeviceOwner(app) || isEnforcedCurfewActive(app)) {
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false)
                }
                return@launch
            }

            val endElapsed = NapController.startNap(app)
            if (endElapsed <= 0L) {
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false)
                }
                return@launch
            }
            CurfewScheduler.scheduleNapEndAlarm(app, NapController.getEndWall(app))
            CurfewEnforcer.syncBlockStateAsync(app) { synced ->
                tickNap()
                onComplete?.invoke(synced)
            }
        }
    }

    /**
     * Extends an active nap mode by an additional hour if Device Owner is active.
     */
    fun extendNap(onComplete: ((Boolean) -> Unit)? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            if (!ProtectionEngine.isDeviceOwner(app) || !NapController.isNapActive(app)) {
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false)
                }
                return@launch
            }

            val endElapsed = NapController.extendNap(app)
            if (endElapsed <= 0L) {
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false)
                }
                return@launch
            }
            CurfewScheduler.scheduleNapEndAlarm(app, NapController.getEndWall(app))
            CurfewEnforcer.syncBlockStateAsync(app) { synced ->
                tickNap()
                onComplete?.invoke(synced)
            }
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
        synchronized(refreshLock) {
            pendingToggleCallbacks.clear()
        }
        stopNapTicker()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AntiPorn_HomeVM"
        internal const val REFRESH_COALESCE_WINDOW_MS = 1_000L

        internal fun shouldSkipCoalescedRefresh(
            isEnforcing: Boolean,
            force: Boolean,
            isEnforcementOperationActive: Boolean,
            lastRefreshWasEnforcing: Boolean,
            hasPreviousNonEnforcing: Boolean,
            elapsedSinceLastMs: Long,
            coalesceWindowMs: Long = REFRESH_COALESCE_WINDOW_MS
        ): Boolean {
            if (isEnforcing || force) return false
            if (isEnforcementOperationActive) return true
            if (lastRefreshWasEnforcing) return false
            if (!hasPreviousNonEnforcing) return false
            return elapsedSinceLastMs in 0L until coalesceWindowMs
        }

        internal fun resolveEffectiveEnforcing(
            requestedEnforcing: Boolean,
            isEnforcementOperationActive: Boolean
        ): Boolean = requestedEnforcing || isEnforcementOperationActive

        /**
         * Returns true only when Device Owner is active and Night Curfew is effectively enforced,
         * keeping Nap unlocked when curfew is disabled or before Device Owner provisioning.
         */
        internal fun isEnforcedCurfewActive(context: Context): Boolean =
            ProtectionEngine.isDeviceOwner(context) && CurfewEnforcer.isEffectiveCurfewActive(context)
    }
}
