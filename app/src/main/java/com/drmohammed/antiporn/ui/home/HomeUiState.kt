package com.drmohammed.antiporn.ui.home

import com.drmohammed.antiporn.ui.ProtectionUiState

/**
 * Immutable UI State for HomeFragment dashboard.
 * Encapsulates hero shield state, 1-hour nap mode timers, and enforcement progress.
 */
data class HomeUiState(
    val protectionState: ProtectionUiState = ProtectionUiState.Loading(),
    val napRemainingMs: Long = 0L,
    val isNapActive: Boolean = false,
    val isCurfewActive: Boolean = false,
    val isApplying: Boolean = false,
    val hasUsageAccess: Boolean = true
)
