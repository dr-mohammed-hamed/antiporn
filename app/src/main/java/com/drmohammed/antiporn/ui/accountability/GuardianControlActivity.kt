package com.drmohammed.antiporn.ui.accountability

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.ui.browser.BrowserUrlFilterActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Control panel enabling a Guardian to configure protection timings, VPN blackhole,
 * browser restrictions, and approve/reject newly installed apps on their Ward's device remotely.
 * Refactored to Clean MVVM: delegates state & network synchronization to [GuardianControlViewModel].
 */
class GuardianControlActivity : AppCompatActivity() {

    private val viewModel: GuardianControlViewModel by viewModel()

    private lateinit var wardId: String
    private lateinit var wardName: String

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvWardName: TextView
    private lateinit var btnEditWardName: MaterialButton
    private lateinit var layoutLiveSyncBadge: LinearLayout
    private lateinit var ivLiveSyncIcon: ImageView
    private lateinit var tvLiveSyncText: TextView
    private lateinit var cardOfflineBanner: MaterialCardView
    private lateinit var tvOfflineStatus: TextView
    private lateinit var btnRetryLoadPolicy: MaterialButton
    private lateinit var alertsViewBinder: GuardianAlertsViewBinder
    private lateinit var curfewViewBinder: GuardianCurfewViewBinder

    // Self-Control Permission
    private lateinit var cardSelfControlPermission: MaterialCardView
    private lateinit var switchAllowSelfControl: MaterialSwitch

    // Curfew controls
    private lateinit var switchCurfewMaster: MaterialSwitch
    private lateinit var switchWeekendCurfew: MaterialSwitch

    // Date & Time Lock control
    private lateinit var switchLockDateTime: MaterialSwitch

    // Timezone controls
    private lateinit var timezoneViewBinder: GuardianTimezoneViewBinder

    // Enforcement & Security controls
    private lateinit var switchVpnBlackhole: MaterialSwitch
    private lateinit var switchInstantInternetKill: MaterialSwitch
    private lateinit var switchIncognitoBlocked: MaterialSwitch
    private lateinit var switchLockNewInstalls: MaterialSwitch
    private lateinit var switchYouTubeRestricted: MaterialSwitch
    private lateinit var switchTelegramWeb: MaterialSwitch

    // Remote App Manager
    private lateinit var btnOpenAppManager: MaterialButton

    // Browser URL Filter
    private lateinit var tvBrowserFilterStatus: TextView
    private lateinit var btnManageBrowserUrls: MaterialButton

    // DNS controls
    private lateinit var tilDnsHost: TextInputLayout
    private lateinit var etDnsHost: TextInputEditText
    private lateinit var btnPresetControlD: MaterialButton
    private lateinit var btnPresetAdguard: MaterialButton
    private lateinit var btnPresetCloudflare: MaterialButton
    private lateinit var btnPresetNextDns: MaterialButton

    // Approvals
    private lateinit var layoutPendingApps: LinearLayout
    private lateinit var tvNoPendingApps: TextView
    private lateinit var btnSavePolicyChanges: MaterialButton

    private lateinit var approvalsViewBinder: GuardianApprovalsViewBinder
    private lateinit var liveSyncViewBinder: GuardianLiveSyncViewBinder
    private var lastEditNameClickTime = 0L
    private var lastSaveClickTime = 0L

    private val appManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.loadWardPolicy()
        }
    }

    private val browserFilterLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val updatedJson = result.data?.getStringExtra(BrowserUrlFilterActivity.EXTRA_UPDATED_POLICY_JSON)
            if (!updatedJson.isNullOrBlank()) {
                val updated = DevicePolicyBundle.fromJson(updatedJson)
                viewModel.updateBrowserFilter(
                    filterMode = updated.browserFilterMode,
                    customBlocked = updated.customBlockedUrls,
                    customAllowed = updated.customAllowedUrls,
                    isFullSystemFilteringEnabled = updated.isFullSystemFilteringEnabled
                )
            }
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (viewModel.uiState.value.hasUnsavedChanges) {
                showDiscardConfirmationDialog()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian_control)

        applyWindowInsets()

        wardId = intent.getStringExtra(EXTRA_WARD_ID) ?: ""
        wardName = intent.getStringExtra(EXTRA_WARD_NAME) ?: getString(R.string.status_ward_badge)

        if (wardId.isBlank()) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        initViews()
        setupListeners()
        observeViewModel()

        viewModel.initialize(wardId, wardName)
    }

    private fun applyWindowInsets() {
        findViewById<View>(R.id.layoutRootGuardianControl)?.let { rootView ->
            val innerBottomBar = findViewById<View>(R.id.layoutBottomBarGuardianInner)
            val baseBottomPadding = innerBottomBar?.paddingBottom ?: 0
            val baseLeftPadding = innerBottomBar?.paddingLeft ?: 0
            val baseRightPadding = innerBottomBar?.paddingRight ?: 0
            val baseTopPadding = innerBottomBar?.paddingTop ?: 0

            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
                )
                view.setPadding(insets.left, insets.top, insets.right, 0)
                innerBottomBar?.setPadding(
                    baseLeftPadding,
                    baseTopPadding,
                    baseRightPadding,
                    baseBottomPadding + insets.bottom
                )
                windowInsets
            }
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarGuardianControl)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        tvWardName = findViewById(R.id.tvWardName)
        tvWardName.text = getString(R.string.guardian_control_ward_name, wardName)
        btnEditWardName = findViewById(R.id.btnEditWardName)
        layoutLiveSyncBadge = findViewById(R.id.layoutLiveSyncBadge)
        ivLiveSyncIcon = findViewById(R.id.ivLiveSyncIcon)
        tvLiveSyncText = findViewById(R.id.tvLiveSyncText)
        liveSyncViewBinder = GuardianLiveSyncViewBinder(
            activity = this,
            layoutLiveSyncBadge = layoutLiveSyncBadge,
            ivLiveSyncIcon = ivLiveSyncIcon,
            tvLiveSyncText = tvLiveSyncText,
            wardIdProvider = { viewModel.uiState.value.wardId }
        )

        cardOfflineBanner = findViewById(R.id.cardOfflineBanner)
        tvOfflineStatus = findViewById(R.id.tvOfflineStatus)
        btnRetryLoadPolicy = findViewById(R.id.btnRetryLoadPolicy)
        alertsViewBinder = GuardianAlertsViewBinder(
            activity = this,
            cardSelfControlAlertBanner = findViewById(R.id.cardSelfControlAlertBanner),
            tvSelfControlAlertMsg = findViewById(R.id.tvSelfControlAlertMsg),
            btnRevertSelfControlChanges = findViewById(R.id.btnRevertSelfControlChanges),
            btnDismissSelfControlAlert = findViewById(R.id.btnDismissSelfControlAlert),
            cardBreakoutAlertBanner = findViewById(R.id.cardBreakoutAlertBanner),
            tvBreakoutAlertMsg = findViewById(R.id.tvBreakoutAlertMsg),
            btnRejectBreakout = findViewById(R.id.btnRejectBreakout),
            wardNameProvider = { viewModel.uiState.value.wardName },
            wardIdProvider = { viewModel.uiState.value.wardId },
            policyProvider = { viewModel.uiState.value.currentPolicy },
            isSelfControlAllowedProvider = { viewModel.uiState.value.isSelfControlAllowed },
            onPolicyReverted = { reverted -> viewModel.onPolicyReverted(reverted) },
            onPolicyAcknowledged = { acknowledged -> viewModel.onPolicyAcknowledged(acknowledged) }
        )

        cardSelfControlPermission = findViewById(R.id.cardSelfControlPermission)
        switchAllowSelfControl = findViewById(R.id.switchAllowSelfControl)

        switchCurfewMaster = findViewById(R.id.switchCurfewMaster)
        switchWeekendCurfew = findViewById(R.id.switchWeekendCurfew)

        curfewViewBinder = GuardianCurfewViewBinder(
            activity = this,
            tvCurfewStartTime = findViewById(R.id.tvCurfewStartTime),
            tvCurfewEndTime = findViewById(R.id.tvCurfewEndTime),
            btnChangeStartTime = findViewById(R.id.btnChangeStartTime),
            btnChangeEndTime = findViewById(R.id.btnChangeEndTime),
            tvWeekendStartTime = findViewById(R.id.tvWeekendStartTime),
            tvWeekendEndTime = findViewById(R.id.tvWeekendEndTime),
            btnChangeWeekendStartTime = findViewById(R.id.btnChangeWeekendStartTime),
            btnChangeWeekendEndTime = findViewById(R.id.btnChangeWeekendEndTime)
        )

        switchLockDateTime = findViewById(R.id.switchLockDateTime)

        timezoneViewBinder = GuardianTimezoneViewBinder(
            activity = this,
            layoutTimezoneHeader = findViewById(R.id.layoutTimezoneHeader),
            ivTimezoneExpand = findViewById(R.id.ivTimezoneExpand),
            layoutTimezoneContent = findViewById(R.id.layoutTimezoneContent),
            tvTimezoneBadge = findViewById(R.id.tvTimezoneBadge),
            tilTimezonePicker = findViewById(R.id.tilTimezonePicker),
            actvTimezonePicker = findViewById(R.id.actvTimezonePicker),
            tvTimezonePreview = findViewById(R.id.tvTimezonePreview),
            btnResetToMakkah = findViewById(R.id.btnResetToMakkah),
            btnAutoDetectTimezone = findViewById(R.id.btnAutoDetectTimezone),
            onTimezoneChanged = { newTz -> viewModel.setTimeZoneId(newTz) }
        )

        switchVpnBlackhole = findViewById(R.id.switchVpnBlackhole)
        switchInstantInternetKill = findViewById(R.id.switchInstantInternetKill)
        switchIncognitoBlocked = findViewById(R.id.switchIncognitoBlocked)
        switchLockNewInstalls = findViewById(R.id.switchLockNewInstalls)
        switchYouTubeRestricted = findViewById(R.id.switchYouTubeRestricted)
        switchTelegramWeb = findViewById(R.id.switchTelegramWeb)

        btnOpenAppManager = findViewById(R.id.btnOpenAppManager)
        tvBrowserFilterStatus = findViewById(R.id.tvBrowserFilterStatus)
        btnManageBrowserUrls = findViewById(R.id.btnManageBrowserUrls)

        tilDnsHost = findViewById(R.id.tilDnsHost)
        etDnsHost = findViewById(R.id.etDnsHost)
        btnPresetControlD = findViewById(R.id.btnPresetControlD)
        btnPresetAdguard = findViewById(R.id.btnPresetAdguard)
        btnPresetCloudflare = findViewById(R.id.btnPresetCloudflare)
        btnPresetNextDns = findViewById(R.id.btnPresetNextDns)

        layoutPendingApps = findViewById(R.id.layoutPendingApps)
        tvNoPendingApps = findViewById(R.id.tvNoPendingApps)
        btnSavePolicyChanges = findViewById(R.id.btnSavePolicyChanges)

        approvalsViewBinder = GuardianApprovalsViewBinder(
            activity = this,
            layoutPendingApps = layoutPendingApps,
            tvNoPendingApps = tvNoPendingApps,
            wardIdProvider = { viewModel.uiState.value.wardId },
            onReloadRequested = { approvalsViewBinder.loadPendingApprovals() }
        )

        setInputsEnabled(false)
    }

    private fun setupListeners() {
        alertsViewBinder.setupListeners()
        curfewViewBinder.setupListeners(
            getStartHour = { viewModel.uiState.value.selectedStartHour },
            getStartMinute = { viewModel.uiState.value.selectedStartMinute },
            onStartTimeChanged = { h, m ->
                val state = viewModel.uiState.value
                viewModel.setCurfewTimings(h, m, state.selectedEndHour, state.selectedEndMinute)
            },
            getEndHour = { viewModel.uiState.value.selectedEndHour },
            getEndMinute = { viewModel.uiState.value.selectedEndMinute },
            onEndTimeChanged = { h, m ->
                val state = viewModel.uiState.value
                viewModel.setCurfewTimings(state.selectedStartHour, state.selectedStartMinute, h, m)
            },
            getWeekendStartHour = { viewModel.uiState.value.selectedWeekendStartHour },
            getWeekendStartMinute = { viewModel.uiState.value.selectedWeekendStartMinute },
            onWeekendStartTimeChanged = { h, m ->
                val state = viewModel.uiState.value
                viewModel.setWeekendCurfewTimings(h, m, state.selectedWeekendEndHour, state.selectedWeekendEndMinute)
            },
            getWeekendEndHour = { viewModel.uiState.value.selectedWeekendEndHour },
            getWeekendEndMinute = { viewModel.uiState.value.selectedWeekendEndMinute },
            onWeekendEndTimeChanged = { h, m ->
                val state = viewModel.uiState.value
                viewModel.setWeekendCurfewTimings(state.selectedWeekendStartHour, state.selectedWeekendStartMinute, h, m)
            }
        )

        timezoneViewBinder.setupListeners(
            getTimeZoneId = { viewModel.uiState.value.selectedTimeZoneId }
        )

        switchAllowSelfControl.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleSelfControl(isChecked)
        }
        switchCurfewMaster.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleCurfew(isChecked)
        }
        switchWeekendCurfew.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleWeekendCurfew(isChecked)
        }
        switchVpnBlackhole.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleVpnBlackhole(isChecked)
        }
        switchInstantInternetKill.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleInstantInternetKill(isChecked)
        }
        switchIncognitoBlocked.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleIncognitoBlocked(isChecked)
        }
        switchLockNewInstalls.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleLockNewInstalls(isChecked)
        }
        switchYouTubeRestricted.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleYouTubeRestricted(isChecked)
        }
        switchTelegramWeb.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleTelegramWeb(isChecked)
        }
        switchLockDateTime.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleLockDateTime(isChecked)
        }

        etDnsHost.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setDnsHost(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnEditWardName.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastEditNameClickTime < 800L) return@setOnClickListener
            lastEditNameClickTime = now
            showEditWardNameDialog()
        }

        var lastOpenAppManagerClickTime = 0L
        btnOpenAppManager.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastOpenAppManagerClickTime < 800L) return@setOnClickListener
            lastOpenAppManagerClickTime = now
            val intent = Intent(this@GuardianControlActivity, RemoteAppManagerActivity::class.java).apply {
                putExtra(RemoteAppManagerActivity.EXTRA_WARD_ID, viewModel.uiState.value.wardId)
                putExtra(RemoteAppManagerActivity.EXTRA_WARD_NAME, viewModel.uiState.value.wardName)
            }
            appManagerLauncher.launch(intent)
        }
        findViewById<View>(R.id.cardAppManager)?.setOnClickListener {
            if (btnOpenAppManager.isEnabled) btnOpenAppManager.performClick()
        }

        var lastBrowserFilterClickTime = 0L
        btnManageBrowserUrls.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBrowserFilterClickTime < 800L) return@setOnClickListener
            lastBrowserFilterClickTime = now

            val state = viewModel.uiState.value
            val intent = Intent(this@GuardianControlActivity, BrowserUrlFilterActivity::class.java).apply {
                putExtra(BrowserUrlFilterActivity.EXTRA_IS_REMOTE, true)
                putExtra(BrowserUrlFilterActivity.EXTRA_WARD_DEVICE_ID, state.wardId)
                putExtra(BrowserUrlFilterActivity.EXTRA_POLICY_JSON, state.currentPolicy.toJson())
            }
            browserFilterLauncher.launch(intent)
        }
        findViewById<View>(R.id.cardBrowserUrlFilter)?.setOnClickListener {
            if (btnManageBrowserUrls.isEnabled) btnManageBrowserUrls.performClick()
        }

        btnPresetControlD.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST)
            tilDnsHost.error = null
        }

        btnPresetAdguard.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST)
            tilDnsHost.error = null
        }

        btnPresetCloudflare.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST)
            tilDnsHost.error = null
        }

        btnPresetNextDns.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.LEGACY_DEFAULT_NEXTDNS_HOST)
            tilDnsHost.error = null
        }

        btnSavePolicyChanges.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSaveClickTime < 800L) return@setOnClickListener
            lastSaveClickTime = now
            viewModel.saveAndPushPolicy(getCleanDnsHost())
        }

        btnRetryLoadPolicy.setOnClickListener {
            btnRetryLoadPolicy.isEnabled = false
            viewModel.loadWardPolicy()
            approvalsViewBinder.loadPendingApprovals()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderUiState(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        handleEvent(event)
                    }
                }
            }
        }
    }

    private fun renderUiState(state: GuardianControlUiState) {
        tvWardName.text = getString(R.string.guardian_control_ward_name, state.wardName)
        cardOfflineBanner.visibility = if (state.isOffline) View.VISIBLE else View.GONE
        setInputsEnabled(!state.isLoading && !state.isSaving)

        // Clear switch listeners to prevent loopback trigger
        switchAllowSelfControl.setOnCheckedChangeListener(null)
        switchCurfewMaster.setOnCheckedChangeListener(null)
        switchWeekendCurfew.setOnCheckedChangeListener(null)
        switchVpnBlackhole.setOnCheckedChangeListener(null)
        switchInstantInternetKill.setOnCheckedChangeListener(null)
        switchIncognitoBlocked.setOnCheckedChangeListener(null)
        switchLockNewInstalls.setOnCheckedChangeListener(null)
        switchYouTubeRestricted.setOnCheckedChangeListener(null)
        switchTelegramWeb.setOnCheckedChangeListener(null)
        switchLockDateTime.setOnCheckedChangeListener(null)

        switchAllowSelfControl.isChecked = state.isSelfControlAllowed
        switchCurfewMaster.isChecked = state.isCurfewEnabled
        switchWeekendCurfew.isChecked = state.isWeekendCurfewEnabled
        switchVpnBlackhole.isChecked = state.isVpnBlackholeEnabled
        switchInstantInternetKill.isChecked = state.isInstantInternetKillEnabled
        switchIncognitoBlocked.isChecked = state.isIncognitoBlocked
        switchLockNewInstalls.isChecked = state.lockNewInstallsUntilApproved
        switchYouTubeRestricted.isChecked = state.isYouTubeRestrictedModeEnabled
        switchTelegramWeb.isChecked = state.isTelegramWebBlocked
        switchLockDateTime.isChecked = state.isDateTimeLocked

        // Re-attach listeners
        switchAllowSelfControl.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleSelfControl(isChecked) }
        switchCurfewMaster.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleCurfew(isChecked) }
        switchWeekendCurfew.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleWeekendCurfew(isChecked) }
        switchVpnBlackhole.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleVpnBlackhole(isChecked) }
        switchInstantInternetKill.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleInstantInternetKill(isChecked) }
        switchIncognitoBlocked.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleIncognitoBlocked(isChecked) }
        switchLockNewInstalls.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleLockNewInstalls(isChecked) }
        switchYouTubeRestricted.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleYouTubeRestricted(isChecked) }
        switchTelegramWeb.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleTelegramWeb(isChecked) }
        switchLockDateTime.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleLockDateTime(isChecked) }

        if (etDnsHost.text?.toString()?.trim() != state.dnsHost) {
            etDnsHost.setText(state.dnsHost)
        }

        curfewViewBinder.updateTimingLabels(
            startHour = state.selectedStartHour,
            startMinute = state.selectedStartMinute,
            endHour = state.selectedEndHour,
            endMinute = state.selectedEndMinute,
            weekendStartHour = state.selectedWeekendStartHour,
            weekendStartMinute = state.selectedWeekendStartMinute,
            weekendEndHour = state.selectedWeekendEndHour,
            weekendEndMinute = state.selectedWeekendEndMinute
        )

        timezoneViewBinder.updateTimezoneViews(state.selectedTimeZoneId)

        tvBrowserFilterStatus.text = when (state.currentPolicy.browserFilterMode) {
            DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST ->
                getString(R.string.browser_filter_status_blocklist, state.currentPolicy.customBlockedUrls.size)
            DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY ->
                getString(R.string.browser_filter_status_whitelist, state.currentPolicy.customAllowedUrls.size)
            else ->
                getString(R.string.browser_filter_status_off)
        }

        alertsViewBinder.renderPolicyAlerts(state.currentPolicy)

        liveSyncViewBinder.updateLiveSyncBadge(
            version = state.version,
            acknowledgedVersion = state.acknowledgedVersion,
            lastAcknowledgedAt = state.lastAcknowledgedAt,
            lastHeartbeat = state.lastHeartbeat
        )

        val dirty = state.hasUnsavedChanges
        btnSavePolicyChanges.isEnabled = state.isSaveEnabled
        btnSavePolicyChanges.alpha = if (dirty) 1.0f else 0.5f
        backPressedCallback.isEnabled = dirty
    }

    private fun handleEvent(event: GuardianControlEvent) {
        when (event) {
            is GuardianControlEvent.ShowToastRes -> {
                val length = if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(this, event.resId, length).show()
            }
            is GuardianControlEvent.ShowToastString -> {
                val length = if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(this, event.message, length).show()
            }
            is GuardianControlEvent.DnsValidationError -> {
                tilDnsHost.error = getString(R.string.dns_probe_error_broken)
                etDnsHost.requestFocus()
                MaterialAlertDialogBuilder(this)
                    .setTitle(event.titleRes)
                    .setMessage(event.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            is GuardianControlEvent.SaveSuccess -> {
                backPressedCallback.isEnabled = false
                finish()
            }
        }
    }

    private fun setInputsEnabled(enabled: Boolean) {
        alertsViewBinder.setInputsEnabled(enabled)
        switchAllowSelfControl.isEnabled = enabled
        switchCurfewMaster.isEnabled = enabled
        curfewViewBinder.setInputsEnabled(enabled)
        switchWeekendCurfew.isEnabled = enabled
        switchVpnBlackhole.isEnabled = enabled
        switchInstantInternetKill.isEnabled = enabled
        switchIncognitoBlocked.isEnabled = enabled
        switchLockNewInstalls.isEnabled = enabled
        switchYouTubeRestricted.isEnabled = enabled
        switchTelegramWeb.isEnabled = enabled
        switchLockDateTime.isEnabled = enabled
        btnOpenAppManager.isEnabled = enabled
        btnManageBrowserUrls.isEnabled = enabled
        findViewById<View>(R.id.cardAppManager)?.isEnabled = enabled
        findViewById<View>(R.id.cardBrowserUrlFilter)?.isEnabled = enabled
        etDnsHost.isEnabled = enabled
        btnPresetControlD.isEnabled = enabled
        btnPresetAdguard.isEnabled = enabled
        btnPresetCloudflare.isEnabled = enabled
        btnPresetNextDns.isEnabled = enabled
        btnRetryLoadPolicy.isEnabled = enabled
        timezoneViewBinder.setInputsEnabled(enabled)
    }

    private fun getCleanDnsHost(): String {
        var rawDns = etDnsHost.text?.toString()?.trim()?.lowercase() ?: ""
        rawDns = rawDns.removePrefix("https://").removePrefix("http://").removePrefix("tls://")
        if (rawDns.contains("/")) rawDns = rawDns.substringBefore("/")
        if (rawDns.contains(":")) rawDns = rawDns.substringBefore(":")
        return rawDns
    }

    private fun showDiscardConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_unsaved_changes_title)
            .setMessage(R.string.dialog_unsaved_changes_msg)
            .setNegativeButton(R.string.btn_keep_editing) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton(R.string.btn_discard_changes) { dialog, _ ->
                dialog.dismiss()
                backPressedCallback.isEnabled = false
                finish()
            }
            .show()
    }

    private fun showEditWardNameDialog() {
        if (isFinishing || isDestroyed) return
        val currentName = viewModel.uiState.value.wardName
        val input = TextInputEditText(this).apply {
            setText(currentName)
            hint = getString(R.string.dialog_edit_ward_name_hint)
            setSingleLine()
            setSelection(text?.length ?: 0)
            filters = arrayOf(android.text.InputFilter.LengthFilter(28))
        }
        val frame = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_edit_ward_name_title)
            .setView(frame)
            .setPositiveButton(R.string.btn_save) { dialog, _ ->
                val newName = input.text?.toString()?.trim() ?: ""
                viewModel.renameWard(newName)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        approvalsViewBinder.listenForPendingApprovals()
        approvalsViewBinder.loadPendingApprovals()
        liveSyncViewBinder.listenForPolicyUpdates()
    }

    override fun onDestroy() {
        approvalsViewBinder.stopListening()
        liveSyncViewBinder.stopListening()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_WARD_NAME = "extra_ward_name"
    }
}
