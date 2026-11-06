package com.drmohammed.antiporn.notification.model

/**
 * Supported notification categories in AntiPorn architecture.
 */
enum class NotificationCategory(val key: String) {
    APPROVALS("APPROVALS"),   // Application approval & zero-trust freeze requests
    GUARDIAN("GUARDIAN"),     // Guardian alerts, emergency breakout, policy updates
    CURFEW("CURFEW"),         // Curfew start/end, nap timer, pre-curfew warnings
    SHIELDS("SHIELDS"),       // Protection shields, DNS/Time tamper detection
    AI("AI"),                 // Modesty AI violations and screen blur actions
    OTA("OTA"),               // Over-The-Air app update progress and status
    SYSTEM("SYSTEM");         // System-level notices, Device Owner provisioning

    companion object {
        fun fromKey(key: String?): NotificationCategory =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * Action types dispatched when tapping an in-app notification.
 */
enum class NotificationActionType(val key: String) {
    OPEN_SELF_APPROVALS("OPEN_SELF_APPROVALS"), // Open pending approvals / self-control tab
    OPEN_GUARDIAN_PANEL("OPEN_GUARDIAN_PANEL"), // Open GuardianControlActivity
    OPEN_CURFEW("OPEN_CURFEW"),                 // Open Curfew / Nap settings
    OPEN_OTA("OPEN_OTA"),                       // Open OTA update checker
    LAUNCH_APP("LAUNCH_APP"),                   // Launch approved application package
    NONE("NONE");                               // Informational notification with no navigation

    companion object {
        fun fromKey(key: String?): NotificationActionType =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: NONE
    }
}

/**
 * Data model representing a persistent in-app notification entry.
 */
data class InAppNotification(
    val id: Long = 0L,
    val dedupKey: String? = null,
    val category: NotificationCategory,
    val title: String,
    val message: String,
    val timestamp: Long,
    val isRead: Boolean = false,
    val actionType: NotificationActionType = NotificationActionType.NONE,
    val actionPayload: String? = null,
    val importance: Int = 1
)
