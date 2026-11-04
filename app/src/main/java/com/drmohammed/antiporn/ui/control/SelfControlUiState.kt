package com.drmohammed.antiporn.ui.control

import com.drmohammed.antiporn.core.model.DevicePolicyBundle

/**
 * Immutable UI State for SelfControlFragment.
 * Encapsulates the active policy bundle, lock/tamper status, pending approvals, and save state.
 */
data class SelfControlUiState(
    val policy: DevicePolicyBundle = DevicePolicyBundle.defaultPolicy(),
    val initialSnapshot: DevicePolicyBundle? = null,
    val isLocked: Boolean = false,
    val isSaving: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val isQrLoading: Boolean = false,
    val pendingApprovalsCount: Int = 0,
    val errorMessage: String? = null
)
