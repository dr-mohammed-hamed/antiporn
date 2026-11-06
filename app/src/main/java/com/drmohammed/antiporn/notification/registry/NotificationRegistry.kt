package com.drmohammed.antiporn.notification.registry

/**
 * Centralized Single-Source-of-Truth for all Android Notification Channels,
 * Foreground Service IDs, and Event Notification IDs.
 *
 * Guarantees zero ID collision across disparate foreground services
 * (specifically resolving Curfew VPN vs AI MediaProjection collision).
 */
object NotificationRegistry {

    // ==========================================
    // Notification Channel Identifiers
    // ==========================================

    /** Low-priority persistent background watchdog channel. */
    const val CHANNEL_ID_WATCHDOG = "antiporn_watchdog"

    /** Low-priority persistent protection and curfew VPN channel. */
    const val CHANNEL_ID_PROTECTION_VPN = "antiporn_curfew_vpn"

    /** Low-priority persistent Modesty AI media projection channel. */
    const val CHANNEL_ID_AI = "antiporn_ai_channel"

    /** Low-priority OTA self-update progress and status channel. */
    const val CHANNEL_ID_OTA = "antiporn_ota_updates"

    /** High-priority alert channel for curfew warnings, approvals, and guardian requests. */
    const val CHANNEL_ID_PROTECTION_EVENTS = "antiporn_protection_events"

    /** High-priority alert channel for security tampering and system protection violations. */
    const val CHANNEL_ID_CRITICAL_ALERTS = "antiporn_critical_alerts"

    /** High-priority interactive channel for wireless ADB pairing and DO provisioning. */
    const val CHANNEL_ID_PAIRING = "anti_porn_pairing_channel"

    // ==========================================
    // Foreground Service Notification Identifiers (Unique per service)
    // ==========================================

    /** DnsWatchdogService ongoing foreground service ID. */
    const val FGS_WATCHDOG_ID = 1001

    /** CurfewBlackholeVpnService ongoing foreground service ID. */
    const val FGS_CURFEW_VPN_ID = 2001

    /** ScreenAiProjectionService ongoing foreground service ID (replaces conflicting 2001). */
    const val FGS_AI_PROJECTION_ID = 3001

    /** OtaNotificationManager download and install ongoing progress notification ID. */
    const val OTA_PROGRESS_ID = 4001

    /** AdbPairingNotificationService ongoing pairing radar notification ID. */
    const val ADB_PAIRING_ID = 5001

    // ==========================================
    // Dynamic Event Notification Identifiers
    // ==========================================

    /** Nap ended notification ID. */
    const val EVENT_NAP_ENDED_ID = 2002

    /** 15-minute advance warning before curfew start notification ID. */
    const val EVENT_CURFEW_PRE_WARNING_ID = 2003

    /** Curfew morning wakeup notification ID. */
    const val EVENT_CURFEW_ENDED_ID = 2004

    /** Curfew start notification ID. */
    const val EVENT_CURFEW_STARTED_ID = 2005

    /** Nap start notification ID. */
    const val EVENT_NAP_STARTED_ID = 2006

    /** System security and DNS/Time tampering alert notification ID. */
    const val EVENT_SYSTEM_TAMPER_ID = 7001

    /** Guardian alert notification ID (new pending approval or emergency breakout). */
    const val EVENT_GUARDIAN_REQUEST_ID = 8001

    /** Ward request alert notification ID. */
    const val EVENT_WARD_REQUEST_ID = 8002

    /** Approval decision notification ID. */
    const val EVENT_APPROVAL_DECISION_ID = 8003

    /** Emergency breakout request notification ID. */
    const val EVENT_BREAKOUT_REQUEST_ID = 8004

    /** Modesty AI violation detection notification ID. */
    const val EVENT_AI_DETECTION_ID = 3002

    /** OTA update completion or failure notification ID. */
    const val EVENT_OTA_FAILED_ID = 4002

}
