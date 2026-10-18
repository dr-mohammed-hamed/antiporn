package com.drmohammed.antiporn.data.local.db.entity

/**
 * Entity representing an application installation pending approval from a guardian.
 */
data class PendingApprovalEntity(
    val packageName: String,
    val appName: String,
    val requestedAt: Long = System.currentTimeMillis(),
    val status: String = STATUS_PENDING,
    val decidedBy: String? = null,
    val decidedAt: Long? = null,
    val isCloudSynced: Boolean = false
) {
    val isPending: Boolean get() = status == STATUS_PENDING
    val isApproved: Boolean get() = status == STATUS_APPROVED
    val isRejected: Boolean get() = status == STATUS_REJECTED

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
    }
}
