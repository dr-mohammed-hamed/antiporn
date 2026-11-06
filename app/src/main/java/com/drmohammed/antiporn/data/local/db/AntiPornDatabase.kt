package com.drmohammed.antiporn.data.local.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.notification.model.InAppNotification
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.telemetry.DiagnosticEventDto

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
        const val DATABASE_VERSION = 4

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

        // Table Diagnostic Events
        const val TABLE_DIAGNOSTIC_EVENTS = "diagnostic_events"
        const val COL_DIAG_ID = "id"
        const val COL_DIAG_TIMESTAMP = "timestamp"
        const val COL_DIAG_TAG = "tag"
        const val COL_DIAG_ACTION_NAME = "action_name"
        const val COL_DIAG_EXECUTION_TIME_MS = "execution_time_ms"
        const val COL_DIAG_IS_SUCCESS = "is_success"
        const val COL_DIAG_ERROR_DETAILS = "error_details"
        const val COL_DIAG_CONTEXT_METADATA = "context_metadata"

        // Table In-App Notifications
        const val TABLE_IN_APP_NOTIFICATIONS = "in_app_notifications"
        const val COL_NOTIF_ID = "id"
        const val COL_NOTIF_DEDUP_KEY = "dedup_key"
        const val COL_NOTIF_CATEGORY = "category"
        const val COL_NOTIF_TITLE = "title"
        const val COL_NOTIF_MESSAGE = "message"
        const val COL_NOTIF_TIMESTAMP = "timestamp"
        const val COL_NOTIF_IS_READ = "is_read"
        const val COL_NOTIF_ACTION_TYPE = "action_type"
        const val COL_NOTIF_ACTION_PAYLOAD = "action_payload"
        const val COL_NOTIF_IMPORTANCE = "importance"

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

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_DIAGNOSTIC_EVENTS (
                    $COL_DIAG_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_DIAG_TIMESTAMP INTEGER NOT NULL,
                    $COL_DIAG_TAG TEXT NOT NULL,
                    $COL_DIAG_ACTION_NAME TEXT NOT NULL,
                    $COL_DIAG_EXECUTION_TIME_MS INTEGER NOT NULL,
                    $COL_DIAG_IS_SUCCESS INTEGER NOT NULL,
                    $COL_DIAG_ERROR_DETAILS TEXT,
                    $COL_DIAG_CONTEXT_METADATA TEXT
                )
                """.trimIndent()
            )

            // Dedicated indexes for query performance
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_partners_role ON $TABLE_PARTNERS ($COL_PARTNER_ROLE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_approvals_status ON $TABLE_PENDING_APPROVALS ($COL_APPROVAL_STATUS)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_timestamp ON $TABLE_DIAGNOSTIC_EVENTS ($COL_DIAG_TIMESTAMP)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_tag ON $TABLE_DIAGNOSTIC_EVENTS ($COL_DIAG_TAG)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_tag_timestamp ON $TABLE_DIAGNOSTIC_EVENTS ($COL_DIAG_TAG, $COL_DIAG_TIMESTAMP DESC)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_IN_APP_NOTIFICATIONS (
                    $COL_NOTIF_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_NOTIF_DEDUP_KEY TEXT UNIQUE,
                    $COL_NOTIF_CATEGORY TEXT NOT NULL,
                    $COL_NOTIF_TITLE TEXT NOT NULL,
                    $COL_NOTIF_MESSAGE TEXT NOT NULL,
                    $COL_NOTIF_TIMESTAMP INTEGER NOT NULL,
                    $COL_NOTIF_IS_READ INTEGER NOT NULL DEFAULT 0,
                    $COL_NOTIF_ACTION_TYPE TEXT,
                    $COL_NOTIF_ACTION_PAYLOAD TEXT,
                    $COL_NOTIF_IMPORTANCE INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_timestamp ON $TABLE_IN_APP_NOTIFICATIONS ($COL_NOTIF_TIMESTAMP DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_is_read ON $TABLE_IN_APP_NOTIFICATIONS ($COL_NOTIF_IS_READ)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_category ON $TABLE_IN_APP_NOTIFICATIONS ($COL_NOTIF_CATEGORY)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_dedup ON $TABLE_IN_APP_NOTIFICATIONS ($COL_NOTIF_DEDUP_KEY)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_category_timestamp ON $TABLE_IN_APP_NOTIFICATIONS ($COL_NOTIF_CATEGORY, $COL_NOTIF_TIMESTAMP DESC)")

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
        if (oldVersion < 3) {
            db.beginTransaction()
            try {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_DIAGNOSTIC_EVENTS (
                        $COL_DIAG_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COL_DIAG_TIMESTAMP INTEGER NOT NULL,
                        $COL_DIAG_TAG TEXT NOT NULL,
                        $COL_DIAG_ACTION_NAME TEXT NOT NULL,
                        $COL_DIAG_EXECUTION_TIME_MS INTEGER NOT NULL,
                        $COL_DIAG_IS_SUCCESS INTEGER NOT NULL,
                        $COL_DIAG_ERROR_DETAILS TEXT,
                        $COL_DIAG_CONTEXT_METADATA TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_timestamp ON $TABLE_DIAGNOSTIC_EVENTS ($COL_DIAG_TIMESTAMP)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_tag ON $TABLE_DIAGNOSTIC_EVENTS ($COL_DIAG_TAG)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_tag_timestamp ON $TABLE_DIAGNOSTIC_EVENTS ($COL_DIAG_TAG, $COL_DIAG_TIMESTAMP DESC)")
                db.setTransactionSuccessful()
                Log.i(TAG, "Successfully upgraded AntiPornDatabase to version 3 (added $TABLE_DIAGNOSTIC_EVENTS)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed creating diagnostic_events table during upgrade to version 3", e)
                throw SQLException("Failed to upgrade database to version 3", e)
            } finally {
                db.endTransaction()
            }
        }
        if (oldVersion < 4) {
            db.beginTransaction()
            try {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_IN_APP_NOTIFICATIONS (
                        $COL_NOTIF_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COL_NOTIF_DEDUP_KEY TEXT UNIQUE,
                        $COL_NOTIF_CATEGORY TEXT NOT NULL,
                        $COL_NOTIF_TITLE TEXT NOT NULL,
                        $COL_NOTIF_MESSAGE TEXT NOT NULL,
                        $COL_NOTIF_TIMESTAMP INTEGER NOT NULL,
                        $COL_NOTIF_IS_READ INTEGER NOT NULL DEFAULT 0,
                        $COL_NOTIF_ACTION_TYPE TEXT,
                        $COL_NOTIF_ACTION_PAYLOAD TEXT,
                        $COL_NOTIF_IMPORTANCE INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_timestamp ON $TABLE_IN_APP_NOTIFICATIONS ($COL_NOTIF_TIMESTAMP DESC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_is_read ON $TABLE_IN_APP_NOTIFICATIONS ($COL_NOTIF_IS_READ)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_category ON $TABLE_IN_APP_NOTIFICATIONS ($COL_NOTIF_CATEGORY)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_dedup ON $TABLE_IN_APP_NOTIFICATIONS ($COL_NOTIF_DEDUP_KEY)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_category_timestamp ON $TABLE_IN_APP_NOTIFICATIONS ($COL_NOTIF_CATEGORY, $COL_NOTIF_TIMESTAMP DESC)")
                db.setTransactionSuccessful()
                Log.i(TAG, "Successfully upgraded AntiPornDatabase to version 4 (added $TABLE_IN_APP_NOTIFICATIONS)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed creating in_app_notifications table during upgrade to version 4", e)
                throw SQLException("Failed to upgrade database to version 4", e)
            } finally {
                db.endTransaction()
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
    fun getPartnerById(id: String): PartnerEntity? {
        val db = readableDatabase
        var cursor: Cursor? = null
        try {
            cursor = db.query(
                TABLE_PARTNERS,
                null,
                "$COL_PARTNER_ID = ?",
                arrayOf(id),
                null,
                null,
                null
            )
            if (cursor.moveToFirst()) {
                return cursorToPartner(cursor)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading partner by ID from SQLite", e)
        } finally {
            cursor?.close()
        }
        return null
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

    @Synchronized
    fun updatePartnerDisplayName(id: String, newName: String): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PARTNER_NAME, newName)
            put(COL_PARTNER_LAST_SYNC_AT, System.currentTimeMillis())
        }
        return try {
            val rows = db.update(TABLE_PARTNERS, values, "$COL_PARTNER_ID = ?", arrayOf(id))
            Log.d(TAG, "Updated partner $id name to '$newName' in SQLite, affected rows: $rows")
            rows
        } catch (e: Exception) {
            Log.e(TAG, "Error updating partner $id name in SQLite", e)
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
            throw e
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
            throw e
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

    @Synchronized
    fun deletePendingApproval(packageName: String): Int {
        val db = writableDatabase
        return try {
            val rows = db.delete(TABLE_PENDING_APPROVALS, "$COL_APPROVAL_PKG = ?", arrayOf(packageName))
            Log.d(TAG, "Deleted pending approval for $packageName from SQLite, affected rows: $rows")
            rows
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting pending approval from SQLite", e)
            0
        }
    }

    // ==========================================
    // Diagnostic & Telemetry Operations
    // ==========================================

    @Synchronized
    fun insertDiagnosticEvent(
        tag: String,
        actionName: String,
        executionTimeMs: Long,
        isSuccess: Boolean,
        errorDetails: String? = null,
        contextMetadata: String? = null,
        timestamp: Long = TrustedTimeProvider.currentTimeMillis()
    ): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_DIAG_TIMESTAMP, timestamp)
            put(COL_DIAG_TAG, tag)
            put(COL_DIAG_ACTION_NAME, actionName)
            put(COL_DIAG_EXECUTION_TIME_MS, executionTimeMs)
            put(COL_DIAG_IS_SUCCESS, if (isSuccess) 1 else 0)
            put(COL_DIAG_ERROR_DETAILS, errorDetails)
            put(COL_DIAG_CONTEXT_METADATA, contextMetadata)
        }
        return try {
            db.insert(TABLE_DIAGNOSTIC_EVENTS, null, values)
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting diagnostic event [$actionName]", e)
            -1L
        }
    }

    fun getRecentDiagnosticEvents(limit: Int = 50): List<DiagnosticEventDto> {
        val db = readableDatabase
        val events = mutableListOf<DiagnosticEventDto>()
        var cursor: Cursor? = null
        try {
            cursor = db.query(
                TABLE_DIAGNOSTIC_EVENTS,
                null,
                null,
                null,
                null,
                null,
                "$COL_DIAG_TIMESTAMP DESC",
                limit.toString()
            )
            if (cursor.moveToFirst()) {
                val idIdx = cursor.getColumnIndexOrThrow(COL_DIAG_ID)
                val timeIdx = cursor.getColumnIndexOrThrow(COL_DIAG_TIMESTAMP)
                val tagIdx = cursor.getColumnIndexOrThrow(COL_DIAG_TAG)
                val actionIdx = cursor.getColumnIndexOrThrow(COL_DIAG_ACTION_NAME)
                val execIdx = cursor.getColumnIndexOrThrow(COL_DIAG_EXECUTION_TIME_MS)
                val successIdx = cursor.getColumnIndexOrThrow(COL_DIAG_IS_SUCCESS)
                val errIdx = cursor.getColumnIndexOrThrow(COL_DIAG_ERROR_DETAILS)
                val metaIdx = cursor.getColumnIndexOrThrow(COL_DIAG_CONTEXT_METADATA)

                do {
                    events.add(
                        DiagnosticEventDto(
                            id = cursor.getLong(idIdx),
                            timestamp = cursor.getLong(timeIdx),
                            tag = cursor.getString(tagIdx),
                            actionName = cursor.getString(actionIdx),
                            executionTimeMs = cursor.getLong(execIdx),
                            isSuccess = cursor.getInt(successIdx) == 1,
                            errorDetails = cursor.getString(errIdx),
                            contextMetadata = cursor.getString(metaIdx)
                        )
                    )
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading recent diagnostic events", e)
        } finally {
            cursor?.close()
        }
        return events
    }

    @Synchronized
    fun pruneDiagnosticEventsOlderThan(days: Int = 30): Int {
        if (days <= 0) return 0
        val cutoffTimestamp = TrustedTimeProvider.currentTimeMillis() - (days.toLong() * 24 * 60 * 60 * 1000L)
        val db = writableDatabase
        return try {
            val deletedRows = db.delete(
                TABLE_DIAGNOSTIC_EVENTS,
                "$COL_DIAG_TIMESTAMP < ?",
                arrayOf(cutoffTimestamp.toString())
            )
            if (deletedRows > 0) {
                Log.i(TAG, "Pruned $deletedRows diagnostic events older than $days days")
            }
            deletedRows
        } catch (e: Exception) {
            Log.e(TAG, "Error pruning old diagnostic events", e)
            0
        }
    }

    /**
     * Enforces a hard cap on the total number of diagnostic events in SQLite (Ring Buffer).
     * Deletes the oldest events exceeding [maxEvents].
     */
    @Synchronized
    fun enforceMaxEventsCap(maxEvents: Int = 5000): Int {
        if (maxEvents <= 0) return 0
        val currentCount = getDiagnosticEventsCount()
        if (currentCount <= maxEvents) return 0
        val excess = (currentCount - maxEvents).toInt()
        val db = writableDatabase
        return try {
            // Delete oldest excess rows based on ascending timestamp / id
            val sql = "DELETE FROM $TABLE_DIAGNOSTIC_EVENTS WHERE $COL_DIAG_ID IN (" +
                    "SELECT $COL_DIAG_ID FROM $TABLE_DIAGNOSTIC_EVENTS ORDER BY $COL_DIAG_TIMESTAMP ASC, $COL_DIAG_ID ASC LIMIT ?)"
            db.execSQL(sql, arrayOf(excess))
            Log.i(TAG, "Enforced ring buffer cap: deleted $excess excess diagnostic events (was $currentCount, cap $maxEvents)")
            excess
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing diagnostic events cap", e)
            0
        }
    }

    /**
     * Clears all diagnostic events from the database.
     */
    @Synchronized
    fun clearAllDiagnosticEvents(): Boolean {
        val db = writableDatabase
        return try {
            db.delete(TABLE_DIAGNOSTIC_EVENTS, null, null)
            Log.i(TAG, "Cleared all diagnostic events from database")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all diagnostic events", e)
            false
        }
    }

    /**
     * Fetches diagnostic events with optional category filtering and custom limit.
     */
    fun getDiagnosticEvents(limit: Int = 200, category: String? = null): List<DiagnosticEventDto> {
        val db = readableDatabase
        val events = mutableListOf<DiagnosticEventDto>()
        var cursor: Cursor? = null
        try {
            val selection = if (category.isNullOrBlank() || category == "ALL") null else "$COL_DIAG_TAG = ?"
            val selectionArgs = if (category.isNullOrBlank() || category == "ALL") null else arrayOf(category)
            cursor = db.query(
                TABLE_DIAGNOSTIC_EVENTS,
                null,
                selection,
                selectionArgs,
                null,
                null,
                "$COL_DIAG_TIMESTAMP DESC",
                limit.toString()
            )
            if (cursor.moveToFirst()) {
                val idIdx = cursor.getColumnIndexOrThrow(COL_DIAG_ID)
                val timeIdx = cursor.getColumnIndexOrThrow(COL_DIAG_TIMESTAMP)
                val tagIdx = cursor.getColumnIndexOrThrow(COL_DIAG_TAG)
                val actionIdx = cursor.getColumnIndexOrThrow(COL_DIAG_ACTION_NAME)
                val execIdx = cursor.getColumnIndexOrThrow(COL_DIAG_EXECUTION_TIME_MS)
                val successIdx = cursor.getColumnIndexOrThrow(COL_DIAG_IS_SUCCESS)
                val errIdx = cursor.getColumnIndexOrThrow(COL_DIAG_ERROR_DETAILS)
                val metaIdx = cursor.getColumnIndexOrThrow(COL_DIAG_CONTEXT_METADATA)

                do {
                    events.add(
                        DiagnosticEventDto(
                            id = cursor.getLong(idIdx),
                            timestamp = cursor.getLong(timeIdx),
                            tag = cursor.getString(tagIdx),
                            actionName = cursor.getString(actionIdx),
                            executionTimeMs = cursor.getLong(execIdx),
                            isSuccess = cursor.getInt(successIdx) == 1,
                            errorDetails = cursor.getString(errIdx),
                            contextMetadata = cursor.getString(metaIdx)
                        )
                    )
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying diagnostic events (category=$category, limit=$limit)", e)
        } finally {
            cursor?.close()
        }
        return events
    }

    fun getDiagnosticEventsCount(): Long {
        val db = readableDatabase
        var cursor: Cursor? = null
        return try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_DIAGNOSTIC_EVENTS", null)
            if (cursor.moveToFirst()) {
                cursor.getLong(0)
            } else 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting diagnostic events count", e)
            0L
        } finally {
            cursor?.close()
        }
    }

    // ==========================================
    // In-App Notification Operations
    // ==========================================

    @Synchronized
    fun insertOrUpdateNotification(notification: InAppNotification, cooldownMs: Long = 0L): Long {
        val db = writableDatabase
        val sanitizedDedupKey = notification.dedupKey?.trim()?.ifEmpty { null }

        if (sanitizedDedupKey != null) {
            val existing = getNotificationByDedupKey(sanitizedDedupKey)
            if (existing != null) {
                val elapsedSinceLast = notification.timestamp - existing.timestamp
                if (cooldownMs > 0 && (elapsedSinceLast in 0 until cooldownMs || elapsedSinceLast < 0)) {
                    Log.d(TAG, "Notification for $sanitizedDedupKey ignored due to active cooldown (${elapsedSinceLast}ms < ${cooldownMs}ms)")
                    return -1L
                }

                // Cooldown passed: Update existing notification to make it prominent and unread again
                val values = ContentValues().apply {
                    put(COL_NOTIF_CATEGORY, notification.category.key)
                    put(COL_NOTIF_TITLE, notification.title)
                    put(COL_NOTIF_MESSAGE, notification.message)
                    put(COL_NOTIF_TIMESTAMP, notification.timestamp)
                    put(COL_NOTIF_IS_READ, 0)
                    put(COL_NOTIF_ACTION_TYPE, notification.actionType.key)
                    put(COL_NOTIF_ACTION_PAYLOAD, notification.actionPayload)
                    put(COL_NOTIF_IMPORTANCE, notification.importance)
                }
                return try {
                    val rows = db.update(TABLE_IN_APP_NOTIFICATIONS, values, "$COL_NOTIF_ID = ?", arrayOf(existing.id.toString()))
                    if (rows > 0) existing.id else -1L
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating existing notification for dedupKey $sanitizedDedupKey", e)
                    -1L
                }
            }
        }

        // Insert fresh notification
        val values = ContentValues().apply {
            put(COL_NOTIF_DEDUP_KEY, sanitizedDedupKey)
            put(COL_NOTIF_CATEGORY, notification.category.key)
            put(COL_NOTIF_TITLE, notification.title)
            put(COL_NOTIF_MESSAGE, notification.message)
            put(COL_NOTIF_TIMESTAMP, notification.timestamp)
            put(COL_NOTIF_IS_READ, if (notification.isRead) 1 else 0)
            put(COL_NOTIF_ACTION_TYPE, notification.actionType.key)
            put(COL_NOTIF_ACTION_PAYLOAD, notification.actionPayload)
            put(COL_NOTIF_IMPORTANCE, notification.importance)
        }

        return try {
            val rowId = db.insertWithOnConflict(
                TABLE_IN_APP_NOTIFICATIONS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            rowId
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting in-app notification [${notification.title}]", e)
            -1L
        }
    }

    fun getNotificationByDedupKey(dedupKey: String): InAppNotification? {
        val db = readableDatabase
        var cursor: Cursor? = null
        return try {
            cursor = db.query(
                TABLE_IN_APP_NOTIFICATIONS,
                null,
                "$COL_NOTIF_DEDUP_KEY = ?",
                arrayOf(dedupKey),
                null,
                null,
                null
            )
            if (cursor.moveToFirst()) {
                parseNotificationCursor(cursor)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching notification by dedupKey $dedupKey", e)
            null
        } finally {
            cursor?.close()
        }
    }

    fun getAllNotifications(limit: Int = 100, category: String? = null): List<InAppNotification> {
        val db = readableDatabase
        val list = mutableListOf<InAppNotification>()
        var cursor: Cursor? = null
        try {
            val selection = if (category.isNullOrBlank() || category == "ALL") null else "$COL_NOTIF_CATEGORY = ?"
            val selectionArgs = if (category.isNullOrBlank() || category == "ALL") null else arrayOf(category)
            cursor = db.query(
                TABLE_IN_APP_NOTIFICATIONS,
                null,
                selection,
                selectionArgs,
                null,
                null,
                "$COL_NOTIF_TIMESTAMP DESC",
                limit.toString()
            )
            while (cursor.moveToNext()) {
                list.add(parseNotificationCursor(cursor))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading all notifications", e)
        } finally {
            cursor?.close()
        }
        return list
    }

    fun getUnreadNotificationsCount(): Int {
        val db = readableDatabase
        var cursor: Cursor? = null
        return try {
            cursor = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_IN_APP_NOTIFICATIONS WHERE $COL_NOTIF_IS_READ = 0",
                null
            )
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting unread notifications count", e)
            0
        } finally {
            cursor?.close()
        }
    }

    @Synchronized
    fun markNotificationAsRead(id: Long): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NOTIF_IS_READ, 1)
        }
        return try {
            val rows = db.update(TABLE_IN_APP_NOTIFICATIONS, values, "$COL_NOTIF_ID = ?", arrayOf(id.toString()))
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error marking notification $id as read", e)
            false
        }
    }

    @Synchronized
    fun markAllNotificationsAsRead(): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NOTIF_IS_READ, 1)
        }
        return try {
            db.update(TABLE_IN_APP_NOTIFICATIONS, values, "$COL_NOTIF_IS_READ = 0", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error marking all notifications as read", e)
            0
        }
    }

    @Synchronized
    fun deleteNotification(id: Long): Boolean {
        val db = writableDatabase
        return try {
            val rows = db.delete(TABLE_IN_APP_NOTIFICATIONS, "$COL_NOTIF_ID = ?", arrayOf(id.toString()))
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting notification $id", e)
            false
        }
    }

    @Synchronized
    fun clearAllNotifications(): Int {
        val db = writableDatabase
        return try {
            db.delete(TABLE_IN_APP_NOTIFICATIONS, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all notifications", e)
            0
        }
    }

    @Synchronized
    fun pruneOldNotifications(maxKeep: Int = 200, olderThanDays: Int = 30): Int {
        val db = writableDatabase
        var pruned = 0
        db.beginTransaction()
        try {
            if (olderThanDays > 0) {
                val cutoff = TrustedTimeProvider.currentTimeMillis() - (olderThanDays.toLong() * 24 * 60 * 60 * 1000L)
                pruned += db.delete(TABLE_IN_APP_NOTIFICATIONS, "$COL_NOTIF_TIMESTAMP < ?", arrayOf(cutoff.toString()))
            }
            if (maxKeep > 0) {
                val total = db.rawQuery("SELECT COUNT(*) FROM $TABLE_IN_APP_NOTIFICATIONS", null).use { countCursor ->
                    if (countCursor.moveToFirst()) countCursor.getInt(0) else 0
                }

                if (total > maxKeep) {
                    val excess = total - maxKeep
                    val sql = "DELETE FROM $TABLE_IN_APP_NOTIFICATIONS WHERE $COL_NOTIF_ID IN (" +
                            "SELECT $COL_NOTIF_ID FROM $TABLE_IN_APP_NOTIFICATIONS ORDER BY $COL_NOTIF_TIMESTAMP ASC, $COL_NOTIF_ID ASC LIMIT ?)"
                    db.execSQL(sql, arrayOf<Any>(excess))
                    pruned += excess
                }
            }
            db.setTransactionSuccessful()
            if (pruned > 0) {
                Log.i(TAG, "Pruned $pruned excess/stale in-app notifications (kept last $maxKeep)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pruning old in-app notifications", e)
        } finally {
            db.endTransaction()
        }
        return pruned
    }

    private fun parseNotificationCursor(cursor: Cursor): InAppNotification {
        val idIdx = cursor.getColumnIndexOrThrow(COL_NOTIF_ID)
        val dedupIdx = cursor.getColumnIndexOrThrow(COL_NOTIF_DEDUP_KEY)
        val categoryIdx = cursor.getColumnIndexOrThrow(COL_NOTIF_CATEGORY)
        val titleIdx = cursor.getColumnIndexOrThrow(COL_NOTIF_TITLE)
        val msgIdx = cursor.getColumnIndexOrThrow(COL_NOTIF_MESSAGE)
        val timeIdx = cursor.getColumnIndexOrThrow(COL_NOTIF_TIMESTAMP)
        val readIdx = cursor.getColumnIndexOrThrow(COL_NOTIF_IS_READ)
        val actionIdx = cursor.getColumnIndexOrThrow(COL_NOTIF_ACTION_TYPE)
        val payloadIdx = cursor.getColumnIndexOrThrow(COL_NOTIF_ACTION_PAYLOAD)
        val impIdx = cursor.getColumnIndexOrThrow(COL_NOTIF_IMPORTANCE)

        return InAppNotification(
            id = cursor.getLong(idIdx),
            dedupKey = if (cursor.isNull(dedupIdx)) null else cursor.getString(dedupIdx),
            category = NotificationCategory.fromKey(cursor.getString(categoryIdx)),
            title = if (cursor.isNull(titleIdx)) "" else (cursor.getString(titleIdx) ?: ""),
            message = if (cursor.isNull(msgIdx)) "" else (cursor.getString(msgIdx) ?: ""),
            timestamp = cursor.getLong(timeIdx),
            isRead = cursor.getInt(readIdx) == 1,
            actionType = NotificationActionType.fromKey(if (cursor.isNull(actionIdx)) null else cursor.getString(actionIdx)),
            actionPayload = if (cursor.isNull(payloadIdx)) null else cursor.getString(payloadIdx),
            importance = cursor.getInt(impIdx)
        )
    }
}
