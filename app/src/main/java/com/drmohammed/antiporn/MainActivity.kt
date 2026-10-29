package com.drmohammed.antiporn

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.drmohammed.antiporn.ai.service.ScreenAiProjectionService
import com.drmohammed.antiporn.ai.ui.AiControlCardViewBinder
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.curfew.NapController
import com.drmohammed.antiporn.data.repository.AppInventoryManager
import com.drmohammed.antiporn.provisioning.ui.ProvisioningWizardActivity
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.DnsShield
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.ota.repository.OtaRepository
import kotlinx.coroutines.launch
import com.drmohammed.antiporn.ui.ProtectionUiState
import com.drmohammed.antiporn.ui.binder.MainViewBinder

/**
 * Main application dashboard activity.
 * Coordinates user actions with [ProtectionEngine] and delegates UI presentation to [MainViewBinder].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewBinder: MainViewBinder
    private lateinit var aiCardBinder: AiControlCardViewBinder
    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastAiToggleMs = 0L
    private var lastNapTapMs = 0L

    private val aiRefreshRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            aiCardBinder.updateUi()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenAiProjectionService::class.java).apply {
                action = ScreenAiProjectionService.ACTION_START
                putExtra(ScreenAiProjectionService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenAiProjectionService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            uiHandler.postDelayed(aiRefreshRunnable, 300)
        } else {
            Toast.makeText(this, R.string.ai_permission_denied, Toast.LENGTH_SHORT).show()
            aiCardBinder.updateUi()
        }
    }

    private val uiTickerRunnable = object : Runnable {
        override fun run() {
            // MINOR(m1): never touch dead Activity views after onDestroy.
            if (!isFinishing && !isDestroyed) {
                try { aiCardBinder.updateUi() } catch (e: Exception) { Log.w(TAG, "Error updating AI card UI: ${e.message}") }
                try { viewBinder.updateNapCountdown(NapController.remainingMs(this@MainActivity)) } catch (e: Exception) { Log.w(TAG, "Error updating nap countdown: ${e.message}") }
            }
            if (!isFinishing && !isDestroyed) uiHandler.postDelayed(this, 1000L)
        }
    }

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<android.view.View>(R.id.scrollMain)?.let { scrollView ->
            ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(
                    insets.left,
                    insets.top,
                    insets.right,
                    insets.bottom
                )
                windowInsets
            }
        }

        viewBinder = MainViewBinder(findViewById(android.R.id.content))
        aiCardBinder = AiControlCardViewBinder(this) {
            handleAiShieldToggle()
        }
        setupListeners()
    }

    override fun onStart() {
        super.onStart()
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        // CRITICAL(C1): targetSdk=36 requires explicit exported flag on API 33+.
        // System PACKAGE_* broadcasts are still delivered to NOT_EXPORTED receivers.
        ContextCompat.registerReceiver(
            this, packageChangeReceiver, pkgFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val statusFilter = IntentFilter(AppSuspensionShield.ACTION_UNINSTALL_STATUS)
        ContextCompat.registerReceiver(this, uninstallStatusReceiver, statusFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        val isDeviceOwner = ProtectionEngine.isDeviceOwner(this)
        if (isDeviceOwner) {
            DnsWatchdogService.startService(this)
            CurfewScheduler.scheduleNextAlarms(this)
            CurfewEnforcer.syncBlockStateAsync(this) {
                if (!isFinishing && !isDestroyed) {
                    refreshUi(isEnforcing = false)
                }
            }
            if (!ProtectionEngine.isSystemFullyProtected(this)) {
                triggerEnforcement(showToast = false)
            }
        }
        refreshUi(isEnforcing = false)
        aiCardBinder.updateUi()
        try { viewBinder.updateNapCountdown(NapController.remainingMs(this)) } catch (e: Exception) { Log.w(TAG, "Error updating nap countdown on resume: ${e.message}") }
        AppInventoryManager.getInstance(this).syncInventoryToCloudAsync()
        uiHandler.post(uiTickerRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiTickerRunnable)
        uiHandler.removeCallbacks(aiRefreshRunnable)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(uiTickerRunnable)
        uiHandler.removeCallbacks(aiRefreshRunnable)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (e: Exception) {
            Log.d(TAG, "Package receiver not registered or already unregistered: ${e.message}")
        }
        try {
            unregisterReceiver(uninstallStatusReceiver)
        } catch (e: Exception) {
            Log.d(TAG, "Uninstall status receiver not registered or already unregistered: ${e.message}")
        }
    }

    private fun tryAutoGrantOverlayPermission() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager ?: return
            val admin = ComponentName(this, AdminReceiver::class.java)
            if (ProtectionEngine.isDeviceOwner(this)) {
                dpm.setPermissionGrantState(
                    admin,
                    packageName,
                    android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                    android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device Owner overlay grant attempt: ${e.message}")
        }
    }

    private fun handleAiShieldToggle() {
        // منع النقر المزدوج: تجاهل التبديلات المتقاربة (<1s) لمنع نوافذ موافقة مكررة.
        val now = SystemClock.elapsedRealtime()
        if (now - lastAiToggleMs < 1000L) return
        lastAiToggleMs = now
        if (ScreenAiProjectionService.isRunning) {
            // MINOR: stopService() avoids spawning the service (TFLite mmap + NNAPI init in
            // onCreate) just to stop it. Notification STOP action still uses ACTION_STOP intent.
            val stopIntent = Intent(this, ScreenAiProjectionService::class.java)
            stopService(stopIntent)
            uiHandler.postDelayed(aiRefreshRunnable, 300)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                tryAutoGrantOverlayPermission()
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(this, R.string.overlay_permission_prompt, Toast.LENGTH_LONG).show()
                    return
                }
            }

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mpManager != null) {
                projectionLauncher.launch(mpManager.createScreenCaptureIntent())
            } else {
                Toast.makeText(this, R.string.ai_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getCurrentAppVersion(): Pair<String, Long> {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val code = PackageInfoCompat.getLongVersionCode(packageInfo)
            val name = packageInfo.versionName ?: "1.0"
            Pair(name, code)
        } catch (e: Exception) {
            Pair("1.0", 1L)
        }
    }

    private fun setupListeners() {
        val (verName, verCode) = getCurrentAppVersion()
        val otaRepo = OtaRepository.getInstance(applicationContext)

        viewBinder.bindOtaState(otaRepo.updateState.value, verName, verCode)

        lifecycleScope.launch {
            otaRepo.updateState.collect { state ->
                if (!isFinishing && !isDestroyed) {
                    viewBinder.bindOtaState(state, verName, verCode)
                }
            }
        }

        viewBinder.btnCheckUpdate.setOnClickListener {
            when (val currentState = otaRepo.updateState.value) {
                is OtaUpdateState.UpdateAvailable -> {
                    val info = currentState.info
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.card_ota_title)
                        .setMessage(
                            getString(R.string.ota_update_available, info.versionName) +
                            (if (!info.releaseNotes.isNullOrBlank()) "\n\n${info.releaseNotes}\n" else "") +
                            "\n\n" + getString(R.string.ota_dialog_prompt)
                        )
                        .setPositiveButton(R.string.btn_download_update) { _, _ ->
                            lifecycleScope.launch {
                                otaRepo.downloadAndInstall(applicationContext, info, showNotification = true)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                is OtaUpdateState.Downloading, is OtaUpdateState.Verifying, is OtaUpdateState.Installing, is OtaUpdateState.Checking -> {
                    // In-progress, ignore duplicate clicks
                }
                else -> {
                    lifecycleScope.launch {
                        otaRepo.checkForUpdates(applicationContext)
                    }
                }
            }
        }

        viewBinder.cardAdbHelp.setOnClickListener {
            startActivity(Intent(this, ProvisioningWizardActivity::class.java))
        }

        viewBinder.btnOpenAccountabilityHub.setOnClickListener {
            startActivity(Intent(this, com.drmohammed.antiporn.ui.accountability.AccountabilityHubActivity::class.java))
        }

        viewBinder.btnEnforceNow.setOnClickListener {
            triggerEnforcement(showToast = true)
        }


        viewBinder.btnCurfewTestToggle.setOnClickListener {
            if (CurfewEnforcer.isCurfewActive(this)) {
                Toast.makeText(this, R.string.card_curfew_night_locked_hint, Toast.LENGTH_SHORT).show()
                refreshUi(isEnforcing = false)
                return@setOnClickListener
            }

            val isCurrentlyActive = CurfewEnforcer.isManualTestActive(this)
            val newActive = !isCurrentlyActive
            viewBinder.btnCurfewTestToggle.isEnabled = false
            CurfewEnforcer.setManualTestActiveAsync(this, newActive) {
                if (!isFinishing && !isDestroyed) {
                    viewBinder.btnCurfewTestToggle.isEnabled = true
                    val toastMessage = if (newActive) {
                        getString(R.string.toast_curfew_test_on)
                    } else {
                        getString(R.string.toast_curfew_test_off)
                    }
                    Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
                    refreshUi(isEnforcing = false)
                }
            }
        }

        viewBinder.btnNapStart.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNapTapMs < 1000L) return@setOnClickListener
            lastNapTapMs = now
            if (CurfewEnforcer.isCurfewActive(this)) {
                Toast.makeText(this, R.string.card_curfew_night_locked_hint, Toast.LENGTH_SHORT).show()
                refreshUi(isEnforcing = false)
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_nap_confirm_title)
                .setMessage(R.string.dialog_nap_confirm_msg)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                    viewBinder.btnNapStart.isEnabled = false
                    viewBinder.btnNapExtend.isEnabled = false
                    NapController.startNap(this)
                    CurfewScheduler.scheduleNapEndAlarm(this, NapController.getEndWall(this))
                    CurfewEnforcer.syncBlockStateAsync(this) {
                        if (!isFinishing && !isDestroyed) {
                            viewBinder.btnNapStart.isEnabled = true
                            viewBinder.btnNapExtend.isEnabled = true
                            Toast.makeText(this, R.string.toast_nap_started, Toast.LENGTH_SHORT).show()
                            refreshUi(isEnforcing = false)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        viewBinder.btnNapExtend.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNapTapMs < 1000L) return@setOnClickListener
            lastNapTapMs = now
            if (!NapController.isNapActive(this)) {
                refreshUi(isEnforcing = false)
                return@setOnClickListener
            }
            viewBinder.btnNapExtend.isEnabled = false
            NapController.extendNap(this)
            CurfewScheduler.scheduleNapEndAlarm(this, NapController.getEndWall(this))
            CurfewEnforcer.syncBlockStateAsync(this) {
                if (!isFinishing && !isDestroyed) {
                    viewBinder.btnNapExtend.isEnabled = true
                    Toast.makeText(this, R.string.toast_nap_extended, Toast.LENGTH_SHORT).show()
                    refreshUi(isEnforcing = false)
                }
            }
        }

        viewBinder.btnGrantUsageAccess.setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open usage access settings", e)
                Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerEnforcement(showToast: Boolean) {
        refreshUi(isEnforcing = true)
        ProtectionEngine.applyAllAsync(this) { results ->
            if (!isFinishing && !isDestroyed) {
                val allSuccess = results.all { it.success }
                val message = if (allSuccess) {
                    getString(R.string.toast_enforce_success, DnsShield.getEffectiveDnsHost(this@MainActivity))
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

    companion object {
        private const val TAG = "AntiPorn_MainActivity"
    }
}
