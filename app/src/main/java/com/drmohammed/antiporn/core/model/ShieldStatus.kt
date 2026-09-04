package com.drmohammed.antiporn.core.model

/**
 * Sealed interface representing the current evaluation state of a protection shield.
 */
sealed interface ShieldStatus {
    /** Shield is fully active and enforcing its policy. */
    data class Active(val detail: String) : ShieldStatus

    /** Shield requires enforcement (tampered, not configured, or misaligned). */
    data class NeedsEnforcement(val reason: String) : ShieldStatus

    /** Shield is disabled or unavailable (e.g. not Device Owner). */
    data class Disabled(val warning: String) : ShieldStatus

    /** Feature is unsupported on the current Android OS level. */
    data class Unsupported(val minSdkRequired: Int) : ShieldStatus
}
