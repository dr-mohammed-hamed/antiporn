package com.drmohammed.antiporn.notification.repository

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
import com.drmohammed.antiporn.notification.model.InAppNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Central repository managing in-app notifications and real-time unread count updates.
 */
class NotificationRepository(
    private val database: AntiPornDatabase,
    private val repositoryScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val _notificationsFlow = MutableStateFlow<List<InAppNotification>>(emptyList())
    val notificationsFlow: StateFlow<List<InAppNotification>> = _notificationsFlow.asStateFlow()

    private val _unreadCountFlow = MutableStateFlow(0)
    val unreadCountFlow: StateFlow<Int> = _unreadCountFlow.asStateFlow()

    private val refreshMutex = Mutex()

    init {
        refresh()
    }

    private suspend fun refreshInternal() {
        refreshMutex.withLock {
            try {
                val list = database.getAllNotifications(limit = 200)
                val unread = database.getUnreadNotificationsCount()
                _notificationsFlow.value = list
                _unreadCountFlow.value = unread
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing notifications flow", e)
            }
        }
    }

    /**
     * Refreshes the local in-memory StateFlows from SQLite.
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
            val id = database.insertOrUpdateNotification(notification, cooldownMs)
            if (id > 0L) {
                refreshInternal()
            }
            id
        }

    /**
     * Synchronously checks whether an in-app notification with the given dedupKey already exists.
     */
    fun hasNotificationWithDedupKey(dedupKey: String): Boolean {
        return database.getNotificationByDedupKey(dedupKey) != null
    }

    /**
     * Retrieves an in-app notification by its dedupKey.
     */
    fun getNotificationByDedupKey(dedupKey: String): InAppNotification? {
        return database.getNotificationByDedupKey(dedupKey)
    }

    /**
     * Marks a single notification as read and updates the unread badge flow.
     */
    suspend fun markAsRead(id: Long) = withContext(Dispatchers.IO) {
        val success = database.markNotificationAsRead(id)
        if (success) {
            refreshInternal()
        }
        success
    }

    /**
     * Marks all in-app notifications as read and resets unread count to 0.
     */
    suspend fun markAllAsRead() = withContext(Dispatchers.IO) {
        val count = database.markAllNotificationsAsRead()
        if (count > 0) {
            refreshInternal()
        }
        count
    }

    /**
     * Deletes a single notification entry by its ID.
     */
    suspend fun deleteNotification(id: Long) = withContext(Dispatchers.IO) {
        val success = database.deleteNotification(id)
        if (success) {
            refreshInternal()
        }
        success
    }

    /**
     * Clears all in-app notification history.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        val count = database.clearAllNotifications()
        refreshInternal()
        count
    }

    /**
     * Prunes excess notifications older than specified days or exceeding max cap.
     */
    suspend fun pruneOldNotifications(maxKeep: Int = 200, olderThanDays: Int = 30): Int =
        withContext(Dispatchers.IO) {
            val pruned = database.pruneOldNotifications(maxKeep, olderThanDays)
            if (pruned > 0) {
                refreshInternal()
            }
            pruned
        }

    companion object {
        private const val TAG = "AntiPorn_NotifRepo"

        @Volatile
        private var instance: NotificationRepository? = null

        fun getInstance(context: Context): NotificationRepository {
            return instance ?: synchronized(this) {
                instance ?: NotificationRepository(
                    AntiPornDatabase.getInstance(context.applicationContext)
                ).also { instance = it }
            }
        }
    }
}
