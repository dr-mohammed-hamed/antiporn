package com.drmohammed.antiporn.ui.settings

import com.drmohammed.antiporn.ota.model.OtaUpdateState

/**
 * Immutable UI State for SettingsFragment.
 * Encapsulates AI shield metrics, Device Owner privileges, OTA update state, and Google account status.
 */
data class SettingsUiState(
    val isAiRunning: Boolean = false,
    val aiInitError: String = "",
    val aiFps: Float = 0f,
    val aiLastDetectionLabel: String = "",
    val aiLastDetectionConfidence: Float = 0f,
    val aiModelName: String = "",
    val isDeviceOwner: Boolean = false,
    val otaState: OtaUpdateState = OtaUpdateState.Idle,
    val appVersionName: String = "1.0",
    val appVersionCode: Long = 1L,
    val isGoogleLinked: Boolean = false,
    val googleAccountEmail: String? = null,
    val isSharingTelemetry: Boolean = false,
    val myDisplayName: String = ""
)
