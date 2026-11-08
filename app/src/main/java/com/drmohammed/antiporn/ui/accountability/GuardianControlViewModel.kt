package com.drmohammed.antiporn.ui.accountability

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Clean MVVM ViewModel for GuardianControlActivity.
 * Coordinates remote policy fetching, offline caching, DNS validation pre-flight probes,
 * partner renaming, and Firestore policy pushes.
 */
class GuardianControlViewModel(
    application: Application,
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application),
    private val accountabilityRepository: AccountabilityRepository = AccountabilityRepository.getInstance(application),
    private val partnerDao: PartnerDao = HisnRoomDatabase.getInstance(application).partnerDao()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(GuardianControlUiState())
    val uiState: StateFlow<GuardianControlUiState> = _uiState.asStateFlow()
    private var loadWardJob: Job? = null

    private val _events = MutableSharedFlow<GuardianControlEvent>()
    val events: SharedFlow<GuardianControlEvent> = _events.asSharedFlow()

    private var isInitialized = false

    fun initialize(wardId: String, wardName: String) {
        if (isInitialized) return
        isInitialized = true
        _uiState.update {
            it.copy(
                wardId = wardId,
                wardName = wardName
            )
        }
        loadWardPolicy()
    }

    fun loadWardPolicy() {
        val state = _uiState.value
        if (state.wardId.isBlank()) return

        _uiState.update { it.copy(isLoading = true) }

        loadWardJob?.cancel()
        loadWardJob = viewModelScope.launch(Dispatchers.IO) {
            val partner = partnerDao.getPartnerById(state.wardId)
            val resolvedWardId = accountabilityRepository.resolveActivePartnerUid(state.wardId, partner?.pairingCode)
            val activeWardId = if (resolvedWardId.isNotBlank()) resolvedWardId else state.wardId
            val activePartner = if (activeWardId != state.wardId) {
                partnerDao.getPartnerById(activeWardId) ?: partner
            } else {
                partner
            }

            val app = getApplication<Application>()
            val fallbackPlaceholder = app.getString(R.string.status_ward_badge)
            val resolvedWardName = if ((state.wardName.isBlank() || state.wardName == fallbackPlaceholder) && activePartner != null && activePartner.displayName.isNotBlank()) {
                activePartner.displayName
            } else {
                state.wardName
            }

            if (activeWardId != state.wardId || resolvedWardName != state.wardName) {
                Log.i(TAG, "[UidMigration] GuardianControlViewModel switched wardId from ${state.wardId} to $activeWardId (name: $resolvedWardName)")
                _uiState.update { it.copy(wardId = activeWardId, wardName = resolvedWardName) }
            }

            var isOffline = false
            var snapshot: com.google.firebase.firestore.DocumentSnapshot? = null
            val firestore = FirebaseFirestore.getInstance()

            try {
                snapshot = firestore.collection("policies").document(activeWardId).get().await()
            } catch (netEx: Exception) {
                if (netEx is CancellationException) throw netEx
                Log.w(TAG, "Network fetch failed for ward policy $activeWardId, attempting offline cache", netEx)
                isOffline = true
                try {
                    snapshot = firestore.collection("policies").document(activeWardId).get(Source.CACHE).await()
                } catch (cacheEx: Exception) {
                    Log.w(TAG, "Cache fetch failed or empty for ward $activeWardId", cacheEx)
                }
            }

            val snapshotData = snapshot?.data
            val isLivePolicy = (snapshot != null && snapshot.exists() && snapshotData != null)
            val policy = if (isLivePolicy) {
                DevicePolicyBundle.fromMap(snapshotData)
            } else {
                DevicePolicyBundle.defaultPolicy()
            }

            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordCloudSync(
                app,
                "WARD_POLICY_FETCH",
                isSuccess = isLivePolicy && !isOffline,
                details = "wardId=$activeWardId, isLive=$isLivePolicy, offline=$isOffline, curfew=${policy.curfewStartHour}:${policy.curfewStartMinute}-${policy.curfewEndHour}:${policy.curfewEndMinute}, selfControl=${policy.isSelfControlAllowed}"
            )

            val version = snapshot?.getLong("version") ?: policy.version
            val acknowledgedVersion = snapshot?.getLong("acknowledgedVersion") ?: 0L
            val lastAcknowledgedAt = snapshot?.getLong("lastAcknowledgedAt") ?: 0L
            val lastHeartbeat = snapshot?.getLong("lastHeartbeat") ?: 0L

            _uiState.update { current ->
                val initial = policy.copy(
                    curfewStartHour = policy.curfewStartHour,
                    curfewStartMinute = policy.curfewStartMinute,
                    curfewEndHour = policy.curfewEndHour,
                    curfewEndMinute = policy.curfewEndMinute,
                    weekendCurfewStartHour = policy.weekendCurfewStartHour,
                    weekendCurfewStartMinute = policy.weekendCurfewStartMinute,
                    weekendCurfewEndHour = policy.weekendCurfewEndHour,
                    weekendCurfewEndMinute = policy.weekendCurfewEndMinute,
                    isCurfewEnabled = policy.isCurfewEnabled,
                    isWeekendCurfewEnabled = policy.isWeekendCurfewEnabled,
                    isSelfControlAllowed = policy.isSelfControlAllowed,
                    isVpnBlackholeEnabled = policy.isVpnBlackholeEnabled,
                    isInstantInternetKillEnabled = policy.isInstantInternetKillEnabled,
                    isIncognitoBlocked = policy.isIncognitoBlocked,
                    lockNewInstallsUntilApproved = policy.lockNewInstallsUntilApproved,
                    isDateTimeLocked = policy.isDateTimeLocked,
                    isYouTubeRestrictedModeEnabled = policy.isYouTubeRestrictedModeEnabled,
                    isTelegramWebBlocked = policy.isTelegramWebBlocked,
                    dnsHost = policy.dnsHost.trim(),
                    timeZoneId = policy.timeZoneId.ifBlank { CurfewConfig.DEFAULT_TIMEZONE_ID }
                )

                current.copy(
                    wardId = activeWardId,
                    isLoading = false,
                    isOffline = isOffline,
                    currentPolicy = policy,
                    initialPolicySnapshot = initial,
                    selectedStartHour = policy.curfewStartHour,
                    selectedStartMinute = policy.curfewStartMinute,
                    selectedEndHour = policy.curfewEndHour,
                    selectedEndMinute = policy.curfewEndMinute,
                    selectedWeekendStartHour = policy.weekendCurfewStartHour,
                    selectedWeekendStartMinute = policy.weekendCurfewStartMinute,
                    selectedWeekendEndHour = policy.weekendCurfewEndHour,
                    selectedWeekendEndMinute = policy.weekendCurfewEndMinute,
                    selectedTimeZoneId = policy.timeZoneId.ifBlank { CurfewConfig.DEFAULT_TIMEZONE_ID },
                    isCurfewEnabled = policy.isCurfewEnabled,
                    isWeekendCurfewEnabled = policy.isWeekendCurfewEnabled,
                    isSelfControlAllowed = policy.isSelfControlAllowed,
                    isVpnBlackholeEnabled = policy.isVpnBlackholeEnabled,
                    isInstantInternetKillEnabled = policy.isInstantInternetKillEnabled,
                    isIncognitoBlocked = policy.isIncognitoBlocked,
                    lockNewInstallsUntilApproved = policy.lockNewInstallsUntilApproved,
                    isDateTimeLocked = policy.isDateTimeLocked,
                    isYouTubeRestrictedModeEnabled = policy.isYouTubeRestrictedModeEnabled,
                    isTelegramWebBlocked = policy.isTelegramWebBlocked,
                    dnsHost = policy.dnsHost.trim(),
                    version = version,
                    acknowledgedVersion = acknowledgedVersion,
                    lastAcknowledgedAt = lastAcknowledgedAt,
                    lastHeartbeat = lastHeartbeat
                )
            }
        }
    }

    fun setCurfewTimings(startHour: Int, startMin: Int, endHour: Int, endMin: Int) {
        _uiState.update {
            it.copy(
                selectedStartHour = startHour,
                selectedStartMinute = startMin,
                selectedEndHour = endHour,
                selectedEndMinute = endMin
            )
        }
    }

    fun setWeekendCurfewTimings(startHour: Int, startMin: Int, endHour: Int, endMin: Int) {
        _uiState.update {
            it.copy(
                selectedWeekendStartHour = startHour,
                selectedWeekendStartMinute = startMin,
                selectedWeekendEndHour = endHour,
                selectedWeekendEndMinute = endMin
            )
        }
    }

    fun setTimeZoneId(tzId: String) {
        _uiState.update { it.copy(selectedTimeZoneId = tzId) }
    }

    fun setDnsHost(host: String) {
        _uiState.update { it.copy(dnsHost = host.trim()) }
    }

    fun toggleCurfew(enabled: Boolean) {
        _uiState.update { it.copy(isCurfewEnabled = enabled) }
    }

    fun toggleWeekendCurfew(enabled: Boolean) {
        _uiState.update { it.copy(isWeekendCurfewEnabled = enabled) }
    }

    fun toggleSelfControl(allowed: Boolean) {
        _uiState.update { it.copy(isSelfControlAllowed = allowed) }
    }

    fun toggleVpnBlackhole(enabled: Boolean) {
        _uiState.update { it.copy(isVpnBlackholeEnabled = enabled) }
    }

    fun toggleInstantInternetKill(enabled: Boolean) {
        _uiState.update { it.copy(isInstantInternetKillEnabled = enabled) }
    }

    fun toggleIncognitoBlocked(blocked: Boolean) {
        _uiState.update { it.copy(isIncognitoBlocked = blocked) }
    }

    fun toggleLockNewInstalls(locked: Boolean) {
        _uiState.update { it.copy(lockNewInstallsUntilApproved = locked) }
    }

    fun toggleLockDateTime(locked: Boolean) {
        _uiState.update { it.copy(isDateTimeLocked = locked) }
    }

    fun toggleYouTubeRestricted(enabled: Boolean) {
        _uiState.update { it.copy(isYouTubeRestrictedModeEnabled = enabled) }
    }

    fun toggleTelegramWeb(enabled: Boolean) {
        _uiState.update { it.copy(isTelegramWebBlocked = enabled) }
    }

    fun updateBrowserFilter(filterMode: Int, customBlocked: Set<String>, customAllowed: Set<String>) {
        _uiState.update { current ->
            val updated = current.currentPolicy.copy(
                browserFilterMode = filterMode,
                customBlockedUrls = customBlocked,
                customAllowedUrls = customAllowed
            )
            current.copy(currentPolicy = updated)
        }
    }

    fun onPolicyReverted(reverted: DevicePolicyBundle) {
        _uiState.update {
            it.copy(
                currentPolicy = reverted,
                initialPolicySnapshot = reverted.copy(),
                selectedStartHour = reverted.curfewStartHour,
                selectedStartMinute = reverted.curfewStartMinute,
                selectedEndHour = reverted.curfewEndHour,
                selectedEndMinute = reverted.curfewEndMinute,
                selectedWeekendStartHour = reverted.weekendCurfewStartHour,
                selectedWeekendStartMinute = reverted.weekendCurfewStartMinute,
                selectedWeekendEndHour = reverted.weekendCurfewEndHour,
                selectedWeekendEndMinute = reverted.weekendCurfewEndMinute,
                selectedTimeZoneId = reverted.timeZoneId,
                isCurfewEnabled = reverted.isCurfewEnabled,
                isWeekendCurfewEnabled = reverted.isWeekendCurfewEnabled,
                isSelfControlAllowed = reverted.isSelfControlAllowed,
                isVpnBlackholeEnabled = reverted.isVpnBlackholeEnabled,
                isInstantInternetKillEnabled = reverted.isInstantInternetKillEnabled,
                isIncognitoBlocked = reverted.isIncognitoBlocked,
                lockNewInstallsUntilApproved = reverted.lockNewInstallsUntilApproved,
                isDateTimeLocked = reverted.isDateTimeLocked,
                isYouTubeRestrictedModeEnabled = reverted.isYouTubeRestrictedModeEnabled,
                isTelegramWebBlocked = reverted.isTelegramWebBlocked,
                dnsHost = reverted.dnsHost.trim()
            )
        }
    }

    fun onPolicyAcknowledged(acknowledged: DevicePolicyBundle) {
        _uiState.update {
            it.copy(
                currentPolicy = acknowledged,
                initialPolicySnapshot = acknowledged.copy()
            )
        }
    }

    fun saveAndPushPolicy(rawDns: String) {
        val state = _uiState.value
        if (state.isSaving || !state.hasUnsavedChanges) return

        if (!rawDns.matches(Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"))) {
            viewModelScope.launch {
                _events.emit(GuardianControlEvent.ShowToastRes(R.string.error_invalid_dns_host))
            }
            return
        }

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()

            // 1. DNS Pre-flight probe
            val validationResult = DnsFilteringValidator.validateDnsHost(rawDns)
            when (validationResult) {
                is DnsFilteringValidator.ValidationResult.Valid -> {
                    // Valid, continue
                }
                is DnsFilteringValidator.ValidationResult.UnfilteredPorn -> {
                    _uiState.update { it.copy(isSaving = false) }
                    _events.emit(
                        GuardianControlEvent.DnsValidationError(
                            titleRes = R.string.dns_probe_dialog_failed_title,
                            message = context.getString(
                                R.string.dns_probe_error_not_blocking_detail,
                                validationResult.domain,
                                validationResult.resolvedIp
                            )
                        )
                    )
                    return@launch
                }
                is DnsFilteringValidator.ValidationResult.Unreachable -> {
                    _uiState.update { it.copy(isSaving = false) }
                    _events.emit(
                        GuardianControlEvent.DnsValidationError(
                            titleRes = R.string.dns_probe_dialog_failed_title,
                            message = context.getString(
                                R.string.dns_probe_error_unreachable_detail,
                                validationResult.reason
                            )
                        )
                    )
                    return@launch
                }
                is DnsFilteringValidator.ValidationResult.ResolverBroken -> {
                    _uiState.update { it.copy(isSaving = false) }
                    _events.emit(
                        GuardianControlEvent.DnsValidationError(
                            titleRes = R.string.dns_probe_dialog_failed_title,
                            message = context.getString(
                                R.string.dns_probe_error_broken_detail,
                                validationResult.reason
                            )
                        )
                    )
                    return@launch
                }
            }

            // 2. Fetch latest policy to avoid version stomp
            val firestore = FirebaseFirestore.getInstance()
            val latestPolicy = try {
                val snap = firestore.collection("policies").document(state.wardId).get().await()
                if (snap != null && snap.exists() && snap.data != null) {
                    DevicePolicyBundle.fromMap(snap.data ?: emptyMap())
                } else {
                    state.currentPolicy
                }
            } catch (netEx: Exception) {
                if (netEx is CancellationException) throw netEx
                Log.w(TAG, "Failed fetching latest policy before push, using current state", netEx)
                state.currentPolicy
            }

            val myUid = accountabilityRepository.getCachedUid()
            val myName = accountabilityRepository.getMyDisplayName()

            val updatedPolicy = latestPolicy.copy(
                guardianUid = latestPolicy.guardianUid?.takeIf { it.isNotBlank() } ?: myUid,
                guardianName = latestPolicy.guardianName?.takeIf { it.isNotBlank() } ?: myName,
                isSupervised = true,
                curfewStartHour = state.selectedStartHour,
                curfewStartMinute = state.selectedStartMinute,
                curfewEndHour = state.selectedEndHour,
                curfewEndMinute = state.selectedEndMinute,
                isCurfewEnabled = state.isCurfewEnabled,
                isInstantInternetKillEnabled = state.isInstantInternetKillEnabled,
                isWeekendCurfewEnabled = state.isWeekendCurfewEnabled,
                weekendCurfewStartHour = state.selectedWeekendStartHour,
                weekendCurfewStartMinute = state.selectedWeekendStartMinute,
                weekendCurfewEndHour = state.selectedWeekendEndHour,
                weekendCurfewEndMinute = state.selectedWeekendEndMinute,
                isVpnBlackholeEnabled = state.isVpnBlackholeEnabled,
                isIncognitoBlocked = state.isIncognitoBlocked,
                lockNewInstallsUntilApproved = state.lockNewInstallsUntilApproved,
                isSelfControlAllowed = state.isSelfControlAllowed,
                isDateTimeLocked = state.isDateTimeLocked,
                isYouTubeRestrictedModeEnabled = state.isYouTubeRestrictedModeEnabled,
                isTelegramWebBlocked = state.isTelegramWebBlocked,
                browserFilterMode = state.currentPolicy.browserFilterMode,
                customBlockedUrls = state.currentPolicy.customBlockedUrls,
                customAllowedUrls = state.currentPolicy.customAllowedUrls,
                dnsHost = rawDns,
                timeZoneId = state.selectedTimeZoneId,
                version = latestPolicy.version + 1,
                guardianPolicySnapshot = null,
                lastSelfModifiedAt = null,
                updatedAt = TrustedTimeProvider.currentTimeMillis(context)
            )

            try {
                val success = policyRepository.pushPolicyToWard(state.wardId, updatedPolicy)
                _uiState.update { it.copy(isSaving = false) }

                if (success) {
                    _uiState.update {
                        it.copy(
                            currentPolicy = updatedPolicy,
                            initialPolicySnapshot = updatedPolicy.copy(),
                            version = updatedPolicy.version
                        )
                    }
                    _events.emit(GuardianControlEvent.ShowToastRes(R.string.toast_policy_saved, isLong = true))
                    _events.emit(GuardianControlEvent.SaveSuccess)
                } else {
                    _events.emit(GuardianControlEvent.ShowToastRes(R.string.toast_policy_push_failed, isLong = true))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error pushing policy to ward", e)
                _uiState.update { it.copy(isSaving = false) }
                _events.emit(GuardianControlEvent.ShowToastRes(R.string.toast_policy_push_failed, isLong = true))
            }
        }
    }

    fun renameWard(newName: String) {
        val state = _uiState.value
        if (newName.isBlank() || newName == state.wardName) return

        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepository.renamePartner(state.wardId, newName)
            result.onSuccess {
                _uiState.update { it.copy(wardName = newName) }
                _events.emit(GuardianControlEvent.ShowToastRes(R.string.toast_name_saved))
            }.onFailure { e ->
                Log.e(TAG, "Failed to rename ward", e)
                val msg = e.localizedMessage
                if (!msg.isNullOrBlank()) {
                    _events.emit(GuardianControlEvent.ShowToastString(msg, isLong = true))
                } else {
                    _events.emit(GuardianControlEvent.ShowToastRes(R.string.toast_rename_failed, isLong = true))
                }
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_GuardianVM"
    }
}
