package com.drmohammed.antiporn

import android.app.Application
import androidx.work.Configuration
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.di.appModule
import com.drmohammed.antiporn.telemetry.GlobalCrashHandler
import com.drmohammed.antiporn.work.CloudSyncWorker
import com.drmohammed.antiporn.work.OtaUpdateWorker
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import timber.log.Timber

/**
 * Custom Application class for AntiPorn.
 * Configures Koin, WorkManager on-demand initialization via [Configuration.Provider],
 * trusted time synchronization, and lifecycle security shields.
 */
class AntiPornApplication : Application(), Configuration.Provider {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(KoinWorkerFactory())
            .build()

    override fun onCreate() {
        super.onCreate()

        // Plant Timber tree for structured logging in debug builds
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Initialize Koin Dependency Injection
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidLogger(Level.ERROR)
                androidContext(this@AntiPornApplication)
                workManagerFactory()
                modules(appModule)
            }
        }

        // 1. Install Global Crash Handler immediately before any subsystem initializes
        GlobalCrashHandler.install(this)

        if (DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        // Initialize tamper-proof Trusted Time subsystem on cold boot
        TrustedTimeProvider.initialize(this)
        TrustedTimeProvider.onTimeDriftCorrected = { driftMs ->
            Timber.i("Trusted time drift of ${driftMs}ms corrected; rescheduling alarms and verifying curfew")
            com.drmohammed.antiporn.curfew.CurfewScheduler.scheduleNextAlarms(this)
            com.drmohammed.antiporn.curfew.CurfewEnforcer.enforce(this, com.drmohammed.antiporn.curfew.CurfewEnforcer.isEffectiveCurfewActive(this))
        }
        TrustedTimeProvider.syncNowAsync(this)

        // Initialize PolicyRepository on cold boot so policy and approval sync listeners start reliably
        PolicyRepository.getInstance(this)

        // Synchronize Zero-Trust active foreground watcher state on startup
        ZeroTrustForegroundWatcher.syncWatcherState(this)

        // Schedule periodic background workers with network constraints and ExistingPeriodicWorkPolicy.KEEP
        OtaUpdateWorker.schedule(this)
        CloudSyncWorker.schedule(this)

        // Prune diagnostic events older than 30 days and enforce 5000 event cap in background (non-blocking)
        applicationScope.launch {
            try {
                val db = HisnRoomDatabase.getInstance(this@AntiPornApplication)
                val cutoff = TrustedTimeProvider.currentTimeMillis(this@AntiPornApplication) - (30L * 24 * 60 * 60 * 1000)
                val pruned = db.diagnosticEventDao().pruneEventsOlderThan(cutoff)
                val capped = db.diagnosticEventDao().enforceMaxEventsCap(5000)
                if (pruned > 0 || capped > 0) {
                    Timber.i("Cleaned up diagnostic events: pruned=$pruned (>30 days), capped=$capped (>5000 events)")
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to prune diagnostic events")
            }
        }
    }
}
