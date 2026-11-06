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

        // Initialize central Notification Channels
        com.drmohammed.antiporn.notification.manager.AppNotificationManager.getInstance(this).createNotificationChannels()

        // Synchronize Zero-Trust active foreground watcher state on startup
        ZeroTrustForegroundWatcher.syncWatcherState(this)

        // Self-healing watchdog: check for stuck user_setup_complete state on cold boot
        try {
            val setupComplete = android.provider.Settings.Secure.getInt(contentResolver, "user_setup_complete", 1)
            val provisioned = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.DEVICE_PROVISIONED, 1)
            if (setupComplete == 0 && provisioned == 1) {
                Timber.w("AntiPorn Boot Watchdog: Detected stuck user_setup_complete=0 on provisioned device.")
                try {
                    android.provider.Settings.Secure.putInt(contentResolver, "user_setup_complete", 1)
                } catch (t: Throwable) {
                    Timber.w(t, "Setup state watchdog: Failed to restore user_setup_complete")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Setup state watchdog check failed")
        }

        // Schedule periodic background workers with network constraints and ExistingPeriodicWorkPolicy.KEEP
        OtaUpdateWorker.schedule(this)
        CloudSyncWorker.schedule(this)

        // Prune diagnostic events and in-app notifications in background (non-blocking)
        applicationScope.launch {
            try {
                val db = HisnRoomDatabase.getInstance(this@AntiPornApplication)
                val cutoff = TrustedTimeProvider.currentTimeMillis(this@AntiPornApplication) - (30L * 24 * 60 * 60 * 1000)
                val pruned = db.diagnosticEventDao().pruneEventsOlderThan(cutoff)
                val capped = db.diagnosticEventDao().enforceMaxEventsCap(5000)
                val prunedNotifs = try {
                    db.inAppNotificationDao().pruneOldNotifications(maxKeep = 200, olderThanMs = cutoff)
                } catch (e: Exception) {
                    0
                }
                if (pruned > 0 || capped > 0 || prunedNotifs > 0) {
                    Timber.i("Cleaned up database: diagPruned=$pruned, diagCapped=$capped, notifsPruned=$prunedNotifs")
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to prune diagnostic events / notifications")
            }
        }
    }
}
