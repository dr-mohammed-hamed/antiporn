package com.drmohammed.antiporn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.drmohammed.antiporn.shields.AppSuspensionShield
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Intercepts package installation broadcasts to enforce immediate freezing
 * and silent uninstallation of blacklisted applications via Device Owner,
 * and monitors PackageInstaller uninstall status broadcasts.
 */
class PackageChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AntiPorn_PkgReceiver"
        private val bgExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                val data = intent.data ?: return
                val packageName = data.schemeSpecificPart ?: return
                val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

                val pendingResult = goAsync()
                val appContext = context.applicationContext
                try {
                    bgExecutor.execute {
                        try {
                            if (AppSuspensionShield.isBlacklisted(packageName)) {
                                Log.i(TAG, "Blacklisted package install detected: $packageName. Enforcing freeze...")
                                AppSuspensionShield.handlePackageAdded(appContext, packageName)
                            } else {
                                val approvalRepo = com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(appContext)
                                val isAlreadyApproved = approvalRepo.getLocalPendingApprovals().any { it.packageName == packageName && it.isApproved }

                                val policy = com.drmohammed.antiporn.data.repository.PolicyRepository
                                    .getInstance(appContext).getCurrentPolicy()
                                if (policy.lockNewInstallsUntilApproved && !isReplacing && !isAlreadyApproved) {
                                    if (packageName == appContext.packageName ||
                                        com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isImmune(packageName) ||
                                        com.drmohammed.antiporn.curfew.CurfewAppResolver.resolveImmunePackages(appContext).contains(packageName)
                                    ) {
                                        Log.i(TAG, "New install $packageName is immune or self. Skipping freeze.")
                                    } else {
                                        Log.i(TAG, "Strict Zero-Trust active: Freezing new install $packageName in 0ms and submitting approval ticket")
                                        approvalRepo.freezeAndSubmitApproval(packageName)
                                    }
                                } else {
                                    Log.i(TAG, "Package $packageName added/updated (replacing=$isReplacing, alreadyApproved=$isAlreadyApproved). Evaluating curfew status...")
                                    com.drmohammed.antiporn.curfew.CurfewEnforcer.handlePackageAdded(appContext, packageName)
                                }
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling added package $packageName", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    Log.e(TAG, "Executor rejected task for package $packageName", e)
                    pendingResult.finish()
                }
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                val data = intent.data ?: return
                val packageName = data.schemeSpecificPart ?: return
                Log.d(TAG, "Package removed notification received: $packageName")
            }

            AppSuspensionShield.ACTION_UNINSTALL_STATUS -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "unknown"
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        Log.i(TAG, "Silent uninstall succeeded for $packageName")
                    }
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        Log.w(TAG, "Silent uninstall requires user action for $packageName")
                    }
                    else -> {
                        Log.w(TAG, "Silent uninstall failed for $packageName (status: $status, msg: $message)")
                    }
                }
            }
        }
    }
}
