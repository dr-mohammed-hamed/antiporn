package com.drmohammed.antiporn

import android.app.Application
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.di.appModule
import com.drmohammed.antiporn.telemetry.GlobalCrashHandler
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import timber.log.Timber

/**
 * Custom Application class for AntiPorn.
 * Applies Material 3 dynamic colors to compatible secondary UI elements while
 * core security/recovery shields maintain dedicated custom semantic brand tokens.
 */
class AntiPornApplication : Application() {
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

        // Prune diagnostic events older than 30 days and enforce 5000 event cap in background (non-blocking)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val db = AntiPornDatabase.getInstance(this@AntiPornApplication)
                val pruned = db.pruneDiagnosticEventsOlderThan(30)
                val capped = db.enforceMaxEventsCap(5000)
                if (pruned > 0 || capped > 0) {
                    Timber.i("Cleaned up diagnostic events: pruned=$pruned (>30 days), capped=$capped (>5000 events)")
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to prune diagnostic events")
            }
        }
    }
}
