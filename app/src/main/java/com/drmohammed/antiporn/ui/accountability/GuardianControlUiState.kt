package com.drmohammed.antiporn.ui.accountability

import com.drmohammed.antiporn.core.model.DevicePolicyBundle

/**
 * Immutable UI State representing the state of the Guardian Control panel.
 */
data class GuardianControlUiState(
    val wardId: String = "",
    val wardName: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isOffline: Boolean = false,
    val currentPolicy: DevicePolicyBundle = DevicePolicyBundle.defaultPolicy(),
    val initialPolicySnapshot: DevicePolicyBundle? = null,
    val selectedStartHour: Int = 20,
    val selectedStartMinute: Int = 30,
    val selectedEndHour: Int = 7,
    val selectedEndMinute: Int = 0,
    val selectedWeekendStartHour: Int = 20,
    val selectedWeekendStartMinute: Int = 30,
    val selectedWeekendEndHour: Int = 7,
    val selectedWeekendEndMinute: Int = 0,
    val selectedTimeZoneId: String = "Asia/Riyadh",
    val isCurfewEnabled: Boolean = false,
    val isWeekendCurfewEnabled: Boolean = false,
    val isSelfControlAllowed: Boolean = false,
    val isVpnBlackholeEnabled: Boolean = true,
    val isInstantInternetKillEnabled: Boolean = true,
    val isIncognitoBlocked: Boolean = true,
    val lockNewInstallsUntilApproved: Boolean = false,
    val isDateTimeLocked: Boolean = true,
    val isYouTubeRestrictedModeEnabled: Boolean = false,
    val isTelegramWebBlocked: Boolean = true,
    val dnsHost: String = "",
    val version: Long = 0L,
    val acknowledgedVersion: Long = 0L,
    val lastAcknowledgedAt: Long = 0L,
    val lastHeartbeat: Long = 0L,
    val errorMessage: String? = null
) {
    val hasUnsavedChanges: Boolean
        get() {
            val initial = initialPolicySnapshot ?: return false
            return selectedStartHour != initial.curfewStartHour ||
                    selectedStartMinute != initial.curfewStartMinute ||
                    selectedEndHour != initial.curfewEndHour ||
                    selectedEndMinute != initial.curfewEndMinute ||
                    selectedWeekendStartHour != initial.weekendCurfewStartHour ||
                    selectedWeekendStartMinute != initial.weekendCurfewStartMinute ||
                    selectedWeekendEndHour != initial.weekendCurfewEndHour ||
                    selectedWeekendEndMinute != initial.weekendCurfewEndMinute ||
                    selectedTimeZoneId != initial.timeZoneId ||
                    isCurfewEnabled != initial.isCurfewEnabled ||
                    isWeekendCurfewEnabled != initial.isWeekendCurfewEnabled ||
                    isSelfControlAllowed != initial.isSelfControlAllowed ||
                    isVpnBlackholeEnabled != initial.isVpnBlackholeEnabled ||
                    isInstantInternetKillEnabled != initial.isInstantInternetKillEnabled ||
                    isIncognitoBlocked != initial.isIncognitoBlocked ||
                    lockNewInstallsUntilApproved != initial.lockNewInstallsUntilApproved ||
                    isDateTimeLocked != initial.isDateTimeLocked ||
                    isYouTubeRestrictedModeEnabled != initial.isYouTubeRestrictedModeEnabled ||
                    isTelegramWebBlocked != initial.isTelegramWebBlocked ||
                    dnsHost.trim() != initial.dnsHost.trim() ||
                    currentPolicy.customBlockedUrls != initial.customBlockedUrls ||
                    currentPolicy.customAllowedUrls != initial.customAllowedUrls ||
                    currentPolicy.browserFilterMode != initial.browserFilterMode ||
                    currentPolicy.isFullSystemFilteringEnabled != initial.isFullSystemFilteringEnabled ||
                    currentPolicy.waqfaConfig != initial.waqfaConfig ||
                    currentPolicy.customAiScanPackages != initial.customAiScanPackages
        }

    val isSaveEnabled: Boolean
        get() = hasUnsavedChanges && !isSaving && !isLoading
}

/**
 * Single-shot UI events emitted from GuardianControlViewModel.
 */
sealed interface GuardianControlEvent {
    data class ShowToastRes(val resId: Int, val isLong: Boolean = false) : GuardianControlEvent
    data class ShowToastString(val message: String, val isLong: Boolean = false) : GuardianControlEvent
    data class DnsValidationError(val titleRes: Int, val message: String) : GuardianControlEvent
    object SaveSuccess : GuardianControlEvent
}
