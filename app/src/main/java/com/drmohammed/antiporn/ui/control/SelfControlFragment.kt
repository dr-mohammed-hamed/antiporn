package com.drmohammed.antiporn.ui.control

import com.drmohammed.antiporn.core.network.DnsFilteringValidator

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.databinding.FragmentSelfControlBinding
import com.drmohammed.antiporn.ui.accountability.RemoteAppManagerActivity
import com.drmohammed.antiporn.ui.browser.BrowserUrlFilterActivity
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.qr.QrExportDialog
import com.google.android.material.color.MaterialColors
import com.drmohammed.antiporn.ui.control.qr.QrScannerBottomSheet
import com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
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

    private var _binding: FragmentSelfControlBinding? = null
    private val binding get() = _binding!!

    private val selfControlViewModel: SelfControlViewModel by viewModel()
    private var approvalsViewBinder: SelfControlApprovalsViewBinder? = null
    private var isQrPolicyLoading = false
    private var isBindingPolicy = false
    private var lastLockState: Boolean? = null
    private var lastRenderedApprovalsCount: Int? = null

    private var selectedStartHour = 20
    private var selectedStartMinute = 30
    private var selectedEndHour = 7
    private var selectedEndMinute = 0

    private var selectedWeekendStartHour = 20
    private var selectedWeekendStartMinute = 30
    private var selectedWeekendEndHour = 7
    private var selectedWeekendEndMinute = 0
    private var selectedTimeZoneId = CurfewConfig.DEFAULT_TIMEZONE_ID

    private val browserFilterLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val ctx = context?.applicationContext ?: return@registerForActivityResult
            val latest = PolicyRepository.getInstance(ctx).getCurrentPolicy()
            val current = selfControlViewModel.uiState.value.policy
            val updated = current.copy(
                browserFilterMode = latest.browserFilterMode,
                isFullSystemFilteringEnabled = latest.isFullSystemFilteringEnabled,
                customBlockedUrls = latest.customBlockedUrls,
                customAllowedUrls = latest.customAllowedUrls
            )
            selfControlViewModel.updateDraftPolicy(updated)
            updateBrowserFilterStatus(latest)
            updateSaveButtonState()
        }
    }

    private val appManagerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            reloadPackageCollectionsOnly()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Dismiss any retained/restored QrExportDialog to prevent unauthorized QR key exposure across theme changes
            (childFragmentManager.findFragmentByTag(QrExportDialog.TAG) as? DialogFragment)?.dismissAllowingStateLoss()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FragmentSelfControlBinding.inflate(inflater, container, false).also { _binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isQrPolicyLoading = true
        isBindingPolicy = true
        initViews()
        setupListeners()
        observeViewModel()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        isQrPolicyLoading = false
        isBindingPolicy = false
    }

    override fun onResume() {
        super.onResume()
        if (isHidden) return
        selfControlViewModel.checkGateAndRefresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            selfControlViewModel.checkGateAndRefresh()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                selfControlViewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: SelfControlUiState) {
        if (_binding == null) return

        // 1. Lock State
        updateLockState(state.isLocked)
        if (state.isLocked) return

        // 2. Policy: sync views to state.policy when no local unsaved edits
        if (!state.hasUnsavedChanges) {
            bindPolicyToViews(state.policy)
        }
        updateBrowserFilterStatus(state.policy)

        // 3. Save Button & Loading state
        updateSaveButtonState()

        // 4. Pending Approvals Count & Card
        if (state.pendingApprovalsCount == 0) {
            lastRenderedApprovalsCount = 0
            binding.cardPendingApps.visibility = View.GONE
            binding.tvNoPendingApps.visibility = View.VISIBLE
        } else {
            binding.cardPendingApps.visibility = View.VISIBLE
            if (lastRenderedApprovalsCount != state.pendingApprovalsCount) {
                lastRenderedApprovalsCount = state.pendingApprovalsCount
                loadPendingApprovals()
            }
        }
    }

    fun updateLockState(isLocked: Boolean) {
        if (lastLockState == isLocked) return
        lastLockState = isLocked
        val b = _binding ?: return
        if (isLocked) {
            b.layoutSelfControlLocked.visibility = View.VISIBLE
            b.scrollSelfControl.visibility = View.GONE
            b.cardBottomBarSelfControl.visibility = View.GONE
        } else {
            b.layoutSelfControlLocked.visibility = View.GONE
            b.scrollSelfControl.visibility = View.VISIBLE
            b.cardBottomBarSelfControl.visibility = View.VISIBLE
        }
    }

    private fun initViews() {
        binding.toolbarSelfControl.navigationIcon = null

        binding.btnGoToAccountability.setOnClickListener {
            (activity as? com.drmohammed.antiporn.MainActivity)?.navigateToTab(R.id.nav_accountability)
        }

        val ctx = requireContext()
        val timezoneOptions = IslamicTimeZoneRegistry.getAllOptions()
        val tzAdapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_dropdown_item_1line,
            timezoneOptions
        )
        binding.actvTimezonePicker.setAdapter(tzAdapter)

        val act = activity as? AppCompatActivity
        if (act != null) {
            approvalsViewBinder = SelfControlApprovalsViewBinder(
                activity = act,
                layoutPendingApps = binding.layoutPendingApps,
                tvNoPendingApps = binding.tvNoPendingApps,
                cardPendingApps = binding.cardPendingApps,
                onReloadRequested = { loadPendingApprovals() }
            )
        }

        updateTimingLabels()
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
        binding.switchQrLockEnabled.setOnCheckedChangeListener { buttonView, isChecked ->
            // Standard Android Touch Guard: ignore programmatic updates and state restorations
            if (isQrPolicyLoading || !buttonView.isPressed) return@setOnCheckedChangeListener
            val ctx = context?.applicationContext ?: return@setOnCheckedChangeListener
            val qrManager = SelfControlQrManager.getInstance(ctx)
            if (isChecked) {
                // If QR lock is already active, this is a state sync — NEVER show export dialog!
                if (qrManager.isQrLockEnabled()) {
                    _binding?.layoutQrActionButtons?.isVisible = true
                    return@setOnCheckedChangeListener
                }
                if (!qrManager.hasToken()) {
                    qrManager.generateAndSaveNewToken()
                }
                qrManager.setQrLockEnabled(true)
                _binding?.layoutQrActionButtons?.isVisible = true
                if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag(QrExportDialog.TAG) == null) {
                    QrExportDialog.newInstance().show(childFragmentManager, QrExportDialog.TAG)
                }
            } else {
                if (qrManager.isQrLockEnabled()) {
                    isQrPolicyLoading = true
                    _binding?.switchQrLockEnabled?.isChecked = true
                    isQrPolicyLoading = false

                    val tag = "qr_scanner_disable_barrier"
                    if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return@setOnCheckedChangeListener
                    showQrScannerWithFallback(tag) {
                        qrManager.setQrLockEnabled(false)
                        isQrPolicyLoading = true
                        _binding?.switchQrLockEnabled?.isChecked = false
                        _binding?.layoutQrActionButtons?.isVisible = false
                        isQrPolicyLoading = false
                        Toast.makeText(context ?: ctx, R.string.self_control_qr_disabled_toast, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    _binding?.layoutQrActionButtons?.isVisible = false
                }
            }
        }

        binding.btnShowQrCode.setOnClickListener {
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

        binding.btnRegenerateQrCode.setOnClickListener {
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
                        _binding?.switchQrLockEnabled?.isChecked = true
                        _binding?.layoutQrActionButtons?.isVisible = true
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

        binding.btnChangeStartTime.setOnClickListener {
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
                onUserDraftChanged()
            }
            val tag = "weekday_start_picker"
            if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
            picker.show(childFragmentManager, tag)
        }

        binding.btnChangeEndTime.setOnClickListener {
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
                onUserDraftChanged()
            }
            val tag = "weekday_end_picker"
            if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
            picker.show(childFragmentManager, tag)
        }

        binding.btnChangeWeekendStartTime.setOnClickListener {
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
                onUserDraftChanged()
            }
            val tag = "weekend_start_picker"
            if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
            picker.show(childFragmentManager, tag)
        }

        binding.btnChangeWeekendEndTime.setOnClickListener {
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
                onUserDraftChanged()
            }
            val tag = "weekend_end_picker"
            if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
            picker.show(childFragmentManager, tag)
        }

        binding.layoutTimezoneHeader.setOnClickListener {
            val isExpanded = binding.layoutTimezoneContent.isVisible
            if (isExpanded) {
                binding.layoutTimezoneContent.visibility = View.GONE
                binding.ivTimezoneExpand.animate().rotation(0f).setDuration(200).start()
            } else {
                binding.layoutTimezoneContent.visibility = View.VISIBLE
                binding.ivTimezoneExpand.animate().rotation(180f).setDuration(200).start()
            }
        }

        binding.actvTimezonePicker.setOnItemClickListener { parent, _, position, _ ->
            val option = parent.getItemAtPosition(position) as? IslamicTimeZoneRegistry.TimeZoneOption
            if (option != null && option.id != selectedTimeZoneId) {
                selectedTimeZoneId = option.id
                updateTimezoneViews()
                onUserDraftChanged()
            }
        }

        var lastAutoDetectClickTime = 0L
        binding.btnAutoDetectTimezone.setOnClickListener {
            if (!isAdded || isDetached) return@setOnClickListener
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAutoDetectClickTime < 800L) return@setOnClickListener
            lastAutoDetectClickTime = now
            val detected = IslamicTimeZoneRegistry.detectCurrentDeviceOption()
            context?.let { ctx ->
                Toast.makeText(ctx, getString(R.string.timezone_detected_toast, detected.displayText), Toast.LENGTH_SHORT).show()
            }
            if (selectedTimeZoneId != detected.id) {
                selectedTimeZoneId = detected.id
                updateTimezoneViews()
                onUserDraftChanged()
            }
        }

        binding.btnResetToMakkah.setOnClickListener {
            if (selectedTimeZoneId != CurfewConfig.DEFAULT_TIMEZONE_ID) {
                selectedTimeZoneId = CurfewConfig.DEFAULT_TIMEZONE_ID
                updateTimezoneViews()
                onUserDraftChanged()
            }
        }

        binding.btnPresetControlD.setOnClickListener {
            binding.etDnsHost.setText(DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST)
        }
        binding.btnPresetAdguard.setOnClickListener {
            binding.etDnsHost.setText(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST)
        }
        binding.btnPresetCloudflare.setOnClickListener {
            binding.etDnsHost.setText(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST)
        }
        binding.btnPresetNextDns.setOnClickListener {
            binding.etDnsHost.setText(DevicePolicyBundle.LEGACY_DEFAULT_NEXTDNS_HOST)
        }

        val switchListener = { view: View, _: Boolean ->
            if (!isBindingPolicy && (view as? CompoundButton)?.isPressed == true) {
                onUserDraftChanged()
            }
        }
        binding.switchCurfewMaster.setOnCheckedChangeListener(switchListener)
        binding.switchWeekendCurfew.setOnCheckedChangeListener(switchListener)
        binding.switchVpnBlackhole.setOnCheckedChangeListener(switchListener)
        binding.switchInstantInternetKill.setOnCheckedChangeListener(switchListener)
        binding.switchIncognitoBlocked.setOnCheckedChangeListener(switchListener)
        binding.switchLockNewInstalls.setOnCheckedChangeListener(switchListener)
        binding.switchYouTubeRestricted.setOnCheckedChangeListener(switchListener)
        binding.switchTelegramWeb.setOnCheckedChangeListener(switchListener)
        binding.switchLockDateTime.setOnCheckedChangeListener { view, _ ->
            if (!isBindingPolicy && (view as? CompoundButton)?.isPressed == true) {
                onUserDraftChanged()
            }
        }

        binding.etDnsHost.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isBindingPolicy) {
                    onUserDraftChanged()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        var lastAppManagerClickTime = 0L
        binding.btnOpenLocalAppManager.setOnClickListener {
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
        binding.tileLocalAppManager.setOnClickListener {
            if (_binding?.btnOpenLocalAppManager?.isEnabled == true) {
                binding.btnOpenLocalAppManager.performClick()
            }
        }

        var lastBrowserFilterClickTime = 0L
        binding.btnManageBrowserUrls.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBrowserFilterClickTime < 800L) return@setOnClickListener
            lastBrowserFilterClickTime = now

            val ctx = context ?: return@setOnClickListener
            val launchAction = {
                val intent = Intent(ctx, BrowserUrlFilterActivity::class.java).apply {
                    putExtra(BrowserUrlFilterActivity.EXTRA_IS_REMOTE, false)
                    putExtra(BrowserUrlFilterActivity.EXTRA_POLICY_JSON, selfControlViewModel.uiState.value.policy.toJson())
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
        binding.cardBrowserUrlFilter.setOnClickListener {
            if (_binding?.btnManageBrowserUrls?.isEnabled == true) {
                binding.btnManageBrowserUrls.performClick()
            }
        }

        binding.btnOpenWaqfaSettings.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val launchAction = {
                val intent = Intent(ctx, com.drmohammed.antiporn.waqfa.ui.settings.WaqfaSettingsActivity::class.java)
                startActivity(intent)
            }
            val qrManager = SelfControlQrManager.getInstance(ctx)
            if (qrManager.isQrLockEnabled()) {
                showQrScannerWithFallback("qr_scanner_waqfa") { launchAction() }
            } else {
                launchAction()
            }
        }
        binding.tileWaqfaControl.setOnClickListener {
            binding.btnOpenWaqfaSettings.performClick()
        }

        binding.btnSaveSelfControlChanges.setOnClickListener {
            saveChanges()
        }
    }

    private fun bindPolicyToViews(policy: DevicePolicyBundle) {
        val ctx = context?.applicationContext ?: return

        isBindingPolicy = true
        try {
            selectedStartHour = policy.curfewStartHour
            selectedStartMinute = policy.curfewStartMinute
            selectedEndHour = policy.curfewEndHour
            selectedEndMinute = policy.curfewEndMinute

            selectedWeekendStartHour = policy.weekendCurfewStartHour
            selectedWeekendStartMinute = policy.weekendCurfewStartMinute
            selectedWeekendEndHour = policy.weekendCurfewEndHour
            selectedWeekendEndMinute = policy.weekendCurfewEndMinute
            selectedTimeZoneId = policy.timeZoneId.ifBlank { CurfewConfig.DEFAULT_TIMEZONE_ID }

            val b = _binding ?: return
            b.switchCurfewMaster.isChecked = policy.isCurfewEnabled
            b.switchWeekendCurfew.isChecked = policy.isWeekendCurfewEnabled
            b.switchVpnBlackhole.isChecked = policy.isVpnBlackholeEnabled
            b.switchInstantInternetKill.isChecked = policy.isInstantInternetKillEnabled
            b.switchIncognitoBlocked.isChecked = policy.isIncognitoBlocked
            b.switchLockNewInstalls.isChecked = policy.lockNewInstallsUntilApproved
            b.switchYouTubeRestricted.isChecked = policy.isYouTubeRestrictedModeEnabled
            b.switchTelegramWeb.isChecked = policy.isTelegramWebBlocked
            b.switchLockDateTime.isChecked = policy.isDateTimeLocked

            if (b.etDnsHost.text?.toString() != policy.dnsHost) {
                b.etDnsHost.setText(policy.dnsHost)
            }
            updateTimingLabels()
            updateTimezoneViews()

            val waqfa = policy.waqfaConfig
            if (waqfa.isEnabled) {
                b.tvWaqfaStatus.text = getString(R.string.waqfa_card_status_active, waqfa.targetPackages.size)
                b.tvWaqfaStatus.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.status_green))
            } else {
                b.tvWaqfaStatus.text = getString(R.string.waqfa_card_status_inactive)
                b.tvWaqfaStatus.setTextColor(MaterialColors.getColor(b.tvWaqfaStatus, com.google.android.material.R.attr.colorOutline))
            }

            val qrManager = SelfControlQrManager.getInstance(ctx)
            val isQrEnabled = qrManager.isQrLockEnabled()
            isQrPolicyLoading = true
            b.switchQrLockEnabled.isChecked = isQrEnabled
            b.layoutQrActionButtons.isVisible = isQrEnabled
            isQrPolicyLoading = false
        } finally {
            isBindingPolicy = false
        }
    }

    private fun reloadPackageCollectionsOnly() {
        val ctx = context?.applicationContext ?: return
        val latestPolicy = PolicyRepository.getInstance(ctx).getCurrentPolicy()
        val current = selfControlViewModel.uiState.value.policy
        val updated = current.copy(
            customBlacklistPackages = latestPolicy.customBlacklistPackages,
            customCurfewWhitelistPackages = latestPolicy.customCurfewWhitelistPackages,
            curfewVpnBypassPackages = latestPolicy.curfewVpnBypassPackages,
            protectedAppPackages = latestPolicy.protectedAppPackages
        )
        selfControlViewModel.updateDraftPolicy(updated)
    }

    private fun updateBrowserFilterStatus(policy: DevicePolicyBundle) {
        val b = _binding ?: return
        b.tvBrowserFilterStatus.text = when (policy.browserFilterMode) {
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
        val b = _binding ?: return
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

        val startFormatted = formatTime(selectedStartHour, selectedStartMinute)
        b.tvCurfewStartTime.text = startFormatted
        b.btnChangeStartTime.text = startFormatted

        val endFormatted = formatTime(selectedEndHour, selectedEndMinute)
        b.tvCurfewEndTime.text = endFormatted
        b.btnChangeEndTime.text = endFormatted

        val weekendStartFormatted = formatTime(selectedWeekendStartHour, selectedWeekendStartMinute)
        b.tvWeekendStartTime.text = weekendStartFormatted
        b.btnChangeWeekendStartTime.text = weekendStartFormatted

        val weekendEndFormatted = formatTime(selectedWeekendEndHour, selectedWeekendEndMinute)
        b.tvWeekendEndTime.text = weekendEndFormatted
        b.btnChangeWeekendEndTime.text = weekendEndFormatted
    }

    private fun updateTimezoneViews() {
        val b = _binding ?: return
        val option = IslamicTimeZoneRegistry.findByIanaId(selectedTimeZoneId)
        val cityName = option.majorCities.split("/").firstOrNull()?.trim() ?: option.majorCities
        b.tvTimezoneBadge.text = getString(R.string.guardian_timezone_badge_format, option.getLiveOffsetLabel(), cityName)
        b.actvTimezonePicker.setText(option.displayText, false)

        try {
            val tz = TimeZone.getTimeZone(option.ianaTimeZoneId)
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault()).apply {
                timeZone = tz
            }
            val formattedTime = sdf.format(Date())
            val timeWithOffset = getString(R.string.guardian_timezone_preview_time_format, formattedTime, option.getLiveOffsetLabel())
            b.tvTimezonePreview.text = getString(
                R.string.guardian_timezone_preview_format,
                timeWithOffset
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed formatting timezone preview: ${e.message}", e)
            b.tvTimezonePreview.text = getString(
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
                if (!isAdded || _binding == null) return@withContext
                approvalsViewBinder?.renderPendingApprovals(items)
            }
        }
    }

    private fun buildDraftPolicyFromViews(): DevicePolicyBundle {
        val basePolicy = selfControlViewModel.uiState.value.policy
        val hostInput = _binding?.etDnsHost?.text?.toString()?.trim().orEmpty()
        val dnsToSave = hostInput.ifBlank { DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST }
        return basePolicy.copy(
            curfewStartHour = selectedStartHour,
            curfewStartMinute = selectedStartMinute,
            curfewEndHour = selectedEndHour,
            curfewEndMinute = selectedEndMinute,
            isCurfewEnabled = _binding?.switchCurfewMaster?.isChecked == true,
            isWeekendCurfewEnabled = _binding?.switchWeekendCurfew?.isChecked == true,
            weekendCurfewStartHour = selectedWeekendStartHour,
            weekendCurfewStartMinute = selectedWeekendStartMinute,
            weekendCurfewEndHour = selectedWeekendEndHour,
            weekendCurfewEndMinute = selectedWeekendEndMinute,
            isVpnBlackholeEnabled = _binding?.switchVpnBlackhole?.isChecked == true,
            isInstantInternetKillEnabled = _binding?.switchInstantInternetKill?.isChecked == true,
            isIncognitoBlocked = _binding?.switchIncognitoBlocked?.isChecked == true,
            lockNewInstallsUntilApproved = _binding?.switchLockNewInstalls?.isChecked == true,
            isYouTubeRestrictedModeEnabled = _binding?.switchYouTubeRestricted?.isChecked == true,
            isTelegramWebBlocked = _binding?.switchTelegramWeb?.isChecked == true,
            isDateTimeLocked = _binding?.switchLockDateTime?.isChecked == true,
            timeZoneId = selectedTimeZoneId,
            dnsHost = dnsToSave
        )
    }

    private fun onUserDraftChanged() {
        if (isBindingPolicy) return
        val draft = buildDraftPolicyFromViews()
        selfControlViewModel.updateDraftPolicy(draft)
    }

    private fun hasUnsavedChanges(): Boolean = selfControlViewModel.uiState.value.hasUnsavedChanges

    private fun updateSaveButtonState() {
        val b = _binding ?: return
        val state = selfControlViewModel.uiState.value
        b.btnSaveSelfControlChanges.isEnabled = state.hasUnsavedChanges && !state.isSaving
        b.btnSaveSelfControlChanges.alpha = if (state.hasUnsavedChanges && !state.isSaving) 1.0f else 0.5f
    }

    private fun saveChanges() {
        val state = selfControlViewModel.uiState.value
        if (state.isSaving || !state.hasUnsavedChanges) return

        val ctx = context?.applicationContext ?: return
        val gate = SelfControlAccessGateRegistry.getActiveGate()
        val canAccess = gate.canAccess(ctx)
        Log.i(TAG, "[SelfControlDiag] SelfControlFragment: User clicked Save Changes. canAccess=$canAccess")
        if (!canAccess) {
            Log.w(TAG, "[SelfControlDiag] SelfControlFragment: Save Changes BLOCKED because access is denied!")
            Toast.makeText(context ?: ctx, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            return
        }
        val qrManager = SelfControlQrManager.getInstance(ctx)

        val executeSave = {
            val updatedPolicy = buildDraftPolicyFromViews()
            val dnsToSave = updatedPolicy.dnsHost

            // Lock button and show immediate feedback to prevent double-tap race conditions
            binding.btnSaveSelfControlChanges.isEnabled = false
            binding.btnSaveSelfControlChanges.alpha = 0.5f

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val validationResult = DnsFilteringValidator.validateDnsHost(dnsToSave)
                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null || activity?.isFinishing == true || activity?.isDestroyed == true) return@withContext

                    when (validationResult) {
                        is DnsFilteringValidator.ValidationResult.Valid -> {
                            binding.tilDnsHost.error = null
                            selfControlViewModel.savePolicy(updatedPolicy) { result ->
                                if (!isAdded || _binding == null) return@savePolicy
                                result.fold(
                                    onSuccess = {
                                        Toast.makeText(context ?: ctx, R.string.toast_self_policy_saved, Toast.LENGTH_SHORT).show()
                                    },
                                    onFailure = {
                                        Toast.makeText(context ?: ctx, R.string.toast_self_policy_save_failed, Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        }
                        is DnsFilteringValidator.ValidationResult.UnfilteredPorn -> {
                            updateSaveButtonState()
                            binding.tilDnsHost.error = getString(R.string.dns_probe_error_not_blocking)
                            binding.etDnsHost.requestFocus()
                            context?.let { c ->
                                MaterialAlertDialogBuilder(c)
                                    .setTitle(R.string.dns_probe_dialog_failed_title)
                                    .setMessage(
                                        getString(
                                            R.string.dns_probe_error_not_blocking_detail,
                                            validationResult.domain,
                                            validationResult.resolvedIp
                                        )
                                    )
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        }
                        is DnsFilteringValidator.ValidationResult.Unreachable -> {
                            updateSaveButtonState()
                            binding.tilDnsHost.error = getString(R.string.dns_probe_error_unreachable)
                            binding.etDnsHost.requestFocus()
                            context?.let { c ->
                                MaterialAlertDialogBuilder(c)
                                    .setTitle(R.string.dns_probe_dialog_failed_title)
                                    .setMessage(
                                        getString(
                                            R.string.dns_probe_error_unreachable_detail,
                                            validationResult.reason
                                        )
                                    )
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        }
                        is DnsFilteringValidator.ValidationResult.ResolverBroken -> {
                            updateSaveButtonState()
                            binding.tilDnsHost.error = getString(R.string.dns_probe_error_broken)
                            binding.etDnsHost.requestFocus()
                            context?.let { c ->
                                MaterialAlertDialogBuilder(c)
                                    .setTitle(R.string.dns_probe_dialog_failed_title)
                                    .setMessage(
                                        getString(
                                            R.string.dns_probe_error_broken_detail,
                                            validationResult.reason
                                        )
                                    )
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        }
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
        super.onDestroyView()
        approvalsViewBinder = null
        _binding = null
    }

    companion object {
        private const val TAG = "AntiPorn_SelfControlFrag"
    }
}
