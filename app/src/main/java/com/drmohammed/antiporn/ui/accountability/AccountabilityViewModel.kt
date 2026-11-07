package com.drmohammed.antiporn.ui.accountability

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.cloud.auth.GoogleAuthManager
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * ViewModel for AccountabilityFragment.
 * Manages partner synchronization, pairing credentials, emergency breakout flow, and approval counting.
 */
class AccountabilityViewModel(
    application: Application,
    private val accountabilityRepo: AccountabilityRepository = AccountabilityRepository.getInstance(application),
    private val approvalRepo: ApprovalRepository = ApprovalRepository.getInstance(application),
    private val googleAuthManager: GoogleAuthManager = GoogleAuthManager.getInstance(application),
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AccountabilityUiState())
    val uiState: StateFlow<AccountabilityUiState> = _uiState.asStateFlow()

    private var breakoutCountdownJob: Job? = null

    init {
        observePolicyFlow()
        refreshAll()
    }

    private fun observePolicyFlow() {
        viewModelScope.launch {
            policyRepository.currentPolicyFlow.collect { policy ->
                _uiState.update { current ->
                    current.copy(
                        isSupervised = policy.isSupervised,
                        breakoutStatus = policy.breakoutStatus,
                        breakoutUnlockAt = policy.breakoutUnlockAt
                    )
                }

                if (policy.isSupervised) {
                    startBreakoutTicker(policy)
                } else {
                    stopBreakoutTicker()
                    loadPairingCode()
                }

                loadPartners()
                updateGoogleSecurityState()
            }
        }
    }

    fun refreshAll() {
        loadDisplayName()
        updateGoogleSecurityState()
        loadPairingCode()
        loadPartners()
        loadPendingApprovals()
    }

    fun loadDisplayName() {
        val name = accountabilityRepo.getMyDisplayName()
        _uiState.update { it.copy(myDisplayName = name) }
    }

    fun updateGoogleSecurityState() {
        val isLinked = googleAuthManager.isLinkedWithGoogle()
        val email = googleAuthManager.getLinkedEmail()
        _uiState.update { current ->
            current.copy(
                isGoogleLinked = isLinked,
                googleAccountEmail = email
            )
        }
    }

    fun loadPairingCode() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentPolicy = policyRepository.getCurrentPolicy()
                if (currentPolicy.isSupervised) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isPairingCodeLocked = true, myPairingCode = "------") }
                    }
                    return@launch
                }

                val code = accountabilityRepo.getOrCreateMyPairingCode()
                withContext(Dispatchers.Main) {
                    val isLocked = (code == AccountabilityRepository.CODE_LOCKED)
                    _uiState.update {
                        it.copy(
                            myPairingCode = code,
                            isPairingCodeLocked = isLocked
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading pairing code", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(myPairingCode = "------") }
                }
            }
        }
    }

    fun loadPartners() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val wards = accountabilityRepo.getWards()
                val guardians = accountabilityRepo.getGuardians()
                withContext(Dispatchers.Main) {
                    _uiState.update { current ->
                        current.copy(
                            wards = wards,
                            guardians = guardians
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading partner records", e)
            }
        }
    }

    fun loadPendingApprovals() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localPending = approvalRepo.getLocalPendingApprovals()
                var totalPending = localPending.size

                val wards = accountabilityRepo.getWards()
                if (wards.isNotEmpty()) {
                    val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    val resolvedWardIds = wards.map { ward ->
                        accountabilityRepo.resolveActivePartnerUid(ward.id, ward.pairingCode)
                    }.distinct()

                    for (wardId in resolvedWardIds) {
                        try {
                            val snap = firestore.collection("policies").document(wardId)
                                .collection("approvals")
                                .whereEqualTo("status", "PENDING")
                                .get()
                                .await()
                            totalPending += snap.size()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed loading pending approvals count for ward $wardId", e)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(pendingApprovalsCount = totalPending) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading approvals", e)
            }
        }
    }

    private fun startBreakoutTicker(policy: DevicePolicyBundle) {
        breakoutCountdownJob?.cancel()
        val app = getApplication<Application>()

        if (policy.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING && policy.breakoutUnlockAt != null) {
            breakoutCountdownJob = viewModelScope.launch {
                while (isActive) {
                    val remainingMs = (policy.breakoutUnlockAt - TrustedTimeProvider.currentTimeMillis(app)).coerceAtLeast(0L)
                    _uiState.update { it.copy(breakoutRemainingMs = remainingMs) }
                    if (remainingMs <= 0L) break
                    delay(1000L)
                }
            }
        } else {
            _uiState.update { it.copy(breakoutRemainingMs = 0L) }
        }
    }

    private fun stopBreakoutTicker() {
        breakoutCountdownJob?.cancel()
        breakoutCountdownJob = null
        _uiState.update { it.copy(breakoutRemainingMs = 0L) }
    }

    fun requestEmergencyBreakout(onResult: (Result<Long>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.requestEmergencyBreakout()
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun cancelEmergencyBreakout(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.cancelEmergencyBreakout()
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun executeEmergencyBreakout(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.executeEmergencyBreakout()
            withContext(Dispatchers.Main) {
                loadPairingCode()
                loadPartners()
                onResult(result)
            }
        }
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

    suspend fun fetchWardPolicy(wardUid: String): DevicePolicyBundle? {
        return accountabilityRepo.fetchWardPolicy(wardUid)
    }

    fun revokeSupervision(wardUid: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.revokeSupervision(wardUid)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    loadPartners()
                }
                onResult(result)
            }
        }
    }

    fun pairWithCode(code: String, role: String, onResult: (Result<PartnerEntity>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.pairWithCode(code, role)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    loadPartners()
                    loadPairingCode()
                }
                onResult(result)
            }
        }
    }

    fun renamePartner(partnerId: String, newName: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.renamePartner(partnerId, newName)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    loadPartners()
                }
                onResult(result)
            }
        }
    }

    override fun onCleared() {
        stopBreakoutTicker()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AntiPorn_AccVM"
    }
}
