package com.drmohammed.antiporn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.SystemClock
import android.util.Log
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.repository.AppInventoryManager
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.shields.AppSuspensionShield
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Centralized, debounced engine for processing package installation and removal events.
 * Invoked by both [PackageChangeReceiver] (manifest) and [DnsWatchdogService] (dynamic receiver)
 * to ensure robust Zero-Trust enforcement without duplicate execution.
 */
object PackageChangeHandler {

    private const val TAG = "AntiPorn_PkgReceiver"
    private const val DEBOUNCE_WINDOW_MS = 3000L

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val handledAddEvents = ConcurrentHashMap<String, Long>()
    private val handledRemoveEvents = ConcurrentHashMap<String, Long>()

    /**
     * Handles package installation / update with Zero-Trust enforcement,
     * thread-safe debouncing, and rich diagnostic logging.
     */
    fun handlePackageAdded(
        context: Context,
        packageName: String,
        isReplacing: Boolean,
        source: String,
        onComplete: (() -> Unit)? = null
    ) {
        val now = SystemClock.elapsedRealtime()
        if (handledAddEvents.size > 100) {
            handledAddEvents.entries.removeIf { now - it.value > 600_000L }
        }
        val lastHandled = handledAddEvents[packageName] ?: 0L
        if (now - lastHandled < DEBOUNCE_WINDOW_MS) {
            Log.d(TAG, "⏩ [DEBOUNCE] Skipping duplicate package added event for $packageName from $source (${now - lastHandled}ms ago)")
            onComplete?.invoke()
            return
        }
        handledAddEvents[packageName] = now

        Log.w(TAG, "📥📥📥 [PACKAGE_ADDED] New package detected: $packageName (source: $source, isReplacing: $isReplacing)")

        val appContext = context.applicationContext
        try {
            bgExecutor.execute {
                try {
                    if (AppSuspensionShield.isBlacklisted(packageName)) {
                        Log.w(TAG, "🚫 [BLACKLIST_DETECTED] Package $packageName is permanently blacklisted. Enforcing instant freeze & silent uninstall...")
                        AppSuspensionShield.handlePackageAdded(appContext, packageName)
                    } else {
                        val approvalRepo = ApprovalRepository.getInstance(appContext)
                        val isAlreadyApproved = approvalRepo.getLocalPendingApprovals().any { it.packageName == packageName && it.isApproved }
                        val policy = PolicyRepository.getInstance(appContext).getCurrentPolicy()

                        Log.w(TAG, "🔍 [ZERO_TRUST_CHECK] Package: $packageName | lockNewInstalls=${policy.lockNewInstallsUntilApproved} | isReplacing=$isReplacing | isAlreadyApproved=$isAlreadyApproved")

                        if (policy.lockNewInstallsUntilApproved && !isReplacing && !isAlreadyApproved) {
                            val isImmune = CurfewAppResolver.isSystemCriticalOrImmune(appContext, packageName)
                            if (isImmune) {
                                Log.w(TAG, "🛡️ [IMMUNE_SKIP] Package $packageName is critical system component, launcher, dialer, or keyboard. Skipping freeze.")
                            } else {
                                Log.w(TAG, "❄️❄️❄️ [ZERO_TRUST_FREEZE] Strict Zero-Trust active: Freezing new install $packageName in 0ms and submitting approval ticket")
                                val frozen = approvalRepo.freezeAndSubmitApprovalLocal(packageName)
                                Log.w(TAG, "❄️❄️❄️ [FREEZE_STATUS] Result of local freeze for $packageName: $frozen")
                            }
                        } else {
                            Log.w(TAG, "⚖️ [CURFEW_EVAL] Package $packageName not subject to Zero-Trust freeze (replacing=$isReplacing, approved=$isAlreadyApproved). Evaluating curfew status...")
                            CurfewEnforcer.handlePackageAdded(appContext, packageName)
                        }
                    }

                    // Active foreground monitoring sync for newly blocked or pending package
                    ZeroTrustForegroundWatcher.syncWatcherState(appContext)

                    // Dispatch cloud synchronization asynchronously
                    handlerScope.launch {
                        try {
                            ApprovalRepository.getInstance(appContext).syncPendingApprovalsToCloud()
                        } catch (e: Exception) {
                            Log.w(TAG, "Async approval cloud sync failed for $packageName: ${e.message}")
                        }
                        try {
                            AppInventoryManager.getInstance(appContext).syncInventoryToCloud(force = true)
                        } catch (e: Exception) {
                            Log.w(TAG, "Async inventory sync failed for $packageName: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [ERROR] Error handling added package $packageName in $source", e)
                } finally {
                    onComplete?.invoke()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "❌ [REJECTED] Executor rejected task for package $packageName", e)
            onComplete?.invoke()
        }
    }

    /**
     * Handles package uninstallation, cleaning up approval records and syncing watcher state.
     */
    fun handlePackageRemoved(
        context: Context,
        packageName: String,
        source: String,
        onComplete: (() -> Unit)? = null
    ) {
        val now = SystemClock.elapsedRealtime()
        if (handledRemoveEvents.size > 100) {
            handledRemoveEvents.entries.removeIf { now - it.value > 600_000L }
        }
        val lastHandled = handledRemoveEvents[packageName] ?: 0L
        if (now - lastHandled < DEBOUNCE_WINDOW_MS) {
            Log.d(TAG, "⏩ [DEBOUNCE] Skipping duplicate package removed event for $packageName from $source")
            onComplete?.invoke()
            return
        }
        handledRemoveEvents[packageName] = now

        Log.i(TAG, "📤 [PACKAGE_REMOVED] Package removed: $packageName (source: $source)")

        val appContext = context.applicationContext
        try {
            bgExecutor.execute {
                try {
                    ApprovalRepository.getInstance(appContext).removePendingApproval(packageName)
                    ZeroTrustForegroundWatcher.syncWatcherState(appContext)

                    handlerScope.launch {
                        try {
                            AppInventoryManager.getInstance(appContext).syncInventoryToCloud(force = true)
                        } catch (e: Exception) {
                            Log.w(TAG, "Async inventory sync failed on package removed: $packageName: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [ERROR] Error handling package removed: $packageName", e)
                } finally {
                    onComplete?.invoke()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "❌ [REJECTED] Executor rejected task on package removed: $packageName", e)
            onComplete?.invoke()
        }
    }
}

/**
 * Manifest-declared BroadcastReceiver for package lifecycle events.
 * Serves as fallback and handles silent uninstallation statuses.
 */
class PackageChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AntiPorn_PkgReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val data = intent.data
        val packageName = data?.schemeSpecificPart

        val isDeviceOwner = com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(context)
        Log.w(TAG, "⚡⚡⚡ [STATIC_RECEIVER] onReceive: action=$action | package=$packageName | isDeviceOwner=$isDeviceOwner")

        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (packageName.isNullOrBlank()) return
                val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                val pendingResult = goAsync()
                PackageChangeHandler.handlePackageAdded(
                    context = context,
                    packageName = packageName,
                    isReplacing = isReplacing,
                    source = "Static_Manifest",
                    onComplete = { pendingResult.finish() }
                )
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                if (packageName.isNullOrBlank()) return
                val pendingResult = goAsync()
                PackageChangeHandler.handlePackageRemoved(
                    context = context,
                    packageName = packageName,
                    source = "Static_Manifest",
                    onComplete = { pendingResult.finish() }
                )
            }

            AppSuspensionShield.ACTION_UNINSTALL_STATUS -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val pkgName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "unknown"
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        Log.i(TAG, "✅ [UNINSTALL_SUCCESS] Silent uninstall succeeded for $pkgName")
                    }
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        Log.w(TAG, "⚠️ [UNINSTALL_PENDING] Silent uninstall requires user action for $pkgName")
                    }
                    else -> {
                        Log.w(TAG, "❌ [UNINSTALL_FAILED] Silent uninstall failed for $pkgName (status: $status, msg: $message)")
                    }
                }
            }
        }
    }
}
