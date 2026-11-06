package com.drmohammed.antiporn

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AntiPorn_AdminReceiver"
        const val ACTION_ENFORCE_POLICIES = "com.drmohammed.antiporn.ENFORCE_POLICIES"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin / Owner enabled. Enforcing NextDNS and security policies...")
        val appContext = context.applicationContext
        try {
            com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(appContext).recordDeviceOwnerEnabledTimestamp()
        } catch (e: Exception) {
            Log.e(TAG, "Error recording DO enabled timestamp in onEnabled", e)
        }
        enforcePoliciesAsync(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Profile provisioning complete. Enforcing NextDNS and security policies...")
        val appContext = context.applicationContext
        try {
            com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(appContext).recordDeviceOwnerEnabledTimestamp()
        } catch (e: Exception) {
            Log.e(TAG, "Error recording DO enabled timestamp in onProfileProvisioningComplete", e)
        }
        enforcePoliciesAsync(context)

        // Bootstrap MainActivity as a fallback strictly for legacy Android versions (Android 11 and below)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            val appContext = context.applicationContext
            try {
                val launchIntent = appContext.packageManager?.getLaunchIntentForPackage(appContext.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                } ?: Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                appContext.startActivity(launchIntent)
                Log.i(TAG, "Dispatched MainActivity launch intent from AdminReceiver fallback.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch MainActivity in onProfileProvisioningComplete", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_ENFORCE_POLICIES -> {
                Log.i(TAG, "Broadcast received (${intent?.action}). Enforcing NextDNS policies...")
                enforcePoliciesAsync(context)
            }
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device Admin disabled.")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.admin_disable_warning)
    }

    private fun enforcePoliciesAsync(context: Context) {
        val appContext = context.applicationContext
        DnsWatchdogService.startService(appContext)
        try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(appContext).startFirestoreSync()
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating PolicyRepository sync in AdminReceiver", e)
        }
        try {
            com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(appContext).syncZeroTrustBaselineAndReconcileAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating syncZeroTrustBaselineAndReconcileAsync in AdminReceiver", e)
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.drmohammed.antiporn.core.engine.ProtectionEngine.applyAllSync(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error applying policies in AdminReceiver", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

}
