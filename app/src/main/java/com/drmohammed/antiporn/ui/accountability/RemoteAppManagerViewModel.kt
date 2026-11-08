package com.drmohammed.antiporn.ui.accountability

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.data.model.InstalledAppDto
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.AppInventoryManager
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.telemetry.DpmExecutionResult
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 * Clean MVVM ViewModel for RemoteAppManagerActivity.
 * Handles state management, Firestore synchronization, app search filtering,
 * package suspension toggling, and repair routines.
 */
class RemoteAppManagerViewModel(
    application: Application,
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application),
    private val accountabilityRepository: AccountabilityRepository = AccountabilityRepository.getInstance(application),
    private val appInventoryManager: AppInventoryManager = AppInventoryManager.getInstance(application),
    private val approvalRepository: ApprovalRepository = ApprovalRepository.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RemoteAppManagerUiState())
    val uiState: StateFlow<RemoteAppManagerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<RemoteAppManagerEvent>()
    val events: SharedFlow<RemoteAppManagerEvent> = _events.asSharedFlow()

    private var inventoryListener: ListenerRegistration? = null
    private var isInitialized = false

    fun initialize(isLocalMode: Boolean, wardId: String, wardName: String) {
        if (isInitialized) return
        isInitialized = true
        _uiState.update {
            it.copy(
                isLocalMode = isLocalMode,
                wardId = wardId,
                wardName = wardName
            )
        }
        loadData()
    }

    fun loadData() {
        _uiState.update { it.copy(isLoading = true) }
        val state = _uiState.value

        viewModelScope.launch(Dispatchers.IO) {
            if (state.isLocalMode) {
                loadLocalData()
            } else {
                loadRemoteData(state.wardId)
            }
        }
    }

    private suspend fun loadLocalData() {
        try {
            val policy = policyRepository.getCurrentPolicy()
            val localApps = appInventoryManager.getInstalledApps()
            val sortedList = localApps.sortedWith(
                compareBy({ it.appName.lowercase() }, { it.packageName })
            )

            val query = _uiState.value.searchQuery.trim().lowercase()
            val filtered = filterAppsList(sortedList, query)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    currentPolicy = policy,
                    customBlacklist = policy.customBlacklistPackages.toSet(),
                    customCurfewWhitelist = policy.customCurfewWhitelistPackages.toSet(),
                    curfewVpnBypass = policy.curfewVpnBypassPackages.toSet(),
                    protectedApps = policy.protectedAppPackages.toSet(),
                    lockNewInstallsUntilApproved = policy.lockNewInstallsUntilApproved,
                    initialBlacklist = policy.customBlacklistPackages.toSet(),
                    initialCurfewWhitelist = policy.customCurfewWhitelistPackages.toSet(),
                    initialCurfewVpnBypass = policy.curfewVpnBypassPackages.toSet(),
                    initialProtectedApps = policy.protectedAppPackages.toSet(),
                    initialLockNewInstalls = policy.lockNewInstallsUntilApproved,
                    allApps = sortedList,
                    displayedApps = filtered
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed loading local app manager data", e)
            _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
        }
    }

    private suspend fun loadRemoteData(wardId: String) {
        val firestore = FirebaseFirestore.getInstance()

        // 1. Fetch current policy bundle
        var policySnapshot: DocumentSnapshot? = null
        try {
            policySnapshot = firestore.collection("policies").document(wardId).get().await()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Network fetch failed for ward policy $wardId, trying cache", e)
            try {
                policySnapshot = firestore.collection("policies").document(wardId).get(Source.CACHE).await()
            } catch (cacheEx: Exception) {
                Log.w(TAG, "Cache fetch failed for ward policy $wardId", cacheEx)
            }
        }

        val policyData = policySnapshot?.data
        val policy = if (policySnapshot != null && policySnapshot.exists() && policyData != null) {
            DevicePolicyBundle.fromMap(policyData)
        } else {
            DevicePolicyBundle.defaultPolicy()
        }

        // 2. Fetch inventory
        var inventoryDoc: DocumentSnapshot? = null
        try {
            inventoryDoc = firestore.collection("inventories").document(wardId).get().await()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Network fetch failed for inventory $wardId, trying cache", e)
            try {
                inventoryDoc = firestore.collection("inventories").document(wardId).get(Source.CACHE).await()
            } catch (cacheEx: Exception) {
                Log.w(TAG, "Cache fetch failed for inventory $wardId", cacheEx)
            }
        }

        val sortedList = parseAndSortInventory(inventoryDoc, policy)
        val query = _uiState.value.searchQuery.trim().lowercase()
        val filtered = filterAppsList(sortedList, query)

        _uiState.update {
            it.copy(
                isLoading = false,
                currentPolicy = policy,
                customBlacklist = policy.customBlacklistPackages.toSet(),
                customCurfewWhitelist = policy.customCurfewWhitelistPackages.toSet(),
                curfewVpnBypass = policy.curfewVpnBypassPackages.toSet(),
                protectedApps = policy.protectedAppPackages.toSet(),
                lockNewInstallsUntilApproved = policy.lockNewInstallsUntilApproved,
                initialBlacklist = policy.customBlacklistPackages.toSet(),
                initialCurfewWhitelist = policy.customCurfewWhitelistPackages.toSet(),
                initialCurfewVpnBypass = policy.curfewVpnBypassPackages.toSet(),
                initialProtectedApps = policy.protectedAppPackages.toSet(),
                initialLockNewInstalls = policy.lockNewInstallsUntilApproved,
                allApps = sortedList,
                displayedApps = filtered
            )
        }

        attachRealtimeInventoryListener(firestore, wardId)
    }

    private fun parseAndSortInventory(
        inventoryDoc: DocumentSnapshot?,
        policy: DevicePolicyBundle
    ): List<InstalledAppDto> {
        @Suppress("UNCHECKED_CAST")
        val rawApps = (inventoryDoc?.get("apps") as? List<*>) ?: emptyList<Any?>()
        @Suppress("UNCHECKED_CAST")
        val parsedApps = rawApps.mapNotNull { raw ->
            (raw as? Map<String, Any?>)?.let { InstalledAppDto.fromMap(it) }
        }.filter { it.packageName.isNotBlank() }.toMutableList()

        val existingPackages = parsedApps.map { it.packageName }.toSet()
        val extraPackages = (policy.customBlacklistPackages + policy.customCurfewWhitelistPackages +
                policy.curfewVpnBypassPackages + policy.protectedAppPackages)
            .filter { !existingPackages.contains(it) }

        for (orphan in extraPackages) {
            parsedApps.add(
                InstalledAppDto(
                    packageName = orphan,
                    appName = orphan,
                    isSuspended = policy.customBlacklistPackages.contains(orphan),
                    isCurfewWhitelisted = policy.customCurfewWhitelistPackages.contains(orphan),
                    isCurfewVpnBypassed = policy.curfewVpnBypassPackages.contains(orphan),
                    isProtected = policy.protectedAppPackages.contains(orphan)
                )
            )
        }

        return parsedApps.sortedWith(
            compareBy({ it.appName.lowercase() }, { it.packageName })
        )
    }

    private fun attachRealtimeInventoryListener(firestore: FirebaseFirestore, wardId: String) {
        inventoryListener?.remove()
        inventoryListener = firestore.collection("inventories").document(wardId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Real-time inventory listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists() && snapshot.data != null) {
                    val currentPolicy = _uiState.value.currentPolicy
                    val sortedList = parseAndSortInventory(snapshot, currentPolicy)
                    val query = _uiState.value.searchQuery.trim().lowercase()
                    val filtered = filterAppsList(sortedList, query)

                    _uiState.update {
                        it.copy(
                            allApps = sortedList,
                            displayedApps = filtered
                        )
                    }
                }
            }
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                displayedApps = filterAppsList(state.allApps, q)
            )
        }
    }

    private fun filterAppsList(apps: List<InstalledAppDto>, query: String): List<InstalledAppDto> {
        if (query.isEmpty()) return apps
        return apps.filter {
            it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        }
    }

    fun setFreeze247(packageName: String, isFrozen: Boolean) {
        _uiState.update { state ->
            val blacklist = state.customBlacklist.toMutableSet()
            val curfewWhitelist = state.customCurfewWhitelist.toMutableSet()
            val curfewVpn = state.curfewVpnBypass.toMutableSet()

            if (isFrozen) {
                blacklist.add(packageName)
                curfewWhitelist.remove(packageName)
                curfewVpn.remove(packageName)
            } else {
                blacklist.remove(packageName)
                if (state.initialCurfewWhitelist.contains(packageName)) {
                    curfewWhitelist.add(packageName)
                }
                if (state.initialCurfewVpnBypass.contains(packageName)) {
                    curfewVpn.add(packageName)
                }
            }
            state.copy(
                customBlacklist = blacklist,
                customCurfewWhitelist = curfewWhitelist,
                curfewVpnBypass = curfewVpn
            )
        }
    }

    fun setCurfewWhitelist(packageName: String, isAllowed: Boolean) {
        _uiState.update { state ->
            val curfewWhitelist = state.customCurfewWhitelist.toMutableSet()
            val curfewVpn = state.curfewVpnBypass.toMutableSet()

            if (isAllowed) {
                curfewWhitelist.add(packageName)
            } else {
                curfewWhitelist.remove(packageName)
                curfewVpn.remove(packageName)
            }
            state.copy(
                customCurfewWhitelist = curfewWhitelist,
                curfewVpnBypass = curfewVpn
            )
        }
    }

    fun setCurfewVpnBypass(packageName: String, isBypassed: Boolean) {
        _uiState.update { state ->
            val curfewVpn = state.curfewVpnBypass.toMutableSet()
            if (isBypassed) {
                curfewVpn.add(packageName)
            } else {
                curfewVpn.remove(packageName)
            }
            state.copy(curfewVpnBypass = curfewVpn)
        }
    }

    fun setProtectedApp(packageName: String, isProtected: Boolean) {
        _uiState.update { state ->
            val protected = state.protectedApps.toMutableSet()
            if (isProtected) {
                protected.add(packageName)
            } else {
                protected.remove(packageName)
            }
            state.copy(protectedApps = protected)
        }
    }

    fun setLockNewInstalls(enabled: Boolean) {
        _uiState.update { it.copy(lockNewInstallsUntilApproved = enabled) }
    }

    fun executeQuickUnsuspend(app: InstalledAppDto) {
        val state = _uiState.value
        val pkg = app.packageName
        val displayName = app.appName.ifBlank { pkg }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            if (state.isLocalMode) {
                var success = false
                var errorMsg: String? = null

                if (SafeDpmGateway.isDeviceOwner(context)) {
                    val result = SafeDpmGateway.setPackagesSuspended(
                        context = context,
                        packages = arrayOf(pkg),
                        suspended = false
                    )
                    when (result) {
                        is DpmExecutionResult.Success -> {
                            success = result.data.isEmpty()
                            if (!success) errorMsg = context.getString(R.string.error_unknown)
                        }
                        is DpmExecutionResult.Failure -> {
                            errorMsg = result.message
                        }
                    }
                } else {
                    errorMsg = context.getString(R.string.toast_not_device_owner)
                }

                // Synchronize Zero-Trust approvals locally so it is never re-frozen
                try {
                    approvalRepository.unsuspendAndApprove(pkg, decidedBy = "SELF")
                    Log.i(TAG, "Quick unsuspend: Auto-approved package $pkg in local Zero-Trust baseline")
                } catch (e: Exception) {
                    Log.e(TAG, "Quick unsuspend: Error approving $pkg in local Zero-Trust", e)
                }

                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        allApps = current.allApps.map { if (it.packageName == pkg) it.copy(isSuspended = false) else it },
                        displayedApps = current.displayedApps.map { if (it.packageName == pkg) it.copy(isSuspended = false) else it }
                    )
                }

                if (success) {
                    _events.emit(
                        RemoteAppManagerEvent.ShowToastString(
                            context.getString(R.string.toast_quick_unsuspend_local_success, displayName)
                        )
                    )
                } else {
                    _events.emit(
                        RemoteAppManagerEvent.ShowToastString(
                            context.getString(R.string.toast_quick_unsuspend_failed, errorMsg ?: context.getString(R.string.error_unknown)),
                            isLong = true
                        )
                    )
                }
            } else {
                val updatedBlacklist = state.customBlacklist - pkg
                val myUid = accountabilityRepository.getCachedUid()
                val myName = accountabilityRepository.getMyDisplayName()
                    ?: context.getString(R.string.guardian_default_name)

                // 1. Resolve Zero-Trust approval on ward cloud ticket so ZeroTrustWatcher never re-freezes it
                try {
                    val approvalSuccess = approvalRepository.resolveWardApproval(
                        wardUid = state.wardId,
                        packageName = pkg,
                        approve = true,
                        guardianName = myName
                    )
                    Log.i(TAG, "Quick unsuspend: Auto-resolved ward approval for $pkg (success=$approvalSuccess)")
                } catch (e: Exception) {
                    Log.e(TAG, "Quick unsuspend: Error resolving ward approval for $pkg", e)
                }

                val updatedPolicy = state.currentPolicy.copy(
                    guardianUid = state.currentPolicy.guardianUid?.takeIf { it.isNotBlank() } ?: myUid,
                    guardianName = state.currentPolicy.guardianName?.takeIf { it.isNotBlank() } ?: myName,
                    isSupervised = true,
                    customBlacklistPackages = updatedBlacklist,
                    updatedAt = TrustedTimeProvider.currentTimeMillis(context)
                )

                val success = policyRepository.pushPolicyToWard(state.wardId, updatedPolicy)

                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        customBlacklist = updatedBlacklist,
                        currentPolicy = updatedPolicy,
                        initialBlacklist = updatedBlacklist,
                        allApps = current.allApps.map { if (it.packageName == pkg) it.copy(isSuspended = false) else it },
                        displayedApps = current.displayedApps.map { if (it.packageName == pkg) it.copy(isSuspended = false) else it }
                    )
                }

                if (success) {
                    _events.emit(
                        RemoteAppManagerEvent.ShowToastString(
                            context.getString(R.string.toast_quick_unsuspend_remote_sent, displayName, state.wardName),
                            isLong = true
                        )
                    )
                } else {
                    _events.emit(
                        RemoteAppManagerEvent.ShowToastString(
                            context.getString(R.string.toast_quick_unsuspend_failed, context.getString(R.string.error_unknown)),
                            isLong = true
                        )
                    )
                }
            }
        }
    }

    fun executeRepairStuckApps() {
        val state = _uiState.value
        if (state.isRepairing || state.isSaving) return
        if (state.hasUnsavedChanges) {
            viewModelScope.launch {
                _events.emit(RemoteAppManagerEvent.ShowToastRes(R.string.dialog_unsaved_changes_msg))
            }
            return
        }

        _uiState.update { it.copy(isRepairing = true, isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val count = CurfewEnforcer.repairStuckSuspendedApps(context)
            _uiState.update { it.copy(isRepairing = false, isLoading = false) }

            if (count > 0) {
                _events.emit(
                    RemoteAppManagerEvent.ShowToastString(
                        context.getString(R.string.toast_repair_stuck_apps_success, count),
                        isLong = true
                    )
                )
                loadData()
            } else {
                _events.emit(RemoteAppManagerEvent.ShowToastRes(R.string.toast_no_stuck_apps))
            }
        }
    }

    fun saveChanges() {
        val state = _uiState.value
        if (state.isSaving || !state.hasUnsavedChanges) return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val myUid = if (!state.isLocalMode) accountabilityRepository.getCachedUid() else null
            val myName = if (!state.isLocalMode) accountabilityRepository.getMyDisplayName() else null

            val updatedPolicy = state.currentPolicy.copy(
                guardianUid = if (!state.isLocalMode) (state.currentPolicy.guardianUid?.takeIf { it.isNotBlank() } ?: myUid) else state.currentPolicy.guardianUid,
                guardianName = if (!state.isLocalMode) (state.currentPolicy.guardianName?.takeIf { it.isNotBlank() } ?: myName) else state.currentPolicy.guardianName,
                isSupervised = if (!state.isLocalMode) true else state.currentPolicy.isSupervised,
                customBlacklistPackages = state.customBlacklist,
                customCurfewWhitelistPackages = state.customCurfewWhitelist,
                curfewVpnBypassPackages = state.curfewVpnBypass,
                protectedAppPackages = state.protectedApps,
                lockNewInstallsUntilApproved = state.lockNewInstallsUntilApproved,
                version = state.currentPolicy.version + 1,
                updatedAt = TrustedTimeProvider.currentTimeMillis(context)
            )

            // Auto-resolve Zero-Trust approvals for any packages removed from 24/7 blacklist
            val unFrozenPackages = state.initialBlacklist - state.customBlacklist
            for (pkg in unFrozenPackages) {
                try {
                    if (state.isLocalMode) {
                        approvalRepository.unsuspendAndApprove(pkg, decidedBy = "SELF")
                    } else {
                        approvalRepository.resolveWardApproval(state.wardId, pkg, approve = true, guardianName = myName ?: "GUARDIAN")
                    }
                    Log.i(TAG, "saveChanges: Auto-approved un-frozen package '$pkg'")
                } catch (e: Exception) {
                    Log.e(TAG, "saveChanges: Error auto-approving package '$pkg'", e)
                }
            }

            try {
                val success = if (state.isLocalMode) {
                    policyRepository.saveAndEnforceSelfPolicy(updatedPolicy)
                } else {
                    policyRepository.pushPolicyToWard(state.wardId, updatedPolicy)
                }

                _uiState.update { it.copy(isSaving = false) }

                if (success) {
                    _uiState.update {
                        it.copy(
                            initialBlacklist = state.customBlacklist,
                            initialCurfewWhitelist = state.customCurfewWhitelist,
                            initialCurfewVpnBypass = state.curfewVpnBypass,
                            initialProtectedApps = state.protectedApps,
                            initialLockNewInstalls = state.lockNewInstallsUntilApproved
                        )
                    }
                    _events.emit(RemoteAppManagerEvent.ShowToastRes(R.string.toast_app_manager_saved))
                    _events.emit(RemoteAppManagerEvent.SaveSuccess)
                } else {
                    _events.emit(RemoteAppManagerEvent.ShowToastRes(R.string.toast_app_manager_save_failed, isLong = true))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error pushing updated policies from RemoteAppManagerViewModel", e)
                _uiState.update { it.copy(isSaving = false) }
                _events.emit(RemoteAppManagerEvent.ShowToastRes(R.string.toast_app_manager_save_failed, isLong = true))
            }
        }
    }

    override fun onCleared() {
        inventoryListener?.remove()
        inventoryListener = null
        AppIconResolver.clear()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AntiPorn_RemoteAppVM"
    }
}
