package com.drmohammed.antiporn.ui.main

import androidx.lifecycle.ViewModel
import com.drmohammed.antiporn.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared ViewModel for MainActivity and top-level navigation orchestration.
 * Coordinates tab selection indices, back-stack interactions, and shared system events.
 */
class MainViewModel : ViewModel() {

    sealed interface MainNavigationEvent {
        object OpenSelfControl : MainNavigationEvent
        object OpenAccountability : MainNavigationEvent
        object OpenSettings : MainNavigationEvent
        object OpenHome : MainNavigationEvent
    }

    private val _selectedTabPosition = MutableStateFlow(0)
    val selectedTabPosition: StateFlow<Int> = _selectedTabPosition.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<MainNavigationEvent>(extraBufferCapacity = 8)
    val navigationEvents: SharedFlow<MainNavigationEvent> = _navigationEvents.asSharedFlow()

    fun selectTab(position: Int) {
        if (position in 0..3) {
            _selectedTabPosition.value = position
        }
    }

    fun selectMenuId(menuId: Int) {
        _selectedTabPosition.value = menuIdToPosition(menuId)
    }

    fun navigateToSelfControl() {
        selectTab(1)
        _navigationEvents.tryEmit(MainNavigationEvent.OpenSelfControl)
    }

    fun navigateToHome() {
        selectTab(0)
        _navigationEvents.tryEmit(MainNavigationEvent.OpenHome)
    }

    fun navigateToAccountability() {
        selectTab(2)
        _navigationEvents.tryEmit(MainNavigationEvent.OpenAccountability)
    }

    fun navigateToSettings() {
        selectTab(3)
        _navigationEvents.tryEmit(MainNavigationEvent.OpenSettings)
    }

    companion object {
        fun positionToMenuId(position: Int): Int = when (position) {
            0 -> R.id.nav_home
            1 -> R.id.nav_self_control
            2 -> R.id.nav_accountability
            3 -> R.id.nav_settings
            else -> R.id.nav_home
        }

        fun menuIdToPosition(menuId: Int): Int = when (menuId) {
            R.id.nav_home -> 0
            R.id.nav_self_control -> 1
            R.id.nav_accountability -> 2
            R.id.nav_settings -> 3
            else -> 0
        }
    }
}
