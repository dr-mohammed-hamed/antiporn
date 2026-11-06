package com.drmohammed.antiporn.di

import com.drmohammed.antiporn.cloud.auth.GoogleAuthManager
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.AppInventoryManager
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ota.repository.OtaRepository
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.repository.NotificationRepository
import com.drmohammed.antiporn.ui.accountability.AccountabilityViewModel
import com.drmohammed.antiporn.ui.control.SelfControlViewModel
import com.drmohammed.antiporn.ui.home.HomeViewModel
import com.drmohammed.antiporn.ui.main.MainViewModel
import com.drmohammed.antiporn.ui.notification.NotificationCenterViewModel
import com.drmohammed.antiporn.ui.settings.SettingsViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Main Koin Dependency Injection module for the AntiPorn application.
 * Defines singletons for databases, repositories, enterprise managers,
 * and lifecycle-aware ViewModel bindings.
 */
val appModule = module {
    // Core Singletons
    single { AntiPornDatabase.getInstance(androidContext()) }
    single { PolicyRepository.getInstance(androidContext()) }
    single { AccountabilityRepository.getInstance(androidContext()) }
    single { ApprovalRepository.getInstance(androidContext()) }
    single { AppInventoryManager.getInstance(androidContext()) }
    single { GoogleAuthManager.getInstance(androidContext()) }
    single { OtaRepository.getInstance(androidContext()) }
    single { NotificationRepository.getInstance(androidContext()) }
    single { AppNotificationManager.getInstance(androidContext()) }

    // ViewModels
    viewModel { MainViewModel() }
    viewModel { HomeViewModel(androidApplication(), get()) }
    viewModel { SelfControlViewModel(androidApplication(), get(), get()) }
    viewModel { AccountabilityViewModel(androidApplication(), get(), get(), get(), get()) }
    viewModel { SettingsViewModel(androidApplication(), get(), get()) }
    viewModel { NotificationCenterViewModel(get()) }
}
