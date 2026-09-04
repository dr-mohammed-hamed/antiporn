package com.drmohammed.antiporn

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.ui.ProtectionUiState
import com.drmohammed.antiporn.ui.binder.MainViewBinder

/**
 * Main application dashboard activity.
 * Coordinates user actions with [ProtectionEngine] and delegates UI presentation to [MainViewBinder].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewBinder: MainViewBinder

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi(isEnforcing = false)
        }
    }

    private val uninstallStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi(isEnforcing = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewBinder = MainViewBinder(findViewById(android.R.id.content))
        setupListeners()
    }

    override fun onStart() {
        super.onStart()
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(packageChangeReceiver, pkgFilter)

        val statusFilter = IntentFilter(AppSuspensionShield.ACTION_UNINSTALL_STATUS)
        ContextCompat.registerReceiver(this, uninstallStatusReceiver, statusFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        val isDeviceOwner = ProtectionEngine.isDeviceOwner(this)
        if (isDeviceOwner) {
            DnsWatchdogService.startService(this)
            if (!ProtectionEngine.isSystemFullyProtected(this)) {
                triggerEnforcement(showToast = false)
            }
        }
        refreshUi(isEnforcing = false)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (e: Exception) {
            // Receiver was not registered or already unregistered
        }
        try {
            unregisterReceiver(uninstallStatusReceiver)
        } catch (e: Exception) {
            // Receiver was not registered or already unregistered
        }
    }

    private fun setupListeners() {
        viewBinder.btnEnforceNow.setOnClickListener {
            triggerEnforcement(showToast = true)
        }
    }

    private fun triggerEnforcement(showToast: Boolean) {
        refreshUi(isEnforcing = true)
        ProtectionEngine.applyAllAsync(this) { results ->
            if (!isFinishing && !isDestroyed) {
                val allSuccess = results.all { it.success }
                val message = if (allSuccess) {
                    getString(R.string.toast_enforce_success, DnsShield.NEXTDNS_HOST)
                } else {
                    results.firstOrNull { !it.success }?.message
                        ?: getString(R.string.status_need_enforce_desc)
                }

                if (showToast) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
                refreshUi(isEnforcing = false)
            }
        }
    }

    private fun refreshUi(isEnforcing: Boolean) {
        val isDo = ProtectionEngine.isDeviceOwner(this)
        val shields = ProtectionEngine.evaluateSystemStatus(this)

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ProtectionEngine.getAdminComponent(this)
        val currentDnsHost = if (dpm != null && isDo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { dpm.getGlobalPrivateDnsHost(admin) } catch (e: Exception) { null }
        } else null

        val state = ProtectionUiState.Success(
            isDeviceOwner = isDo,
            shields = shields,
            currentDnsHost = currentDnsHost,
            isEnforcing = isEnforcing
        )
        viewBinder.bind(state)
    }
}
