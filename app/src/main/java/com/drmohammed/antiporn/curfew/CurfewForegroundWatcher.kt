package com.drmohammed.antiporn.curfew

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Utility and policy evaluator for night curfew.
 * 
 * Background polling loop has been completely retired in favor of native Android Enterprise
 * DevicePolicyManager controls:
 * - [UserManager.DISALLOW_INSTALL_APPS] prevents all app store installations.
 * - [SafeDpmGateway.setPackagesSuspended] enforces instant, hardware-level suspension on Google Play Store
 *   and Google Search/Gemini UI during active curfew hours.
 * 
 * Preserves public utility methods for unit tests and dashboard permission evaluation with 0 CPU overhead.
 */
object CurfewForegroundWatcher {

    private const val TAG = "AntiPorn_CurfewWatcher"

    val isRunning: Boolean get() = false

    /**
     * Determines whether the given activity class belongs to the Gemini AI assistant
     * interface running within Google App (Robin / Assistant surfaces).
     */
    fun isGeminiActivity(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        val lower = className.lowercase()
        return lower.contains(".robin.") || lower.contains(".assistant.")
    }

    /**
     * Verifies if the special PACKAGE_USAGE_STATS AppOp permission is granted to this app.
     */
    @Suppress("DEPRECATION")
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check usage stats permission", e)
            false
        }
    }

    /**
     * Compatibility entry point: no-op since polling loop has been replaced with native DPM controls.
     */
    @Synchronized
    fun start(context: Context) {
        Log.d(TAG, "CurfewForegroundWatcher.start() called - active monitoring handled natively via DevicePolicyManager.")
    }

    /**
     * Compatibility entry point: no-op.
     */
    @Synchronized
    fun stop() {
        Log.d(TAG, "CurfewForegroundWatcher.stop() called - watcher is completely dormant.")
    }
}
