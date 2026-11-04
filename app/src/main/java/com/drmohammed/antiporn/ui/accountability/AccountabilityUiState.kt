package com.drmohammed.antiporn.ui.accountability

import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity

/**
 * Immutable UI State for AccountabilityFragment.
 * Encapsulates pairing keys, guardian/ward relationships, emergency breakout countdown, and Google security sync.
 */
data class AccountabilityUiState(
    val myPairingCode: String = "",
    val myDisplayName: String = "",
    val isSupervised: Boolean = false,
    val isPairingCodeLocked: Boolean = false,
    val wards: List<PartnerEntity> = emptyList(),
    val guardians: List<PartnerEntity> = emptyList(),
    val isGoogleLinked: Boolean = false,
    val googleAccountEmail: String? = null,
    val breakoutStatus: String? = null,
    val breakoutUnlockAt: Long? = null,
    val breakoutRemainingMs: Long = 0L,
    val pendingApprovalsCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
