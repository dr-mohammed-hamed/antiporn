package com.drmohammed.antiporn.ui

import com.drmohammed.antiporn.core.model.ShieldStatus

/**
 * Sealed hierarchy representing the immutable UI presentation state of AntiPorn Guard.
 * Follows Unidirectional Data Flow (UDF) to ensure deterministic UI rendering.
 */
sealed interface ProtectionUiState {
    /** Background evaluation or enforcement is in progress */
    data class Loading(val message: String? = null) : ProtectionUiState

    /** Evaluated state containing Device Owner status and individual shield statuses */
    data class Success(
        val isDeviceOwner: Boolean,
        val shields: Map<String, ShieldStatus>,
        val currentDnsHost: String? = null,
        val isEnforcing: Boolean = false
    ) : ProtectionUiState

    /** Error state displaying localized failure message */
    data class Error(val message: String) : ProtectionUiState
}
