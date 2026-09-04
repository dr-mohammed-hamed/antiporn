package com.drmohammed.antiporn.core.model

/**
 * Result of applying an enterprise policy for a specific protection shield.
 *
 * @param shieldId Unique identifier of the shield that produced this result.
 * @param success Whether the shield's policy was applied successfully.
 * @param message Human-readable status or error description (localized).
 * @param statusCode System API return code (e.g. PRIVATE_DNS_SET_NO_ERROR) or 0 on success.
 */
data class ShieldResult(
    val shieldId: String,
    val success: Boolean,
    val message: String,
    val statusCode: Int = 0
)
