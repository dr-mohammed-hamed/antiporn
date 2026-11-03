package com.drmohammed.antiporn.data.model

/**
 * Data Transfer Object representing an installed application on the device.
 * Used for synchronization with cloud inventory and parental control inspection.
 */
data class InstalledAppDto(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean = false,
    val isSuspended: Boolean = false,
    val isCurfewWhitelisted: Boolean = false,
    val isCurfewVpnBypassed: Boolean = false,
    val isProtected: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {

    /**
     * Converts this DTO to a Map suitable for Firestore serialization.
     */
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "packageName" to packageName,
            "appName" to appName,
            "isSystem" to isSystem,
            "isSuspended" to isSuspended,
            "isCurfewWhitelisted" to isCurfewWhitelisted,
            "isCurfewVpnBypassed" to isCurfewVpnBypassed,
            "isProtected" to isProtected,
            "updatedAt" to updatedAt
        )
    }

    companion object {
        /**
         * Reconstructs an [InstalledAppDto] instance from a Firestore document map.
         */
        fun fromMap(map: Map<String, Any?>): InstalledAppDto {
            return InstalledAppDto(
                packageName = map["packageName"] as? String ?: "",
                appName = map["appName"] as? String ?: "",
                isSystem = map["isSystem"] as? Boolean ?: false,
                isSuspended = map["isSuspended"] as? Boolean ?: false,
                isCurfewWhitelisted = map["isCurfewWhitelisted"] as? Boolean ?: false,
                isCurfewVpnBypassed = map["isCurfewVpnBypassed"] as? Boolean ?: false,
                isProtected = map["isProtected"] as? Boolean ?: false,
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
