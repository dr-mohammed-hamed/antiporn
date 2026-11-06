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
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.dao.PendingApprovalDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.DiagnosticEventEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity

/**
 * Modern Jetpack Room Database for Project Hisn.
 * Replaces legacy SQLiteOpenHelper while strictly preserving existing user data.
 */
@Database(
    entities = [
        DevicePolicyEntity::class,
        PartnerEntity::class,
        PendingApprovalEntity::class,
        DiagnosticEventEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class HisnRoomDatabase : RoomDatabase() {

    abstract fun devicePolicyDao(): DevicePolicyDao
    abstract fun partnerDao(): PartnerDao
    abstract fun pendingApprovalDao(): PendingApprovalDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao

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

        fun getInstance(context: Context): HisnRoomDatabase {
            return try {
                org.koin.core.context.GlobalContext.getOrNull()?.get<HisnRoomDatabase>()
            } catch (e: Exception) {
                null
            } ?: (instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also {
                    instance = it
                }
            })
        }

        fun build(context: Context): HisnRoomDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                HisnRoomDatabase::class.java,
                DATABASE_NAME
            )
            .addMigrations(MIGRATION_3_4)
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
        }
    }
}
