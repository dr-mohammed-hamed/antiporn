package com.drmohammed.antiporn.curfew

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Event-Driven Zero-Trust Security Enforcer.
 * 
 * Operates purely on system events without background polling loops:
 * - Cold-boot synchronization (CurfewBootReceiver / AntiPornApplication)
 * - Service initialization (DnsWatchdogService)
 * - Real-time package installation/removal (PackageChangeReceiver)
 * 
 * Enforces immediate, tamper-proof OS-level package suspension via Android Enterprise (DevicePolicyManager)
 * consuming 0 background CPU cycles and preserving battery life.
 */
object ZeroTrustForegroundWatcher {

    private const val TAG = "AntiPorn_ZeroTrustWatcher"
    private const val CACHE_REFRESH_INTERVAL_MS = 3000L

    val isRunning: Boolean get() = false

    @Volatile
    private var lastCacheRefreshTime = 0L

    @Volatile
    private var pendingBlockedPackagesCache: Set<String> = emptySet()

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
     * Returns true if there are currently any unapproved applications waiting for guardian approval.
     */
    fun hasPendingApprovals(context: Context): Boolean {
        return getPendingBlockedPackages(context).isNotEmpty()
    }

    /**
     * Retrieves the set of currently unapproved pending packages, cached for 3 seconds to avoid SQLite churn.
     * Strictest system whitelist is enforced: launchers, dialer, keyboards, and immune apps are NEVER blocked.
     */
    fun getPendingBlockedPackages(context: Context, forceRefresh: Boolean = false): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (forceRefresh || (now - lastCacheRefreshTime > CACHE_REFRESH_INTERVAL_MS) || lastCacheRefreshTime == 0L) {
            val appContext = context.applicationContext
            val pending = try {
                ApprovalRepository.getInstance(appContext)
                    .getLocalPendingApprovals()
                    .filter { it.isPending }
                    .map { it.packageName }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching pending approvals from SQLite", e)
                emptyList()
            }

            if (pending.isEmpty()) {
                pendingBlockedPackagesCache = emptySet()
                lastCacheRefreshTime = now
                return emptySet()
            }

            // Exclude self and critical system framework / launcher / dialer / keyboard, and filter uninstalled
            val criticalPackages = CurfewAppResolver.resolveCoreSystemCriticalPackages(appContext)
            val curfewWhitelisted = try {
                com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(appContext)
                    .getCurrentPolicy().customCurfewWhitelistPackages
            } catch (e: Exception) {
                emptySet()
            }
            val pm = appContext.packageManager
            val updated = pending.filter { pkg ->
                pkg.isNotBlank() &&
                    !criticalPackages.contains(pkg) &&
                    !AppPolicyRegistry.isImmune(pkg) &&
                    !curfewWhitelisted.contains(pkg) &&
                    isPackageInstalled(pm, pkg)
            }.toSet()

            pendingBlockedPackagesCache = updated
            lastCacheRefreshTime = now
        }
        return pendingBlockedPackagesCache
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking package info for $packageName", e)
            false
        }
    }

    /**
     * Event-driven synchronization of Zero-Trust unapproved packages with DevicePolicyManager.
     * Enforces native OS-level package suspension on any unapproved package immediately without polling.
     */
    @Synchronized
    fun syncWatcherState(context: Context) {
        val appContext = context.applicationContext
        val pending = getPendingBlockedPackages(appContext, forceRefresh = true)
        Log.i(TAG, "syncWatcherState: ${pending.size} pending unapproved packages awaiting supervisor ($pending)")

        if (pending.isNotEmpty()) {
            if (com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(appContext)) {
                val unapprovedToSuspend = pending.filter { pkg ->
                    com.drmohammed.antiporn.telemetry.SafeDpmGateway.isPackageSuspendedBlocking(appContext, pkg).getOrNull() != true
                }
                if (unapprovedToSuspend.isNotEmpty()) {
                    try {
                        com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackagesSuspendedBlocking(
                            appContext,
                            unapprovedToSuspend.toTypedArray(),
                            true
                        )
                        Log.i(TAG, "Event-driven Zero-Trust reconciliation: Enforced DPM suspension on ${unapprovedToSuspend.size} packages: $unapprovedToSuspend")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to enforce DPM suspension during syncWatcherState", e)
                    }
                }
            }
        }
    }

    /**
     * Asynchronously synchronizes Zero-Trust unapproved packages off the main thread.
     */
    fun syncWatcherStateAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            try {
                syncWatcherState(appContext)
                success = true
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncWatcherStateAsync", e)
            } finally {
                onComplete?.invoke(success)
            }
        }
    }

    /**
     * Compatibility entry point: triggers synchronous state reconciliation.
     */
    @Synchronized
    fun start(context: Context) {
        syncWatcherState(context)
    }

    /**
     * Compatibility entry point: no-op since no background polling loops exist.
     */
    @Synchronized
    fun stop() {
        // No background thread to stop; watcher is completely dormant by default
    }

    /**
     * Evaluates immediate target or synchronizes pending packages using native DPM suspension.
     */
    fun evaluateCurrentForegroundApp(context: Context, immediateTargetPackage: String? = null) {
        val appContext = context.applicationContext
        if (!immediateTargetPackage.isNullOrBlank()) {
            if (!CurfewAppResolver.isSystemCriticalOrImmune(appContext, immediateTargetPackage)) {
                try {
                    com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackagesSuspendedBlocking(
                        appContext,
                        arrayOf(immediateTargetPackage),
                        true
                    )
                    Log.i(TAG, "Evaluated immediate target package: enforced suspension on $immediateTargetPackage")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed enforcing suspension on immediate target package $immediateTargetPackage", e)
                }
            }
        } else {
            syncWatcherState(appContext)
        }
    }
}
