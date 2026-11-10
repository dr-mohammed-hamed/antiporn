package com.drmohammed.antiporn.di

import androidx.work.WorkerParameters
import com.drmohammed.antiporn.cloud.auth.GoogleAuthManager
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.DiagnosticEventDao
import com.drmohammed.antiporn.data.local.db.dao.InAppNotificationDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.dao.PendingApprovalDao
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.AppInventoryManager
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ota.repository.OtaRepository
import com.drmohammed.antiporn.ui.accountability.AccountabilityViewModel
import com.drmohammed.antiporn.ui.accountability.GuardianControlViewModel
import com.drmohammed.antiporn.ui.accountability.RemoteAppManagerViewModel
import com.drmohammed.antiporn.ui.control.SelfControlViewModel
import com.drmohammed.antiporn.ui.home.HomeViewModel
import com.drmohammed.antiporn.ui.main.MainViewModel
import com.drmohammed.antiporn.ui.settings.SettingsViewModel
import com.drmohammed.antiporn.work.CloudSyncWorker
import com.drmohammed.antiporn.work.OtaUpdateWorker
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.repository.NotificationRepository
import com.drmohammed.antiporn.ui.notification.NotificationCenterViewModel
import com.drmohammed.antiporn.waqfa.data.WaqfaDao
import com.drmohammed.antiporn.waqfa.data.WaqfaRepository
import com.drmohammed.antiporn.waqfa.engine.WaqfaSessionManager
import com.drmohammed.antiporn.waqfa.ui.gate.WaqfaGateViewModel
import com.drmohammed.antiporn.waqfa.ui.settings.WaqfaSettingsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.test.KoinTest
import org.koin.test.verify.verify

/**
 * Unit tests verifying Koin Dependency Injection module declarations,
 * definition mappings, and ViewModel resolution.
 */
class AppModuleTest : KoinTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun testAppModuleGraphVerification() {
        appModule.verify(
            extraTypes = listOf(
                android.content.Context::class,
                android.app.Application::class,
                WorkerParameters::class,
                kotlinx.coroutines.CoroutineScope::class
            )
        )
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun testAllRequiredDefinitionsArePresentInAppModule() {
        val registeredTypes = appModule.mappings.values.map { it.beanDefinition.primaryType }.toSet()

        val expectedTypes = listOf(
            HisnRoomDatabase::class,
            DevicePolicyDao::class,
            PartnerDao::class,
            PendingApprovalDao::class,
            DiagnosticEventDao::class,
            InAppNotificationDao::class,
            WaqfaDao::class,
            WaqfaRepository::class,
            WaqfaSessionManager::class,
            PolicyRepository::class,
            AccountabilityRepository::class,
            ApprovalRepository::class,
            AppInventoryManager::class,
            GoogleAuthManager::class,
            OtaRepository::class,
            MainViewModel::class,
            HomeViewModel::class,
            SelfControlViewModel::class,
            AccountabilityViewModel::class,
            RemoteAppManagerViewModel::class,
            GuardianControlViewModel::class,
            SettingsViewModel::class,
            NotificationRepository::class,
            AppNotificationManager::class,
            NotificationCenterViewModel::class,
            WaqfaGateViewModel::class,
            WaqfaSettingsViewModel::class,
            com.drmohammed.antiporn.accessibility.bus.ForegroundAppEventBus::class,
            com.drmohammed.antiporn.accessibility.filter.TransientWindowEventFilter::class,
            OtaUpdateWorker::class,
            CloudSyncWorker::class
        )

        for (expected in expectedTypes) {
            assertTrue(
                "Missing Koin definition for type ${expected.qualifiedName} in appModule",
                registeredTypes.contains(expected)
            )
        }
        assertEquals(31, registeredTypes.size)
    }

    @Test
    fun testMainViewModelResolutionFromKoin() {
        val koinApp = koinApplication {
            modules(appModule)
        }
        val mainViewModel = koinApp.koin.get<MainViewModel>()
        assertNotNull(mainViewModel)
        assertEquals(0, mainViewModel.selectedTabPosition.value)
    }

    @Test
    fun testSingletonSetInstanceAndGetInstanceAntiRecursion() {
        // Verify setInstance directly bypasses Koin and prevents any recursion
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }

        // Verify AppInventoryManager
        val manager = AppInventoryManager.create(dummyContext)
        AppInventoryManager.setInstance(manager)
        assertEquals(manager, AppInventoryManager.getInstance(dummyContext))
        AppInventoryManager.setInstance(null)

        // Verify that setting instance on companion is reflected cleanly
        HisnRoomDatabase.setInstance(null)
    }
}
