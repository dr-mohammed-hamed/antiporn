package com.drmohammed.antiporn.ui.control

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for SelfControlFragment policy editor and gate access management.
 * Tracks draft modifications against baseline snapshot and persists updates safely.
 */
class SelfControlViewModel(
    application: Application,
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application),
    private val approvalRepository: ApprovalRepository = ApprovalRepository.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        SelfControlUiState(
            policy = policyRepository.getCurrentPolicy(),
            initialSnapshot = policyRepository.getCurrentPolicy()
        )
    )
    val uiState: StateFlow<SelfControlUiState> = _uiState.asStateFlow()

    init {
        checkGateAndRefresh()
        observePolicyFlow()
    }

    private fun observePolicyFlow() {
        viewModelScope.launch {
            policyRepository.currentPolicyFlow.collect { freshPolicy ->
                val app = getApplication<Application>()
                val gate = SelfControlAccessGateRegistry.getActiveGate()
                val isLocked = !gate.canAccess(app)

                _uiState.update { current ->
                    // If we haven't modified the draft or have no initial snapshot, sync draft to incoming
                    val newInitial = current.initialSnapshot ?: freshPolicy
                    val hasChanges = current.hasUnsavedChanges && (current.policy != newInitial)
                    val activePolicy = if (hasChanges) current.policy else freshPolicy

                    current.copy(
                        policy = activePolicy,
                        initialSnapshot = if (hasChanges) current.initialSnapshot else freshPolicy,
                        isLocked = isLocked,
                        hasUnsavedChanges = hasChanges
                    )
                }
            }
        }
    }

    /**
     * Re-checks the active access gate and loads current policy if accessible.
     */
    fun checkGateAndRefresh() {
        val app = getApplication<Application>()
        val gate = SelfControlAccessGateRegistry.getActiveGate()
        val isLocked = !gate.canAccess(app)

        _uiState.update { it.copy(isLocked = isLocked) }

        if (!isLocked) {
            loadCurrentPolicy()
            loadPendingApprovals()
        }
    }

    /**
     * Loads the current saved policy from repository as baseline snapshot.
     */
    fun loadCurrentPolicy() {
        viewModelScope.launch(Dispatchers.IO) {
            val policy = policyRepository.getCurrentPolicy()
            withContext(Dispatchers.Main) {
                _uiState.update { current ->
                    current.copy(
                        policy = policy,
                        initialSnapshot = policy,
                        hasUnsavedChanges = false
                    )
                }
            }
        }
    }

    /**
     * Updates draft policy and compares with baseline snapshot to determine unsaved state.
     */
    fun updateDraftPolicy(newDraft: DevicePolicyBundle) {
        _uiState.update { current ->
            val hasChanges = current.initialSnapshot?.let { snapshot ->
                newDraft != snapshot
            } ?: false

            current.copy(
                policy = newDraft,
                hasUnsavedChanges = hasChanges
            )
        }
    }

    /**
     * Saves the modified policy, schedules curfews, and reapplies active shields.
     */
    fun savePolicy(
        policyToSave: DevicePolicyBundle,
        onComplete: ((Result<Unit>) -> Unit)? = null
    ) {
        val app = getApplication<Application>()
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val success = policyRepository.saveAndEnforceSelfPolicy(policyToSave)
                CurfewScheduler.scheduleNextAlarms(app)
                CurfewEnforcer.syncBlockState(app)

                withContext(Dispatchers.Main) {
                    ProtectionEngine.applyAllAsync(app) {
                        Log.i(TAG, "ProtectionEngine reapplied following policy save.")
                    }
                    if (success) {
                        _uiState.update { current ->
                            current.copy(
                                policy = policyToSave,
                                initialSnapshot = policyToSave,
                                isSaving = false,
                                hasUnsavedChanges = false
                            )
                        }
                        onComplete?.invoke(Result.success(Unit))
                    } else {
                        _uiState.update { it.copy(isSaving = false) }
                        onComplete?.invoke(Result.failure(Exception("Failed to enforce policy")))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist policy bundle", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isSaving = false, errorMessage = e.message) }
                    onComplete?.invoke(Result.failure(e))
                }
            }
        }
    }

    /**
     * Refreshes count of pending app approvals.
     */
    fun loadPendingApprovals() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pending = approvalRepository.getLocalPendingApprovals()
                    .filter { !it.isApproved && !it.isRejected }
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(pendingApprovalsCount = pending.size) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching pending approvals", e)
            }
        }
    }

    fun setQrLoading(isLoading: Boolean) {
        _uiState.update { it.copy(isQrLoading = isLoading) }
    }

    companion object {
        private const val TAG = "AntiPorn_SelfCtrlVM"
    }
}
