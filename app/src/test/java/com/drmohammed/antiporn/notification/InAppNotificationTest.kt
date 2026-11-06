package com.drmohammed.antiporn.notification

import com.drmohammed.antiporn.notification.model.InAppNotification
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppNotificationTest {

    @Test
    fun testNotificationCategory_fromKey() {
        assertEquals(NotificationCategory.APPROVALS, NotificationCategory.fromKey("APPROVALS"))
        assertEquals(NotificationCategory.APPROVALS, NotificationCategory.fromKey("approvals"))
        assertEquals(NotificationCategory.GUARDIAN, NotificationCategory.fromKey("GUARDIAN"))
        assertEquals(NotificationCategory.CURFEW, NotificationCategory.fromKey("CURFEW"))
        assertEquals(NotificationCategory.SHIELDS, NotificationCategory.fromKey("SHIELDS"))
        assertEquals(NotificationCategory.AI, NotificationCategory.fromKey("AI"))
        assertEquals(NotificationCategory.OTA, NotificationCategory.fromKey("OTA"))
        assertEquals(NotificationCategory.SYSTEM, NotificationCategory.fromKey("SYSTEM"))
        assertEquals(NotificationCategory.SYSTEM, NotificationCategory.fromKey("UNKNOWN_KEY"))
        assertEquals(NotificationCategory.SYSTEM, NotificationCategory.fromKey(null))
    }

    @Test
    fun testNotificationActionType_fromKey() {
        assertEquals(NotificationActionType.OPEN_SELF_APPROVALS, NotificationActionType.fromKey("OPEN_SELF_APPROVALS"))
        assertEquals(NotificationActionType.OPEN_GUARDIAN_PANEL, NotificationActionType.fromKey("OPEN_GUARDIAN_PANEL"))
        assertEquals(NotificationActionType.OPEN_CURFEW, NotificationActionType.fromKey("OPEN_CURFEW"))
        assertEquals(NotificationActionType.OPEN_OTA, NotificationActionType.fromKey("OPEN_OTA"))
        assertEquals(NotificationActionType.LAUNCH_APP, NotificationActionType.fromKey("LAUNCH_APP"))
        assertEquals(NotificationActionType.NONE, NotificationActionType.fromKey("NONE"))
        assertEquals(NotificationActionType.NONE, NotificationActionType.fromKey("INVALID"))
        assertEquals(NotificationActionType.NONE, NotificationActionType.fromKey(null))
    }

    @Test
    fun testInAppNotification_instantiationAndProperties() {
        val notif = InAppNotification(
            id = 1L,
            dedupKey = "approval_com.test.app_APPROVED",
            category = NotificationCategory.APPROVALS,
            title = "تمت الموافقة على التطبيق",
            message = "وافق المشرف على تشغيل التطبيق.",
            timestamp = 1700000000000L,
            isRead = false,
            actionType = NotificationActionType.LAUNCH_APP,
            actionPayload = "com.test.app",
            importance = 2
        )

        assertEquals(1L, notif.id)
        assertEquals("approval_com.test.app_APPROVED", notif.dedupKey)
        assertEquals(NotificationCategory.APPROVALS, notif.category)
        assertEquals("تمت الموافقة على التطبيق", notif.title)
        assertEquals("وافق المشرف على تشغيل التطبيق.", notif.message)
        assertEquals(1700000000000L, notif.timestamp)
        assertFalse(notif.isRead)
        assertEquals(NotificationActionType.LAUNCH_APP, notif.actionType)
        assertEquals("com.test.app", notif.actionPayload)
        assertEquals(2, notif.importance)

        val updated = notif.copy(isRead = true)
        assertTrue(updated.isRead)
    }

    @Test
    fun testNotificationRegistry_noForegroundServiceIdCollisions() {
        // Assert all foreground service IDs are strictly unique to eliminate crash / overwrite risks
        val fgsIds = listOf(
            NotificationRegistry.FGS_WATCHDOG_ID,
            NotificationRegistry.FGS_CURFEW_VPN_ID,
            NotificationRegistry.FGS_AI_PROJECTION_ID,
            NotificationRegistry.OTA_PROGRESS_ID,
            NotificationRegistry.ADB_PAIRING_ID
        )

        assertEquals("Foreground service notification IDs must all be unique", fgsIds.toSet().size, fgsIds.size)

        assertNotEquals(NotificationRegistry.FGS_CURFEW_VPN_ID, NotificationRegistry.FGS_AI_PROJECTION_ID)
        assertNotEquals(NotificationRegistry.FGS_WATCHDOG_ID, NotificationRegistry.FGS_AI_PROJECTION_ID)
        assertNotEquals(NotificationRegistry.FGS_WATCHDOG_ID, NotificationRegistry.FGS_CURFEW_VPN_ID)
    }

    @Test
    fun testNotificationRegistry_eventNotificationIdsAreUnique() {
        val eventIds = listOf(
            NotificationRegistry.EVENT_WARD_REQUEST_ID,
            NotificationRegistry.EVENT_APPROVAL_DECISION_ID,
            NotificationRegistry.EVENT_BREAKOUT_REQUEST_ID,
            NotificationRegistry.EVENT_CURFEW_PRE_WARNING_ID,
            NotificationRegistry.EVENT_CURFEW_STARTED_ID,
            NotificationRegistry.EVENT_CURFEW_ENDED_ID,
            NotificationRegistry.EVENT_NAP_STARTED_ID,
            NotificationRegistry.EVENT_NAP_ENDED_ID,
            NotificationRegistry.EVENT_SYSTEM_TAMPER_ID,
            NotificationRegistry.EVENT_AI_DETECTION_ID,
            NotificationRegistry.EVENT_OTA_FAILED_ID
        )

        assertEquals("Event notification IDs must all be unique", eventIds.toSet().size, eventIds.size)
    }

    @Test
    fun testNotificationRegistry_channelIdsAreDefined() {
        assertEquals("antiporn_watchdog", NotificationRegistry.CHANNEL_ID_WATCHDOG)
        assertEquals("antiporn_curfew_vpn", NotificationRegistry.CHANNEL_ID_PROTECTION_VPN)
        assertEquals("antiporn_ai_channel", NotificationRegistry.CHANNEL_ID_AI)
        assertEquals("antiporn_protection_events", NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS)
        assertEquals("antiporn_critical_alerts", NotificationRegistry.CHANNEL_ID_CRITICAL_ALERTS)
        assertEquals("antiporn_ota_updates", NotificationRegistry.CHANNEL_ID_OTA)
    }
}
