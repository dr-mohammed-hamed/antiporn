package com.drmohammed.antiporn.di

import androidx.work.WorkerParameters
import com.drmohammed.antiporn.cloud.auth.GoogleAuthManager
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.DiagnosticEventDao
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
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.androidx.workmanager.dsl.worker
import org.koin.dsl.module

/**
 * Main Koin Dependency Injection module for the AntiPorn application.
 * Defines singletons for Room database, DAOs, repositories, enterprise managers,
 * lifecycle-aware ViewModel bindings, and WorkManager background workers.
 */
val appModule = module {
    // Room Database and DAOs Singletons
    single { HisnRoomDatabase.getInstance(androidContext()) }
    single { get<HisnRoomDatabase>().devicePolicyDao() }
    single { get<HisnRoomDatabase>().partnerDao() }
    single { get<HisnRoomDatabase>().pendingApprovalDao() }
    single { get<HisnRoomDatabase>().diagnosticEventDao() }

    // Repositories and Enterprise Managers
    single { PolicyRepository(androidContext(), get(), get()) }
    single { AccountabilityRepository(androidContext(), get(), get()) }
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

    // Background Workers
    worker { (workerParams: WorkerParameters) ->
        OtaUpdateWorker(androidContext(), workerParams, get())
    }
    worker { (workerParams: WorkerParameters) ->
        CloudSyncWorker(androidContext(), workerParams, get(), get())
    }
}
