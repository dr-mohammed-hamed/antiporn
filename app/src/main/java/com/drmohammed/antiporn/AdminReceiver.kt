package com.drmohammed.antiporn

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AntiPorn_AdminReceiver"
        const val ACTION_ENFORCE_POLICIES = "com.drmohammed.antiporn.ENFORCE_POLICIES"
        private val bgExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin / Owner enabled. Enforcing NextDNS and security policies...")
        enforcePoliciesAsync(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Profile provisioning complete. Enforcing NextDNS and security policies...")
        enforcePoliciesAsync(context)
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
        DnsWatchdogService.startService(context)
        val pendingResult = goAsync()
        bgExecutor.execute {
            try {
                DnsManager.applyPoliciesSync(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
