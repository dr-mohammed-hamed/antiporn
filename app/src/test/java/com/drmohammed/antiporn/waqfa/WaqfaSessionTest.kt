package com.drmohammed.antiporn.waqfa

import com.drmohammed.antiporn.waqfa.data.WaqfaSessionEntity
import com.drmohammed.antiporn.waqfa.engine.WaqfaCooldownStore
import com.drmohammed.antiporn.waqfa.model.WaqfaIntentType
import com.drmohammed.antiporn.waqfa.model.WaqfaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WaqfaSessionTest {

    @Test
    fun `test WaqfaSession expiration and remaining time calculations`() {
        val startMs = 1_000_000L
        val plannedEndMs = startMs + 15 * 60_000L

        val session = WaqfaSession(
            id = 1L,
            sessionId = UUID.randomUUID().toString(),
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            intentType = WaqfaIntentType.PURPOSEFUL,
            reasonOrFeeling = "Research coding",
            durationMinutes = 15,
            startedAtTrustedMs = startMs,
            plannedEndAtTrustedMs = plannedEndMs,
            isActive = true
        )

        // Before expiration
        assertFalse(session.isExpired(startMs + 5 * 60_000L))
        assertEquals(10 * 60_000L, session.remainingMs(startMs + 5 * 60_000L))

        // Exactly at expiration
        assertTrue(session.isExpired(plannedEndMs))
        assertEquals(0L, session.remainingMs(plannedEndMs))

        // After expiration
        assertTrue(session.isExpired(plannedEndMs + 10_000L))
        assertEquals(0L, session.remainingMs(plannedEndMs + 10_000L))
    }

    @Test
    fun `test WaqfaSession toEntity and toDomain bidirectional mapping`() {
        val session = WaqfaSession(
            id = 42L,
            sessionId = "uuid-1234-5678",
            packageName = "org.telegram.messenger",
            appName = "Telegram",
            intentType = WaqfaIntentType.ESCAPISM,
            reasonOrFeeling = "Boredom",
            durationMinutes = 10,
            startedAtTrustedMs = 500_000L,
            plannedEndAtTrustedMs = 500_000L + 10 * 60_000L,
            actualEndAtTrustedMs = 500_000L + 8 * 60_000L,
            endActionApplied = WaqfaSession.ACTION_USER_EARLY_EXIT,
            isActive = false
        )

        val entity = WaqfaSessionEntity.fromDomain(session)
        assertEquals(session.id, entity.id)
        assertEquals(session.sessionId, entity.sessionUuid)
        assertEquals(session.packageName, entity.packageName)
        assertEquals(session.appName, entity.appName)
        assertEquals(WaqfaIntentType.ESCAPISM.name, entity.intentType)
        assertEquals(session.reasonOrFeeling, entity.reasonOrFeeling)
        assertEquals(session.durationMinutes, entity.durationMinutes)
        assertEquals(0, entity.isActive)

        val restored = entity.toDomain()
        assertEquals(session, restored)
    }

    @Test
    fun `test FrozenPackage expiration in WaqfaCooldownStore`() {
        val startWall = 2_000_000L
        val endWall = startWall + 3 * 60_000L

        val frozen = WaqfaCooldownStore.FrozenPackage(
            packageName = "com.facebook.katana",
            appName = "Facebook",
            freezeStartWallMs = startWall,
            freezeEndWallMs = endWall,
            cooldownMinutes = 3
        )

        assertFalse(frozen.isExpired(startWall + 1 * 60_000L))
        assertEquals(2 * 60_000L, frozen.remainingMs(startWall + 1 * 60_000L))

        assertTrue(frozen.isExpired(endWall))
        assertEquals(0L, frozen.remainingMs(endWall))
    }

    @Test
    fun `test WaqfaSession isExtended flag and extension calculation`() {
        val startMs = 5_000_000L
        val originalEnd = startMs + 10 * 60_000L

        val initial = WaqfaSession(
            id = 10L,
            sessionId = "uuid-ext-test",
            packageName = "com.instagram.android",
            appName = "Instagram",
            intentType = WaqfaIntentType.PURPOSEFUL,
            reasonOrFeeling = "Checking messages",
            durationMinutes = 10,
            startedAtTrustedMs = startMs,
            plannedEndAtTrustedMs = originalEnd,
            isActive = true,
            isExtended = false
        )

        assertFalse(initial.isExtended)

        // Perform emergency extension (+2 min)
        val extended = initial.copy(
            plannedEndAtTrustedMs = initial.plannedEndAtTrustedMs + (2 * 60_000L),
            durationMinutes = initial.durationMinutes + 2,
            isExtended = true
        )

        assertTrue(extended.isExtended)
        assertEquals(12, extended.durationMinutes)
        assertEquals(originalEnd + 2 * 60_000L, extended.plannedEndAtTrustedMs)
        assertEquals(2 * 60_000L, extended.remainingMs(originalEnd))
    }

    @Test
    fun `test WaqfaSession isOpenSession behavior and expiration`() {
        val startMs = 10_000_000L
        val openSession = WaqfaSession(
            id = 20L,
            sessionId = "uuid-open-session",
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            intentType = WaqfaIntentType.PURPOSEFUL,
            reasonOrFeeling = "Learning math",
            durationMinutes = 0,
            startedAtTrustedMs = startMs,
            plannedEndAtTrustedMs = 0L,
            isActive = true
        )

        assertTrue(openSession.isOpenSession)
        assertFalse(openSession.isExpired(startMs + 100 * 60_000L))
        assertEquals(0L, openSession.remainingMs(startMs + 50 * 60_000L))
    }

    @Test
    fun `test WaqfaSession negative or zero duration is treated as open session`() {
        val sessionZero = WaqfaSession(
            sessionId = "uuid-0",
            packageName = "com.pkg",
            appName = "App",
            intentType = WaqfaIntentType.PURPOSEFUL,
            reasonOrFeeling = null,
            durationMinutes = 0,
            startedAtTrustedMs = 1_000_000L,
            plannedEndAtTrustedMs = 0L
        )
        val sessionNegative = WaqfaSession(
            sessionId = "uuid-neg",
            packageName = "com.pkg",
            appName = "App",
            intentType = WaqfaIntentType.PURPOSEFUL,
            reasonOrFeeling = null,
            durationMinutes = -1,
            startedAtTrustedMs = 1_000_000L,
            plannedEndAtTrustedMs = 0L
        )

        assertTrue(sessionZero.isOpenSession)
        assertTrue(sessionNegative.isOpenSession)
        assertFalse(sessionZero.isExpired(Long.MAX_VALUE))
        assertFalse(sessionNegative.isExpired(Long.MAX_VALUE))
        assertEquals(0L, sessionZero.remainingMs(2_000_000L))
        assertEquals(0L, sessionNegative.remainingMs(2_000_000L))
    }
}
