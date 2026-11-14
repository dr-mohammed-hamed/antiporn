package com.drmohammed.antiporn.ui.accountability

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.model.InstalledAppDto

/**
 * Immutable UI State representing the complete presentation state of the Remote App Manager.
 */
data class RemoteAppManagerUiState(
    val isLocalMode: Boolean = false,
    val wardId: String = "",
    val wardName: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isRepairing: Boolean = false,
    val allApps: List<InstalledAppDto> = emptyList(),
    val displayedApps: List<InstalledAppDto> = emptyList(),
    val searchQuery: String = "",
    val currentPolicy: DevicePolicyBundle = DevicePolicyBundle.defaultPolicy(),
    val customBlacklist: Set<String> = emptySet(),
    val customCurfewWhitelist: Set<String> = emptySet(),
    val customAiScanPackages: Set<String> = emptySet(),
    val curfewVpnBypass: Set<String> = emptySet(),
    val protectedApps: Set<String> = emptySet(),
    val lockNewInstallsUntilApproved: Boolean = false,
    val initialBlacklist: Set<String> = emptySet(),
    val initialCurfewWhitelist: Set<String> = emptySet(),
    val initialAiScanPackages: Set<String> = emptySet(),
    val initialCurfewVpnBypass: Set<String> = emptySet(),
    val initialProtectedApps: Set<String> = emptySet(),
    val initialLockNewInstalls: Boolean = false,
    val isScreenAiEnabledGlobally: Boolean = true,
    val isScreenAiServiceRunning: Boolean = false,
    val errorMessage: String? = null
) {
    val hasUnsavedChanges: Boolean
        get() = customBlacklist != initialBlacklist ||
                customCurfewWhitelist != initialCurfewWhitelist ||
                customAiScanPackages != initialAiScanPackages ||
                curfewVpnBypass != initialCurfewVpnBypass ||
                protectedApps != initialProtectedApps ||
                lockNewInstallsUntilApproved != initialLockNewInstalls

    val isSaveEnabled: Boolean
        get() = hasUnsavedChanges && !isSaving

    val isEmptyState: Boolean
        get() = !isLoading && displayedApps.isEmpty()
}

/**
 * Single-shot events emitted by RemoteAppManagerViewModel to the Activity.
 */
sealed interface RemoteAppManagerEvent {
    data class ShowToastRes(val resId: Int, val isLong: Boolean = false) : RemoteAppManagerEvent
    data class ShowToastString(val message: String, val isLong: Boolean = false) : RemoteAppManagerEvent
    object SaveSuccess : RemoteAppManagerEvent
}
