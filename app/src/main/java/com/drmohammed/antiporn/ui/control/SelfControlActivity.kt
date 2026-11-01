package com.drmohammed.antiporn.ui.control

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
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
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
 * Self-Control Activity enabling the device owner to manage all policy timings,
 * app suspensions, DNS settings, browser restrictions, and pending approvals directly.
 */

class SelfControlActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar

    // Curfew controls
    private lateinit var switchCurfewMaster: MaterialSwitch
    private lateinit var tvCurfewStartTime: TextView
    private lateinit var tvCurfewEndTime: TextView
    private lateinit var btnChangeStartTime: MaterialButton
    private lateinit var btnChangeEndTime: MaterialButton

    // Weekend curfew controls
    private lateinit var switchWeekendCurfew: MaterialSwitch
    private lateinit var tvWeekendStartTime: TextView
    private lateinit var tvWeekendEndTime: TextView
    private lateinit var btnChangeWeekendStartTime: MaterialButton
    private lateinit var btnChangeWeekendEndTime: MaterialButton

    // Date & Time Lock control
    private lateinit var switchLockDateTime: MaterialSwitch

    // Timezone controls
    private lateinit var cardTimezoneControl: MaterialCardView
    private lateinit var layoutTimezoneHeader: View
    private lateinit var tvTimezoneTitle: TextView
    private lateinit var tvTimezoneSubtitle: TextView
    private lateinit var tvTimezoneBadge: TextView
    private lateinit var ivTimezoneExpand: ImageView
    private lateinit var layoutTimezoneContent: LinearLayout
    private lateinit var tilTimezonePicker: TextInputLayout
    private lateinit var actvTimezonePicker: MaterialAutoCompleteTextView
    private lateinit var tvTimezonePreview: TextView
    private lateinit var btnResetToMakkah: MaterialButton
    private lateinit var btnAutoDetectTimezone: MaterialButton

    // DNS controls
    private lateinit var tilDnsHost: TextInputLayout
    private lateinit var etDnsHost: TextInputEditText
    private lateinit var btnPresetAdguard: MaterialButton
    private lateinit var btnPresetCloudflare: MaterialButton
    private lateinit var btnPresetNextDns: MaterialButton

    // Enforcement & Security controls
    private lateinit var switchVpnBlackhole: MaterialSwitch
    private lateinit var switchInstantInternetKill: MaterialSwitch
    private lateinit var switchIncognitoBlocked: MaterialSwitch
    private lateinit var switchLockNewInstalls: MaterialSwitch

    // QR Lock controls
    private lateinit var switchQrLockEnabled: MaterialSwitch
    private lateinit var layoutQrActionButtons: View
    private lateinit var btnShowQrCode: MaterialButton
    private lateinit var btnRegenerateQrCode: MaterialButton

    private var isPolicyLoading = false

    // Local App Manager
    private lateinit var btnOpenLocalAppManager: MaterialButton

    // Browser URL Filter
    private lateinit var tvBrowserFilterStatus: TextView
    private lateinit var btnManageBrowserUrls: MaterialButton

    // Approvals
    private lateinit var layoutPendingApps: LinearLayout
    private lateinit var tvNoPendingApps: TextView

    // Bottom Bar
    private lateinit var btnSaveSelfControlChanges: MaterialButton

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
    private val appManagerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {

            // Reload package collections without wiping in-progress time/switch edits
            reloadPackageCollectionsOnly()
        }
    }
    private val browserFilterLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val latest = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
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
    private val backPressedCallback = object : OnBackPressedCallback(false) {

        override fun handleOnBackPressed() {
            if (isFinishing || isDestroyed) return
            if (isSavingPolicy) return
            if (hasUnsavedChanges()) {
                showDiscardConfirmationDialog()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Check Pluggable Access Gate
        val gate = SelfControlAccessGateRegistry.getActiveGate()
        if (!gate.canAccess(this)) {
            Log.w(TAG, "Access denied by active gate (${gate.gateId})")
            val policy = try {
                PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to retrieve policy for gate message", e)
                null
            }
            val msgRes = if (policy?.isSupervised == true && !policy.isSelfControlAllowed) {
                R.string.gate_access_denied_by_guardian
            } else {
                R.string.gate_access_denied
            }
            Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_self_control)
        findViewById<View>(R.id.layoutRootSelfControl)?.let { rootView ->
            val innerBottomBar = findViewById<View>(R.id.layoutBottomBarSelfControlInner)
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
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        initViews()
        setupListeners()
        loadCurrentPolicy()
        loadPendingApprovals()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PolicyRepository.getInstance(applicationContext).currentPolicyFlow.collect {
                    if (isFinishing || isDestroyed) return@collect
                    val activeGate = SelfControlAccessGateRegistry.getActiveGate()
                    if (!activeGate.canAccess(this@SelfControlActivity)) {
                        val policy = try {
                            PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to retrieve policy on real-time ejection", e)
                            null
                        }
                        val msgRes = if (policy?.isSupervised == true && !policy.isSelfControlAllowed) {
                            R.string.gate_access_denied_by_guardian
                        } else {
                            R.string.gate_access_denied
                        }
                        Toast.makeText(this@SelfControlActivity, msgRes, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        val gate = SelfControlAccessGateRegistry.getActiveGate()
        if (!gate.canAccess(this)) {
            val policy = try {
                PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to retrieve policy in onResume", e)
                null
            }
            val msgRes = if (policy?.isSupervised == true && !policy.isSelfControlAllowed) {
                R.string.gate_access_denied_by_guardian
            } else {
                R.string.gate_access_denied
            }
            Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val latest = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
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
        loadPendingApprovals()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarSelfControl)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Curfew
        switchCurfewMaster = findViewById(R.id.switchCurfewMaster)
        tvCurfewStartTime = findViewById(R.id.tvCurfewStartTime)
        tvCurfewEndTime = findViewById(R.id.tvCurfewEndTime)
        btnChangeStartTime = findViewById(R.id.btnChangeStartTime)
        btnChangeEndTime = findViewById(R.id.btnChangeEndTime)

        // Weekend
        switchWeekendCurfew = findViewById(R.id.switchWeekendCurfew)
        tvWeekendStartTime = findViewById(R.id.tvWeekendStartTime)
        tvWeekendEndTime = findViewById(R.id.tvWeekendEndTime)
        btnChangeWeekendStartTime = findViewById(R.id.btnChangeWeekendStartTime)
        btnChangeWeekendEndTime = findViewById(R.id.btnChangeWeekendEndTime)

        // Date & Time
        switchLockDateTime = findViewById(R.id.switchLockDateTime)
        cardTimezoneControl = findViewById(R.id.cardTimezoneControl)
        layoutTimezoneHeader = findViewById(R.id.layoutTimezoneHeader)
        tvTimezoneTitle = findViewById(R.id.tvTimezoneTitle)
        tvTimezoneSubtitle = findViewById(R.id.tvTimezoneSubtitle)
        tvTimezoneBadge = findViewById(R.id.tvTimezoneBadge)
        ivTimezoneExpand = findViewById(R.id.ivTimezoneExpand)
        layoutTimezoneContent = findViewById(R.id.layoutTimezoneContent)
        tilTimezonePicker = findViewById(R.id.tilTimezonePicker)
        actvTimezonePicker = findViewById(R.id.actvTimezonePicker)
        tvTimezonePreview = findViewById(R.id.tvTimezonePreview)
        btnResetToMakkah = findViewById(R.id.btnResetToMakkah)
        btnAutoDetectTimezone = findViewById(R.id.btnAutoDetectTimezone)

        // Timezone adapter
        val timezoneOptions = IslamicTimeZoneRegistry.getAllOptions()
        val tzAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            timezoneOptions
        )
        actvTimezonePicker.setAdapter(tzAdapter)

        // DNS
        tilDnsHost = findViewById(R.id.tilDnsHost)
        etDnsHost = findViewById(R.id.etDnsHost)
        btnPresetAdguard = findViewById(R.id.btnPresetAdguard)
        btnPresetCloudflare = findViewById(R.id.btnPresetCloudflare)
        btnPresetNextDns = findViewById(R.id.btnPresetNextDns)

        // Security switches
        switchVpnBlackhole = findViewById(R.id.switchVpnBlackhole)
        switchInstantInternetKill = findViewById(R.id.switchInstantInternetKill)
        switchIncognitoBlocked = findViewById(R.id.switchIncognitoBlocked)
        switchLockNewInstalls = findViewById(R.id.switchLockNewInstalls)

        // QR Lock
        switchQrLockEnabled = findViewById(R.id.switchQrLockEnabled)
        layoutQrActionButtons = findViewById(R.id.layoutQrActionButtons)
        btnShowQrCode = findViewById(R.id.btnShowQrCode)
        btnRegenerateQrCode = findViewById(R.id.btnRegenerateQrCode)

        // App Manager
        btnOpenLocalAppManager = findViewById(R.id.btnOpenLocalAppManager)

        // Browser Filter
        tvBrowserFilterStatus = findViewById(R.id.tvBrowserFilterStatus)
        btnManageBrowserUrls = findViewById(R.id.btnManageBrowserUrls)

        // Approvals
        layoutPendingApps = findViewById(R.id.layoutPendingApps)
        tvNoPendingApps = findViewById(R.id.tvNoPendingApps)

        // Bottom Bar
        btnSaveSelfControlChanges = findViewById(R.id.btnSaveSelfControlChanges)
    }

    private fun setupListeners() {

        // Weekday start
        btnChangeStartTime.setOnClickListener {
            val is24Hour = android.text.format.DateFormat.is24HourFormat(this)
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
            picker.show(supportFragmentManager, "weekday_start_picker")
        }

        // Weekday end
        btnChangeEndTime.setOnClickListener {
            val is24Hour = android.text.format.DateFormat.is24HourFormat(this)
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
            picker.show(supportFragmentManager, "weekday_end_picker")
        }

        // Weekend start
        btnChangeWeekendStartTime.setOnClickListener {
            val is24Hour = android.text.format.DateFormat.is24HourFormat(this)
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
            picker.show(supportFragmentManager, "weekend_start_picker")
        }

        // Weekend end
        btnChangeWeekendEndTime.setOnClickListener {
            val is24Hour = android.text.format.DateFormat.is24HourFormat(this)
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
            picker.show(supportFragmentManager, "weekend_end_picker")
        }

        // Timezone expand/collapse
        layoutTimezoneHeader.setOnClickListener {
            val isExpanded = layoutTimezoneContent.isVisible
            if (isExpanded) {
                layoutTimezoneContent.visibility = View.GONE
                ivTimezoneExpand.animate().rotation(0f).setDuration(200).start()
            } else {
                layoutTimezoneContent.visibility = View.VISIBLE
                ivTimezoneExpand.animate().rotation(180f).setDuration(200).start()
            }
        }
        actvTimezonePicker.setOnItemClickListener { parent, _, position, _ ->
            val option = parent.getItemAtPosition(position) as? IslamicTimeZoneRegistry.TimeZoneOption
            if (option != null && option.id != selectedTimeZoneId) {
                selectedTimeZoneId = option.id
                updateTimezoneViews()
                updateSaveButtonState()
            }
        }
        var lastAutoDetectClickTime = 0L
        btnAutoDetectTimezone.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAutoDetectClickTime < 800L) return@setOnClickListener
            lastAutoDetectClickTime = now
            val detected = IslamicTimeZoneRegistry.detectCurrentDeviceOption()
            android.widget.Toast.makeText(this, getString(R.string.timezone_detected_toast, detected.displayText), android.widget.Toast.LENGTH_SHORT).show()
            if (selectedTimeZoneId != detected.id) {
                selectedTimeZoneId = detected.id
                updateTimezoneViews()
                updateSaveButtonState()

            }
        }

        btnResetToMakkah.setOnClickListener {
            if (selectedTimeZoneId != CurfewConfig.MAKKAH_TIMEZONE_ID) {
                selectedTimeZoneId = CurfewConfig.MAKKAH_TIMEZONE_ID
                updateTimezoneViews()
                updateSaveButtonState()
            }
        }

        // DNS presets
        btnPresetAdguard.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST)
        }
        btnPresetCloudflare.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST)
        }
        btnPresetNextDns.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.LEGACY_DEFAULT_NEXTDNS_HOST)
        }
        val switchListener = { _: View, _: Boolean -> updateSaveButtonState() }
        switchCurfewMaster.setOnCheckedChangeListener(switchListener)
        switchWeekendCurfew.setOnCheckedChangeListener(switchListener)
        switchVpnBlackhole.setOnCheckedChangeListener(switchListener)
        switchInstantInternetKill.setOnCheckedChangeListener(switchListener)
        switchIncognitoBlocked.setOnCheckedChangeListener(switchListener)
        switchLockNewInstalls.setOnCheckedChangeListener(switchListener)
        switchLockDateTime.setOnCheckedChangeListener { _, _ ->
            updateSaveButtonState()
        }
        etDnsHost.addTextChangedListener(object : TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSaveButtonState()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Local App Manager
        var lastAppManagerClickTime = 0L
        btnOpenLocalAppManager.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAppManagerClickTime < 800L) return@setOnClickListener
            lastAppManagerClickTime = now
            val intent = Intent(this, RemoteAppManagerActivity::class.java).apply {
                putExtra(RemoteAppManagerActivity.EXTRA_IS_LOCAL, true)
            }
            val qrManager = SelfControlQrManager.getInstance(applicationContext)
            if (qrManager.isQrLockEnabled()) {
                val tag = "qr_scanner_app_manager"
                if (supportFragmentManager.isStateSaved || supportFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
                QrScannerBottomSheet.newInstance().apply {
                    onVerified = { appManagerLauncher.launch(intent) }
                    onEmergencyBypassTriggered = { appManagerLauncher.launch(intent) }
                }.show(supportFragmentManager, tag)
            } else {
                appManagerLauncher.launch(intent)
            }
        }

        // QR Lock listeners
        switchQrLockEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isPolicyLoading) return@setOnCheckedChangeListener
            val qrManager = SelfControlQrManager.getInstance(applicationContext)
            if (isChecked) {
                if (!qrManager.hasToken()) {
                    qrManager.generateAndSaveNewToken()
                }
                qrManager.setQrLockEnabled(true)
                layoutQrActionButtons.isVisible = true
                if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentByTag(QrExportDialog.TAG) == null) {
                    QrExportDialog.newInstance().show(supportFragmentManager, QrExportDialog.TAG)
                }
            } else {

                // If QR lock is currently active, disabling it requires scanning the QR code or emergency bypass
                if (qrManager.isQrLockEnabled()) {

                    // Keep switch checked visually until authentication succeeds
                    isPolicyLoading = true
                    switchQrLockEnabled.isChecked = true
                    isPolicyLoading = false
                    val tag = "qr_scanner_disable_barrier"
                    if (supportFragmentManager.isStateSaved || supportFragmentManager.findFragmentByTag(tag) != null) return@setOnCheckedChangeListener
                    val scannerSheet = QrScannerBottomSheet.newInstance().apply {
                        onVerified = {
                            qrManager.setQrLockEnabled(false)
                            isPolicyLoading = true
                            switchQrLockEnabled.isChecked = false
                            layoutQrActionButtons.isVisible = false
                            isPolicyLoading = false
                            Toast.makeText(this@SelfControlActivity, R.string.self_control_qr_disabled_toast, Toast.LENGTH_SHORT).show()
                        }
                        onEmergencyBypassTriggered = {
                            qrManager.setQrLockEnabled(false)
                            isPolicyLoading = true
                            switchQrLockEnabled.isChecked = false
                            layoutQrActionButtons.isVisible = false
                            isPolicyLoading = false
                            Toast.makeText(this@SelfControlActivity, R.string.self_control_qr_disabled_toast, Toast.LENGTH_SHORT).show()
                        }
                    }
                    scannerSheet.show(supportFragmentManager, tag)
                } else {
                    layoutQrActionButtons.isVisible = false
                }
            }
        }
        btnShowQrCode.setOnClickListener {
            val qrManager = SelfControlQrManager.getInstance(applicationContext)
            if (!qrManager.hasToken()) {
                qrManager.generateAndSaveNewToken()
            }
            if (qrManager.isQrLockEnabled()) {
                val tag = "qr_scanner_show_code"
                if (supportFragmentManager.isStateSaved || supportFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
                QrScannerBottomSheet.newInstance().apply {
                    onVerified = {
                        if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentByTag(QrExportDialog.TAG) == null) {
                            QrExportDialog.newInstance().show(supportFragmentManager, QrExportDialog.TAG)
                        }
                    }
                    onEmergencyBypassTriggered = {
                        if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentByTag(QrExportDialog.TAG) == null) {
                            QrExportDialog.newInstance().show(supportFragmentManager, QrExportDialog.TAG)
                        }
                    }
                }.show(supportFragmentManager, tag)
            } else {
                if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentByTag(QrExportDialog.TAG) == null) {
                    QrExportDialog.newInstance().show(supportFragmentManager, QrExportDialog.TAG)
                }
            }
        }
        btnRegenerateQrCode.setOnClickListener {
            val qrManager = SelfControlQrManager.getInstance(applicationContext)
            val proceedWithRegeneration = {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.self_control_qr_regenerate_confirm_title)
                    .setMessage(R.string.self_control_qr_regenerate_confirm_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.self_control_qr_btn_regenerate_confirm) { _, _ ->
                        qrManager.generateAndSaveNewToken()
                        qrManager.setQrLockEnabled(true)
                        isPolicyLoading = true
                        switchQrLockEnabled.isChecked = true
                        layoutQrActionButtons.isVisible = true
                        isPolicyLoading = false
                        if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentByTag(QrExportDialog.TAG) == null) {
                            QrExportDialog.newInstance().show(supportFragmentManager, QrExportDialog.TAG)
                        }
                    }
                    .show()
            }
            if (qrManager.isQrLockEnabled()) {
                val tag = "qr_scanner_regenerate_auth"
                if (supportFragmentManager.isStateSaved || supportFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
                val scannerSheet = QrScannerBottomSheet.newInstance().apply {
                    onVerified = {
                        proceedWithRegeneration()
                    }
                    onEmergencyBypassTriggered = {
                        proceedWithRegeneration()
                    }
                }
                scannerSheet.show(supportFragmentManager, tag)
            } else {
                proceedWithRegeneration()
            }
        }
        var lastBrowserFilterClickTime = 0L
        btnManageBrowserUrls.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBrowserFilterClickTime < 800L) return@setOnClickListener
            lastBrowserFilterClickTime = now
            val launchAction = {
                val intent = Intent(this, BrowserUrlFilterActivity::class.java).apply {
                    putExtra(BrowserUrlFilterActivity.EXTRA_IS_REMOTE, false)
                    putExtra(BrowserUrlFilterActivity.EXTRA_POLICY_JSON, currentPolicy.toJson())
                }
                browserFilterLauncher.launch(intent)
            }
            val qrManager = SelfControlQrManager.getInstance(applicationContext)
            if (qrManager.isQrLockEnabled()) {
                val tag = "qr_scanner_browser_filter"
                if (supportFragmentManager.isStateSaved || supportFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
                QrScannerBottomSheet.newInstance().apply {
                    onVerified = { launchAction() }
                    onEmergencyBypassTriggered = { launchAction() }
                }.show(supportFragmentManager, tag)
            } else {
                launchAction()
            }
        }
        btnSaveSelfControlChanges.setOnClickListener {
            saveChanges()
        }
    }

    private fun loadCurrentPolicy() {
        isPolicyLoading = true
        try {
            val policy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
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
            switchCurfewMaster.isChecked = policy.isCurfewEnabled
            switchWeekendCurfew.isChecked = policy.isWeekendCurfewEnabled
            switchVpnBlackhole.isChecked = policy.isVpnBlackholeEnabled
            switchInstantInternetKill.isChecked = policy.isInstantInternetKillEnabled
            switchIncognitoBlocked.isChecked = policy.isIncognitoBlocked
            switchLockNewInstalls.isChecked = policy.lockNewInstallsUntilApproved
            switchLockDateTime.isChecked = policy.isDateTimeLocked
            // cardTimezoneControl is always visible
            val qrManager = SelfControlQrManager.getInstance(applicationContext)
            val isQrEnabled = qrManager.isQrLockEnabled()
            switchQrLockEnabled.isChecked = isQrEnabled
            layoutQrActionButtons.isVisible = isQrEnabled
            etDnsHost.setText(policy.dnsHost)
        updateBrowserFilterStatus(policy)
            updateTimingLabels()
            updateTimezoneViews()
            updateSaveButtonState()
        } finally {
            isPolicyLoading = false
        }
    }

    private fun reloadPackageCollectionsOnly() {
        val latestPolicy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
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
        tvBrowserFilterStatus.text = when (policy.browserFilterMode) {
            DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST ->
                getString(R.string.browser_filter_status_blocklist, policy.customBlockedUrls.size)
            DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY ->
                getString(R.string.browser_filter_status_whitelist, policy.customAllowedUrls.size)
            else ->
                getString(R.string.browser_filter_status_off)
        }
    }

    private fun updateTimingLabels() {
        val is24Hour = android.text.format.DateFormat.is24HourFormat(this)
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
        tvCurfewStartTime.text = formatTime(selectedStartHour, selectedStartMinute)
        tvCurfewEndTime.text = formatTime(selectedEndHour, selectedEndMinute)
        tvWeekendStartTime.text = formatTime(selectedWeekendStartHour, selectedWeekendStartMinute)
        tvWeekendEndTime.text = formatTime(selectedWeekendEndHour, selectedWeekendEndMinute)
    }

    private fun updateTimezoneViews() {
        val option = IslamicTimeZoneRegistry.findByIanaId(selectedTimeZoneId)
        val cityName = option.majorCities.split("/").firstOrNull()?.trim() ?: option.majorCities
        tvTimezoneBadge.text = getString(R.string.guardian_timezone_badge_format, option.getLiveOffsetLabel(), cityName)
        actvTimezonePicker.setText(option.displayText, false)
        try {
            val tz = TimeZone.getTimeZone(option.ianaTimeZoneId)
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault()).apply {
                timeZone = tz
            }
            val formattedTime = sdf.format(Date())
            val timeWithOffset = getString(R.string.guardian_timezone_preview_time_format, formattedTime, option.getLiveOffsetLabel())
            tvTimezonePreview.text = getString(
                R.string.guardian_timezone_preview_format,
                timeWithOffset
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed formatting timezone preview: ${e.message}", e)
            tvTimezonePreview.text = getString(
                R.string.guardian_timezone_preview_format,
                option.gmtOffsetLabel
            )
        }
    }

    private fun loadPendingApprovals() {
        lifecycleScope.launch(Dispatchers.IO) {
            val approvalRepo = ApprovalRepository.getInstance(applicationContext)
            val pendingList = approvalRepo.getLocalPendingApprovals()
                .filter { !it.isApproved && !it.isRejected }
            val pm = packageManager
            val items = pendingList.map { entity ->
                val appName = try {
                    val appInfo = pm.getApplicationInfo(entity.packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    Log.d(TAG, "Package label not found for ${entity.packageName}, falling back to package name: ${e.message}")
                    entity.packageName
                }
                Pair(entity.packageName, appName)
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                renderPendingApprovals(items)
            }
        }
    }

    private fun renderPendingApprovals(items: List<Pair<String, String>>) {
        layoutPendingApps.removeAllViews()
        if (items.isEmpty()) {
            layoutPendingApps.addView(tvNoPendingApps)
            tvNoPendingApps.visibility = View.VISIBLE
            return
        }
        tvNoPendingApps.visibility = View.GONE
        for ((pkg, name) in items) {
            val card = MaterialCardView(this).apply {
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
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val titleView = TextView(this).apply {
                text = name
                textSize = 14f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                typeface = Typeface.DEFAULT_BOLD
            }
            container.addView(titleView)
            val pkgView = TextView(this).apply {
                text = pkg
                textSize = 11f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
            }
            container.addView(pkgView)
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            lateinit var approveBtn: MaterialButton
            lateinit var rejectBtn: MaterialButton
            approveBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                text = getString(R.string.btn_approve_app)
                textSize = 12f
                cornerRadius = (8 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    val executeApproval = {
                        approveBtn.isEnabled = false
                        rejectBtn.isEnabled = false
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                ApprovalRepository.getInstance(applicationContext).unsuspendAndApprove(pkg, "SELF")
                                withContext(Dispatchers.Main) {
                                    if (!isFinishing && !isDestroyed) {
                                        loadPendingApprovals()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed approving package $pkg", e)
                                withContext(Dispatchers.Main) {
                                    if (!isFinishing && !isDestroyed) {
                                        approveBtn.isEnabled = true
                                        rejectBtn.isEnabled = true
                                        Toast.makeText(this@SelfControlActivity, R.string.toast_self_policy_save_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                    val qrManager = SelfControlQrManager.getInstance(applicationContext)
                    if (qrManager.isQrLockEnabled()) {
                        val tag = "qr_scanner_approve_app"
                        if (supportFragmentManager.isStateSaved || supportFragmentManager.findFragmentByTag(tag) != null) return@setOnClickListener
                        QrScannerBottomSheet.newInstance().apply {
                            onVerified = { executeApproval() }
                            onEmergencyBypassTriggered = { executeApproval() }
                        }.show(supportFragmentManager, tag)
                    } else {
                        executeApproval()
                    }
                }
            }
            btnRow.addView(approveBtn)
            rejectBtn = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.btn_reject_app)
                textSize = 12f
                setTextColor(getColor(R.color.status_orange))
                setOnClickListener {
                    approveBtn.isEnabled = false
                    rejectBtn.isEnabled = false
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            ApprovalRepository.getInstance(applicationContext).recordRejection(pkg, "SELF")
                            withContext(Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed) {
                                    loadPendingApprovals()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed recording rejection for package $pkg", e)
                            withContext(Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed) {
                                    approveBtn.isEnabled = true
                                    rejectBtn.isEnabled = true
                                    Toast.makeText(this@SelfControlActivity, R.string.toast_self_policy_save_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
            btnRow.addView(rejectBtn)
            container.addView(btnRow)
            card.addView(container)
            layoutPendingApps.addView(card)
        }
    }

    private fun hasUnsavedChanges(): Boolean {
        val snapshot = initialPolicySnapshot ?: return false
        val cleanHost = etDnsHost.text?.toString()?.trim().orEmpty()
        return selectedStartHour != snapshot.curfewStartHour ||
                selectedStartMinute != snapshot.curfewStartMinute ||
                selectedEndHour != snapshot.curfewEndHour ||
                selectedEndMinute != snapshot.curfewEndMinute ||
                selectedWeekendStartHour != snapshot.weekendCurfewStartHour ||
                selectedWeekendStartMinute != snapshot.weekendCurfewStartMinute ||
                selectedWeekendEndHour != snapshot.weekendCurfewEndHour ||
                selectedWeekendEndMinute != snapshot.weekendCurfewEndMinute ||
                selectedTimeZoneId != snapshot.timeZoneId.ifBlank { CurfewConfig.MAKKAH_TIMEZONE_ID } ||
                switchCurfewMaster.isChecked != snapshot.isCurfewEnabled ||
                switchWeekendCurfew.isChecked != snapshot.isWeekendCurfewEnabled ||
                switchVpnBlackhole.isChecked != snapshot.isVpnBlackholeEnabled ||
                switchInstantInternetKill.isChecked != snapshot.isInstantInternetKillEnabled ||
                switchIncognitoBlocked.isChecked != snapshot.isIncognitoBlocked ||
                switchLockNewInstalls.isChecked != snapshot.lockNewInstallsUntilApproved ||
                switchLockDateTime.isChecked != snapshot.isDateTimeLocked ||
                cleanHost != snapshot.dnsHost
    }

    private fun updateSaveButtonState() {
        val dirty = hasUnsavedChanges()
        btnSaveSelfControlChanges.isEnabled = dirty && !isSavingPolicy
        btnSaveSelfControlChanges.alpha = if (dirty) 1.0f else 0.5f
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
                onBackPressedDispatcher.onBackPressed()
            }
            .show()
    }

    private fun saveChanges() {
        if (isSavingPolicy || !hasUnsavedChanges()) return
        val hostInput = etDnsHost.text?.toString()?.trim().orEmpty()
        val dnsToSave = hostInput.ifBlank { DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST }
        val updatedPolicy = currentPolicy.copy(
            curfewStartHour = selectedStartHour,
            curfewStartMinute = selectedStartMinute,
            curfewEndHour = selectedEndHour,
            curfewEndMinute = selectedEndMinute,
            isCurfewEnabled = switchCurfewMaster.isChecked,
            isWeekendCurfewEnabled = switchWeekendCurfew.isChecked,
            weekendCurfewStartHour = selectedWeekendStartHour,
            weekendCurfewStartMinute = selectedWeekendStartMinute,
            weekendCurfewEndHour = selectedWeekendEndHour,
            weekendCurfewEndMinute = selectedWeekendEndMinute,
            isVpnBlackholeEnabled = switchVpnBlackhole.isChecked,
            isInstantInternetKillEnabled = switchInstantInternetKill.isChecked,
            isIncognitoBlocked = switchIncognitoBlocked.isChecked,
            lockNewInstallsUntilApproved = switchLockNewInstalls.isChecked,
            isDateTimeLocked = switchLockDateTime.isChecked,
            timeZoneId = selectedTimeZoneId,
            dnsHost = dnsToSave
        )
        val qrManager = SelfControlQrManager.getInstance(applicationContext)
        if (qrManager.isQrLockEnabled()) {
            val tag = "qr_scanner_bottom_sheet"
            if (supportFragmentManager.isStateSaved || supportFragmentManager.findFragmentByTag(tag) != null) return
            val scannerSheet = QrScannerBottomSheet.newInstance().apply {
                onVerified = {
                    persistUpdatedPolicy(updatedPolicy)
                }
                onEmergencyBypassTriggered = {
                    persistUpdatedPolicy(updatedPolicy)
                }
            }
            scannerSheet.show(supportFragmentManager, tag)
        } else {
            persistUpdatedPolicy(updatedPolicy)
        }
    }

    private fun persistUpdatedPolicy(updatedPolicy: DevicePolicyBundle) {
        if (isSavingPolicy) return
        isSavingPolicy = true
        btnSaveSelfControlChanges.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            try {
                success = PolicyRepository.getInstance(applicationContext).saveAndEnforceSelfPolicy(updatedPolicy)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist self policy", e)
                success = false
            } finally {
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    isSavingPolicy = false
                    if (success) {
                        currentPolicy = updatedPolicy
                        initialPolicySnapshot = updatedPolicy
                        updateSaveButtonState()
                        Toast.makeText(this@SelfControlActivity, R.string.toast_self_policy_saved, Toast.LENGTH_SHORT).show()
                        setResult(Activity.RESULT_OK)
                        finish()
                    } else {
                        updateSaveButtonState()
                        Toast.makeText(this@SelfControlActivity, R.string.toast_self_policy_save_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_SelfControl"
    }
}
