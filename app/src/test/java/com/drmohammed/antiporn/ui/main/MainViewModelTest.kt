package com.drmohammed.antiporn.ui.main

import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.ui.accountability.AccountabilityUiState
import com.drmohammed.antiporn.ui.control.SelfControlUiState
import com.drmohammed.antiporn.ui.home.HomeUiState
import com.drmohammed.antiporn.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelTest {

    @Test
    fun testPositionToMenuIdMapping() {
        assertEquals(R.id.nav_home, MainViewModel.positionToMenuId(0))
        assertEquals(R.id.nav_self_control, MainViewModel.positionToMenuId(1))
        assertEquals(R.id.nav_accountability, MainViewModel.positionToMenuId(2))
        assertEquals(R.id.nav_settings, MainViewModel.positionToMenuId(3))
        assertEquals(R.id.nav_home, MainViewModel.positionToMenuId(99))
    }

    @Test
    fun testMenuIdToPositionMapping() {
        assertEquals(0, MainViewModel.menuIdToPosition(R.id.nav_home))
        assertEquals(1, MainViewModel.menuIdToPosition(R.id.nav_self_control))
        assertEquals(2, MainViewModel.menuIdToPosition(R.id.nav_accountability))
        assertEquals(3, MainViewModel.menuIdToPosition(R.id.nav_settings))
        assertEquals(0, MainViewModel.menuIdToPosition(-1))
    }

    @Test
    fun testSelectTabUpdatesState() {
        val vm = MainViewModel()
        assertEquals(0, vm.selectedTabPosition.value)

        vm.selectTab(2)
        assertEquals(2, vm.selectedTabPosition.value)

        vm.selectMenuId(R.id.nav_settings)
        assertEquals(3, vm.selectedTabPosition.value)
    }

    @Test
    fun testNavigationShortcuts() {
        val vm = MainViewModel()
        vm.navigateToSelfControl()
        assertEquals(1, vm.selectedTabPosition.value)

        vm.navigateToAccountability()
        assertEquals(2, vm.selectedTabPosition.value)

        vm.navigateToSettings()
        assertEquals(3, vm.selectedTabPosition.value)

        vm.navigateToHome()
        assertEquals(0, vm.selectedTabPosition.value)
    }

    @Test
    fun testDefaultUiStates() {
        val homeState = HomeUiState()
        assertFalse(homeState.isNapActive)
        assertFalse(homeState.isApplying)
        assertEquals(0L, homeState.napRemainingMs)

        val selfControlState = SelfControlUiState()
        assertNotNull(selfControlState.policy)
        assertFalse(selfControlState.isSaving)
        assertFalse(selfControlState.hasUnsavedChanges)

        val settingsState = SettingsUiState()
        assertFalse(settingsState.isAiRunning)
        assertFalse(settingsState.isDeviceOwner)
        assertEquals(0f, settingsState.aiFps, 0.001f)

        val accountabilityState = AccountabilityUiState()
        assertEquals("", accountabilityState.myPairingCode)
        assertFalse(accountabilityState.isSupervised)
        assertTrue(accountabilityState.wards.isEmpty())
        assertTrue(accountabilityState.guardians.isEmpty())
    }
}
