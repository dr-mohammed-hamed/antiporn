package com.drmohammed.antiporn.di

import com.drmohammed.antiporn.cloud.auth.GoogleAuthManager
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
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
    single { PolicyRepository(androidContext(), get()) }
    single { AccountabilityRepository(androidContext(), get()) }
    single { ApprovalRepository(androidContext(), get()) }
    single { AppInventoryManager.getInstance(androidContext()) }
    single { GoogleAuthManager.getInstance(androidContext()) }
    single { OtaRepository.getInstance(androidContext()) }

    // ViewModels
    viewModel { MainViewModel() }
    viewModel { HomeViewModel(androidApplication(), get()) }
    viewModel { SelfControlViewModel(androidApplication(), get(), get()) }
    viewModel { AccountabilityViewModel(androidApplication(), get(), get(), get(), get()) }
    viewModel { RemoteAppManagerViewModel(androidApplication(), get(), get(), get()) }
    viewModel { GuardianControlViewModel(androidApplication(), get(), get(), get()) }
    viewModel { SettingsViewModel(androidApplication(), get(), get()) }
}
