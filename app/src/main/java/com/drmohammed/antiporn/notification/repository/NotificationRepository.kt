package com.drmohammed.antiporn.notification.repository

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.dao.InAppNotificationDao
import com.drmohammed.antiporn.data.local.db.entity.InAppNotificationEntity
import com.drmohammed.antiporn.notification.model.InAppNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Central repository managing in-app notifications and real-time unread count updates.
 * Powered by Jetpack Room [InAppNotificationDao] with reactive [StateFlow] synchronization.
 */
class NotificationRepository(
    private val notificationDao: InAppNotificationDao,
    private val repositoryScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val _notificationsFlow = MutableStateFlow<List<InAppNotification>>(emptyList())
    val notificationsFlow: StateFlow<List<InAppNotification>> = _notificationsFlow.asStateFlow()

    private val _unreadCountFlow = MutableStateFlow(0)
    val unreadCountFlow: StateFlow<Int> = _unreadCountFlow.asStateFlow()

    private val refreshMutex = Mutex()

    init {
        repositoryScope.launch {
            try {
                notificationDao.observeAllNotifications(limit = 200).collectLatest { entities ->
                    _notificationsFlow.value = entities.map { it.toDomain() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing all notifications", e)
            }
        }
        repositoryScope.launch {
            try {
                notificationDao.observeUnreadCount().collectLatest { count ->
                    _unreadCountFlow.value = count
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing unread count", e)
            }
        }
        refresh()
    }

    private suspend fun refreshInternal() {
        refreshMutex.withLock {
            try {
                val list = notificationDao.getAllNotifications(limit = 200).map { it.toDomain() }
                val unread = notificationDao.getUnreadCount()
                _notificationsFlow.value = list
                _unreadCountFlow.value = unread
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing notifications flow", e)
            }
        }
    }

    /**
     * Refreshes the local in-memory StateFlows from database.
     */
    fun refresh() {
        repositoryScope.launch {
            refreshInternal()
        }
    }

    /**
     * Saves or updates an in-app notification with optional cooldown deduplication.
     * Returns the inserted/updated ID, or -1 if throttled by cooldown.
     */
    suspend fun saveNotification(notification: InAppNotification, cooldownMs: Long = 0L): Long =
        withContext(Dispatchers.IO) {
            try {
                val sanitizedDedupKey = notification.dedupKey?.trim()?.ifEmpty { null }
                if (sanitizedDedupKey != null) {
                    val existing = notificationDao.getByDedupKey(sanitizedDedupKey)
                    if (existing != null) {
                        val elapsedSinceLast = notification.timestamp - existing.timestamp
                        if (cooldownMs > 0 && (elapsedSinceLast in 0 until cooldownMs || elapsedSinceLast < 0)) {
                            Log.d(
                                TAG,
                                "Notification for $sanitizedDedupKey ignored due to active cooldown (${elapsedSinceLast}ms < ${cooldownMs}ms)"
                            )
                            return@withContext -1L
                        }
                        val updatedEntity = InAppNotificationEntity.fromDomain(
                            notification.copy(id = existing.id, isRead = false)
                        )
                        val id = notificationDao.insertOrReplace(updatedEntity)
                        refreshInternal()
                        return@withContext if (id > 0L) existing.id else id
                    }
                }
                val entity = InAppNotificationEntity.fromDomain(notification.copy(id = 0L, isRead = false))
                val id = notificationDao.insertOrReplace(entity)
                if (id > 0L) {
                    refreshInternal()
                }
                id
            } catch (e: Exception) {
                Log.e(TAG, "Error saving in-app notification", e)
                -1L
            }
        }

    /**
     * Synchronously checks whether an in-app notification with the given dedupKey already exists.
     */
    fun hasNotificationWithDedupKey(dedupKey: String): Boolean {
        return try {
            notificationDao.getByDedupKeyBlocking(dedupKey) != null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking dedup key synchronously", e)
            false
        }
    }

    /**
     * Retrieves an in-app notification by its dedupKey.
     */
    fun getNotificationByDedupKey(dedupKey: String): InAppNotification? {
        return try {
            notificationDao.getByDedupKeyBlocking(dedupKey)?.toDomain()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching notification by dedup key", e)
            null
        }
    }

    /**
     * Marks a single notification as read and updates the unread badge flow.
     */
    suspend fun markAsRead(id: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val rows = notificationDao.markAsRead(id)
            if (rows > 0) {
                refreshInternal()
            }
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error marking notification $id as read", e)
            false
        }
    }

    /**
     * Marks all in-app notifications as read and resets unread count to 0.
     */
    suspend fun markAllAsRead(): Int = withContext(Dispatchers.IO) {
        try {
            val count = notificationDao.markAllAsRead()
            if (count > 0) {
                refreshInternal()
            }
            count
        } catch (e: Exception) {
            Log.e(TAG, "Error marking all notifications as read", e)
            0
        }
    }

    /**
     * Deletes a single notification entry by its ID.
     */
    suspend fun deleteNotification(id: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val rows = notificationDao.deleteById(id)
            if (rows > 0) {
                refreshInternal()
            }
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting notification $id", e)
            false
        }
    }

    /**
     * Clears all in-app notification history.
     */
    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        try {
            val count = notificationDao.clearAll()
            refreshInternal()
            count
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all notifications", e)
            0
        }
    }

    /**
     * Prunes excess notifications older than specified days or exceeding max cap.
     */
    suspend fun pruneOldNotifications(maxKeep: Int = 200, olderThanDays: Int = 30): Int =
        withContext(Dispatchers.IO) {
            try {
                val cutoffMs = if (olderThanDays > 0) {
                    TrustedTimeProvider.currentTimeMillis() - (olderThanDays.toLong() * 24 * 60 * 60 * 1000L)
                } else {
                    0L
                }
                val pruned = notificationDao.pruneOldNotifications(maxKeep = maxKeep, olderThanMs = cutoffMs)
                if (pruned > 0) {
                    refreshInternal()
                }
                pruned
            } catch (e: Exception) {
                Log.e(TAG, "Error pruning old notifications", e)
                0
            }
        }

    companion object {
        private const val TAG = "AntiPorn_NotifRepo"

        @Volatile
        private var instance: NotificationRepository? = null

        fun getInstance(context: Context): NotificationRepository {
            return instance ?: synchronized(this) {
                instance ?: NotificationRepository(
                    HisnRoomDatabase.getInstance(context.applicationContext).inAppNotificationDao()
                ).also { instance = it }
            }
        }
    }
}
