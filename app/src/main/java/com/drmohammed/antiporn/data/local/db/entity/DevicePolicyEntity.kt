package com.drmohammed.antiporn.data.local.db.entity

import com.drmohammed.antiporn.core.model.DevicePolicyBundle

/**
 * Entity representing the locally cached active device policy.
 */
data class DevicePolicyEntity(
    val id: Int = 1,
    val policyJson: String,
    val version: Long,
    val updatedAt: Long
) {
    fun toBundle(): DevicePolicyBundle {
        return DevicePolicyBundle.fromJson(policyJson)
    }

    companion object {
        fun fromBundle(bundle: DevicePolicyBundle): DevicePolicyEntity {
            return DevicePolicyEntity(
                id = 1,
                policyJson = bundle.toJson(),
                version = bundle.version,
                updatedAt = bundle.updatedAt
            )
        }
    }
}
