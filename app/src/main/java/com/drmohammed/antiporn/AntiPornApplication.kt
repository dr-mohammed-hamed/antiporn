package com.drmohammed.antiporn

import android.app.Application
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.google.android.material.color.DynamicColors

/**
 * Custom Application class for AntiPorn.
 * Applies Material 3 dynamic colors to compatible secondary UI elements while
 * core security/recovery shields maintain dedicated custom semantic brand tokens.
 */
class AntiPornApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        // Initialize PolicyRepository on cold boot so policy and approval sync listeners start reliably
        PolicyRepository.getInstance(this)

        // Synchronize Zero-Trust active foreground watcher state on startup
        ZeroTrustForegroundWatcher.syncWatcherState(this)
    }
}
