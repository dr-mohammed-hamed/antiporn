package com.drmohammed.antiporn.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.DiagnosticEventEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.telemetry.DiagnosticEventDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Unit tests verifying Room Database configuration, MIGRATION_3_4 invariants,
 * and entity-to-domain transformations.
 */
class RoomMigrationTest {

    @Test
    fun testMigration3To4VersionInvariants() {
        val migration = HisnRoomDatabase.MIGRATION_3_4
        assertEquals("Migration start version must be 3", 3, migration.startVersion)
        assertEquals("Migration end version must be 4", 4, migration.endVersion)
    }

    @Test
    fun testMigrationExecutionOnSupportDatabase() {
        val executedSqlList = mutableListOf<String>()
        val dummyDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSqlList.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        // Executing migration should recreate tables with Room-compliant schemas and indices
        HisnRoomDatabase.MIGRATION_3_4.migrate(dummyDb)

        assertTrue("Migration must execute DDL statements", executedSqlList.isNotEmpty())

        val allSql = executedSqlList.joinToString(separator = "\n")
        assertTrue("Must create device_policies_new", allSql.contains("device_policies_new"))
        assertTrue("Must create partners_new", allSql.contains("partners_new"))
        assertTrue("Must create pending_approvals_new", allSql.contains("pending_approvals_new"))
        assertTrue("Must create diagnostic_events_new", allSql.contains("diagnostic_events_new"))

        assertTrue("Must create idx_partners_role", allSql.contains("idx_partners_role"))
        assertTrue("Must create idx_pending_approvals_status", allSql.contains("idx_pending_approvals_status"))
        assertTrue("Must create idx_diag_timestamp", allSql.contains("idx_diag_timestamp"))
        assertTrue("Must create idx_diag_tag", allSql.contains("idx_diag_tag"))
        assertTrue("Must create idx_diag_tag_timestamp", allSql.contains("idx_diag_tag_timestamp"))
    }

    @Test
    fun testDevicePolicyEntityMapping() {
        val bundle = DevicePolicyBundle.defaultPolicy()
        val entity = DevicePolicyEntity.fromBundle(bundle)

        assertEquals(1, entity.id)
        assertEquals(bundle.version, entity.version)
        assertEquals(bundle.updatedAt, entity.updatedAt)

        val restoredBundle = entity.toBundle()
        assertEquals(bundle.version, restoredBundle.version)
        assertEquals(bundle.isCurfewEnabled, restoredBundle.isCurfewEnabled)
        assertEquals(bundle.dnsHost, restoredBundle.dnsHost)
    }

    @Test
    fun testPartnerEntityConstantsAndProperties() {
        val partner = PartnerEntity(
            id = "partner_123",
            pairingCode = "987654",
            displayName = "Guardian 1",
            role = PartnerEntity.ROLE_GUARDIAN,
            status = PartnerEntity.STATUS_ACTIVE,
            createdAt = 1000L,
            lastSyncAt = 2000L
        )

        assertTrue(partner.isGuardian)
        assertFalse(partner.isWard)
        assertEquals("partner_123", partner.id)
        assertEquals("987654", partner.pairingCode)
        assertEquals("Guardian 1", partner.displayName)
        assertEquals(PartnerEntity.STATUS_ACTIVE, partner.status)
    }

    @Test
    fun testPendingApprovalEntityNullableHandling() {
        val pending = PendingApprovalEntity(
            packageName = "com.example.test",
            appName = "Test App",
            requestedAt = 1000L,
            status = PendingApprovalEntity.STATUS_PENDING,
            decidedBy = null,
            decidedAt = null,
            isCloudSynced = false
        )

        assertTrue(pending.isPending)
        assertFalse(pending.isApproved)
        assertFalse(pending.isRejected)
        assertNull(pending.decidedBy)
        assertNull(pending.decidedAt)
        assertFalse(pending.isCloudSynced)

        val approved = pending.copy(
            status = PendingApprovalEntity.STATUS_APPROVED,
            decidedBy = "guardian_abc",
            decidedAt = 2000L,
            isCloudSynced = true
        )

        assertFalse(approved.isPending)
        assertTrue(approved.isApproved)
        assertEquals("guardian_abc", approved.decidedBy)
        assertEquals(2000L, approved.decidedAt)
        assertTrue(approved.isCloudSynced)
    }

    @Test
    fun testDiagnosticEventEntityDtoRoundTrip() {
        val dto = DiagnosticEventDto(
            id = 42L,
            timestamp = 1730000000000L,
            tag = "CurfewEnforcer",
            actionName = "enforceCurfewLock",
            executionTimeMs = 12L,
            isSuccess = true,
            errorDetails = null,
            contextMetadata = "packages=5"
        )

        val entity = DiagnosticEventEntity.fromDto(dto)
        assertEquals(42L, entity.id)
        assertEquals(1730000000000L, entity.timestamp)
        assertEquals("CurfewEnforcer", entity.tag)
        assertEquals("enforceCurfewLock", entity.actionName)
        assertEquals(12L, entity.executionTimeMs)
        assertTrue(entity.isSuccess)
        assertNull(entity.errorDetails)
        assertEquals("packages=5", entity.contextMetadata)

        val roundTripDto = entity.toDto()
        assertEquals(dto.id, roundTripDto.id)
        assertEquals(dto.tag, roundTripDto.tag)
        assertEquals(dto.actionName, roundTripDto.actionName)
        assertEquals(dto.executionTimeMs, roundTripDto.executionTimeMs)
        assertEquals(dto.isSuccess, roundTripDto.isSuccess)
    }

    @Test
    fun testPendingApprovalUpsertEntityCreation() {
        val packageName = "com.untracked.rogueapp"
        val decidedAt = 1730000000000L
        val entity = PendingApprovalEntity(
            packageName = packageName,
            appName = packageName,
            requestedAt = decidedAt,
            status = PendingApprovalEntity.STATUS_REJECTED,
            decidedBy = "CUSTOM_BLACKLIST",
            decidedAt = decidedAt,
            isCloudSynced = true
        )

        assertEquals("com.untracked.rogueapp", entity.packageName)
        assertEquals(PendingApprovalEntity.STATUS_REJECTED, entity.status)
        assertEquals("CUSTOM_BLACKLIST", entity.decidedBy)
        assertTrue(entity.isCloudSynced)
        assertTrue(entity.isRejected)
        assertFalse(entity.isPending)
    }
}
