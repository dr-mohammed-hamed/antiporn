package com.drmohammed.antiporn.ui.control

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ui.accountability.RemoteAppManagerActivity
import com.drmohammed.antiporn.ui.browser.BrowserUrlFilterActivity
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.qr.QrExportDialog
import com.drmohammed.antiporn.ui.control.qr.QrScannerBottomSheet
import com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Self Control Fragment embedded in the main navigation hub.
 * Enables the device owner to manage curfew timings, suspensions, DNS hosts, and pending approvals directly.
 */
class SelfControlFragment : Fragment() {

    private var toolbar: MaterialToolbar? = null

    // Curfew controls
    private var switchCurfewMaster: MaterialSwitch? = null
    private var tvCurfewStartTime: TextView? = null
    private var tvCurfewEndTime: TextView? = null
    private var btnChangeStartTime: MaterialButton? = null
    private var btnChangeEndTime: MaterialButton? = null

    // Weekend curfew controls
    private var switchWeekendCurfew: MaterialSwitch? = null
    private var tvWeekendStartTime: TextView? = null
    private var tvWeekendEndTime: TextView? = null
    private var btnChangeWeekendStartTime: MaterialButton? = null
    private var btnChangeWeekendEndTime: MaterialButton? = null

    // Date & Time Lock control
    private var switchLockDateTime: MaterialSwitch? = null

    // Timezone controls
    private var cardTimezoneControl: MaterialCardView? = null
    private var layoutTimezoneHeader: View? = null
    private var tvTimezoneTitle: TextView? = null
    private var tvTimezoneSubtitle: TextView? = null
    private var tvTimezoneBadge: TextView? = null
    private var ivTimezoneExpand: ImageView? = null
    private var layoutTimezoneContent: LinearLayout? = null
    private var tilTimezonePicker: TextInputLayout? = null
    private var actvTimezonePicker: MaterialAutoCompleteTextView? = null
    private var tvTimezonePreview: TextView? = null
    private var btnResetToMakkah: MaterialButton? = null

    // DNS controls
    private var tilDnsHost: TextInputLayout? = null
    private var etDnsHost: TextInputEditText? = null
    private var btnPresetAdguard: MaterialButton? = null
    private var btnPresetCloudflare: MaterialButton? = null
    private var btnPresetNextDns: MaterialButton? = null

    // Enforcement & Security controls
    private var switchVpnBlackhole: MaterialSwitch? = null
    private var switchInstantInternetKill: MaterialSwitch? = null
    private var switchIncognitoBlocked: MaterialSwitch? = null
    private var switchLockNewInstalls: MaterialSwitch? = null

    // Local App Manager
    private var btnOpenLocalAppManager: MaterialButton? = null

    // Browser URL Filter
    private var tvBrowserFilterStatus: TextView? = null
    private var btnManageBrowserUrls: MaterialButton? = null

    // Approvals
    private var layoutPendingApps: LinearLayout? = null
    private var tvNoPendingApps: TextView? = null

    // QR Lock controls
    private var switchQrLockEnabled: MaterialSwitch? = null
    private var layoutQrActionButtons: View? = null
    private var btnShowQrCode: MaterialButton? = null
    private var btnRegenerateQrCode: MaterialButton? = null
    private var isQrPolicyLoading = false

    // Bottom Bar
    private var btnSaveSelfControlChanges: MaterialButton? = null

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
    private var selectedTimeZoneId = CurfewConfig.MAKKAH_TIMEZONE_ID

    private var isSavingPolicy = false

    private val browserFilterLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!isAdded || view == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val ctx = context?.applicationContext ?: return@registerForActivityResult
            val latest = PolicyRepository.getInstance(ctx).getCurrentPolicy()
            currentPolicy = currentPolicy.copy(
                browserFilterMode = latest.browserFilterMode,
                customBlockedUrls = latest.customBlockedUrls,
                customAllowedUrls = latest.customAllowedUrls
            )
            initialPolicySnapshot = initialPolicySnapshot?.copy(
                browserFilterMode = latest.browserFilterMode,
                customBlockedUrls = latest.customBlockedUrls,
                customAllowedUrls = latest.customAllowedUrls
            )
            updateBrowserFilterStatus(latest)
            updateSaveButtonState()
        }
    }

    private val appManagerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!isAdded || view == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            reloadPackageCollectionsOnly()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.activity_self_control, container, false)
        initViews(root)
        setupListeners()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadCurrentPolicy()
        loadPendingApprovals()
    }

    override fun onResume() {
        super.onResume()
        checkGateAndRefresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            checkGateAndRefresh()
        }
    }

    private fun checkGateAndRefresh() {
        val ctx = context ?: return
        val gate = SelfControlAccessGateRegistry.getActiveGate()
        if (!gate.canAccess(ctx)) {
            Toast.makeText(ctx, R.string.gate_access_denied, Toast.LENGTH_SHORT).show()
            (activity as? com.drmohammed.antiporn.MainActivity)?.navigateToTab(R.id.nav_home)
            return
        }
        loadPendingApprovals()
    }

    private fun initViews(root: View) {
        toolbar = root.findViewById(R.id.toolbarSelfControl)
        toolbar?.navigationIcon = null

        switchCurfewMaster = root.findViewById(R.id.switchCurfewMaster)
        tvCurfewStartTime = root.findViewById(R.id.tvCurfewStartTime)
        tvCurfewEndTime = root.findViewById(R.id.tvCurfewEndTime)
        btnChangeStartTime = root.findViewById(R.id.btnChangeStartTime)
        btnChangeEndTime = root.findViewById(R.id.btnChangeEndTime)

        switchWeekendCurfew = root.findViewById(R.id.switchWeekendCurfew)
        tvWeekendStartTime = root.findViewById(R.id.tvWeekendStartTime)
        tvWeekendEndTime = root.findViewById(R.id.tvWeekendEndTime)
        btnChangeWeekendStartTime = root.findViewById(R.id.btnChangeWeekendStartTime)
        btnChangeWeekendEndTime = root.findViewById(R.id.btnChangeWeekendEndTime)

        switchLockDateTime = root.findViewById(R.id.switchLockDateTime)
        cardTimezoneControl = root.findViewById(R.id.cardTimezoneControl)
        layoutTimezoneHeader = root.findViewById(R.id.layoutTimezoneHeader)
        tvTimezoneTitle = root.findViewById(R.id.tvTimezoneTitle)
        tvTimezoneSubtitle = root.findViewById(R.id.tvTimezoneSubtitle)
        tvTimezoneBadge = root.findViewById(R.id.tvTimezoneBadge)
        ivTimezoneExpand = root.findViewById(R.id.ivTimezoneExpand)
        layoutTimezoneContent = root.findViewById(R.id.layoutTimezoneContent)
        tilTimezonePicker = root.findViewById(R.id.tilTimezonePicker)
        actvTimezonePicker = root.findViewById(R.id.actvTimezonePicker)
        tvTimezonePreview = root.findViewById(R.id.tvTimezonePreview)
        btnResetToMakkah = root.findViewById(R.id.btnResetToMakkah)

        val ctx = root.context
        val timezoneOptions = IslamicTimeZoneRegistry.getAllOptions()
        val tzAdapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_dropdown_item_1line,
            timezoneOptions
        )
        actvTimezonePicker?.setAdapter(tzAdapter)

        tilDnsHost = root.findViewById(R.id.tilDnsHost)
        etDnsHost = root.findViewById(R.id.etDnsHost)
        btnPresetAdguard = root.findViewById(R.id.btnPresetAdguard)
        btnPresetCloudflare = root.findViewById(R.id.btnPresetCloudflare)
        btnPresetNextDns = root.findViewById(R.id.btnPresetNextDns)

        switchVpnBlackhole = root.findViewById(R.id.switchVpnBlackhole)
        switchInstantInternetKill = root.findViewById(R.id.switchInstantInternetKill)
        switchIncognitoBlocked = root.findViewById(R.id.switchIncognitoBlocked)
        switchLockNewInstalls = root.findViewById(R.id.switchLockNewInstalls)

        btnOpenLocalAppManager = root.findViewById(R.id.btnOpenLocalAppManager)

        tvBrowserFilterStatus = root.findViewById(R.id.tvBrowserFilterStatus)
        btnManageBrowserUrls = root.findViewById(R.id.btnManageBrowserUrls)

        switchQrLockEnabled = root.findViewById(R.id.switchQrLockEnabled)
        layoutQrActionButtons = root.findViewById(R.id.layoutQrActionButtons)
        btnShowQrCode = root.findViewById(R.id.btnShowQrCode)
        btnRegenerateQrCode = root.findViewById(R.id.btnRegenerateQrCode)

        layoutPendingApps = root.findViewById(R.id.layoutPendingApps)
        tvNoPendingApps = root.findViewById(R.id.tvNoPendingApps)

        btnSaveSelfControlChanges = root.findViewById(R.id.btnSaveSelfControlChanges)
    }

    private fun showQrScannerWithFallback(tag: String, onAuthorized: () -> Unit) {
        if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return
        childFragmentManager.setFragmentResultListener(tag, viewLifecycleOwner) { _, bundle ->
            val verified = bundle.getBoolean(QrScannerBottomSheet.RESULT_KEY_VERIFIED, false)
            val bypass = bundle.getBoolean(QrScannerBottomSheet.RESULT_KEY_EMERGENCY_BYPASS, false)
            if (verified || bypass) {
                onAuthorized()
            }
        }
        QrScannerBottomSheet.newInstance(tag).apply {
            onVerified = { onAuthorized() }
            onEmergencyBypassTriggered = { onAuthorized() }
        }.show(childFragmentManager, tag)
    }

    private fun setupListeners() {
        switchQrLockEnabled?.setOnCheckedChangeListener { _, isChecked ->
            if (isQrPolicyLoading) return@setOnCheckedChangeListener
            val ctx = context?.applicationContext ?: return@setOnCheckedChangeListener
            val qrManager = SelfControlQrManager.getInstance(ctx)
            if (isChecked) {
                if (!qrManager.hasToken()) {
                    qrManager.generateAndSaveNewToken()
                }
                qrManager.setQrLockEnabled(true)
                layoutQrActionButtons?.isVisible = true
                if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag(QrExportDialog.TAG) == null) {
                    QrExportDialog.newInstance().show(childFragmentManager, QrExportDialog.TAG)
                }
            } else {
                if (qrManager.isQrLockEnabled()) {
                    isQrPolicyLoading = true
                    switchQrLockEnabled?.isChecked = true
                    isQrPolicyLoading = false

                    val tag = "qr_scanner_disable_barrier"
                    if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return@setOnCheckedChangeListener
                    showQrScannerWithFallback(tag) {
                        qrManager.setQrLockEnabled(false)
                        isQrPolicyLoading = true
                        switchQrLockEnabled?.isChecked = false
                        layoutQrActionButtons?.isVisible = false
                        isQrPolicyLoading = false
                        Toast.makeText(context ?: ctx, R.string.self_control_qr_disabled_toast, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    layoutQrActionButtons?.isVisible = false
                }
            }
        }

        btnShowQrCode?.setOnClickListener {
            val ctx = context?.applicationContext ?: return@setOnClickListener
            val qrManager = SelfControlQrManager.getInstance(ctx)
            if (!qrManager.hasToken()) {
                qrManager.generateAndSaveNewToken()
            }
            fun showExportDialog() {
                if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag(QrExportDialog.TAG) == null) {
                    QrExportDialog.newInstance().show(childFragmentManager, QrExportDialog.TAG)
                }
            }
            if (qrManager.isQrLockEnabled()) {
                val tag = "qr_scanner_show_code"
                if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
                showQrScannerWithFallback(tag) {
                    showExportDialog()
                }
            } else {
                showExportDialog()
            }
        }

        btnRegenerateQrCode?.setOnClickListener {
            val ctx = context?.applicationContext ?: return@setOnClickListener
            val qrManager = SelfControlQrManager.getInstance(ctx)
            fun proceedWithRegeneration() {
                val act = activity ?: return
                MaterialAlertDialogBuilder(act)
                    .setTitle(R.string.self_control_qr_regenerate_confirm_title)
                    .setMessage(R.string.self_control_qr_regenerate_confirm_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.self_control_qr_btn_regenerate_confirm) { _, _ ->
                        qrManager.generateAndSaveNewToken()
                        qrManager.setQrLockEnabled(true)
                        isQrPolicyLoading = true
                        switchQrLockEnabled?.isChecked = true
                        layoutQrActionButtons?.isVisible = true
                        isQrPolicyLoading = false
                        if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag(QrExportDialog.TAG) == null) {
                            QrExportDialog.newInstance().show(childFragmentManager, QrExportDialog.TAG)
                        }
                    }
                    .show()
            }

            if (qrManager.isQrLockEnabled()) {
                val tag = "qr_scanner_regenerate_auth"
                if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
                showQrScannerWithFallback(tag) {
                    proceedWithRegeneration()
                }
            } else {
                proceedWithRegeneration()
            }
        }
        btnChangeStartTime?.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val is24Hour = android.text.format.DateFormat.is24HourFormat(ctx)
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(if (is24Hour) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                .setHour(selectedStartHour)
                .setMinute(selectedStartMinute)
                .setTitleText(R.string.guardian_curfew_start_label)
                .build()

            picker.addOnPositiveButtonClickListener {
                selectedStartHour = picker.hour
                selectedStartMinute = picker.minute
                updateTimingLabels()
                updateSaveButtonState()
            }
            val tag = "weekday_start_picker"
            if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
            picker.show(childFragmentManager, tag)
        }

        btnChangeEndTime?.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val is24Hour = android.text.format.DateFormat.is24HourFormat(ctx)
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(if (is24Hour) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                .setHour(selectedEndHour)
                .setMinute(selectedEndMinute)
                .setTitleText(R.string.guardian_curfew_end_label)
                .build()

            picker.addOnPositiveButtonClickListener {
                selectedEndHour = picker.hour
                selectedEndMinute = picker.minute
                updateTimingLabels()
                updateSaveButtonState()
            }
            val tag = "weekday_end_picker"
            if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
            picker.show(childFragmentManager, tag)
        }

        btnChangeWeekendStartTime?.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val is24Hour = android.text.format.DateFormat.is24HourFormat(ctx)
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(if (is24Hour) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                .setHour(selectedWeekendStartHour)
                .setMinute(selectedWeekendStartMinute)
                .setTitleText(R.string.guardian_weekend_curfew_start_label)
                .build()

            picker.addOnPositiveButtonClickListener {
                selectedWeekendStartHour = picker.hour
                selectedWeekendStartMinute = picker.minute
                updateTimingLabels()
                updateSaveButtonState()
            }
            val tag = "weekend_start_picker"
            if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
            picker.show(childFragmentManager, tag)
        }

        btnChangeWeekendEndTime?.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val is24Hour = android.text.format.DateFormat.is24HourFormat(ctx)
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(if (is24Hour) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                .setHour(selectedWeekendEndHour)
                .setMinute(selectedWeekendEndMinute)
                .setTitleText(R.string.guardian_weekend_curfew_end_label)
                .build()

            picker.addOnPositiveButtonClickListener {
                selectedWeekendEndHour = picker.hour
                selectedWeekendEndMinute = picker.minute
                updateTimingLabels()
                updateSaveButtonState()
            }
            val tag = "weekend_end_picker"
            if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
            picker.show(childFragmentManager, tag)
        }

        layoutTimezoneHeader?.setOnClickListener {
            val isExpanded = layoutTimezoneContent?.isVisible == true
            if (isExpanded) {
                layoutTimezoneContent?.visibility = View.GONE
                ivTimezoneExpand?.animate()?.rotation(0f)?.setDuration(200)?.start()
            } else {
                layoutTimezoneContent?.visibility = View.VISIBLE
                ivTimezoneExpand?.animate()?.rotation(180f)?.setDuration(200)?.start()
            }
        }

        actvTimezonePicker?.setOnItemClickListener { parent, _, position, _ ->
            val option = parent.getItemAtPosition(position) as? IslamicTimeZoneRegistry.TimeZoneOption
            if (option != null && option.id != selectedTimeZoneId) {
                selectedTimeZoneId = option.id
                updateTimezoneViews()
                updateSaveButtonState()
            }
        }

        btnResetToMakkah?.setOnClickListener {
            if (selectedTimeZoneId != CurfewConfig.MAKKAH_TIMEZONE_ID) {
                selectedTimeZoneId = CurfewConfig.MAKKAH_TIMEZONE_ID
                updateTimezoneViews()
                updateSaveButtonState()
            }
        }

        btnPresetAdguard?.setOnClickListener {
            etDnsHost?.setText(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST)
        }
        btnPresetCloudflare?.setOnClickListener {
            etDnsHost?.setText(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST)
        }
        btnPresetNextDns?.setOnClickListener {
            etDnsHost?.setText(DevicePolicyBundle.LEGACY_DEFAULT_NEXTDNS_HOST)
        }

        val switchListener = { _: View, _: Boolean -> updateSaveButtonState() }
        switchCurfewMaster?.setOnCheckedChangeListener(switchListener)
        switchWeekendCurfew?.setOnCheckedChangeListener(switchListener)
        switchVpnBlackhole?.setOnCheckedChangeListener(switchListener)
        switchInstantInternetKill?.setOnCheckedChangeListener(switchListener)
        switchIncognitoBlocked?.setOnCheckedChangeListener(switchListener)
        switchLockNewInstalls?.setOnCheckedChangeListener(switchListener)
        switchLockDateTime?.setOnCheckedChangeListener { _, isChecked ->
            cardTimezoneControl?.isVisible = isChecked
            updateSaveButtonState()
        }

        etDnsHost?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSaveButtonState()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        var lastAppManagerClickTime = 0L
        btnOpenLocalAppManager?.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAppManagerClickTime < 800L) return@setOnClickListener
            lastAppManagerClickTime = now

            val ctx = context ?: return@setOnClickListener
            val launchAction = {
                val intent = Intent(ctx, RemoteAppManagerActivity::class.java).apply {
                    putExtra(RemoteAppManagerActivity.EXTRA_IS_LOCAL, true)
                }
                appManagerLauncher.launch(intent)
            }

            val qrManager = SelfControlQrManager.getInstance(ctx)
            if (qrManager.isQrLockEnabled()) {
                showQrScannerWithFallback("qr_scanner_app_mgr") { launchAction() }
            } else {
                launchAction()
            }
        }

        var lastBrowserFilterClickTime = 0L
        btnManageBrowserUrls?.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBrowserFilterClickTime < 800L) return@setOnClickListener
            lastBrowserFilterClickTime = now

            val ctx = context ?: return@setOnClickListener
            val launchAction = {
                val intent = Intent(ctx, BrowserUrlFilterActivity::class.java).apply {
                    putExtra(BrowserUrlFilterActivity.EXTRA_IS_REMOTE, false)
                    putExtra(BrowserUrlFilterActivity.EXTRA_POLICY_JSON, currentPolicy.toJson())
                }
                browserFilterLauncher.launch(intent)
            }

            val qrManager = SelfControlQrManager.getInstance(ctx)
            if (qrManager.isQrLockEnabled()) {
                showQrScannerWithFallback("qr_scanner_browser_filter") { launchAction() }
            } else {
                launchAction()
            }
        }

        btnSaveSelfControlChanges?.setOnClickListener {
            saveChanges()
        }
    }

    private fun loadCurrentPolicy() {
        val ctx = context?.applicationContext ?: return
        val policy = PolicyRepository.getInstance(ctx).getCurrentPolicy()
        currentPolicy = policy
        initialPolicySnapshot = policy

        selectedStartHour = policy.curfewStartHour
        selectedStartMinute = policy.curfewStartMinute
        selectedEndHour = policy.curfewEndHour
        selectedEndMinute = policy.curfewEndMinute

        selectedWeekendStartHour = policy.weekendCurfewStartHour
        selectedWeekendStartMinute = policy.weekendCurfewStartMinute
        selectedWeekendEndHour = policy.weekendCurfewEndHour
        selectedWeekendEndMinute = policy.weekendCurfewEndMinute
        selectedTimeZoneId = policy.timeZoneId.ifBlank { CurfewConfig.MAKKAH_TIMEZONE_ID }

        switchCurfewMaster?.isChecked = policy.isCurfewEnabled
        switchWeekendCurfew?.isChecked = policy.isWeekendCurfewEnabled
        switchVpnBlackhole?.isChecked = policy.isVpnBlackholeEnabled
        switchInstantInternetKill?.isChecked = policy.isInstantInternetKillEnabled
        switchIncognitoBlocked?.isChecked = policy.isIncognitoBlocked
        switchLockNewInstalls?.isChecked = policy.lockNewInstallsUntilApproved
        switchLockDateTime?.isChecked = policy.isDateTimeLocked
        cardTimezoneControl?.isVisible = policy.isDateTimeLocked

        etDnsHost?.setText(policy.dnsHost)
        updateBrowserFilterStatus(policy)
        updateTimingLabels()
        updateTimezoneViews()

        val qrManager = SelfControlQrManager.getInstance(ctx)
        val isQrEnabled = qrManager.isQrLockEnabled()
        isQrPolicyLoading = true
        switchQrLockEnabled?.isChecked = isQrEnabled
        layoutQrActionButtons?.isVisible = isQrEnabled
        isQrPolicyLoading = false

        updateSaveButtonState()
    }

    private fun reloadPackageCollectionsOnly() {
        val ctx = context?.applicationContext ?: return
        val latestPolicy = PolicyRepository.getInstance(ctx).getCurrentPolicy()
        currentPolicy = currentPolicy.copy(
            customBlacklistPackages = latestPolicy.customBlacklistPackages,
            customCurfewWhitelistPackages = latestPolicy.customCurfewWhitelistPackages,
            protectedAppPackages = latestPolicy.protectedAppPackages
        )
        initialPolicySnapshot = initialPolicySnapshot?.copy(
            customBlacklistPackages = latestPolicy.customBlacklistPackages,
            customCurfewWhitelistPackages = latestPolicy.customCurfewWhitelistPackages,
            protectedAppPackages = latestPolicy.protectedAppPackages
        )
        updateSaveButtonState()
    }

    private fun updateBrowserFilterStatus(policy: DevicePolicyBundle) {
        tvBrowserFilterStatus?.text = when (policy.browserFilterMode) {
            DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST ->
                getString(R.string.browser_filter_status_blocklist, policy.customBlockedUrls.size)
            DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY ->
                getString(R.string.browser_filter_status_whitelist, policy.customAllowedUrls.size)
            else ->
                getString(R.string.browser_filter_status_off)
        }
    }

    private fun updateTimingLabels() {
        val ctx = context ?: return
        val is24Hour = android.text.format.DateFormat.is24HourFormat(ctx)
        val cal = java.util.Calendar.getInstance()

        fun formatTime(h: Int, m: Int): String {
            return if (is24Hour) {
                String.format(Locale.US, "%02d:%02d", h, m)
            } else {
                cal.set(java.util.Calendar.HOUR_OF_DAY, h)
                cal.set(java.util.Calendar.MINUTE, m)
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time)
            }
        }

        tvCurfewStartTime?.text = formatTime(selectedStartHour, selectedStartMinute)
        tvCurfewEndTime?.text = formatTime(selectedEndHour, selectedEndMinute)
        tvWeekendStartTime?.text = formatTime(selectedWeekendStartHour, selectedWeekendStartMinute)
        tvWeekendEndTime?.text = formatTime(selectedWeekendEndHour, selectedWeekendEndMinute)
    }

    private fun updateTimezoneViews() {
        val option = IslamicTimeZoneRegistry.findByIanaId(selectedTimeZoneId)
        val cityName = option.majorCities.split("/").firstOrNull()?.trim() ?: option.majorCities
        tvTimezoneBadge?.text = getString(R.string.guardian_timezone_badge_format, option.gmtOffsetLabel, cityName)
        actvTimezonePicker?.setText(option.displayText, false)

        try {
            val tz = TimeZone.getTimeZone(option.ianaTimeZoneId)
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault()).apply {
                timeZone = tz
            }
            val formattedTime = sdf.format(Date())
            val timeWithOffset = getString(R.string.guardian_timezone_preview_time_format, formattedTime, option.gmtOffsetLabel)
            tvTimezonePreview?.text = getString(
                R.string.guardian_timezone_preview_format,
                timeWithOffset
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed formatting timezone preview: ${e.message}", e)
            tvTimezonePreview?.text = getString(
                R.string.guardian_timezone_preview_format,
                option.gmtOffsetLabel
            )
        }
    }

    private fun loadPendingApprovals() {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val approvalRepo = ApprovalRepository.getInstance(ctx)
            val pendingList = approvalRepo.getLocalPendingApprovals()
                .filter { !it.isApproved && !it.isRejected }

            val pm = ctx.packageManager
            val items = pendingList.map { entity ->
                val appName = try {
                    val appInfo = pm.getApplicationInfo(entity.packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    entity.packageName
                }
                Pair(entity.packageName, appName)
            }

            withContext(Dispatchers.Main) {
                if (!isAdded || view == null) return@withContext
                renderPendingApprovals(items)
            }
        }
    }

    private fun renderPendingApprovals(items: List<Pair<String, String>>) {
        val containerLayout = layoutPendingApps ?: return
        val noPendingText = tvNoPendingApps ?: return
        val ctx = context ?: return

        containerLayout.removeAllViews()
        if (items.isEmpty()) {
            containerLayout.addView(noPendingText)
            noPendingText.visibility = View.VISIBLE
            return
        }
        noPendingText.visibility = View.GONE

        for ((pkg, name) in items) {
            val card = MaterialCardView(ctx).apply {
                radius = resources.displayMetrics.density * 10
                cardElevation = resources.displayMetrics.density * 1
                setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh))
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
                strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
            }

            val cardInner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            val titleView = TextView(ctx).apply {
                text = name
                textSize = 14f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                typeface = Typeface.DEFAULT_BOLD
            }
            cardInner.addView(titleView)

            val pkgView = TextView(ctx).apply {
                text = pkg
                textSize = 11f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
            }
            cardInner.addView(pkgView)

            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }

            val approveBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                text = getString(R.string.btn_approve_app)
                textSize = 12f
                cornerRadius = (8 * resources.displayMetrics.density).toInt()
            }

            val rejectBtn = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.btn_reject_app)
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.status_orange))
            }

            approveBtn.setOnClickListener {
                val qrManager = com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager.getInstance(ctx)
                val executeApprove = {
                    approveBtn.isEnabled = false
                    rejectBtn.isEnabled = false
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            ApprovalRepository.getInstance(ctx.applicationContext).unsuspendAndApprove(pkg, "SELF")
                            withContext(Dispatchers.Main) {
                                if (isAdded && view != null) loadPendingApprovals()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed approving package $pkg", e)
                            withContext(Dispatchers.Main) {
                                if (isAdded && view != null) {
                                    approveBtn.isEnabled = true
                                    rejectBtn.isEnabled = true
                                    Toast.makeText(ctx, R.string.toast_self_policy_save_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }

                if (qrManager.isQrLockEnabled()) {
                    showQrScannerWithFallback("qr_scanner_approve_$pkg") { executeApprove() }
                } else {
                    executeApprove()
                }
            }

            rejectBtn.setOnClickListener {
                approveBtn.isEnabled = false
                rejectBtn.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        ApprovalRepository.getInstance(ctx.applicationContext).recordRejection(pkg, "SELF")
                        withContext(Dispatchers.Main) {
                            if (isAdded && view != null) loadPendingApprovals()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed rejecting package $pkg", e)
                        withContext(Dispatchers.Main) {
                            if (isAdded && view != null) {
                                approveBtn.isEnabled = true
                                rejectBtn.isEnabled = true
                                Toast.makeText(ctx, R.string.toast_self_policy_save_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            btnRow.addView(approveBtn)
            btnRow.addView(rejectBtn)
            cardInner.addView(btnRow)
            card.addView(cardInner)
            containerLayout.addView(card)
        }
    }

    private fun hasUnsavedChanges(): Boolean {
        val snapshot = initialPolicySnapshot ?: return false
        val cleanHost = etDnsHost?.text?.toString()?.trim().orEmpty()

        return selectedStartHour != snapshot.curfewStartHour ||
                selectedStartMinute != snapshot.curfewStartMinute ||
                selectedEndHour != snapshot.curfewEndHour ||
                selectedEndMinute != snapshot.curfewEndMinute ||
                selectedWeekendStartHour != snapshot.weekendCurfewStartHour ||
                selectedWeekendStartMinute != snapshot.weekendCurfewStartMinute ||
                selectedWeekendEndHour != snapshot.weekendCurfewEndHour ||
                selectedWeekendEndMinute != snapshot.weekendCurfewEndMinute ||
                selectedTimeZoneId != snapshot.timeZoneId.ifBlank { CurfewConfig.MAKKAH_TIMEZONE_ID } ||
                switchCurfewMaster?.isChecked != snapshot.isCurfewEnabled ||
                switchWeekendCurfew?.isChecked != snapshot.isWeekendCurfewEnabled ||
                switchVpnBlackhole?.isChecked != snapshot.isVpnBlackholeEnabled ||
                switchInstantInternetKill?.isChecked != snapshot.isInstantInternetKillEnabled ||
                switchIncognitoBlocked?.isChecked != snapshot.isIncognitoBlocked ||
                switchLockNewInstalls?.isChecked != snapshot.lockNewInstallsUntilApproved ||
                switchLockDateTime?.isChecked != snapshot.isDateTimeLocked ||
                cleanHost != snapshot.dnsHost
    }

    private fun updateSaveButtonState() {
        val dirty = hasUnsavedChanges()
        btnSaveSelfControlChanges?.isEnabled = dirty && !isSavingPolicy
        btnSaveSelfControlChanges?.alpha = if (dirty) 1.0f else 0.5f
    }

    private fun saveChanges() {
        if (isSavingPolicy || !hasUnsavedChanges()) return

        val ctx = context?.applicationContext ?: return
        val qrManager = com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager.getInstance(ctx)

        val executeSave = {
            isSavingPolicy = true
            btnSaveSelfControlChanges?.isEnabled = false

            val hostInput = etDnsHost?.text?.toString()?.trim().orEmpty()
            val dnsToSave = hostInput.ifBlank { DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST }

            val updatedPolicy = currentPolicy.copy(
                curfewStartHour = selectedStartHour,
                curfewStartMinute = selectedStartMinute,
                curfewEndHour = selectedEndHour,
                curfewEndMinute = selectedEndMinute,
                isCurfewEnabled = switchCurfewMaster?.isChecked == true,
                isWeekendCurfewEnabled = switchWeekendCurfew?.isChecked == true,
                weekendCurfewStartHour = selectedWeekendStartHour,
                weekendCurfewStartMinute = selectedWeekendStartMinute,
                weekendCurfewEndHour = selectedWeekendEndHour,
                weekendCurfewEndMinute = selectedWeekendEndMinute,
                isVpnBlackholeEnabled = switchVpnBlackhole?.isChecked == true,
                isInstantInternetKillEnabled = switchInstantInternetKill?.isChecked == true,
                isIncognitoBlocked = switchIncognitoBlocked?.isChecked == true,
                lockNewInstallsUntilApproved = switchLockNewInstalls?.isChecked == true,
                isDateTimeLocked = switchLockDateTime?.isChecked == true,
                timeZoneId = selectedTimeZoneId,
                dnsHost = dnsToSave
            )

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val success = PolicyRepository.getInstance(ctx).saveAndEnforceSelfPolicy(updatedPolicy)
                withContext(Dispatchers.Main) {
                    if (!isAdded || view == null) return@withContext
                    isSavingPolicy = false
                    if (success) {
                        currentPolicy = updatedPolicy
                        initialPolicySnapshot = updatedPolicy
                        updateSaveButtonState()
                        Toast.makeText(context ?: ctx, R.string.toast_self_policy_saved, Toast.LENGTH_SHORT).show()
                    } else {
                        updateSaveButtonState()
                        Toast.makeText(context ?: ctx, R.string.toast_self_policy_save_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        if (qrManager.isQrLockEnabled()) {
            showQrScannerWithFallback("qr_scanner_fragment_save") { executeSave() }
        } else {
            executeSave()
        }
    }

    override fun onDestroyView() {
        toolbar = null
        switchCurfewMaster = null
        tvCurfewStartTime = null
        tvCurfewEndTime = null
        btnChangeStartTime = null
        btnChangeEndTime = null
        switchWeekendCurfew = null
        tvWeekendStartTime = null
        tvWeekendEndTime = null
        btnChangeWeekendStartTime = null
        btnChangeWeekendEndTime = null
        switchLockDateTime = null
        cardTimezoneControl = null
        layoutTimezoneHeader = null
        tvTimezoneTitle = null
        tvTimezoneSubtitle = null
        tvTimezoneBadge = null
        ivTimezoneExpand = null
        layoutTimezoneContent = null
        tilTimezonePicker = null
        actvTimezonePicker = null
        tvTimezonePreview = null
        btnResetToMakkah = null
        tilDnsHost = null
        etDnsHost = null
        btnPresetAdguard = null
        btnPresetCloudflare = null
        btnPresetNextDns = null
        switchVpnBlackhole = null
        switchInstantInternetKill = null
        switchIncognitoBlocked = null
        switchLockNewInstalls = null
        btnOpenLocalAppManager = null
        tvBrowserFilterStatus = null
        btnManageBrowserUrls = null
        layoutPendingApps = null
        tvNoPendingApps = null
        btnSaveSelfControlChanges = null
        switchQrLockEnabled = null
        layoutQrActionButtons = null
        btnShowQrCode = null
        btnRegenerateQrCode = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "AntiPorn_SelfControlFrag"
    }
}
