package com.drmohammed.antiporn

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.ai.service.ScreenAiProjectionService
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.curfew.NapController
import com.drmohammed.antiporn.data.repository.AppInventoryManager
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.ota.model.OtaVersionInfo
import com.drmohammed.antiporn.ota.repository.OtaRepository
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.ui.ProtectionUiState
import com.drmohammed.antiporn.ui.accountability.AccountabilityFragment
import com.drmohammed.antiporn.ui.control.SelfControlFragment
import com.drmohammed.antiporn.ui.home.HomeFragment
import com.drmohammed.antiporn.ui.settings.SettingsFragment
import com.drmohammed.antiporn.ui.splash.SplashAnimationController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main application dashboard activity hosting the Material 3 Bottom Navigation Hub.
 * Coordinates system services and manages the 4 primary tabs:
 * 1. Home (Hero Shield & 1-Hour Nap)
 * 2. Self Control (Policies, Curfew, Restrictions)
 * 3. Accountability (Partner Hub & Pairing)
 * 4. Settings (AI Detection, OTA Updates, Google Account)
 */
class MainActivity : AppCompatActivity(),
    HomeFragment.HomeActionListener,
    SettingsFragment.SettingsActionListener,
    AccountabilityFragment.AccountabilityNavigationListener {

    private lateinit var bottomNav: BottomNavigationView

    private var homeFragment = HomeFragment()
    private var selfControlFragment = SelfControlFragment()
    private var accountabilityFragment = AccountabilityFragment()
    private var settingsFragment = SettingsFragment()

    private var activeFragment: Fragment = homeFragment

    private var splashController: SplashAnimationController? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastAiToggleMs = 0L
    private var hasShownOtaPrompt = false
    private var lastProtectionState: ProtectionUiState? = null
    private var lastDeniedToastTime = 0L

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            navigateToTab(R.id.nav_home)
        }
    }

    private val aiRefreshRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            settingsFragment.updateAiUi()
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
            settingsFragment.updateAiUi()
        }
    }

    private val uiTickerRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                try {
                    homeFragment.updateNapCountdown(NapController.remainingMs(this@MainActivity))
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating nap countdown: ${e.message}")
                }
                try {
                    if (activeFragment == settingsFragment) {
                        settingsFragment.updateAiUi()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating settings AI UI: ${e.message}")
                }
                uiHandler.postDelayed(this, 1000L)
            }

        }
    }

    private val uninstallStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi(isEnforcing = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::bottomNav.isInitialized) {
            outState.putInt(KEY_SELECTED_TAB, bottomNav.selectedItemId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottomNavigationView)
        val fragmentContainer = findViewById<android.view.View>(R.id.fragmentContainer)

        findViewById<android.view.View>(R.id.layoutMainRoot)?.let { rootView ->
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                fragmentContainer?.setPadding(insets.left, insets.top, insets.right, 0)
                bottomNav.setPadding(0, 0, 0, insets.bottom)
                windowInsets
            }
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        setupFragments(savedInstanceState)
        setupNavigation()
        setupOtaTracking()

        splashController = SplashAnimationController(this).apply {
            showIfColdBoot()
        }
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        if (savedInstanceState == null) {
            fm.beginTransaction()
                .add(R.id.fragmentContainer, homeFragment, TAG_HOME)
                .add(R.id.fragmentContainer, selfControlFragment, TAG_SELF_CONTROL).hide(selfControlFragment)
                .add(R.id.fragmentContainer, accountabilityFragment, TAG_ACCOUNTABILITY).hide(accountabilityFragment)
                .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS).hide(settingsFragment)
                .commit()
            activeFragment = homeFragment
        } else {
            homeFragment = fm.findFragmentByTag(TAG_HOME) as? HomeFragment ?: homeFragment
            selfControlFragment = fm.findFragmentByTag(TAG_SELF_CONTROL) as? SelfControlFragment ?: selfControlFragment
            accountabilityFragment = fm.findFragmentByTag(TAG_ACCOUNTABILITY) as? AccountabilityFragment ?: accountabilityFragment
            settingsFragment = fm.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment ?: settingsFragment

            var restoredTabId = savedInstanceState?.getInt(KEY_SELECTED_TAB, R.id.nav_home) ?: R.id.nav_home
            if (restoredTabId == R.id.nav_self_control) {
                val gate = com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.getActiveGate()
                if (!gate.canAccess(this)) {
                    restoredTabId = R.id.nav_home
                }
            }
            bottomNav.selectedItemId = restoredTabId
            activeFragment = when (restoredTabId) {
                R.id.nav_self_control -> selfControlFragment
                R.id.nav_accountability -> accountabilityFragment
                R.id.nav_settings -> settingsFragment
                else -> homeFragment
            }

            val tx = fm.beginTransaction()
            listOf(homeFragment, selfControlFragment, accountabilityFragment, settingsFragment).forEach { frag ->
                if (frag.isAdded) {
                    if (frag == activeFragment) tx.show(frag) else tx.hide(frag)
                }
            }
            tx.commit()
            backPressedCallback.isEnabled = (activeFragment != homeFragment)
        }
    }

    private fun setupNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    switchFragment(homeFragment)
                    true
                }
                R.id.nav_self_control -> {
                    val gate = com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.getActiveGate()
                    if (!gate.canAccess(this)) {
                        showSelfControlAccessDeniedMessage()
                        false
                    } else {
                        switchFragment(selfControlFragment)
                        true
                    }
                }
                R.id.nav_accountability -> {
                    switchFragment(accountabilityFragment)
                    true
                }
                R.id.nav_settings -> {
                    switchFragment(settingsFragment)
                    true
                }
                else -> false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(applicationContext).currentPolicyFlow.collect { policy ->
                    if (isFinishing || isDestroyed) return@collect
                    if (activeFragment == selfControlFragment) {
                        val gate = com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.getActiveGate()
                        if (!gate.canAccess(this@MainActivity)) {
                            showSelfControlAccessDeniedMessage()
                            bottomNav.selectedItemId = R.id.nav_home
                        }
                    }
                }
            }
        }
    }

    private fun showSelfControlAccessDeniedMessage() {
        if (isFinishing || isDestroyed) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDeniedToastTime < 1500L) return
        lastDeniedToastTime = now

        val policy = try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve policy for access denied message", e)
            null
        }

        val msgRes = if (policy?.isSupervised == true && !policy.isSelfControlAllowed) {
            R.string.gate_access_denied_by_guardian
        } else {
            R.string.gate_access_denied
        }
        Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
    }

    private fun switchFragment(target: Fragment) {
        if (activeFragment == target) return

        supportFragmentManager.beginTransaction()
            .hide(activeFragment)
            .show(target)
            .commit()

        activeFragment = target
        backPressedCallback.isEnabled = (target != homeFragment)

        if (target == homeFragment) {
            lastProtectionState?.let { homeFragment.updateProtectionState(it) }
            homeFragment.updateNapCountdown(NapController.remainingMs(this))
        } else if (target == settingsFragment) {
            settingsFragment.updateAllStates()
        }
    }

    fun navigateToTab(tabId: Int) {
        bottomNav.selectedItemId = tabId
    }

    override fun onNavigateToSelfControl() {
        val gate = com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.getActiveGate()
        if (!gate.canAccess(this)) {
            showSelfControlAccessDeniedMessage()
            return
        }
        bottomNav.selectedItemId = R.id.nav_self_control
    }

    override fun onStart() {
        super.onStart()
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
            com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(this).ensureInitialBaselineSeededAsync()
            com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(this).reconcileUnapprovedInstalledAppsAsync()
        }
        refreshUi(isEnforcing = false)
        settingsFragment.updateAllStates()
        homeFragment.updateNapCountdown(NapController.remainingMs(this))
        AppInventoryManager.getInstance(this).syncInventoryToCloudAsync()
        uiHandler.post(uiTickerRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiTickerRunnable)
        uiHandler.removeCallbacks(aiRefreshRunnable)
        splashController?.cancel()
        splashController = null
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(uiTickerRunnable)
        uiHandler.removeCallbacks(aiRefreshRunnable)
        splashController?.cancel()
        splashController = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(uninstallStatusReceiver)
        } catch (e: Exception) {
            Log.d(TAG, "Uninstall status receiver not registered: ${e.message}")
        }
    }

    override fun onEnforceRequested() {
        triggerEnforcement(showToast = true)
    }

    override fun onToggleAiRequested(enable: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAiToggleMs < 1000L) return
        lastAiToggleMs = now

        if (enable) {
            if (!Settings.canDrawOverlays(this)) {
                tryAutoGrantOverlayPermission()
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:$packageName".toUri()
                    )
                    startActivity(intent)
                    Toast.makeText(this, R.string.overlay_permission_prompt, Toast.LENGTH_LONG).show()
                    settingsFragment.updateAiUi()
                    return
                }
            }

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mpManager != null) {
                projectionLauncher.launch(mpManager.createScreenCaptureIntent())
            } else {
                Toast.makeText(this, R.string.ai_permission_denied, Toast.LENGTH_SHORT).show()
                settingsFragment.updateAiUi()
            }
        } else {
            val stopIntent = Intent(this, ScreenAiProjectionService::class.java)
            stopService(stopIntent)
            uiHandler.postDelayed(aiRefreshRunnable, 300)
        }
    }

    override fun onCheckOtaRequested() {
        val otaRepo = OtaRepository.getInstance(applicationContext)
        when (val currentState = otaRepo.updateState.value) {
            is OtaUpdateState.UpdateAvailable -> {
                showOtaUpdateDialog(currentState.info, otaRepo)
            }
            is OtaUpdateState.Downloading, is OtaUpdateState.Verifying, is OtaUpdateState.Installing, is OtaUpdateState.Checking -> {
                // In progress
            }
            else -> {
                lifecycleScope.launch {
                    otaRepo.checkForUpdates(applicationContext)
                }
            }
        }
    }

    override fun onLinkGoogleRequested() {
        lifecycleScope.launch {
            try {
                val googleAuthManager = com.drmohammed.antiporn.cloud.auth.GoogleAuthManager.getInstance(applicationContext)
                val result = googleAuthManager.signInOrLinkWithGoogle(this@MainActivity)
                if (isFinishing || isDestroyed) return@launch
                result.fold(
                    onSuccess = {
                        Toast.makeText(this@MainActivity, R.string.toast_account_secured_success, Toast.LENGTH_SHORT).show()
                        settingsFragment.updateGoogleUi()
                    },
                    onFailure = { err ->
                        if (err !is androidx.credentials.exceptions.GetCredentialCancellationException) {
                            Toast.makeText(this@MainActivity, getString(R.string.toast_account_secured_failed, err.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                )
            } finally {
                settingsFragment.updateGoogleUi()
            }
        }
    }

    private fun tryAutoGrantOverlayPermission() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val admin = ComponentName(this, AdminReceiver::class.java)
            if (ProtectionEngine.isDeviceOwner(this)) {
                dpm.setPermissionGrantState(
                    admin,
                    packageName,
                    android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device Owner overlay grant attempt: ${e.message}")
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

    private fun setupOtaTracking() {
        val (verName, verCode) = getCurrentAppVersion()
        val otaRepo = OtaRepository.getInstance(applicationContext)

        settingsFragment.bindOtaState(otaRepo.updateState.value, verName, verCode)

        lifecycleScope.launch {
            otaRepo.updateState.collect { state ->
                if (!isFinishing && !isDestroyed) {
                    settingsFragment.bindOtaState(state, verName, verCode)
                    if (state is OtaUpdateState.UpdateAvailable && !hasShownOtaPrompt) {
                        hasShownOtaPrompt = true
                        showOtaUpdateDialog(state.info, otaRepo)
                    }
                }
            }
        }

        lifecycleScope.launch {
            delay(1200L)
            if (!isFinishing && !isDestroyed) {
                otaRepo.checkForUpdates(applicationContext)
            }
        }
    }

    private fun showOtaUpdateDialog(info: OtaVersionInfo, otaRepo: OtaRepository) {
        if (isFinishing || isDestroyed) return
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
        lastProtectionState = state
        homeFragment.updateProtectionState(state)
        settingsFragment.updateDeviceOwnerUi()
    }

    companion object {
        private const val TAG = "AntiPorn_MainActivity"
        private const val TAG_HOME = "TAB_HOME"
        private const val TAG_SELF_CONTROL = "TAB_SELF_CONTROL"
        private const val TAG_ACCOUNTABILITY = "TAB_ACCOUNTABILITY"
        private const val TAG_SETTINGS = "TAB_SETTINGS"
        private const val KEY_SELECTED_TAB = "key_selected_tab"
    }
}
