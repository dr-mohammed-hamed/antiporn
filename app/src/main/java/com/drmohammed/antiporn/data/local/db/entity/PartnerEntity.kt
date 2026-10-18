package com.drmohammed.antiporn.data.local.db.entity

/**
 * Entity representing an accountability partner (Guardian or Ward).
 */
data class PartnerEntity(
    val id: String,
    val pairingCode: String,
    val displayName: String,
    val role: String, // "GUARDIAN" (protects me) or "WARD" (I protect them)
    val status: String = "ACTIVE", // "ACTIVE", "PENDING", "REVOKED"
    val createdAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long = System.currentTimeMillis()
) {
    val isGuardian: Boolean get() = role == ROLE_GUARDIAN
    val isWard: Boolean get() = role == ROLE_WARD

    companion object {
        const val ROLE_GUARDIAN = "GUARDIAN"
        const val ROLE_WARD = "WARD"

        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_REVOKED = "REVOKED"
    }
}
