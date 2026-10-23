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
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.repository.PolicyRepository
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
    private lateinit var cardOfflineBanner: MaterialCardView
    private lateinit var tvOfflineStatus: TextView
    private lateinit var btnRetryLoadPolicy: MaterialButton

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

    // Enforcement & Security controls
    private lateinit var switchVpnBlackhole: MaterialSwitch
    private lateinit var switchInstantInternetKill: MaterialSwitch
    private lateinit var switchIncognitoBlocked: MaterialSwitch
    private lateinit var switchLockNewInstalls: MaterialSwitch

    // Remote App Manager
    private lateinit var btnOpenAppManager: MaterialButton

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

    private lateinit var approvalsViewBinder: GuardianApprovalsViewBinder
    private var hasLoadedInitialPolicy = false

    private val appManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadWardPolicy()
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
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
        setContentView(R.layout.activity_guardian_control)

        findViewById<View>(R.id.layoutRootGuardianControl)?.let { rootView ->
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
                findViewById<View>(R.id.cardBottomBarGuardian)?.setPadding(
                    0, 0, 0, insets.bottom
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

        cardOfflineBanner = findViewById(R.id.cardOfflineBanner)
        tvOfflineStatus = findViewById(R.id.tvOfflineStatus)
        btnRetryLoadPolicy = findViewById(R.id.btnRetryLoadPolicy)

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

        // Enforcement & Zero Trust
        switchVpnBlackhole = findViewById(R.id.switchVpnBlackhole)
        switchInstantInternetKill = findViewById(R.id.switchInstantInternetKill)
        switchIncognitoBlocked = findViewById(R.id.switchIncognitoBlocked)
        switchLockNewInstalls = findViewById(R.id.switchLockNewInstalls)

        // App Manager
        btnOpenAppManager = findViewById(R.id.btnOpenAppManager)

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
        btnOpenAppManager.isEnabled = enabled
        etDnsHost.isEnabled = enabled
        btnPresetAdguard.isEnabled = enabled
        btnPresetCloudflare.isEnabled = enabled
        btnPresetNextDns.isEnabled = enabled
        btnRetryLoadPolicy.isEnabled = enabled
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
        return selectedStartHour != snapshot.curfewStartHour ||
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
                getCleanDnsHost() != snapshot.dnsHost
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

    private fun setupListeners() {
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

        val switchListener = { _: View, _: Boolean -> updateSaveButtonState() }
        switchCurfewMaster.setOnCheckedChangeListener(switchListener)
        switchWeekendCurfew.setOnCheckedChangeListener(switchListener)
        switchVpnBlackhole.setOnCheckedChangeListener(switchListener)
        switchInstantInternetKill.setOnCheckedChangeListener(switchListener)
        switchIncognitoBlocked.setOnCheckedChangeListener(switchListener)
        switchLockNewInstalls.setOnCheckedChangeListener(switchListener)

        etDnsHost.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSaveButtonState()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnOpenAppManager.setOnClickListener {
            val intent = Intent(this@GuardianControlActivity, RemoteAppManagerActivity::class.java).apply {
                putExtra(RemoteAppManagerActivity.EXTRA_WARD_ID, wardId)
                putExtra(RemoteAppManagerActivity.EXTRA_WARD_NAME, wardName)
            }
            appManagerLauncher.launch(intent)
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
            saveAndPushPolicy()
        }

        btnRetryLoadPolicy.setOnClickListener {
            btnRetryLoadPolicy.isEnabled = false
            loadWardPolicy()
            loadPendingApprovals()
        }
    }

    private fun updateTimingLabels() {
        tvCurfewStartTime.text = getString(R.string.guardian_curfew_time_format, selectedStartHour, selectedStartMinute)
        tvCurfewEndTime.text = getString(R.string.guardian_curfew_time_format, selectedEndHour, selectedEndMinute)
        tvWeekendStartTime.text = getString(R.string.guardian_curfew_time_format, selectedWeekendStartHour, selectedWeekendStartMinute)
        tvWeekendEndTime.text = getString(R.string.guardian_curfew_time_format, selectedWeekendEndHour, selectedWeekendEndMinute)
    }

    private fun loadWardPolicy() {
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

                selectedStartHour = currentPolicy.curfewStartHour
                selectedStartMinute = currentPolicy.curfewStartMinute
                selectedEndHour = currentPolicy.curfewEndHour
                selectedEndMinute = currentPolicy.curfewEndMinute

                selectedWeekendStartHour = currentPolicy.weekendCurfewStartHour
                selectedWeekendStartMinute = currentPolicy.weekendCurfewStartMinute
                selectedWeekendEndHour = currentPolicy.weekendCurfewEndHour
                selectedWeekendEndMinute = currentPolicy.weekendCurfewEndMinute

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    updateTimingLabels()
                    switchCurfewMaster.isChecked = currentPolicy.isCurfewEnabled
                    switchWeekendCurfew.isChecked = currentPolicy.isWeekendCurfewEnabled
                    switchVpnBlackhole.isChecked = currentPolicy.isVpnBlackholeEnabled
                    switchInstantInternetKill.isChecked = currentPolicy.isInstantInternetKillEnabled
                    switchIncognitoBlocked.isChecked = currentPolicy.isIncognitoBlocked
                    switchLockNewInstalls.isChecked = currentPolicy.lockNewInstallsUntilApproved
                    etDnsHost.setText(currentPolicy.dnsHost)
                    tilDnsHost.error = null
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
                        isVpnBlackholeEnabled = switchVpnBlackhole.isChecked,
                        isInstantInternetKillEnabled = switchInstantInternetKill.isChecked,
                        isIncognitoBlocked = switchIncognitoBlocked.isChecked,
                        lockNewInstallsUntilApproved = switchLockNewInstalls.isChecked,
                        dnsHost = getCleanDnsHost()
                    )
                    setInputsEnabled(true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isFinishing || isDestroyed) return@launch
                Log.e(TAG, "Error loading ward policy for $wardId", e)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    updateTimingLabels()
                    switchCurfewMaster.isChecked = currentPolicy.isCurfewEnabled
                    switchWeekendCurfew.isChecked = currentPolicy.isWeekendCurfewEnabled
                    switchVpnBlackhole.isChecked = currentPolicy.isVpnBlackholeEnabled
                    switchInstantInternetKill.isChecked = currentPolicy.isInstantInternetKillEnabled
                    switchIncognitoBlocked.isChecked = currentPolicy.isIncognitoBlocked
                    switchLockNewInstalls.isChecked = currentPolicy.lockNewInstallsUntilApproved
                    etDnsHost.setText(currentPolicy.dnsHost)
                    tilDnsHost.error = null
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
                        isVpnBlackholeEnabled = switchVpnBlackhole.isChecked,
                        isInstantInternetKillEnabled = switchInstantInternetKill.isChecked,
                        isIncognitoBlocked = switchIncognitoBlocked.isChecked,
                        lockNewInstallsUntilApproved = switchLockNewInstalls.isChecked,
                        dnsHost = getCleanDnsHost()
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
                val snapshot = firestore.collection("approvals")
                    .whereEqualTo("wardUid", wardId)
                    .whereEqualTo("status", "PENDING")
                    .get()
                    .await()

                val pendingList = mutableListOf<Pair<String, String>>()
                for (doc in snapshot.documents) {
                    val pkg = doc.getString("packageName") ?: ""
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

    private fun saveAndPushPolicy() {
        val rawDns = getCleanDnsHost()
        if (!rawDns.matches(Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"))) {
            tilDnsHost.error = getString(R.string.error_invalid_dns_host)
            etDnsHost.requestFocus()
            return
        }
        tilDnsHost.error = null

        if (isSavingPolicy || !hasUnsavedChanges()) return
        isSavingPolicy = true
        setInputsEnabled(false)
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) {
                isSavingPolicy = false
                return@launch
            }
            try {
                val policyRepo = PolicyRepository.getInstance(applicationContext)
                val firestore = FirebaseFirestore.getInstance()
                val latestPolicy = try {
                    val snap = firestore.collection("policies").document(wardId).get().await()
                    if (snap != null && snap.exists() && snap.data != null) {
                        DevicePolicyBundle.fromMap(snap.data!!)
                    } else {
                        currentPolicy
                    }
                } catch (netEx: Exception) {
                    if (netEx is kotlinx.coroutines.CancellationException) throw netEx
                    Log.w(TAG, "Failed fetching latest policy before push, using current snapshot", netEx)
                    currentPolicy
                }

                val updatedPolicy = latestPolicy.copy(
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
                    dnsHost = rawDns,
                    version = latestPolicy.version + 1,
                    updatedAt = System.currentTimeMillis()
                )
                val success = policyRepo.pushPolicyToWard(wardId, updatedPolicy)
                if (isFinishing || isDestroyed) return@launch
                if (success) {
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
                isSavingPolicy = false
                if (!isFinishing && !isDestroyed) {
                    setInputsEnabled(true)
                }
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_GuardianCtrl"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_WARD_NAME = "extra_ward_name"
    }
}
