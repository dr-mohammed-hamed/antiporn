package com.drmohammed.antiporn.ui.accountability

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
import com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.ui.browser.BrowserUrlFilterActivity
import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Control panel enabling a Guardian to configure protection timings, VPN blackhole,
 * browser restrictions, and approve/reject newly installed apps on their Ward's device remotely.
 */
class GuardianControlActivity : AppCompatActivity() {

    private lateinit var wardId: String
    private lateinit var wardName: String
    private var isSavingPolicy = false

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

    // Remote App Manager
    private lateinit var btnOpenAppManager: MaterialButton

    // Browser URL Filter
    private lateinit var tvBrowserFilterStatus: TextView
    private lateinit var btnManageBrowserUrls: MaterialButton

    // DNS controls
    private lateinit var tilDnsHost: TextInputLayout
    private lateinit var etDnsHost: TextInputEditText
    private lateinit var btnPresetAdguard: MaterialButton
    private lateinit var btnPresetCloudflare: MaterialButton
    private lateinit var btnPresetNextDns: MaterialButton

    // Approvals
    private lateinit var layoutPendingApps: LinearLayout
    private lateinit var tvNoPendingApps: TextView
    private lateinit var btnSavePolicyChanges: MaterialButton

    private var currentPolicy = DevicePolicyBundle.defaultPolicy()
    private var initialPolicySnapshot: DevicePolicyBundle? = null

    private var selectedStartHour = 20
    private var selectedStartMinute = 30
    private var selectedEndHour = 7
    private var selectedEndMinute = 0

    private var selectedWeekendStartHour = 20
    private var selectedWeekendStartMinute = 30
    private var selectedWeekendEndHour = 7
    private var selectedWeekendEndMinute = 0
    private var selectedTimeZoneId = CurfewConfig.DEFAULT_TIMEZONE_ID

    private lateinit var approvalsViewBinder: GuardianApprovalsViewBinder
    private lateinit var liveSyncViewBinder: GuardianLiveSyncViewBinder
    private var hasLoadedInitialPolicy = false
    private var lastEditNameClickTime = 0L
    private val policyRepo by lazy { PolicyRepository.getInstance(applicationContext) }
    private var lastSaveClickTime = 0L

    private val appManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadWardPolicy()
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
                currentPolicy = currentPolicy.copy(
                    browserFilterMode = updated.browserFilterMode,
                    customBlockedUrls = updated.customBlockedUrls,
                    customAllowedUrls = updated.customAllowedUrls
                )
                populateUiFromPolicy(currentPolicy)
                updateSaveButtonState()
            }
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (hasUnsavedChanges()) {
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
                view.setPadding(
                    insets.left,
                    insets.top,
                    insets.right,
                    0
                )
                innerBottomBar?.setPadding(
                    baseLeftPadding,
                    baseTopPadding,
                    baseRightPadding,
                    baseBottomPadding + insets.bottom
                )
                windowInsets
            }
        }

        wardId = intent.getStringExtra(EXTRA_WARD_ID) ?: ""
        wardName = intent.getStringExtra(EXTRA_WARD_NAME) ?: getString(R.string.status_ward_badge)

        if (wardId.isBlank()) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        initViews()
        setupListeners()
        approvalsViewBinder.listenForPendingApprovals()
        liveSyncViewBinder.listenForPolicyUpdates()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        if (!hasLoadedInitialPolicy) {
            hasLoadedInitialPolicy = true
            loadWardPolicy()
        }
        approvalsViewBinder.loadPendingApprovals()
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
            wardIdProvider = { wardId }
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
            wardNameProvider = { wardName },
            wardIdProvider = { wardId },
            policyProvider = { currentPolicy },
            isSelfControlAllowedProvider = { switchAllowSelfControl.isChecked },
            onPolicyReverted = { reverted ->
                currentPolicy = reverted
                populateUiFromPolicy(reverted)
                initialPolicySnapshot = reverted.copy()
            },
            onPolicyAcknowledged = { acknowledged ->
                currentPolicy = acknowledged
                initialPolicySnapshot = acknowledged.copy()
            }
        )

        cardSelfControlPermission = findViewById(R.id.cardSelfControlPermission)
        switchAllowSelfControl = findViewById(R.id.switchAllowSelfControl)

        // Curfew controls
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

        // Date & Time Lock control
        switchLockDateTime = findViewById(R.id.switchLockDateTime)

        // Timezone controls
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
            onTimezoneChanged = { newTz ->
                selectedTimeZoneId = newTz
                updateTimezoneViews()
                updateSaveButtonState()
            }
        )

        // Enforcement & Zero Trust
        switchVpnBlackhole = findViewById(R.id.switchVpnBlackhole)
        switchInstantInternetKill = findViewById(R.id.switchInstantInternetKill)
        switchIncognitoBlocked = findViewById(R.id.switchIncognitoBlocked)
        switchLockNewInstalls = findViewById(R.id.switchLockNewInstalls)

        // App Manager
        btnOpenAppManager = findViewById(R.id.btnOpenAppManager)

        // Browser Filter
        tvBrowserFilterStatus = findViewById(R.id.tvBrowserFilterStatus)
        btnManageBrowserUrls = findViewById(R.id.btnManageBrowserUrls)

        // DNS
        tilDnsHost = findViewById(R.id.tilDnsHost)
        etDnsHost = findViewById(R.id.etDnsHost)
        btnPresetAdguard = findViewById(R.id.btnPresetAdguard)
        btnPresetCloudflare = findViewById(R.id.btnPresetCloudflare)
        btnPresetNextDns = findViewById(R.id.btnPresetNextDns)

        // Approvals & Save
        layoutPendingApps = findViewById(R.id.layoutPendingApps)
        tvNoPendingApps = findViewById(R.id.tvNoPendingApps)
        btnSavePolicyChanges = findViewById(R.id.btnSavePolicyChanges)

        approvalsViewBinder = GuardianApprovalsViewBinder(
            activity = this,
            layoutPendingApps = layoutPendingApps,
            tvNoPendingApps = tvNoPendingApps,
            wardIdProvider = { wardId },
            onReloadRequested = { approvalsViewBinder.loadPendingApprovals() }
        )

        updateTimingLabels()
        setInputsEnabled(false)
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
        switchLockDateTime.isEnabled = enabled
        btnOpenAppManager.isEnabled = enabled
        btnManageBrowserUrls.isEnabled = enabled
        findViewById<View>(R.id.cardAppManager)?.isEnabled = enabled
        findViewById<View>(R.id.cardBrowserUrlFilter)?.isEnabled = enabled
        etDnsHost.isEnabled = enabled
        btnPresetAdguard.isEnabled = enabled
        btnPresetCloudflare.isEnabled = enabled
        btnPresetNextDns.isEnabled = enabled
        btnRetryLoadPolicy.isEnabled = enabled
        timezoneViewBinder.setInputsEnabled(enabled)
        updateSaveButtonState()
    }

    private fun getCleanDnsHost(): String {
        var rawDns = etDnsHost.text?.toString()?.trim()?.lowercase() ?: ""
        rawDns = rawDns.removePrefix("https://").removePrefix("http://").removePrefix("tls://")
        if (rawDns.contains("/")) rawDns = rawDns.substringBefore("/")
        if (rawDns.contains(":")) rawDns = rawDns.substringBefore(":")
        return rawDns
    }

    private fun hasUnsavedChanges(): Boolean {
        val snapshot = initialPolicySnapshot ?: return false
        return switchAllowSelfControl.isChecked != snapshot.isSelfControlAllowed ||
                selectedStartHour != snapshot.curfewStartHour ||
                selectedStartMinute != snapshot.curfewStartMinute ||
                selectedEndHour != snapshot.curfewEndHour ||
                selectedEndMinute != snapshot.curfewEndMinute ||
                switchCurfewMaster.isChecked != snapshot.isCurfewEnabled ||
                switchWeekendCurfew.isChecked != snapshot.isWeekendCurfewEnabled ||
                selectedWeekendStartHour != snapshot.weekendCurfewStartHour ||
                selectedWeekendStartMinute != snapshot.weekendCurfewStartMinute ||
                selectedWeekendEndHour != snapshot.weekendCurfewEndHour ||
                selectedWeekendEndMinute != snapshot.weekendCurfewEndMinute ||
                switchVpnBlackhole.isChecked != snapshot.isVpnBlackholeEnabled ||
                switchInstantInternetKill.isChecked != snapshot.isInstantInternetKillEnabled ||
                switchIncognitoBlocked.isChecked != snapshot.isIncognitoBlocked ||
                switchLockNewInstalls.isChecked != snapshot.lockNewInstallsUntilApproved ||
                switchLockDateTime.isChecked != snapshot.isDateTimeLocked ||
                getCleanDnsHost() != snapshot.dnsHost ||
                selectedTimeZoneId != snapshot.timeZoneId ||
                currentPolicy.browserFilterMode != snapshot.browserFilterMode ||
                currentPolicy.customBlockedUrls != snapshot.customBlockedUrls ||
                currentPolicy.customAllowedUrls != snapshot.customAllowedUrls
    }

    private fun updateSaveButtonState() {
        val snapshot = initialPolicySnapshot
        if (snapshot == null) {
            btnSavePolicyChanges.isEnabled = false
            btnSavePolicyChanges.alpha = 0.5f
            backPressedCallback.isEnabled = false
            return
        }
        val dirty = hasUnsavedChanges()
        btnSavePolicyChanges.isEnabled = dirty && !isSavingPolicy
        btnSavePolicyChanges.alpha = if (dirty) 1.0f else 0.5f
        backPressedCallback.isEnabled = dirty
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

    private fun setupListeners() {
        alertsViewBinder.setupListeners()
        curfewViewBinder.setupListeners(
            getStartHour = { selectedStartHour },
            getStartMinute = { selectedStartMinute },
            onStartTimeChanged = { h, m ->
                selectedStartHour = h
                selectedStartMinute = m
                updateTimingLabels()
                updateSaveButtonState()
            },
            getEndHour = { selectedEndHour },
            getEndMinute = { selectedEndMinute },
            onEndTimeChanged = { h, m ->
                selectedEndHour = h
                selectedEndMinute = m
                updateTimingLabels()
                updateSaveButtonState()
            },
            getWeekendStartHour = { selectedWeekendStartHour },
            getWeekendStartMinute = { selectedWeekendStartMinute },
            onWeekendStartTimeChanged = { h, m ->
                selectedWeekendStartHour = h
                selectedWeekendStartMinute = m
                updateTimingLabels()
                updateSaveButtonState()
            },
            getWeekendEndHour = { selectedWeekendEndHour },
            getWeekendEndMinute = { selectedWeekendEndMinute },
            onWeekendEndTimeChanged = { h, m ->
                selectedWeekendEndHour = h
                selectedWeekendEndMinute = m
                updateTimingLabels()
                updateSaveButtonState()
            }
        )

        timezoneViewBinder.setupListeners(
            getTimeZoneId = { selectedTimeZoneId }
        )

        val switchListener = { _: View, _: Boolean -> updateSaveButtonState() }
        switchAllowSelfControl.setOnCheckedChangeListener { _, isChecked ->
            Log.i(TAG, "[SelfControlDiag] Guardian toggled switchAllowSelfControl: isChecked=$isChecked")
            updateSaveButtonState()
        }
        switchCurfewMaster.setOnCheckedChangeListener(switchListener)
        switchWeekendCurfew.setOnCheckedChangeListener(switchListener)
        switchVpnBlackhole.setOnCheckedChangeListener(switchListener)
        switchInstantInternetKill.setOnCheckedChangeListener(switchListener)
        switchIncognitoBlocked.setOnCheckedChangeListener(switchListener)
        switchLockNewInstalls.setOnCheckedChangeListener(switchListener)
        switchLockDateTime.setOnCheckedChangeListener { _, isChecked ->
            // cardTimezoneControl is always visible
            updateSaveButtonState()
        }

        etDnsHost.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSaveButtonState()
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
                putExtra(RemoteAppManagerActivity.EXTRA_WARD_ID, wardId)
                putExtra(RemoteAppManagerActivity.EXTRA_WARD_NAME, wardName)
            }
            appManagerLauncher.launch(intent)
        }
        findViewById<View>(R.id.cardAppManager)?.setOnClickListener { if (btnOpenAppManager.isEnabled) btnOpenAppManager.performClick() }

        var lastBrowserFilterClickTime = 0L
        btnManageBrowserUrls.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBrowserFilterClickTime < 800L) return@setOnClickListener
            lastBrowserFilterClickTime = now

            val intent = Intent(this@GuardianControlActivity, BrowserUrlFilterActivity::class.java).apply {
                putExtra(BrowserUrlFilterActivity.EXTRA_IS_REMOTE, true)
                putExtra(BrowserUrlFilterActivity.EXTRA_WARD_DEVICE_ID, wardId)
                putExtra(BrowserUrlFilterActivity.EXTRA_POLICY_JSON, currentPolicy.toJson())
            }
            browserFilterLauncher.launch(intent)
        }
        findViewById<View>(R.id.cardBrowserUrlFilter)?.setOnClickListener { if (btnManageBrowserUrls.isEnabled) btnManageBrowserUrls.performClick() }

        btnPresetAdguard.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST)
            tilDnsHost.error = null
            updateSaveButtonState()
        }

        btnPresetCloudflare.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST)
            tilDnsHost.error = null
            updateSaveButtonState()
        }

        btnPresetNextDns.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.LEGACY_DEFAULT_NEXTDNS_HOST)
            tilDnsHost.error = null
            updateSaveButtonState()
        }

        btnSavePolicyChanges.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSaveClickTime < 800L) return@setOnClickListener
            lastSaveClickTime = now
            saveAndPushPolicy()
        }

        btnRetryLoadPolicy.setOnClickListener {
            btnRetryLoadPolicy.isEnabled = false
            loadWardPolicy()
            approvalsViewBinder.loadPendingApprovals()
            approvalsViewBinder.listenForPendingApprovals()
        }
    }

    private fun updateTimingLabels() {
        curfewViewBinder.updateTimingLabels(
            startHour = selectedStartHour,
            startMinute = selectedStartMinute,
            endHour = selectedEndHour,
            endMinute = selectedEndMinute,
            weekendStartHour = selectedWeekendStartHour,
            weekendStartMinute = selectedWeekendStartMinute,
            weekendEndHour = selectedWeekendEndHour,
            weekendEndMinute = selectedWeekendEndMinute
        )
    }

    private fun updateTimezoneViews() {
        timezoneViewBinder.updateTimezoneViews(selectedTimeZoneId)
    }

    private fun populateUiFromPolicy(policy: DevicePolicyBundle) {
        selectedStartHour = policy.curfewStartHour
        selectedStartMinute = policy.curfewStartMinute
        selectedEndHour = policy.curfewEndHour
        selectedEndMinute = policy.curfewEndMinute

        selectedWeekendStartHour = policy.weekendCurfewStartHour
        selectedWeekendStartMinute = policy.weekendCurfewStartMinute
        selectedWeekendEndHour = policy.weekendCurfewEndHour
        selectedWeekendEndMinute = policy.weekendCurfewEndMinute

        selectedTimeZoneId = policy.timeZoneId

        updateTimingLabels()
        updateTimezoneViews()

        switchAllowSelfControl.isChecked = policy.isSelfControlAllowed
        switchCurfewMaster.isChecked = policy.isCurfewEnabled
        switchWeekendCurfew.isChecked = policy.isWeekendCurfewEnabled
        switchVpnBlackhole.isChecked = policy.isVpnBlackholeEnabled
        switchInstantInternetKill.isChecked = policy.isInstantInternetKillEnabled
        switchIncognitoBlocked.isChecked = policy.isIncognitoBlocked
        switchLockNewInstalls.isChecked = policy.lockNewInstallsUntilApproved
        switchLockDateTime.isChecked = policy.isDateTimeLocked
        // cardTimezoneControl is always visible
        etDnsHost.setText(policy.dnsHost)
        tilDnsHost.error = null

        tvBrowserFilterStatus.text = when (policy.browserFilterMode) {
            DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST ->
                getString(R.string.browser_filter_status_blocklist, policy.customBlockedUrls.size)
            DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY ->
                getString(R.string.browser_filter_status_whitelist, policy.customAllowedUrls.size)
            else ->
                getString(R.string.browser_filter_status_off)
        }

        alertsViewBinder.renderPolicyAlerts(policy)
    }

    private fun loadWardPolicy() {
        approvalsViewBinder.listenForPendingApprovals()
        liveSyncViewBinder.listenForPolicyUpdates()
        setInputsEnabled(false)
        lifecycleScope.launch(Dispatchers.IO) {
            if (isFinishing || isDestroyed) return@launch

            val database = AntiPornDatabase.getInstance(applicationContext)
            val partner = database.getPartnerById(wardId)
            val resolvedWardId = AccountabilityRepository.getInstance(applicationContext)
                .resolveActivePartnerUid(wardId, partner?.pairingCode)
            if (resolvedWardId.isNotBlank() && resolvedWardId != wardId) {
                Log.i(TAG, "[UidMigration] GuardianControlActivity switched wardId from $wardId to $resolvedWardId")
                withContext(Dispatchers.Main) {
                    wardId = resolvedWardId
                    approvalsViewBinder.listenForPendingApprovals()
                    liveSyncViewBinder.listenForPolicyUpdates()
                }
            }

            var isOffline = false
            var snapshot: com.google.firebase.firestore.DocumentSnapshot? = null
            try {
                val firestore = FirebaseFirestore.getInstance()
                try {
                    snapshot = firestore.collection("policies").document(wardId).get().await()
                } catch (netEx: Exception) {
                    if (netEx is kotlinx.coroutines.CancellationException) throw netEx
                    Log.w(TAG, "Network fetch failed for ward policy $wardId, attempting offline cache", netEx)
                    isOffline = true
                    try {
                        snapshot = firestore.collection("policies").document(wardId).get(Source.CACHE).await()
                    } catch (cacheEx: Exception) {
                        Log.w(TAG, "Cache fetch also failed or empty for ward $wardId", cacheEx)
                    }
                }

                val snapshotData = snapshot?.data
                if (snapshot != null && snapshot.exists() && snapshotData != null) {
                    currentPolicy = DevicePolicyBundle.fromMap(snapshotData)
                } else {
                    currentPolicy = DevicePolicyBundle.defaultPolicy()
                }

                val version = snapshot?.getLong("version") ?: currentPolicy.version
                val acknowledgedVersion = snapshot?.getLong("acknowledgedVersion") ?: 0L
                val lastAcknowledgedAt = snapshot?.getLong("lastAcknowledgedAt") ?: 0L
                val lastHeartbeat = snapshot?.getLong("lastHeartbeat") ?: 0L

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    populateUiFromPolicy(currentPolicy)
                    cardOfflineBanner.visibility = if (isOffline) View.VISIBLE else View.GONE
                    btnRetryLoadPolicy.isEnabled = true
                    liveSyncViewBinder.updateLiveSyncBadge(version, acknowledgedVersion, lastAcknowledgedAt, lastHeartbeat)

                    initialPolicySnapshot = currentPolicy.copy(
                        curfewStartHour = selectedStartHour,
                        curfewStartMinute = selectedStartMinute,
                        curfewEndHour = selectedEndHour,
                        curfewEndMinute = selectedEndMinute,
                        weekendCurfewStartHour = selectedWeekendStartHour,
                        weekendCurfewStartMinute = selectedWeekendStartMinute,
                        weekendCurfewEndHour = selectedWeekendEndHour,
                        weekendCurfewEndMinute = selectedWeekendEndMinute,
                        isCurfewEnabled = switchCurfewMaster.isChecked,
                        isWeekendCurfewEnabled = switchWeekendCurfew.isChecked,
                        isSelfControlAllowed = switchAllowSelfControl.isChecked,
                        isVpnBlackholeEnabled = switchVpnBlackhole.isChecked,
                        isInstantInternetKillEnabled = switchInstantInternetKill.isChecked,
                        isIncognitoBlocked = switchIncognitoBlocked.isChecked,
                        lockNewInstallsUntilApproved = switchLockNewInstalls.isChecked,
                        dnsHost = getCleanDnsHost(),
                        timeZoneId = selectedTimeZoneId
                    )
                    setInputsEnabled(true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isFinishing || isDestroyed) return@launch
                Log.e(TAG, "Error loading ward policy for $wardId", e)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    populateUiFromPolicy(currentPolicy)
                    cardOfflineBanner.visibility = View.VISIBLE
                    btnRetryLoadPolicy.isEnabled = true
                    liveSyncViewBinder.updateLiveSyncBadge(currentPolicy.version, 0L, 0L, 0L)

                    initialPolicySnapshot = currentPolicy.copy(
                        curfewStartHour = selectedStartHour,
                        curfewStartMinute = selectedStartMinute,
                        curfewEndHour = selectedEndHour,
                        curfewEndMinute = selectedEndMinute,
                        weekendCurfewStartHour = selectedWeekendStartHour,
                        weekendCurfewStartMinute = selectedWeekendStartMinute,
                        weekendCurfewEndHour = selectedWeekendEndHour,
                        weekendCurfewEndMinute = selectedWeekendEndMinute,
                        isCurfewEnabled = switchCurfewMaster.isChecked,
                        isWeekendCurfewEnabled = switchWeekendCurfew.isChecked,
                        isSelfControlAllowed = switchAllowSelfControl.isChecked,
                        isVpnBlackholeEnabled = switchVpnBlackhole.isChecked,
                        isInstantInternetKillEnabled = switchInstantInternetKill.isChecked,
                        isIncognitoBlocked = switchIncognitoBlocked.isChecked,
                        lockNewInstallsUntilApproved = switchLockNewInstalls.isChecked,
                        dnsHost = getCleanDnsHost(),
                        timeZoneId = selectedTimeZoneId
                    )
                    setInputsEnabled(true)
                    Toast.makeText(this@GuardianControlActivity, R.string.toast_load_policy_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveAndPushPolicy() {
        if (isFinishing || isDestroyed || isSavingPolicy || !hasUnsavedChanges()) return
        val rawDns = getCleanDnsHost()
        if (!rawDns.matches(Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"))) {
            tilDnsHost.error = getString(R.string.error_invalid_dns_host)
            etDnsHost.requestFocus()
            return
        }
        tilDnsHost.error = null


        isSavingPolicy = true
        setInputsEnabled(false)

        val progressDialog = MaterialAlertDialogBuilder(this@GuardianControlActivity)
            .setTitle(R.string.dns_probe_progress_title)
            .setMessage(R.string.dns_probe_progress_message)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            if (isFinishing || isDestroyed) {
                isSavingPolicy = false
                return@launch
            }
            try {
                // Pre-flight DNS validation if DNS host is not a verified preset
                val validationResult = withContext(Dispatchers.IO) {
                    DnsFilteringValidator.validateDnsHost(rawDns)
                }

                if (isFinishing || isDestroyed) return@launch
                if (progressDialog.isShowing) {
                    try { progressDialog.dismiss() } catch (e: Exception) { Log.d(TAG, "Dismiss progressDialog non-critical: ${e.message}") }
                }

                when (validationResult) {
                    is DnsFilteringValidator.ValidationResult.Valid -> {
                        // All checks passed! Proceed to save policy
                    }
                    is DnsFilteringValidator.ValidationResult.UnfilteredPorn -> {
                        isSavingPolicy = false
                        setInputsEnabled(true)
                        tilDnsHost.error = getString(R.string.dns_probe_error_not_blocking)
                        etDnsHost.requestFocus()
                        MaterialAlertDialogBuilder(this@GuardianControlActivity)
                            .setTitle(R.string.dns_probe_dialog_failed_title)
                            .setMessage(getString(R.string.dns_probe_error_not_blocking_detail, validationResult.domain, validationResult.resolvedIp))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        return@launch
                    }
                    is DnsFilteringValidator.ValidationResult.Unreachable -> {
                        isSavingPolicy = false
                        setInputsEnabled(true)
                        tilDnsHost.error = getString(R.string.dns_probe_error_unreachable)
                        etDnsHost.requestFocus()
                        MaterialAlertDialogBuilder(this@GuardianControlActivity)
                            .setTitle(R.string.dns_probe_dialog_failed_title)
                            .setMessage(getString(R.string.dns_probe_error_unreachable_detail, validationResult.reason))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        return@launch
                    }
                    is DnsFilteringValidator.ValidationResult.ResolverBroken -> {
                        isSavingPolicy = false
                        setInputsEnabled(true)
                        tilDnsHost.error = getString(R.string.dns_probe_error_broken)
                        etDnsHost.requestFocus()
                        MaterialAlertDialogBuilder(this@GuardianControlActivity)
                            .setTitle(R.string.dns_probe_dialog_failed_title)
                            .setMessage(getString(R.string.dns_probe_error_broken_detail, validationResult.reason))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        return@launch
                    }
                }

                val policyRepo = PolicyRepository.getInstance(applicationContext)
                val firestore = FirebaseFirestore.getInstance()
                val latestPolicy = try {
                    val snap = firestore.collection("policies").document(wardId).get().await()
                    if (snap != null && snap.exists() && snap.data != null) {
                        DevicePolicyBundle.fromMap(snap.data ?: emptyMap())
                    } else {
                        currentPolicy
                    }
                } catch (netEx: Exception) {
                    if (netEx is kotlinx.coroutines.CancellationException) throw netEx
                    Log.w(TAG, "Failed fetching latest policy before push, using current snapshot", netEx)
                    currentPolicy
                }

                val myUid = AccountabilityRepository.getInstance(applicationContext).getCachedUid()
                val myName = AccountabilityRepository.getInstance(applicationContext).getMyDisplayName()
                Log.i(TAG, "[SelfControlDiag] Guardian saving policy for wardId='$wardId': isSelfControlAllowed=${switchAllowSelfControl.isChecked}, version=${latestPolicy.version + 1}, myUid='$myUid'")
                val updatedPolicy = latestPolicy.copy(
                    guardianUid = latestPolicy.guardianUid?.takeIf { it.isNotBlank() } ?: myUid,
                    guardianName = latestPolicy.guardianName?.takeIf { it.isNotBlank() } ?: myName,
                    isSupervised = true,
                    curfewStartHour = selectedStartHour,
                    curfewStartMinute = selectedStartMinute,
                    curfewEndHour = selectedEndHour,
                    curfewEndMinute = selectedEndMinute,
                    isCurfewEnabled = switchCurfewMaster.isChecked,
                    isInstantInternetKillEnabled = switchInstantInternetKill.isChecked,
                    isWeekendCurfewEnabled = switchWeekendCurfew.isChecked,
                    weekendCurfewStartHour = selectedWeekendStartHour,
                    weekendCurfewStartMinute = selectedWeekendStartMinute,
                    weekendCurfewEndHour = selectedWeekendEndHour,
                    weekendCurfewEndMinute = selectedWeekendEndMinute,
                    isVpnBlackholeEnabled = switchVpnBlackhole.isChecked,
                    isIncognitoBlocked = switchIncognitoBlocked.isChecked,
                    lockNewInstallsUntilApproved = switchLockNewInstalls.isChecked,
                    isSelfControlAllowed = switchAllowSelfControl.isChecked,
                    isDateTimeLocked = switchLockDateTime.isChecked,
                    browserFilterMode = currentPolicy.browserFilterMode,
                    customBlockedUrls = currentPolicy.customBlockedUrls,
                    customAllowedUrls = currentPolicy.customAllowedUrls,
                    dnsHost = rawDns,
                    timeZoneId = selectedTimeZoneId,
                    version = latestPolicy.version + 1,
                    guardianPolicySnapshot = null,
                    lastSelfModifiedAt = null,
                    updatedAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(applicationContext)
                )
                val success = policyRepo.pushPolicyToWard(wardId, updatedPolicy)
                Log.i(TAG, "[SelfControlDiag] pushPolicyToWard finished: success=$success for wardId='$wardId'")
                if (isFinishing || isDestroyed) return@launch
                if (success) {
                    alertsViewBinder.hideSelfControlAlert()
                    initialPolicySnapshot = updatedPolicy.copy()
                    liveSyncViewBinder.updateLiveSyncBadge(
                        version = updatedPolicy.version,
                        acknowledgedVersion = 0L,
                        lastAcknowledgedAt = 0L,
                        lastHeartbeat = 0L
                    )
                    backPressedCallback.isEnabled = false
                    Toast.makeText(this@GuardianControlActivity, R.string.toast_policy_saved, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this@GuardianControlActivity, getString(R.string.toast_policy_push_failed), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isFinishing || isDestroyed) return@launch
                Log.e(TAG, "Error pushing policy to ward", e)
                Toast.makeText(this@GuardianControlActivity, getString(R.string.toast_policy_push_failed), Toast.LENGTH_LONG).show()
            } finally {
                if (progressDialog.isShowing) {
                    try { progressDialog.dismiss() } catch (e: Exception) { Log.d(TAG, "Dismiss progressDialog non-critical: ${e.message}") }
                }
                isSavingPolicy = false
                if (!isFinishing && !isDestroyed) {
                    setInputsEnabled(true)
                }
            }
        }
    }

    private fun showEditWardNameDialog() {
        if (isFinishing || isDestroyed) return
        val input = TextInputEditText(this).apply {
            setText(wardName)
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
                if (newName.isNotBlank() && newName != wardName) {
                    lifecycleScope.launch {
                        val repository = com.drmohammed.antiporn.data.repository.AccountabilityRepository.getInstance(applicationContext)
                        val result = repository.renamePartner(wardId, newName)
                        if (isFinishing || isDestroyed) return@launch
                        result.onSuccess {
                            wardName = newName
                            tvWardName.text = getString(R.string.guardian_control_ward_name, wardName)
                            Toast.makeText(this@GuardianControlActivity, R.string.toast_name_saved, Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            Log.e(TAG, "Failed to rename ward", e)
                            Toast.makeText(this@GuardianControlActivity, getString(R.string.toast_partner_failed, e.localizedMessage), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    override fun onDestroy() {
        approvalsViewBinder.stopListening()
        liveSyncViewBinder.stopListening()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AntiPorn_GuardianCtrl"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_WARD_NAME = "extra_ward_name"
    }
}
