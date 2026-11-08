package com.drmohammed.antiporn.ui.control.curfew

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Dedicated Sub-Screen for Night Curfew Settings.
 * Supports both Local (Self-Control) and Remote (Guardian) modes.
 */
class CurfewSettingsActivity : AppCompatActivity() {

    private var isRemote = false
    private var wardId: String = ""
    private lateinit var initialPolicy: DevicePolicyBundle

    private var selectedStartHour = 20
    private var selectedStartMinute = 30
    private var selectedEndHour = 7
    private var selectedEndMinute = 0

    private var selectedWeekendStartHour = 20
    private var selectedWeekendStartMinute = 30
    private var selectedWeekendEndHour = 7
    private var selectedWeekendEndMinute = 0

    private var isCurfewEnabled = true
    private var isWeekendCurfewEnabled = false
    private var isCurfewInternetKillEnabled = false

    private lateinit var toolbar: MaterialToolbar
    private lateinit var switchCurfewMaster: MaterialSwitch
    private lateinit var btnChangeStartTime: MaterialButton
    private lateinit var btnChangeEndTime: MaterialButton
    private lateinit var switchWeekendCurfew: MaterialSwitch
    private lateinit var layoutWeekendTimingContent: View
    private lateinit var btnChangeWeekendStartTime: MaterialButton
    private lateinit var btnChangeWeekendEndTime: MaterialButton
    private lateinit var switchCurfewInternetKill: MaterialSwitch
    private lateinit var btnSaveCurfewChanges: MaterialButton

    private var isSaving = false

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
        setContentView(R.layout.activity_curfew_settings)

        applyWindowInsets()

        isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        wardId = intent.getStringExtra(EXTRA_WARD_ID).orEmpty()
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)

        if (!isRemote && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initialPolicy = if (isRemote && !policyJson.isNullOrBlank()) {
            DevicePolicyBundle.fromJson(policyJson)
        } else {
            PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        initViews()
        bindInitialPolicy()
        setupListeners()
        updateTimingLabels()
        updateSaveButtonState()
    }

    override fun onResume() {
        super.onResume()
        if (!isRemote && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun applyWindowInsets() {
        findViewById<View>(R.id.layoutRootCurfewSettings)?.let { root ->
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
        toolbar = findViewById(R.id.toolbarCurfew)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        switchCurfewMaster = findViewById(R.id.switchCurfewMaster)
        btnChangeStartTime = findViewById(R.id.btnChangeStartTime)
        btnChangeEndTime = findViewById(R.id.btnChangeEndTime)
        switchWeekendCurfew = findViewById(R.id.switchWeekendCurfew)
        layoutWeekendTimingContent = findViewById(R.id.layoutWeekendTimingContent)
        btnChangeWeekendStartTime = findViewById(R.id.btnChangeWeekendStartTime)
        btnChangeWeekendEndTime = findViewById(R.id.btnChangeWeekendEndTime)
        switchCurfewInternetKill = findViewById(R.id.switchCurfewInternetKill)
        btnSaveCurfewChanges = findViewById(R.id.btnSaveCurfewChanges)
    }

    private fun bindInitialPolicy() {
        selectedStartHour = initialPolicy.curfewStartHour
        selectedStartMinute = initialPolicy.curfewStartMinute
        selectedEndHour = initialPolicy.curfewEndHour
        selectedEndMinute = initialPolicy.curfewEndMinute

        selectedWeekendStartHour = initialPolicy.weekendCurfewStartHour
        selectedWeekendStartMinute = initialPolicy.weekendCurfewStartMinute
        selectedWeekendEndHour = initialPolicy.weekendCurfewEndHour
        selectedWeekendEndMinute = initialPolicy.weekendCurfewEndMinute

        isCurfewEnabled = initialPolicy.isCurfewEnabled
        isWeekendCurfewEnabled = initialPolicy.isWeekendCurfewEnabled
        isCurfewInternetKillEnabled = initialPolicy.isVpnBlackholeEnabled

        switchCurfewMaster.isChecked = isCurfewEnabled
        switchWeekendCurfew.isChecked = isWeekendCurfewEnabled
        layoutWeekendTimingContent.visibility = if (isWeekendCurfewEnabled) View.VISIBLE else View.GONE
        switchCurfewInternetKill.isChecked = isCurfewInternetKillEnabled
    }

    private fun setupListeners() {
        switchCurfewMaster.setOnCheckedChangeListener { _, isChecked ->
            isCurfewEnabled = isChecked
            updateSaveButtonState()
        }

        switchWeekendCurfew.setOnCheckedChangeListener { _, isChecked ->
            isWeekendCurfewEnabled = isChecked
            layoutWeekendTimingContent.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateSaveButtonState()
        }

        switchCurfewInternetKill.setOnCheckedChangeListener { _, isChecked ->
            isCurfewInternetKillEnabled = isChecked
            updateSaveButtonState()
        }

        btnChangeStartTime.setOnClickListener {
            showTimePicker(selectedStartHour, selectedStartMinute, getString(R.string.guardian_curfew_start_label)) { h, m ->
                selectedStartHour = h
                selectedStartMinute = m
                updateTimingLabels()
                updateSaveButtonState()
            }
        }

        btnChangeEndTime.setOnClickListener {
            showTimePicker(selectedEndHour, selectedEndMinute, getString(R.string.guardian_curfew_end_label)) { h, m ->
                selectedEndHour = h
                selectedEndMinute = m
                updateTimingLabels()
                updateSaveButtonState()
            }
        }

        btnChangeWeekendStartTime.setOnClickListener {
            showTimePicker(selectedWeekendStartHour, selectedWeekendStartMinute, getString(R.string.guardian_weekend_curfew_start_label)) { h, m ->
                selectedWeekendStartHour = h
                selectedWeekendStartMinute = m
                updateTimingLabels()
                updateSaveButtonState()
            }
        }

        btnChangeWeekendEndTime.setOnClickListener {
            showTimePicker(selectedWeekendEndHour, selectedWeekendEndMinute, getString(R.string.guardian_weekend_curfew_end_label)) { h, m ->
                selectedWeekendEndHour = h
                selectedWeekendEndMinute = m
                updateTimingLabels()
                updateSaveButtonState()
            }
        }

        btnSaveCurfewChanges.setOnClickListener {
            saveChanges()
        }
    }

    private fun showTimePicker(hour: Int, minute: Int, title: String, onTimeSelected: (Int, Int) -> Unit) {
        val is24Hour = android.text.format.DateFormat.is24HourFormat(this)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (is24Hour) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText(title)
            .build()

        picker.addOnPositiveButtonClickListener {
            onTimeSelected(picker.hour, picker.minute)
        }
        picker.show(supportFragmentManager, "time_picker_${title.hashCode()}")
    }

    private fun updateTimingLabels() {
        val is24Hour = android.text.format.DateFormat.is24HourFormat(this)
        val cal = Calendar.getInstance()

        fun format(h: Int, m: Int): String {
            return if (is24Hour) {
                String.format(Locale.US, "%02d:%02d", h, m)
            } else {
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, m)
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time)
            }
        }

        btnChangeStartTime.text = format(selectedStartHour, selectedStartMinute)
        btnChangeEndTime.text = format(selectedEndHour, selectedEndMinute)
        btnChangeWeekendStartTime.text = format(selectedWeekendStartHour, selectedWeekendStartMinute)
        btnChangeWeekendEndTime.text = format(selectedWeekendEndHour, selectedWeekendEndMinute)
    }

    private fun hasUnsavedChanges(): Boolean {
        return isCurfewEnabled != initialPolicy.isCurfewEnabled ||
                selectedStartHour != initialPolicy.curfewStartHour ||
                selectedStartMinute != initialPolicy.curfewStartMinute ||
                selectedEndHour != initialPolicy.curfewEndHour ||
                selectedEndMinute != initialPolicy.curfewEndMinute ||
                isWeekendCurfewEnabled != initialPolicy.isWeekendCurfewEnabled ||
                selectedWeekendStartHour != initialPolicy.weekendCurfewStartHour ||
                selectedWeekendStartMinute != initialPolicy.weekendCurfewStartMinute ||
                selectedWeekendEndHour != initialPolicy.weekendCurfewEndHour ||
                selectedWeekendEndMinute != initialPolicy.weekendCurfewEndMinute ||
                isCurfewInternetKillEnabled != initialPolicy.isVpnBlackholeEnabled
    }

    private fun updateSaveButtonState() {
        val dirty = hasUnsavedChanges()
        btnSaveCurfewChanges.isEnabled = dirty && !isSaving
        btnSaveCurfewChanges.alpha = if (dirty && !isSaving) 1.0f else 0.5f
        backPressedCallback.isEnabled = dirty
    }

    private fun saveChanges() {
        if (isSaving || !hasUnsavedChanges()) return
        isSaving = true
        updateSaveButtonState()

        val updatedPolicy = initialPolicy.copy(
            isCurfewEnabled = isCurfewEnabled,
            curfewStartHour = selectedStartHour,
            curfewStartMinute = selectedStartMinute,
            curfewEndHour = selectedEndHour,
            curfewEndMinute = selectedEndMinute,
            isWeekendCurfewEnabled = isWeekendCurfewEnabled,
            weekendCurfewStartHour = selectedWeekendStartHour,
            weekendCurfewStartMinute = selectedWeekendStartMinute,
            weekendCurfewEndHour = selectedWeekendEndHour,
            weekendCurfewEndMinute = selectedWeekendEndMinute,
            isVpnBlackholeEnabled = isCurfewInternetKillEnabled
        )

        val app = applicationContext
        val policyRepo = PolicyRepository.getInstance(app)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = if (isRemote && wardId.isNotBlank()) {
                    policyRepo.pushPolicyToWard(wardId, updatedPolicy)
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
                    isSaving = false
                    if (success) {
                        Toast.makeText(this@CurfewSettingsActivity, R.string.toast_self_policy_saved, Toast.LENGTH_SHORT).show()
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_UPDATED_POLICY_JSON, updatedPolicy.toJson())
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        backPressedCallback.isEnabled = false
                        finish()
                    } else {
                        updateSaveButtonState()
                        Toast.makeText(this@CurfewSettingsActivity, R.string.toast_self_policy_save_failed, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("CurfewSettings", "Error saving curfew settings", e)
                withContext(Dispatchers.Main) {
                    isSaving = false
                    updateSaveButtonState()
                    Toast.makeText(this@CurfewSettingsActivity, R.string.toast_self_policy_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
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
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        const val EXTRA_UPDATED_POLICY_JSON = "extra_updated_policy_json"
    }
}
