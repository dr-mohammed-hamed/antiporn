package com.drmohammed.antiporn.ui.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.notification.model.InAppNotification
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.repository.NotificationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel managing In-App Notification Center UI state, category filtering,
 * and bulk operations (mark all read, clear all).
 */
class NotificationCenterViewModel(
    private val repository: NotificationRepository
) : ViewModel() {

    private val _selectedCategory = MutableStateFlow<NotificationCategory?>(null)
    val selectedCategory: StateFlow<NotificationCategory?> = _selectedCategory.asStateFlow()

    val unreadCount: StateFlow<Int> = repository.unreadCountFlow

    val notifications: StateFlow<List<InAppNotification>> = combine(
        repository.notificationsFlow,
        _selectedCategory
    ) { list, category ->
        if (category != null) {
            list.filter { it.category == category }
        } else {
            list
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setCategoryFilter(category: NotificationCategory?) {
        _selectedCategory.value = category
    }

    fun markAsRead(id: Long) {
        viewModelScope.launch {
            repository.markAsRead(id)
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            repository.markAllAsRead()
        }
    }

    fun deleteNotification(id: Long) {
        viewModelScope.launch {
            repository.deleteNotification(id)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    fun clearAllNotifications() {
        clearAll()
    }
}
