package com.drmohammed.antiporn.data.local.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.DiagnosticEventDao
import com.drmohammed.antiporn.data.local.db.dao.InAppNotificationDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.dao.PendingApprovalDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.DiagnosticEventEntity
import com.drmohammed.antiporn.data.local.db.entity.InAppNotificationEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.waqfa.data.WaqfaDao
import com.drmohammed.antiporn.waqfa.data.WaqfaSessionEntity

/**
 * Modern Jetpack Room Database for Project Hisn.
 * Replaces legacy SQLiteOpenHelper while strictly preserving existing user data.
 */
@Database(
    entities = [
        DevicePolicyEntity::class,
        PartnerEntity::class,
        PendingApprovalEntity::class,
        DiagnosticEventEntity::class,
        InAppNotificationEntity::class,
        WaqfaSessionEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class HisnRoomDatabase : RoomDatabase() {

    abstract fun devicePolicyDao(): DevicePolicyDao
    abstract fun partnerDao(): PartnerDao
    abstract fun pendingApprovalDao(): PendingApprovalDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao
    abstract fun inAppNotificationDao(): InAppNotificationDao
    abstract fun waqfaDao(): WaqfaDao

    companion object {
        const val DATABASE_NAME = "antiporn_guard.db"

        @Volatile
        private var instance: HisnRoomDatabase? = null

        /**
         * Migration from raw SQLiteOpenHelper version 3 to Room version 4.
         * Recreates tables with exact NOT NULL constraints and indices expected by Room schema validation,
         * copying over all existing rows without data loss.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Table: device_policies
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `device_policies_new` (`id` INTEGER NOT NULL, `policy_json` TEXT NOT NULL, `version` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `device_policies_new` (`id`, `policy_json`, `version`, `updated_at`) SELECT `id`, `policy_json`, `version`, `updated_at` FROM `device_policies`"
                )
                db.execSQL("DROP TABLE IF EXISTS `device_policies`")
                db.execSQL("ALTER TABLE `device_policies_new` RENAME TO `device_policies`")

                // 2. Table: partners
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `partners_new` (`id` TEXT NOT NULL, `pairing_code` TEXT NOT NULL, `display_name` TEXT NOT NULL, `role` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `last_sync_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `partners_new` (`id`, `pairing_code`, `display_name`, `role`, `status`, `created_at`, `last_sync_at`) SELECT `id`, `pairing_code`, `display_name`, `role`, `status`, `created_at`, `last_sync_at` FROM `partners`"
                )
                db.execSQL("DROP TABLE IF EXISTS `partners`")
                db.execSQL("ALTER TABLE `partners_new` RENAME TO `partners`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_partners_role` ON `partners` (`role`)")

                // 3. Table: pending_approvals
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_approvals_new` (`package_name` TEXT NOT NULL, `app_name` TEXT NOT NULL, `requested_at` INTEGER NOT NULL, `status` TEXT NOT NULL, `decided_by` TEXT, `decided_at` INTEGER, `is_synced` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`package_name`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `pending_approvals_new` (`package_name`, `app_name`, `requested_at`, `status`, `decided_by`, `decided_at`, `is_synced`) SELECT `package_name`, `app_name`, `requested_at`, `status`, `decided_by`, `decided_at`, `is_synced` FROM `pending_approvals`"
                )
                db.execSQL("DROP TABLE IF EXISTS `pending_approvals`")
                db.execSQL("ALTER TABLE `pending_approvals_new` RENAME TO `pending_approvals`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_pending_approvals_status` ON `pending_approvals` (`status`)")

                // 4. Table: diagnostic_events
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `diagnostic_events_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `tag` TEXT NOT NULL, `action_name` TEXT NOT NULL, `execution_time_ms` INTEGER NOT NULL, `is_success` INTEGER NOT NULL, `error_details` TEXT, `context_metadata` TEXT)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `diagnostic_events_new` (`id`, `timestamp`, `tag`, `action_name`, `execution_time_ms`, `is_success`, `error_details`, `context_metadata`) SELECT `id`, `timestamp`, `tag`, `action_name`, `execution_time_ms`, `is_success`, `error_details`, `context_metadata` FROM `diagnostic_events`"
                )
                db.execSQL("DROP TABLE IF EXISTS `diagnostic_events`")
                db.execSQL("ALTER TABLE `diagnostic_events_new` RENAME TO `diagnostic_events`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_diag_timestamp` ON `diagnostic_events` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_diag_tag` ON `diagnostic_events` (`tag`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_diag_tag_timestamp` ON `diagnostic_events` (`tag` ASC, `timestamp` DESC)")
            }
        }

        /**
         * Migration from raw SQLite version 4 (from main branch) or Room v4 to Room version 5.
         * Recreates tables with NOT NULL primary keys, drops legacy in_app_notifications table,
         * and copies over all existing data without data loss.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Table: device_policies
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `device_policies_new` (`id` INTEGER NOT NULL, `policy_json` TEXT NOT NULL, `version` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `device_policies_new` (`id`, `policy_json`, `version`, `updated_at`) SELECT `id`, `policy_json`, `version`, `updated_at` FROM `device_policies`"
                )
                db.execSQL("DROP TABLE IF EXISTS `device_policies`")
                db.execSQL("ALTER TABLE `device_policies_new` RENAME TO `device_policies`")

                // 2. Table: partners
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `partners_new` (`id` TEXT NOT NULL, `pairing_code` TEXT NOT NULL, `display_name` TEXT NOT NULL, `role` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `last_sync_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `partners_new` (`id`, `pairing_code`, `display_name`, `role`, `status`, `created_at`, `last_sync_at`) SELECT `id`, `pairing_code`, `display_name`, `role`, `status`, `created_at`, `last_sync_at` FROM `partners`"
                )
                db.execSQL("DROP TABLE IF EXISTS `partners`")
                db.execSQL("ALTER TABLE `partners_new` RENAME TO `partners`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_partners_role` ON `partners` (`role`)")

                // 3. Table: pending_approvals
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_approvals_new` (`package_name` TEXT NOT NULL, `app_name` TEXT NOT NULL, `requested_at` INTEGER NOT NULL, `status` TEXT NOT NULL, `decided_by` TEXT, `decided_at` INTEGER, `is_synced` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`package_name`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `pending_approvals_new` (`package_name`, `app_name`, `requested_at`, `status`, `decided_by`, `decided_at`, `is_synced`) SELECT `package_name`, `app_name`, `requested_at`, `status`, `decided_by`, `decided_at`, `is_synced` FROM `pending_approvals`"
                )
                db.execSQL("DROP TABLE IF EXISTS `pending_approvals`")
                db.execSQL("ALTER TABLE `pending_approvals_new` RENAME TO `pending_approvals`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_pending_approvals_status` ON `pending_approvals` (`status`)")

                // 4. Table: diagnostic_events
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `diagnostic_events_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `tag` TEXT NOT NULL, `action_name` TEXT NOT NULL, `execution_time_ms` INTEGER NOT NULL, `is_success` INTEGER NOT NULL, `error_details` TEXT, `context_metadata` TEXT)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `diagnostic_events_new` (`id`, `timestamp`, `tag`, `action_name`, `execution_time_ms`, `is_success`, `error_details`, `context_metadata`) SELECT `id`, `timestamp`, `tag`, `action_name`, `execution_time_ms`, `is_success`, `error_details`, `context_metadata` FROM `diagnostic_events`"
                )
                db.execSQL("DROP TABLE IF EXISTS `diagnostic_events`")
                db.execSQL("ALTER TABLE `diagnostic_events_new` RENAME TO `diagnostic_events`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_diag_timestamp` ON `diagnostic_events` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_diag_tag` ON `diagnostic_events` (`tag`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_diag_tag_timestamp` ON `diagnostic_events` (`tag` ASC, `timestamp` DESC)")

                // 5. Table: in_app_notifications (Preserve existing notifications from SQLite v4)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `in_app_notifications` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dedup_key` TEXT,
                        `category` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `is_read` INTEGER NOT NULL DEFAULT 0,
                        `action_type` TEXT,
                        `action_payload` TEXT,
                        `importance` INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `in_app_notifications_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dedup_key` TEXT,
                        `category` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `is_read` INTEGER NOT NULL DEFAULT 0,
                        `action_type` TEXT,
                        `action_payload` TEXT,
                        `importance` INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `in_app_notifications_new` 
                    (`id`, `dedup_key`, `category`, `title`, `message`, `timestamp`, `is_read`, `action_type`, `action_payload`, `importance`)
                    SELECT `id`, `dedup_key`, `category`, `title`, `message`, `timestamp`, `is_read`, `action_type`, `action_payload`, `importance` 
                    FROM `in_app_notifications`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS `in_app_notifications`")
                db.execSQL("ALTER TABLE `in_app_notifications_new` RENAME TO `in_app_notifications`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_notif_timestamp` ON `in_app_notifications` (`timestamp` DESC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_notif_is_read` ON `in_app_notifications` (`is_read`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_notif_category` ON `in_app_notifications` (`category`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_notif_dedup` ON `in_app_notifications` (`dedup_key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_notif_category_timestamp` ON `in_app_notifications` (`category` ASC, `timestamp` DESC)")
            }
        }

        /**
         * Migration directly from raw SQLite version 3 to Room version 5.
         * Executes in a single transactional pass to avoid rebuilding/copying tables twice.
         */
        val MIGRATION_3_5 = object : Migration(3, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Table: device_policies
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `device_policies_new` (`id` INTEGER NOT NULL, `policy_json` TEXT NOT NULL, `version` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `device_policies_new` (`id`, `policy_json`, `version`, `updated_at`) SELECT `id`, `policy_json`, `version`, `updated_at` FROM `device_policies`"
                )
                db.execSQL("DROP TABLE IF EXISTS `device_policies`")
                db.execSQL("ALTER TABLE `device_policies_new` RENAME TO `device_policies`")

                // 2. Table: partners
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `partners_new` (`id` TEXT NOT NULL, `pairing_code` TEXT NOT NULL, `display_name` TEXT NOT NULL, `role` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `last_sync_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `partners_new` (`id`, `pairing_code`, `display_name`, `role`, `status`, `created_at`, `last_sync_at`) SELECT `id`, `pairing_code`, `display_name`, `role`, `status`, `created_at`, `last_sync_at` FROM `partners`"
                )
                db.execSQL("DROP TABLE IF EXISTS `partners`")
                db.execSQL("ALTER TABLE `partners_new` RENAME TO `partners`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_partners_role` ON `partners` (`role`)")

                // 3. Table: pending_approvals
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_approvals_new` (`package_name` TEXT NOT NULL, `app_name` TEXT NOT NULL, `requested_at` INTEGER NOT NULL, `status` TEXT NOT NULL, `decided_by` TEXT, `decided_at` INTEGER, `is_synced` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`package_name`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `pending_approvals_new` (`package_name`, `app_name`, `requested_at`, `status`, `decided_by`, `decided_at`, `is_synced`) SELECT `package_name`, `app_name`, `requested_at`, `status`, `decided_by`, `decided_at`, `is_synced` FROM `pending_approvals`"
                )
                db.execSQL("DROP TABLE IF EXISTS `pending_approvals`")
                db.execSQL("ALTER TABLE `pending_approvals_new` RENAME TO `pending_approvals`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_pending_approvals_status` ON `pending_approvals` (`status`)")

                // 4. Table: diagnostic_events
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `diagnostic_events_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `tag` TEXT NOT NULL, `action_name` TEXT NOT NULL, `execution_time_ms` INTEGER NOT NULL, `is_success` INTEGER NOT NULL, `error_details` TEXT, `context_metadata` TEXT)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `diagnostic_events_new` (`id`, `timestamp`, `tag`, `action_name`, `execution_time_ms`, `is_success`, `error_details`, `context_metadata`) SELECT `id`, `timestamp`, `tag`, `action_name`, `execution_time_ms`, `is_success`, `error_details`, `context_metadata` FROM `diagnostic_events`"
                )
                db.execSQL("DROP TABLE IF EXISTS `diagnostic_events`")
                db.execSQL("ALTER TABLE `diagnostic_events_new` RENAME TO `diagnostic_events`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_diag_timestamp` ON `diagnostic_events` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_diag_tag` ON `diagnostic_events` (`tag`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_diag_tag_timestamp` ON `diagnostic_events` (`tag` ASC, `timestamp` DESC)")

                // 5. Table: in_app_notifications (Fresh creation for v3 users)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `in_app_notifications` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dedup_key` TEXT,
                        `category` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `is_read` INTEGER NOT NULL DEFAULT 0,
                        `action_type` TEXT,
                        `action_payload` TEXT,
                        `importance` INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_notif_timestamp` ON `in_app_notifications` (`timestamp` DESC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_notif_is_read` ON `in_app_notifications` (`is_read`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_notif_category` ON `in_app_notifications` (`category`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_notif_dedup` ON `in_app_notifications` (`dedup_key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_notif_category_timestamp` ON `in_app_notifications` (`category` ASC, `timestamp` DESC)")
            }
        }

        /**
         * Migration from Room version 5 to Room version 6.
         * Creates table [waqfa_sessions] for the Waqfa Mindful Intervention System.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `waqfa_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `session_uuid` TEXT NOT NULL,
                        `package_name` TEXT NOT NULL,
                        `app_name` TEXT NOT NULL,
                        `intent_type` TEXT NOT NULL,
                        `reason_or_feeling` TEXT,
                        `duration_minutes` INTEGER NOT NULL,
                        `started_at_trusted_ms` INTEGER NOT NULL,
                        `planned_end_at_trusted_ms` INTEGER NOT NULL,
                        `actual_end_at_trusted_ms` INTEGER,
                        `end_action_applied` TEXT,
                        `is_active` INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_waqfa_sessions_pkg` ON `waqfa_sessions` (`package_name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_waqfa_sessions_start` ON `waqfa_sessions` (`started_at_trusted_ms` DESC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_waqfa_sessions_active` ON `waqfa_sessions` (`package_name`, `is_active`)")
            }
        }

        fun getInstance(context: Context): HisnRoomDatabase {
            return instance ?: synchronized(this) {
                instance ?: try {
                    org.koin.core.context.GlobalContext.getOrNull()?.get<HisnRoomDatabase>()
                } catch (t: Throwable) {
                    null
                } ?: build(context.applicationContext)
            }
        }

        fun build(context: Context): HisnRoomDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                HisnRoomDatabase::class.java,
                DATABASE_NAME
            )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_3_5, MIGRATION_5_6)
            .fallbackToDestructiveMigrationFrom(true, 1, 2)
            .addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // Seed initial default policy if creating from scratch
                    val defaultBundle = DevicePolicyBundle.defaultPolicy()
                    val values = ContentValues().apply {
                        put("id", 1)
                        put("policy_json", defaultBundle.toJson())
                        put("version", defaultBundle.version)
                        put("updated_at", defaultBundle.updatedAt)
                    }
                    db.insert("device_policies", SQLiteDatabase.CONFLICT_IGNORE, values)
                }
            })
            .build()
            .also {
                instance = it
            }
        }

        @androidx.annotation.VisibleForTesting
        fun setInstance(db: HisnRoomDatabase?) {
            instance = db
        }
    }
}
