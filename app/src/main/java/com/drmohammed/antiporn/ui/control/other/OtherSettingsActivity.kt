package com.drmohammed.antiporn.ui.control.other

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Sub-Screen for Other Settings (Date & Time Lock, Timezone, and Total Internet Cutoff).
 * Supports both Local and Remote Guardian modes.
 */
class OtherSettingsActivity : AppCompatActivity() {

    private var isRemote = false
    private var wardId: String = ""
    private lateinit var initialPolicy: DevicePolicyBundle

    private var isDateTimeLocked = false
    private var selectedTimeZoneId = CurfewConfig.DEFAULT_TIMEZONE_ID
    private var isTotalInternetKillEnabled = false

    private lateinit var toolbar: MaterialToolbar
    private lateinit var switchLockDateTime: MaterialSwitch
    private lateinit var tvTimezoneBadge: TextView
    private lateinit var tilTimezonePicker: TextInputLayout
    private lateinit var actvTimezonePicker: AutoCompleteTextView
    private lateinit var btnAutoDetectTimezone: MaterialButton
    private lateinit var btnResetToMakkah: MaterialButton
    private lateinit var switchTotalInternetKill: MaterialSwitch
    private lateinit var btnSaveOtherSettingsChanges: MaterialButton

    private var isSaving = false
    private var isProgrammaticUpdate = false
    private var lastAutoDetectClickTime = 0L
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null

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
        setContentView(R.layout.activity_other_settings)

        applyWindowInsets()

        isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        wardId = intent.getStringExtra(EXTRA_WARD_ID).orEmpty()
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)

        if (!isRemote && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (isRemote) {
            if (wardId.isBlank() || policyJson.isNullOrBlank()) {
                android.util.Log.e("OtherSettings", "Refusing remote launch: missing wardId or EXTRA_POLICY_JSON")
                Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val parsed = DevicePolicyBundle.fromJsonOrNull(policyJson)
            if (parsed == null) {
                android.util.Log.e("OtherSettings", "Refusing remote launch: invalid EXTRA_POLICY_JSON")
                Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            initialPolicy = parsed
        } else {
            initialPolicy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        initViews()
        bindInitialPolicy()
        savedInstanceState?.let { restoreDraftState(it) }
        setupListeners()
        updateSaveButtonState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_DATE_TIME_LOCKED, isDateTimeLocked)
        outState.putString(STATE_TIMEZONE_ID, selectedTimeZoneId)
        outState.putBoolean(STATE_TOTAL_INTERNET_KILL, isTotalInternetKillEnabled)
    }

    private fun restoreDraftState(state: Bundle) {
        isProgrammaticUpdate = true
        isDateTimeLocked = state.getBoolean(STATE_DATE_TIME_LOCKED, isDateTimeLocked)
        selectedTimeZoneId = state.getString(STATE_TIMEZONE_ID, selectedTimeZoneId)
            .ifBlank { CurfewConfig.DEFAULT_TIMEZONE_ID }
        isTotalInternetKillEnabled = state.getBoolean(STATE_TOTAL_INTERNET_KILL, isTotalInternetKillEnabled)

        switchLockDateTime.isChecked = isDateTimeLocked
        switchTotalInternetKill.isChecked = isTotalInternetKillEnabled
        updateTimezoneViews()
        isProgrammaticUpdate = false
    }

    override fun onResume() {
        super.onResume()
        if (!isRemote && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun applyWindowInsets() {
        findViewById<View>(R.id.layoutRootOtherSettings)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarOtherSettings)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        switchLockDateTime = findViewById(R.id.switchLockDateTime)
        switchLockDateTime.isSaveEnabled = false
        tvTimezoneBadge = findViewById(R.id.tvTimezoneBadge)
        tilTimezonePicker = findViewById(R.id.tilTimezonePicker)
        actvTimezonePicker = findViewById(R.id.actvTimezonePicker)
        btnAutoDetectTimezone = findViewById(R.id.btnAutoDetectTimezone)
        btnResetToMakkah = findViewById(R.id.btnResetToMakkah)
        switchTotalInternetKill = findViewById(R.id.switchTotalInternetKill)
        switchTotalInternetKill.isSaveEnabled = false
        btnSaveOtherSettingsChanges = findViewById(R.id.btnSaveOtherSettingsChanges)

        val timezoneOptions = IslamicTimeZoneRegistry.getAllOptions()
        val tzAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, timezoneOptions)
        actvTimezonePicker.setAdapter(tzAdapter)
    }

    private fun bindInitialPolicy() {
        isProgrammaticUpdate = true
        isDateTimeLocked = initialPolicy.isDateTimeLocked
        selectedTimeZoneId = initialPolicy.timeZoneId.ifBlank { CurfewConfig.DEFAULT_TIMEZONE_ID }
        isTotalInternetKillEnabled = initialPolicy.isInstantInternetKillEnabled

        switchLockDateTime.isChecked = isDateTimeLocked
        switchTotalInternetKill.isChecked = isTotalInternetKillEnabled
        updateTimezoneViews()
        isProgrammaticUpdate = false
    }

    private fun setupListeners() {
        switchLockDateTime.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticUpdate) return@setOnCheckedChangeListener
            isDateTimeLocked = isChecked
            updateSaveButtonState()
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
            val now = SystemClock.elapsedRealtime()
            if (now - lastAutoDetectClickTime < 800L) return@setOnClickListener
            lastAutoDetectClickTime = now
            val detected = IslamicTimeZoneRegistry.detectCurrentDeviceOption()
            Toast.makeText(this, getString(R.string.timezone_detected_toast, detected.displayText), Toast.LENGTH_SHORT).show()
            if (selectedTimeZoneId != detected.id) {
                selectedTimeZoneId = detected.id
                updateTimezoneViews()
                updateSaveButtonState()
            }
        }

        btnResetToMakkah.setOnClickListener {
            if (selectedTimeZoneId != CurfewConfig.DEFAULT_TIMEZONE_ID) {
                selectedTimeZoneId = CurfewConfig.DEFAULT_TIMEZONE_ID
                updateTimezoneViews()
                updateSaveButtonState()
            }
        }

        switchTotalInternetKill.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticUpdate) return@setOnCheckedChangeListener
            if (isChecked) {
                // High severity warning confirmation
                activeDialog?.dismiss()
                activeDialog = MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_total_internet_kill_title)
                    .setMessage(R.string.dialog_total_internet_kill_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        isTotalInternetKillEnabled = true
                        updateSaveButtonState()
                    }
                    .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                        isProgrammaticUpdate = true
                        switchTotalInternetKill.isChecked = false
                        isTotalInternetKillEnabled = false
                        isProgrammaticUpdate = false
                        dialog.dismiss()
                    }
                    .setOnCancelListener {
                        isProgrammaticUpdate = true
                        switchTotalInternetKill.isChecked = false
                        isTotalInternetKillEnabled = false
                        isProgrammaticUpdate = false
                    }
                    .setOnDismissListener {
                        activeDialog = null
                    }
                    .show()
            } else {
                isTotalInternetKillEnabled = false
                updateSaveButtonState()
            }
        }

        btnSaveOtherSettingsChanges.setOnClickListener {
            saveChanges()
        }
    }

    private fun updateTimezoneViews() {
        val option = IslamicTimeZoneRegistry.findByIanaId(selectedTimeZoneId)
        val cityName = option.majorCities.split("/").firstOrNull()?.trim() ?: option.majorCities
        tvTimezoneBadge.text = getString(R.string.guardian_timezone_badge_format, option.getLiveOffsetLabel(), cityName)
        actvTimezonePicker.setText(option.displayText, false)
    }

    private fun hasUnsavedChanges(): Boolean {
        return isDateTimeLocked != initialPolicy.isDateTimeLocked ||
                selectedTimeZoneId != initialPolicy.timeZoneId ||
                isTotalInternetKillEnabled != initialPolicy.isInstantInternetKillEnabled
    }

    private fun updateSaveButtonState() {
        val dirty = hasUnsavedChanges()
        btnSaveOtherSettingsChanges.isEnabled = dirty && !isSaving
        btnSaveOtherSettingsChanges.alpha = if (dirty && !isSaving) 1.0f else 0.5f
        backPressedCallback.isEnabled = dirty
    }

    private fun saveChanges() {
        if (isSaving || !hasUnsavedChanges()) return
        isSaving = true
        updateSaveButtonState()

        val updatedPolicy = initialPolicy.copy(
            isDateTimeLocked = isDateTimeLocked,
            timeZoneId = selectedTimeZoneId,
            isInstantInternetKillEnabled = isTotalInternetKillEnabled
        )

        val app = applicationContext
        val policyRepo = PolicyRepository.getInstance(app)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = if (isRemote) {
                    if (wardId.isBlank()) {
                        android.util.Log.e("OtherSettings", "Refusing remote push: wardId is blank")
                        false
                    } else {
                        policyRepo.pushPolicyToWard(wardId, updatedPolicy)
                    }
                } else {
                    val saved = policyRepo.saveAndEnforceSelfPolicy(updatedPolicy)
                    if (saved) {
                        CurfewScheduler.scheduleNextAlarms(app)
                        CurfewEnforcer.syncBlockState(app)
                        withContext(Dispatchers.Main) {
                            ProtectionEngine.applyAllAsync(app)
                        }
                    }
                    saved
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    isSaving = false
                    if (success) {
                        val successRes = if (isRemote) R.string.toast_guardian_policy_saved else R.string.toast_self_policy_saved
                        Toast.makeText(applicationContext, successRes, Toast.LENGTH_SHORT).show()
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_UPDATED_POLICY_JSON, updatedPolicy.toJson())
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        backPressedCallback.isEnabled = false
                        finish()
                    } else {
                        updateSaveButtonState()
                        val failRes = if (isRemote) R.string.toast_guardian_policy_save_failed else R.string.toast_self_policy_save_failed
                        Toast.makeText(applicationContext, failRes, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("OtherSettings", "Error saving other settings", e)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    isSaving = false
                    updateSaveButtonState()
                    val failRes = if (isRemote) R.string.toast_guardian_policy_save_failed else R.string.toast_self_policy_save_failed
                    Toast.makeText(applicationContext, failRes, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showDiscardConfirmationDialog() {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(this)
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
            .setOnDismissListener {
                activeDialog = null
            }
            .show()
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        activeDialog = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        const val EXTRA_UPDATED_POLICY_JSON = "extra_updated_policy_json"

        private const val STATE_DATE_TIME_LOCKED = "state_date_time_locked"
        private const val STATE_TIMEZONE_ID = "state_timezone_id"
        private const val STATE_TOTAL_INTERNET_KILL = "state_total_internet_kill"
    }
}
