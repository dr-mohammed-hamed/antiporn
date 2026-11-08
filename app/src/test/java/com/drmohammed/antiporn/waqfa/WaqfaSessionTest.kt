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
}
