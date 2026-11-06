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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.drmohammed.antiporn.ai.service.ScreenAiProjectionService
import com.drmohammed.antiporn.notification.router.NotificationActionRouter
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.data.repository.AppInventoryManager
import com.drmohammed.antiporn.databinding.ActivityMainBinding
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.ota.model.OtaVersionInfo
import com.drmohammed.antiporn.ota.repository.OtaRepository
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.ui.ProtectionUiState
import com.drmohammed.antiporn.ui.accountability.AccountabilityFragment
import com.drmohammed.antiporn.ui.control.SelfControlFragment
import com.drmohammed.antiporn.ui.home.HomeFragment
import com.drmohammed.antiporn.ui.main.MainViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import com.drmohammed.antiporn.ui.settings.SettingsFragment
import com.drmohammed.antiporn.ui.splash.SplashAnimationController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main application dashboard activity hosting the Material 3 Bottom Navigation Hub.
 * Coordinates system services and manages the 4 primary tabs via ViewPager2:
 * 1. Home (Hero Shield & 1-Hour Nap)
 * 2. Self Control (Policies, Curfew, Restrictions)
 * 3. Accountability (Partner Hub & Pairing)
 * 4. Settings (AI Detection, OTA Updates, Google Account)
 */
class MainActivity : AppCompatActivity(),
    HomeFragment.HomeActionListener,
    SettingsFragment.SettingsActionListener,
    AccountabilityFragment.AccountabilityNavigationListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView
    private val mainViewModel: MainViewModel by viewModel()

    private var splashController: SplashAnimationController? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastAiToggleMs = 0L
    private var hasShownOtaPrompt = false

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (viewPager.currentItem != 0) {
                viewPager.setCurrentItem(0, true) // Smooth scroll back to Home tab
            }
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
        } else {
            Toast.makeText(this, R.string.ai_permission_denied, Toast.LENGTH_SHORT).show()
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewPager = binding.viewPagerMain
        bottomNav = binding.bottomNavigationView

        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutMainRoot) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            viewPager.setPadding(insets.left, insets.top, insets.right, 0)
            bottomNav.setPadding(0, 0, 0, insets.bottom)
            windowInsets
        }

        viewPager.adapter = MainPagerAdapter(this)
        viewPager.offscreenPageLimit = 3
        viewPager.isUserInputEnabled = true

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        setupNavigation(savedInstanceState)
        handleIntentNavigation(intent)
        setupOtaTracking()

        splashController = SplashAnimationController(this).apply {
            showIfColdBoot()
        }
    }

    private inner class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> HomeFragment()
                1 -> SelfControlFragment()
                2 -> AccountabilityFragment()
                3 -> SettingsFragment()
                else -> throw IllegalArgumentException("Invalid pager position: $position")
            }
        }
    }

    private fun positionToMenuId(position: Int): Int = MainViewModel.positionToMenuId(position)

    private fun menuIdToPosition(menuId: Int): Int = MainViewModel.menuIdToPosition(menuId)

    private fun setupNavigation(savedInstanceState: Bundle?) {
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val targetMenuId = positionToMenuId(position)
                if (bottomNav.selectedItemId != targetMenuId) {
                    bottomNav.selectedItemId = targetMenuId
                }
                mainViewModel.selectTab(position)
                backPressedCallback.isEnabled = (position != 0)
            }
        })

        bottomNav.setOnItemSelectedListener { item ->
            val targetPosition = menuIdToPosition(item.itemId)
            if (viewPager.currentItem != targetPosition) {
                viewPager.setCurrentItem(targetPosition, false)
            }
            mainViewModel.selectTab(targetPosition)
            true
        }

        val restoredTabId = savedInstanceState?.getInt(KEY_SELECTED_TAB, R.id.nav_home) ?: R.id.nav_home
        val targetPosition = menuIdToPosition(restoredTabId)
        viewPager.setCurrentItem(targetPosition, false)
        bottomNav.selectedItemId = restoredTabId
        mainViewModel.selectTab(targetPosition)
        backPressedCallback.isEnabled = (targetPosition != 0)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.navigationEvents.collect { event ->
                    when (event) {
                        is MainViewModel.MainNavigationEvent.OpenHome -> navigateToTab(R.id.nav_home)
                        is MainViewModel.MainNavigationEvent.OpenSelfControl -> navigateToTab(R.id.nav_self_control)
                        is MainViewModel.MainNavigationEvent.OpenAccountability -> navigateToTab(R.id.nav_accountability)
                        is MainViewModel.MainNavigationEvent.OpenSettings -> navigateToTab(R.id.nav_settings)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentNavigation(intent)
    }

    private fun handleIntentNavigation(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra(NotificationActionRouter.EXTRA_NAVIGATE_TAB)) {
            val tabPos = intent.getIntExtra(NotificationActionRouter.EXTRA_NAVIGATE_TAB, 0)
            intent.removeExtra(NotificationActionRouter.EXTRA_NAVIGATE_TAB)
            val menuId = positionToMenuId(tabPos)
            navigateToTab(menuId)
        }
    }

    fun navigateToTab(tabId: Int) {
        val targetPosition = menuIdToPosition(tabId)
        if (viewPager.currentItem != targetPosition) {
            viewPager.setCurrentItem(targetPosition, false)
        }
        if (bottomNav.selectedItemId != tabId) {
            bottomNav.selectedItemId = tabId
        }
        mainViewModel.selectTab(targetPosition)
    }

    override fun onNavigateToSelfControl() {
        navigateToTab(R.id.nav_self_control)
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
            com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(this).syncZeroTrustBaselineAndReconcileAsync()
        }
        refreshUi(isEnforcing = false)
        AppInventoryManager.getInstance(this).syncInventoryToCloudAsync()
    }

    override fun onPause() {
        super.onPause()
        splashController?.cancel()
        splashController = null
    }

    override fun onDestroy() {
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
                    return
                }
            }

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mpManager != null) {
                projectionLauncher.launch(mpManager.createScreenCaptureIntent())
            } else {
                Toast.makeText(this, R.string.ai_permission_denied, Toast.LENGTH_SHORT).show()
            }
        } else {
            val stopIntent = Intent(this, ScreenAiProjectionService::class.java)
            stopService(stopIntent)
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
            val googleAuthManager = com.drmohammed.antiporn.cloud.auth.GoogleAuthManager.getInstance(applicationContext)
            val result = googleAuthManager.signInOrLinkWithGoogle(this@MainActivity)
            if (isFinishing || isDestroyed) return@launch
            result.fold(
                onSuccess = {
                    Toast.makeText(this@MainActivity, R.string.toast_account_secured_success, Toast.LENGTH_SHORT).show()
                },
                onFailure = { err ->
                    if (err !is androidx.credentials.exceptions.GetCredentialCancellationException) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_account_secured_failed, err.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            )
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
        val otaRepo = OtaRepository.getInstance(applicationContext)

        lifecycleScope.launch {
            otaRepo.updateState.collect { state ->
                if (!isFinishing && !isDestroyed) {
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

        // Internal check: evaluates system status without mutating detached fragment state
    }

    companion object {
        private const val TAG = "AntiPorn_MainActivity"
        private const val KEY_SELECTED_TAB = "key_selected_tab"
    }
}
