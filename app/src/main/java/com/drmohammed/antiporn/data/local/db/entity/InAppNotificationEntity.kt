package com.drmohammed.antiporn.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.drmohammed.antiporn.notification.model.InAppNotification
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory

/**
 * Entity representing a persistent In-App Notification entry in Room database.
 * Replaces legacy raw SQLite table with 100% schema parity.
 */
@Entity(
    tableName = "in_app_notifications",
    indices = [
        Index(value = ["timestamp"], orders = [Index.Order.DESC], name = "idx_notif_timestamp"),
        Index(value = ["is_read"], name = "idx_notif_is_read"),
        Index(value = ["category"], name = "idx_notif_category"),
        Index(value = ["dedup_key"], unique = true, name = "idx_notif_dedup"),
        Index(
            value = ["category", "timestamp"],
            orders = [Index.Order.ASC, Index.Order.DESC],
            name = "idx_notif_category_timestamp"
        )
    ]
)
data class InAppNotificationEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "dedup_key")
    val dedupKey: String? = null,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "message")
    val message: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "is_read", defaultValue = "0")
    val isRead: Boolean = false,

    @ColumnInfo(name = "action_type")
    val actionType: String? = null,

    @ColumnInfo(name = "action_payload")
    val actionPayload: String? = null,

    @ColumnInfo(name = "importance", defaultValue = "1")
    val importance: Int = 1
) {
    fun toDomain(): InAppNotification = InAppNotification(
        id = id,
        dedupKey = dedupKey,
        category = NotificationCategory.fromKey(category),
        title = title,
        message = message,
        timestamp = timestamp,
        isRead = isRead,
        actionType = NotificationActionType.fromKey(actionType),
        actionPayload = actionPayload,
        importance = importance
    )

    companion object {
        fun fromDomain(domain: InAppNotification): InAppNotificationEntity = InAppNotificationEntity(
            id = domain.id,
            dedupKey = domain.dedupKey,
            category = domain.category.key,
            title = domain.title,
            message = domain.message,
            timestamp = domain.timestamp,
            isRead = domain.isRead,
            actionType = domain.actionType.key,
            actionPayload = domain.actionPayload,
            importance = domain.importance
        )
    }
}
