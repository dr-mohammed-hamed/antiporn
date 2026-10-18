package com.drmohammed.antiporn.data.local.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity

/**
 * Embedded SQLite database for AntiPorn.
 * Adheres strictly to SQLite Database Expert principles:
 * - WAL mode enabled for high-performance concurrent reads and writes
 * - 100% parameterized queries (Zero SQL injection vulnerability)
 * - Safe transaction management for atomicity
 * - Robust error handling with propagated write exceptions
 */
class AntiPornDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    companion object {
        private const val TAG = "AntiPorn_Database"
        const val DATABASE_NAME = "antiporn_guard.db"
        const val DATABASE_VERSION = 2

        // Table Device Policies
        const val TABLE_DEVICE_POLICIES = "device_policies"
        const val COL_POLICY_ID = "id"
        const val COL_POLICY_JSON = "policy_json"
        const val COL_POLICY_VERSION = "version"
        const val COL_POLICY_UPDATED_AT = "updated_at"

        // Table Partners
        const val TABLE_PARTNERS = "partners"
        const val COL_PARTNER_ID = "id"
        const val COL_PARTNER_PAIRING_CODE = "pairing_code"
        const val COL_PARTNER_NAME = "display_name"
        const val COL_PARTNER_ROLE = "role"
        const val COL_PARTNER_STATUS = "status"
        const val COL_PARTNER_CREATED_AT = "created_at"
        const val COL_PARTNER_LAST_SYNC_AT = "last_sync_at"

        // Table Pending Approvals
        const val TABLE_PENDING_APPROVALS = "pending_approvals"
        const val COL_APPROVAL_PKG = "package_name"
        const val COL_APPROVAL_APP_NAME = "app_name"
        const val COL_APPROVAL_REQUESTED_AT = "requested_at"
        const val COL_APPROVAL_STATUS = "status"
        const val COL_APPROVAL_DECIDED_BY = "decided_by"
        const val COL_APPROVAL_DECIDED_AT = "decided_at"
        const val COL_APPROVAL_IS_SYNCED = "is_synced"

        @Volatile
        private var instance: AntiPornDatabase? = null

        fun getInstance(context: Context): AntiPornDatabase {
            return instance ?: synchronized(this) {
                instance ?: AntiPornDatabase(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_DEVICE_POLICIES (
                    $COL_POLICY_ID INTEGER PRIMARY KEY,
                    $COL_POLICY_JSON TEXT NOT NULL,
                    $COL_POLICY_VERSION INTEGER NOT NULL,
                    $COL_POLICY_UPDATED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_PARTNERS (
                    $COL_PARTNER_ID TEXT PRIMARY KEY,
                    $COL_PARTNER_PAIRING_CODE TEXT NOT NULL,
                    $COL_PARTNER_NAME TEXT NOT NULL,
                    $COL_PARTNER_ROLE TEXT NOT NULL,
                    $COL_PARTNER_STATUS TEXT NOT NULL,
                    $COL_PARTNER_CREATED_AT INTEGER NOT NULL,
                    $COL_PARTNER_LAST_SYNC_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_PENDING_APPROVALS (
                    $COL_APPROVAL_PKG TEXT PRIMARY KEY,
                    $COL_APPROVAL_APP_NAME TEXT NOT NULL,
                    $COL_APPROVAL_REQUESTED_AT INTEGER NOT NULL,
                    $COL_APPROVAL_STATUS TEXT NOT NULL,
                    $COL_APPROVAL_DECIDED_BY TEXT,
                    $COL_APPROVAL_DECIDED_AT INTEGER,
                    $COL_APPROVAL_IS_SYNCED INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )

            // Dedicated indexes for query performance
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_partners_role ON $TABLE_PARTNERS ($COL_PARTNER_ROLE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_approvals_status ON $TABLE_PENDING_APPROVALS ($COL_APPROVAL_STATUS)")

            // Seed default policy if empty
            val defaultBundle = DevicePolicyBundle.defaultPolicy()
            val initialValues = ContentValues().apply {
                put(COL_POLICY_ID, 1)
                put(COL_POLICY_JSON, defaultBundle.toJson())
                put(COL_POLICY_VERSION, defaultBundle.version)
                put(COL_POLICY_UPDATED_AT, defaultBundle.updatedAt)
            }
            db.insertWithOnConflict(TABLE_DEVICE_POLICIES, null, initialValues, SQLiteDatabase.CONFLICT_IGNORE)

            db.setTransactionSuccessful()
            Log.i(TAG, "AntiPornDatabase schema initialized successfully (version $DATABASE_VERSION)")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing database tables", e)
            throw IllegalStateException("Failed to initialize AntiPornDatabase tables", e)
        } finally {
            db.endTransaction()
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading database from $oldVersion to $newVersion")
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_PENDING_APPROVALS ADD COLUMN $COL_APPROVAL_IS_SYNCED INTEGER NOT NULL DEFAULT 0")
            } catch (e: Exception) {
                Log.w(TAG, "Column $COL_APPROVAL_IS_SYNCED may already exist", e)
            }
            try {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_partners_role ON $TABLE_PARTNERS ($COL_PARTNER_ROLE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_approvals_status ON $TABLE_PENDING_APPROVALS ($COL_APPROVAL_STATUS)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed creating upgrade indexes", e)
            }
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "Downgrading database from $oldVersion to $newVersion; preserving tables and data safely")
    }

    // ==========================================
    // Device Policy Operations
    // ==========================================

    @Synchronized
    fun getDevicePolicy(): DevicePolicyEntity? {
        val db = readableDatabase
        var cursor: Cursor? = null
        return try {
            cursor = db.query(
                TABLE_DEVICE_POLICIES,
                null,
                "$COL_POLICY_ID = ?",
                arrayOf("1"),
                null,
                null,
                null
            )
            if (cursor.moveToFirst()) {
                val json = cursor.getString(cursor.getColumnIndexOrThrow(COL_POLICY_JSON))
                val version = cursor.getLong(cursor.getColumnIndexOrThrow(COL_POLICY_VERSION))
                val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_POLICY_UPDATED_AT))
                DevicePolicyEntity(1, json, version, updatedAt)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading device policy from SQLite", e)
            null
        } finally {
            cursor?.close()
        }
    }

    @Synchronized
    fun saveDevicePolicy(entity: DevicePolicyEntity) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_POLICY_ID, 1)
            put(COL_POLICY_JSON, entity.policyJson)
            put(COL_POLICY_VERSION, entity.version)
            put(COL_POLICY_UPDATED_AT, entity.updatedAt)
        }
        try {
            val rowId = db.insertWithOnConflict(TABLE_DEVICE_POLICIES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            if (rowId == -1L) {
                throw SQLException("Failed to insert/replace device policy in SQLite")
            }
            Log.d(TAG, "Saved device policy to SQLite version ${entity.version}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving device policy to SQLite", e)
            throw e
        }
    }

    // ==========================================
    // Partner Operations
    // ==========================================

    @Synchronized
    fun getAllPartners(): List<PartnerEntity> {
        val db = readableDatabase
        val list = mutableListOf<PartnerEntity>()
        var cursor: Cursor? = null
        try {
            cursor = db.query(
                TABLE_PARTNERS,
                null,
                null,
                null,
                null,
                null,
                "$COL_PARTNER_CREATED_AT DESC"
            )
            while (cursor.moveToNext()) {
                list.add(cursorToPartner(cursor))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading all partners from SQLite", e)
        } finally {
            cursor?.close()
        }
        return list
    }

    @Synchronized
    fun getPartnersByRole(role: String): List<PartnerEntity> {
        val db = readableDatabase
        val list = mutableListOf<PartnerEntity>()
        var cursor: Cursor? = null
        try {
            cursor = db.query(
                TABLE_PARTNERS,
                null,
                "$COL_PARTNER_ROLE = ?",
                arrayOf(role),
                null,
                null,
                "$COL_PARTNER_CREATED_AT DESC"
            )
            while (cursor.moveToNext()) {
                list.add(cursorToPartner(cursor))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading partners by role from SQLite", e)
        } finally {
            cursor?.close()
        }
        return list
    }

    @Synchronized
    fun savePartner(partner: PartnerEntity) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PARTNER_ID, partner.id)
            put(COL_PARTNER_PAIRING_CODE, partner.pairingCode)
            put(COL_PARTNER_NAME, partner.displayName)
            put(COL_PARTNER_ROLE, partner.role)
            put(COL_PARTNER_STATUS, partner.status)
            put(COL_PARTNER_CREATED_AT, partner.createdAt)
            put(COL_PARTNER_LAST_SYNC_AT, partner.lastSyncAt)
        }
        try {
            val rowId = db.insertWithOnConflict(TABLE_PARTNERS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            if (rowId == -1L) {
                throw SQLException("Failed to insert/replace partner in SQLite")
            }
            Log.d(TAG, "Saved partner ${partner.displayName} (${partner.role}) to SQLite")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving partner to SQLite", e)
            throw e
        }
    }

    @Synchronized
    fun deletePartner(id: String): Int {
        val db = writableDatabase
        return try {
            val rows = db.delete(TABLE_PARTNERS, "$COL_PARTNER_ID = ?", arrayOf(id))
            Log.d(TAG, "Deleted partner $id from SQLite, affected rows: $rows")
            rows
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting partner $id from SQLite", e)
            throw e
        }
    }

    private fun cursorToPartner(cursor: Cursor): PartnerEntity {
        return PartnerEntity(
            id = cursor.getString(cursor.getColumnIndexOrThrow(COL_PARTNER_ID)),
            pairingCode = cursor.getString(cursor.getColumnIndexOrThrow(COL_PARTNER_PAIRING_CODE)),
            displayName = cursor.getString(cursor.getColumnIndexOrThrow(COL_PARTNER_NAME)),
            role = cursor.getString(cursor.getColumnIndexOrThrow(COL_PARTNER_ROLE)),
            status = cursor.getString(cursor.getColumnIndexOrThrow(COL_PARTNER_STATUS)),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_PARTNER_CREATED_AT)),
            lastSyncAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_PARTNER_LAST_SYNC_AT))
        )
    }

    // ==========================================
    // Pending Approvals Operations
    // ==========================================

    @Synchronized
    fun getPendingApprovals(): List<PendingApprovalEntity> {
        val db = readableDatabase
        val list = mutableListOf<PendingApprovalEntity>()
        var cursor: Cursor? = null
        try {
            cursor = db.query(
                TABLE_PENDING_APPROVALS,
                null,
                null,
                null,
                null,
                null,
                "$COL_APPROVAL_REQUESTED_AT DESC"
            )
            while (cursor.moveToNext()) {
                val decidedByIdx = cursor.getColumnIndexOrThrow(COL_APPROVAL_DECIDED_BY)
                val decidedAtIdx = cursor.getColumnIndexOrThrow(COL_APPROVAL_DECIDED_AT)
                val isSyncedIdx = cursor.getColumnIndex(COL_APPROVAL_IS_SYNCED)
                val isSynced = if (isSyncedIdx != -1 && !cursor.isNull(isSyncedIdx)) cursor.getInt(isSyncedIdx) == 1 else false
                list.add(
                    PendingApprovalEntity(
                        packageName = cursor.getString(cursor.getColumnIndexOrThrow(COL_APPROVAL_PKG)),
                        appName = cursor.getString(cursor.getColumnIndexOrThrow(COL_APPROVAL_APP_NAME)),
                        requestedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_APPROVAL_REQUESTED_AT)),
                        status = cursor.getString(cursor.getColumnIndexOrThrow(COL_APPROVAL_STATUS)),
                        decidedBy = if (cursor.isNull(decidedByIdx)) null else cursor.getString(decidedByIdx),
                        decidedAt = if (cursor.isNull(decidedAtIdx)) null else cursor.getLong(decidedAtIdx),
                        isCloudSynced = isSynced
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading pending approvals from SQLite", e)
        } finally {
            cursor?.close()
        }
        return list
    }

    @Synchronized
    fun getUnsyncedApprovals(): List<PendingApprovalEntity> {
        val db = readableDatabase
        val list = mutableListOf<PendingApprovalEntity>()
        var cursor: Cursor? = null
        try {
            cursor = db.query(
                TABLE_PENDING_APPROVALS,
                null,
                "$COL_APPROVAL_IS_SYNCED = 0",
                null,
                null,
                null,
                "$COL_APPROVAL_REQUESTED_AT ASC"
            )
            while (cursor.moveToNext()) {
                val decidedByIdx = cursor.getColumnIndexOrThrow(COL_APPROVAL_DECIDED_BY)
                val decidedAtIdx = cursor.getColumnIndexOrThrow(COL_APPROVAL_DECIDED_AT)
                val isSyncedIdx = cursor.getColumnIndex(COL_APPROVAL_IS_SYNCED)
                val isSynced = if (isSyncedIdx != -1 && !cursor.isNull(isSyncedIdx)) cursor.getInt(isSyncedIdx) == 1 else false
                list.add(
                    PendingApprovalEntity(
                        packageName = cursor.getString(cursor.getColumnIndexOrThrow(COL_APPROVAL_PKG)),
                        appName = cursor.getString(cursor.getColumnIndexOrThrow(COL_APPROVAL_APP_NAME)),
                        requestedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_APPROVAL_REQUESTED_AT)),
                        status = cursor.getString(cursor.getColumnIndexOrThrow(COL_APPROVAL_STATUS)),
                        decidedBy = if (cursor.isNull(decidedByIdx)) null else cursor.getString(decidedByIdx),
                        decidedAt = if (cursor.isNull(decidedAtIdx)) null else cursor.getLong(decidedAtIdx),
                        isCloudSynced = isSynced
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading unsynced approvals from SQLite", e)
        } finally {
            cursor?.close()
        }
        return list
    }

    @Synchronized
    fun savePendingApproval(approval: PendingApprovalEntity) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_APPROVAL_PKG, approval.packageName)
            put(COL_APPROVAL_APP_NAME, approval.appName)
            put(COL_APPROVAL_REQUESTED_AT, approval.requestedAt)
            put(COL_APPROVAL_STATUS, approval.status)
            put(COL_APPROVAL_DECIDED_BY, approval.decidedBy)
            put(COL_APPROVAL_DECIDED_AT, approval.decidedAt)
            put(COL_APPROVAL_IS_SYNCED, if (approval.isCloudSynced) 1 else 0)
        }
        try {
            val rowId = db.insertWithOnConflict(TABLE_PENDING_APPROVALS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            if (rowId == -1L) {
                throw SQLException("Failed to insert/replace pending approval in SQLite")
            }
            Log.d(TAG, "Saved pending approval for ${approval.packageName} to SQLite (synced=${approval.isCloudSynced})")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving pending approval to SQLite", e)
            throw e
        }
    }

    @Synchronized
    fun updateApprovalStatus(packageName: String, status: String, decidedBy: String?, isSynced: Boolean = true) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_APPROVAL_STATUS, status)
            put(COL_APPROVAL_DECIDED_BY, decidedBy)
            put(COL_APPROVAL_DECIDED_AT, System.currentTimeMillis())
            put(COL_APPROVAL_IS_SYNCED, if (isSynced) 1 else 0)
        }
        try {
            val affectedRows = db.update(
                TABLE_PENDING_APPROVALS,
                values,
                "$COL_APPROVAL_PKG = ?",
                arrayOf(packageName)
            )
            if (affectedRows == 0) {
                values.put(COL_APPROVAL_PKG, packageName)
                values.put(COL_APPROVAL_APP_NAME, packageName)
                values.put(COL_APPROVAL_REQUESTED_AT, System.currentTimeMillis())
                val rowId = db.insertWithOnConflict(TABLE_PENDING_APPROVALS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                if (rowId == -1L) {
                    throw SQLException("Failed to insert pending approval status in SQLite")
                }
            }
            Log.d(TAG, "Updated approval status for $packageName to $status")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating approval status in SQLite", e)
            throw e
        }
    }

    @Synchronized
    fun markApprovalCloudSynced(packageName: String, isSynced: Boolean = true) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_APPROVAL_IS_SYNCED, if (isSynced) 1 else 0)
        }
        try {
            db.update(TABLE_PENDING_APPROVALS, values, "$COL_APPROVAL_PKG = ?", arrayOf(packageName))
        } catch (e: Exception) {
            Log.e(TAG, "Error marking approval synced", e)
            throw e
        }
    }
}
