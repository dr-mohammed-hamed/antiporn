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
    private lateinit var cardOfflineBanner: MaterialCardView
    private lateinit var tvOfflineStatus: TextView
    private lateinit var btnRetryLoadPolicy: MaterialButton
    private lateinit var cardBreakoutAlertBanner: MaterialCardView
    private lateinit var tvBreakoutAlertTitle: TextView
    private lateinit var tvBreakoutAlertMsg: TextView
    private lateinit var btnRejectBreakout: MaterialButton

    // Self-Control Permission
    private lateinit var cardSelfControlAlertBanner: MaterialCardView
    private lateinit var tvSelfControlAlertTitle: TextView
    private lateinit var tvSelfControlAlertMsg: TextView
    private lateinit var btnRevertSelfControlChanges: MaterialButton
    private lateinit var btnDismissSelfControlAlert: MaterialButton
    private lateinit var cardSelfControlPermission: MaterialCardView
    private lateinit var switchAllowSelfControl: MaterialSwitch

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
    private var selectedTimeZoneId = CurfewConfig.MAKKAH_TIMEZONE_ID

    private lateinit var approvalsViewBinder: GuardianApprovalsViewBinder
    private var hasLoadedInitialPolicy = false
    private var lastEditNameClickTime = 0L
    private var lastRejectBreakoutClickTime = 0L
    private var lastAutoDetectClickTime = 0L
    private val policyRepo by lazy { PolicyRepository.getInstance(applicationContext) }
    private var lastSaveClickTime = 0L
    private var approvalsListener: ListenerRegistration? = null

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
        listenForPendingApprovals()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        if (!hasLoadedInitialPolicy) {
            hasLoadedInitialPolicy = true
            loadWardPolicy()
        }
        loadPendingApprovals()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarGuardianControl)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        tvWardName = findViewById(R.id.tvWardName)
        tvWardName.text = getString(R.string.guardian_control_ward_name, wardName)
        btnEditWardName = findViewById(R.id.btnEditWardName)

        cardOfflineBanner = findViewById(R.id.cardOfflineBanner)
        tvOfflineStatus = findViewById(R.id.tvOfflineStatus)
        btnRetryLoadPolicy = findViewById(R.id.btnRetryLoadPolicy)
        cardBreakoutAlertBanner = findViewById(R.id.cardBreakoutAlertBanner)
        tvBreakoutAlertTitle = findViewById(R.id.tvBreakoutAlertTitle)
        tvBreakoutAlertMsg = findViewById(R.id.tvBreakoutAlertMsg)
        btnRejectBreakout = findViewById(R.id.btnRejectBreakout)

        // Self-Control Permission
        cardSelfControlAlertBanner = findViewById(R.id.cardSelfControlAlertBanner)
        tvSelfControlAlertTitle = findViewById(R.id.tvSelfControlAlertTitle)
        tvSelfControlAlertMsg = findViewById(R.id.tvSelfControlAlertMsg)
        btnRevertSelfControlChanges = findViewById(R.id.btnRevertSelfControlChanges)
        btnDismissSelfControlAlert = findViewById(R.id.btnDismissSelfControlAlert)
        cardSelfControlPermission = findViewById(R.id.cardSelfControlPermission)
        switchAllowSelfControl = findViewById(R.id.switchAllowSelfControl)

        // Master Curfew & Weekday
        switchCurfewMaster = findViewById(R.id.switchCurfewMaster)
        tvCurfewStartTime = findViewById(R.id.tvCurfewStartTime)
        tvCurfewEndTime = findViewById(R.id.tvCurfewEndTime)
        btnChangeStartTime = findViewById(R.id.btnChangeStartTime)
        btnChangeEndTime = findViewById(R.id.btnChangeEndTime)

        // Weekend Curfew
        switchWeekendCurfew = findViewById(R.id.switchWeekendCurfew)
        tvWeekendStartTime = findViewById(R.id.tvWeekendStartTime)
        tvWeekendEndTime = findViewById(R.id.tvWeekendEndTime)
        btnChangeWeekendStartTime = findViewById(R.id.btnChangeWeekendStartTime)
        btnChangeWeekendEndTime = findViewById(R.id.btnChangeWeekendEndTime)

        // Date & Time Lock control
        switchLockDateTime = findViewById(R.id.switchLockDateTime)

        // Timezone controls
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

        val timezoneOptions = IslamicTimeZoneRegistry.getAllOptions()
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, timezoneOptions)
        actvTimezonePicker.setAdapter(adapter)

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
            onReloadRequested = { loadPendingApprovals() }
        )

        setInputsEnabled(false)
    }

    private fun setInputsEnabled(enabled: Boolean) {
        switchAllowSelfControl.isEnabled = enabled
        switchCurfewMaster.isEnabled = enabled
        btnChangeStartTime.isEnabled = enabled
        btnChangeEndTime.isEnabled = enabled
        switchWeekendCurfew.isEnabled = enabled
        btnChangeWeekendStartTime.isEnabled = enabled
        btnChangeWeekendEndTime.isEnabled = enabled
        switchVpnBlackhole.isEnabled = enabled
        switchInstantInternetKill.isEnabled = enabled
        switchIncognitoBlocked.isEnabled = enabled
        switchLockNewInstalls.isEnabled = enabled
        switchLockDateTime.isEnabled = enabled
        btnOpenAppManager.isEnabled = enabled
        btnManageBrowserUrls.isEnabled = enabled
        etDnsHost.isEnabled = enabled
        btnPresetAdguard.isEnabled = enabled
        btnPresetCloudflare.isEnabled = enabled
        btnPresetNextDns.isEnabled = enabled
        btnRetryLoadPolicy.isEnabled = enabled
        tilTimezonePicker.isEnabled = enabled
        actvTimezonePicker.isEnabled = enabled
        btnResetToMakkah.isEnabled = enabled
        btnAutoDetectTimezone.isEnabled = enabled
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
        btnRevertSelfControlChanges.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            val snapshotJson = currentPolicy.guardianPolicySnapshot
            if (snapshotJson.isNullOrBlank()) {
                cardSelfControlAlertBanner.visibility = View.GONE
                return@setOnClickListener
            }
            btnRevertSelfControlChanges.isEnabled = false
            lifecycleScope.launch {
                try {
                    val snapshotPolicy = DevicePolicyBundle.fromJson(snapshotJson)
                    val myUid = AccountabilityRepository.getInstance(applicationContext).getCachedUid()
                    val myName = AccountabilityRepository.getInstance(applicationContext).getMyDisplayName()
                    val revertedPolicy = snapshotPolicy.copy(
                        guardianUid = currentPolicy.guardianUid?.takeIf { it.isNotBlank() } ?: myUid,
                        guardianName = currentPolicy.guardianName?.takeIf { it.isNotBlank() } ?: myName,
                        isSupervised = true,
                        isSelfControlAllowed = switchAllowSelfControl.isChecked,
                        guardianPolicySnapshot = null,
                        lastSelfModifiedAt = null,
                        version = currentPolicy.version + 1,
                        updatedAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(applicationContext)
                    )
                    val success = policyRepo.pushPolicyToWard(wardId, revertedPolicy)
                    if (isFinishing || isDestroyed) return@launch
                    if (success) {
                        currentPolicy = revertedPolicy
                        populateUiFromPolicy(revertedPolicy)
                        initialPolicySnapshot = revertedPolicy.copy()
                        cardSelfControlAlertBanner.visibility = View.GONE
                        Toast.makeText(this@GuardianControlActivity, R.string.toast_self_control_reverted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@GuardianControlActivity, R.string.toast_policy_push_failed, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Error reverting self-control policy", e)
                } finally {
                    if (!isFinishing && !isDestroyed) {
                        btnRevertSelfControlChanges.isEnabled = true
                    }
                }
            }
        }

        btnDismissSelfControlAlert.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            btnDismissSelfControlAlert.isEnabled = false
            lifecycleScope.launch {
                try {
                    val myUid = AccountabilityRepository.getInstance(applicationContext).getCachedUid()
                    val myName = AccountabilityRepository.getInstance(applicationContext).getMyDisplayName()
                    val acknowledgedPolicy = currentPolicy.copy(
                        guardianUid = currentPolicy.guardianUid?.takeIf { it.isNotBlank() } ?: myUid,
                        guardianName = currentPolicy.guardianName?.takeIf { it.isNotBlank() } ?: myName,
                        isSupervised = true,
                        guardianPolicySnapshot = null,
                        lastSelfModifiedAt = null,
                        version = currentPolicy.version + 1,
                        updatedAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(applicationContext)
                    )
                    val success = policyRepo.pushPolicyToWard(wardId, acknowledgedPolicy)
                    if (isFinishing || isDestroyed) return@launch
                    if (success) {
                        currentPolicy = acknowledgedPolicy
                        initialPolicySnapshot = acknowledgedPolicy.copy()
                        cardSelfControlAlertBanner.visibility = View.GONE
                        Toast.makeText(this@GuardianControlActivity, R.string.toast_self_control_dismissed, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@GuardianControlActivity, R.string.toast_policy_push_failed, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Error dismissing self-control alert", e)
                } finally {
                    if (!isFinishing && !isDestroyed) {
                        btnDismissSelfControlAlert.isEnabled = true
                    }
                }
            }
        }

        btnRejectBreakout.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastRejectBreakoutClickTime < 1000L) return@setOnClickListener
            lastRejectBreakoutClickTime = now
            btnRejectBreakout.isEnabled = false
            lifecycleScope.launch {
                try {
                    val result = com.drmohammed.antiporn.data.repository.AccountabilityRepository.getInstance(applicationContext).rejectEmergencyBreakout(wardId)
                    if (isFinishing || isDestroyed) return@launch
                    result.fold(
                        onSuccess = {
                            Toast.makeText(this@GuardianControlActivity, R.string.toast_breakout_rejected, Toast.LENGTH_SHORT).show()
                            cardBreakoutAlertBanner.visibility = View.GONE
                        },
                        onFailure = { err ->
                            Toast.makeText(this@GuardianControlActivity, err.localizedMessage ?: getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
                        }
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Error rejecting breakout", e)
                } finally {
                    if (!isFinishing && !isDestroyed) {
                        btnRejectBreakout.isEnabled = true
                    }
                }
            }
        }

        btnChangeStartTime.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            if (supportFragmentManager.findFragmentByTag("start_time_picker") != null) return@setOnClickListener
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
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
            picker.show(supportFragmentManager, "start_time_picker")
        }

        btnChangeEndTime.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            if (supportFragmentManager.findFragmentByTag("end_time_picker") != null) return@setOnClickListener
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
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
            picker.show(supportFragmentManager, "end_time_picker")
        }

        btnChangeWeekendStartTime.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            if (supportFragmentManager.findFragmentByTag("weekend_start_time_picker") != null) return@setOnClickListener
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
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
            picker.show(supportFragmentManager, "weekend_start_time_picker")
        }

        btnChangeWeekendEndTime.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            if (supportFragmentManager.findFragmentByTag("weekend_end_time_picker") != null) return@setOnClickListener
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
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
            picker.show(supportFragmentManager, "weekend_end_time_picker")
        }

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

        btnAutoDetectTimezone.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAutoDetectClickTime < 1000L) return@setOnClickListener
            lastAutoDetectClickTime = now
            val detected = IslamicTimeZoneRegistry.detectCurrentDeviceOption()
            if (selectedTimeZoneId != detected.id) {
                selectedTimeZoneId = detected.id
                updateTimezoneViews()
                updateSaveButtonState()
            }
            android.widget.Toast.makeText(this, getString(R.string.timezone_detected_toast, detected.displayText), android.widget.Toast.LENGTH_SHORT).show()
        }

        btnResetToMakkah.setOnClickListener {
            if (selectedTimeZoneId != CurfewConfig.MAKKAH_TIMEZONE_ID) {
                selectedTimeZoneId = CurfewConfig.MAKKAH_TIMEZONE_ID
                updateTimezoneViews()
                updateSaveButtonState()
            }
        }

        val switchListener = { _: View, _: Boolean -> updateSaveButtonState() }
        switchAllowSelfControl.setOnCheckedChangeListener(switchListener)
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
            loadPendingApprovals()
            listenForPendingApprovals()
        }
    }

    private fun updateTimingLabels() {
        tvCurfewStartTime.text = getString(R.string.guardian_curfew_time_format, selectedStartHour, selectedStartMinute)
        tvCurfewEndTime.text = getString(R.string.guardian_curfew_time_format, selectedEndHour, selectedEndMinute)
        tvWeekendStartTime.text = getString(R.string.guardian_curfew_time_format, selectedWeekendStartHour, selectedWeekendStartMinute)
        tvWeekendEndTime.text = getString(R.string.guardian_curfew_time_format, selectedWeekendEndHour, selectedWeekendEndMinute)
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
            tvTimezonePreview.text = getString(
                R.string.guardian_timezone_preview_format,
                option.gmtOffsetLabel
            )
        }
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

        if (!policy.guardianPolicySnapshot.isNullOrBlank() || policy.lastSelfModifiedAt != null) {
            cardSelfControlAlertBanner.visibility = View.VISIBLE
            val formattedTime = if (policy.lastSelfModifiedAt != null) {
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                sdf.format(java.util.Date(policy.lastSelfModifiedAt))
            } else {
                ""
            }
            tvSelfControlAlertMsg.text = getString(R.string.guardian_self_control_alert_msg, wardName, formattedTime)
        } else {
            cardSelfControlAlertBanner.visibility = View.GONE
        }

        if (policy.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING && policy.breakoutUnlockAt != null) {
            cardBreakoutAlertBanner.visibility = View.VISIBLE
            val nowTrusted = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(applicationContext)
            val remainingMs = (policy.breakoutUnlockAt - nowTrusted).coerceAtLeast(0L)
            val hours = remainingMs / (1000 * 3600)
            val mins = (remainingMs % (1000 * 3600)) / (1000 * 60)
            val timeStr = String.format(Locale.US, "%02d:%02d", hours, mins)
            tvBreakoutAlertMsg.text = getString(R.string.guardian_breakout_alert_msg, wardName, timeStr)
        } else {
            cardBreakoutAlertBanner.visibility = View.GONE
        }
    }

    private fun loadWardPolicy() {
        if (approvalsListener == null) {
            listenForPendingApprovals()
        }
        setInputsEnabled(false)
        lifecycleScope.launch(Dispatchers.IO) {
            if (isFinishing || isDestroyed) return@launch
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

                if (snapshot != null && snapshot.exists() && snapshot.data != null) {
                    currentPolicy = DevicePolicyBundle.fromMap(snapshot.data!!)
                } else {
                    currentPolicy = DevicePolicyBundle.defaultPolicy()
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    populateUiFromPolicy(currentPolicy)
                    cardOfflineBanner.visibility = if (isOffline) View.VISIBLE else View.GONE
                    btnRetryLoadPolicy.isEnabled = true

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

    private fun loadPendingApprovals() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (isFinishing || isDestroyed) return@launch
            try {
                val firestore = FirebaseFirestore.getInstance()
                val snapshot = firestore.collection("policies").document(wardId)
                    .collection("approvals")
                    .whereEqualTo("status", "PENDING")
                    .get()
                    .await()

                val pendingList = mutableListOf<Pair<String, String>>()
                for (doc in snapshot.documents) {
                    val pkg = doc.getString("packageName") ?: doc.id
                    val appName = doc.getString("appName") ?: pkg
                    if (pkg.isNotBlank()) {
                        pendingList.add(pkg to appName)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    approvalsViewBinder.renderPendingApprovals(pendingList)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isFinishing || isDestroyed) return@launch
                Log.e(TAG, "Error loading pending approvals for ward $wardId", e)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    approvalsViewBinder.renderError()
                    Toast.makeText(this@GuardianControlActivity, R.string.error_loading_pending_apps, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun listenForPendingApprovals() {
        if (wardId.isBlank()) return
        approvalsListener?.remove()
        try {
            val firestore = FirebaseFirestore.getInstance()
            approvalsListener = firestore.collection("policies").document(wardId)
                .collection("approvals")
                .whereEqualTo("status", "PENDING")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Error in real-time approvals listener for ward $wardId", error)
                        return@addSnapshotListener
                    }
                    if (isFinishing || isDestroyed) return@addSnapshotListener
                    if (snapshot != null) {
                        val pendingList = mutableListOf<Pair<String, String>>()
                        for (doc in snapshot.documents) {
                            val pkg = doc.getString("packageName") ?: doc.id
                            val appName = doc.getString("appName") ?: pkg
                            if (pkg.isNotBlank()) {
                                pendingList.add(pkg to appName)
                            }
                        }
                        approvalsViewBinder.renderPendingApprovals(pendingList)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach real-time approvals listener for ward $wardId", e)
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
                if (isFinishing || isDestroyed) return@launch
                if (success) {
                    cardSelfControlAlertBanner.visibility = View.GONE
                    initialPolicySnapshot = updatedPolicy.copy()
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
        approvalsListener?.remove()
        approvalsListener = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AntiPorn_GuardianCtrl"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_WARD_NAME = "extra_ward_name"
    }
}
