package com.drmohammed.antiporn.waqfa.model

/**
 * كائن النطاق (Domain Model) لجلسة «وَقْفَة».
 */
data class WaqfaSession(
    val id: Long = 0L,
    val sessionId: String,
    val packageName: String,
    val appName: String,
    val intentType: WaqfaIntentType,
    val reasonOrFeeling: String?,
    val durationMinutes: Int,
    val startedAtTrustedMs: Long,
    val plannedEndAtTrustedMs: Long,
    val actualEndAtTrustedMs: Long? = null,
    val endActionApplied: String? = null,
    val isActive: Boolean = true
) {
    fun isExpired(currentTimeMs: Long): Boolean = currentTimeMs >= plannedEndAtTrustedMs

    fun remainingMs(currentTimeMs: Long): Long = maxOf(0L, plannedEndAtTrustedMs - currentTimeMs)

    companion object {
        const val ACTION_EXIT_ONLY = "EXIT_ONLY"
        const val ACTION_EXIT_AND_FREEZE = "EXIT_AND_FREEZE"
        const val ACTION_NOTIFY_ONLY = "NOTIFY_ONLY"
        const val ACTION_USER_EARLY_EXIT = "USER_EARLY_EXIT"
    }
}
