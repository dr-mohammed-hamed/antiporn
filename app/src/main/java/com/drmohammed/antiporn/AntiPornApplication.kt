package com.drmohammed.antiporn

import android.app.Application
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
    }
}
