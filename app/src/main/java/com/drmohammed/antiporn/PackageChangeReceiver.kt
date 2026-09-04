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

                if (!AppSuspensionShield.isBlacklisted(packageName)) {
                    return
                }

                Log.i(TAG, "Blacklisted package install detected: $packageName. Enforcing freeze...")
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                try {
                    bgExecutor.execute {
                        try {
                            AppSuspensionShield.handlePackageAdded(appContext, packageName)
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
