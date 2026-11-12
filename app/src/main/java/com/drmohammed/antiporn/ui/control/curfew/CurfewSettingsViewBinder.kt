package com.drmohammed.antiporn.ui.control.curfew

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Mutable UI state holder and view binder for [CurfewSettingsActivity],
 * isolating view wiring, time formatting, and dirty-state tracking.
 */
internal class CurfewSettingsViewBinder(
    private val activity: CurfewSettingsActivity
) {
    var selectedStartHour = CurfewConfig.START_HOUR
    var selectedStartMinute = CurfewConfig.START_MINUTE
    var selectedEndHour = CurfewConfig.END_HOUR
    var selectedEndMinute = CurfewConfig.END_MINUTE

    var selectedWeekendStartHour = CurfewConfig.START_HOUR
    var selectedWeekendStartMinute = CurfewConfig.START_MINUTE
    var selectedWeekendEndHour = CurfewConfig.END_HOUR
    var selectedWeekendEndMinute = CurfewConfig.END_MINUTE
    var selectedCurfewWhitelist = emptySet<String>()
    var selectedCurfewVpnBypass = emptySet<String>()

    var isCurfewEnabled = false
    var isWeekendCurfewEnabled = false
    var isCurfewInternetKillEnabled = CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED

    private lateinit var toolbar: MaterialToolbar
    private lateinit var switchCurfewMaster: MaterialSwitch
    private lateinit var btnChangeStartTime: MaterialButton
    private lateinit var btnChangeEndTime: MaterialButton
    private lateinit var switchWeekendCurfew: MaterialSwitch
    private lateinit var layoutWeekendTimingContent: View
    private lateinit var btnChangeWeekendStartTime: MaterialButton
    private lateinit var btnChangeWeekendEndTime: MaterialButton
    private lateinit var switchCurfewInternetKill: MaterialSwitch
    private lateinit var tvCurfewAllowedAppsCount: TextView
    private lateinit var btnSelectCurfewApps: MaterialButton
    private lateinit var btnSaveCurfewChanges: MaterialButton

    fun bindViews(canGoBack: () -> Boolean) {
        toolbar = activity.findViewById(R.id.toolbarCurfew)
        toolbar.setNavigationOnClickListener {
            if (canGoBack()) {
                activity.onBackPressedDispatcher.onBackPressed()
            }
        }

        switchCurfewMaster = activity.findViewById(R.id.switchCurfewMaster)
        btnChangeStartTime = activity.findViewById(R.id.btnChangeStartTime)
        btnChangeEndTime = activity.findViewById(R.id.btnChangeEndTime)
        switchWeekendCurfew = activity.findViewById(R.id.switchWeekendCurfew)
        layoutWeekendTimingContent = activity.findViewById(R.id.layoutWeekendTimingContent)
        btnChangeWeekendStartTime = activity.findViewById(R.id.btnChangeWeekendStartTime)
        btnChangeWeekendEndTime = activity.findViewById(R.id.btnChangeWeekendEndTime)
        switchCurfewInternetKill = activity.findViewById(R.id.switchCurfewInternetKill)
        tvCurfewAllowedAppsCount = activity.findViewById(R.id.tvCurfewAllowedAppsCount)
        btnSelectCurfewApps = activity.findViewById(R.id.btnSelectCurfewApps)
        btnSaveCurfewChanges = activity.findViewById(R.id.btnSaveCurfewChanges)
    }

    fun applyInitialPolicy(policy: DevicePolicyBundle) {
        selectedStartHour = policy.curfewStartHour
        selectedStartMinute = policy.curfewStartMinute
        selectedEndHour = policy.curfewEndHour
        selectedEndMinute = policy.curfewEndMinute

        selectedWeekendStartHour = policy.weekendCurfewStartHour
        selectedWeekendStartMinute = policy.weekendCurfewStartMinute
        selectedWeekendEndHour = policy.weekendCurfewEndHour
        selectedWeekendEndMinute = policy.weekendCurfewEndMinute

        isCurfewEnabled = policy.isCurfewEnabled
        isWeekendCurfewEnabled = policy.isWeekendCurfewEnabled
        isCurfewInternetKillEnabled = policy.isVpnBlackholeEnabled
        selectedCurfewWhitelist = policy.customCurfewWhitelistPackages
        selectedCurfewVpnBypass = policy.curfewVpnBypassPackages

        switchCurfewMaster.isChecked = isCurfewEnabled
        switchWeekendCurfew.isChecked = isWeekendCurfewEnabled
        layoutWeekendTimingContent.visibility = if (isWeekendCurfewEnabled) View.VISIBLE else View.GONE
        switchCurfewInternetKill.isChecked = isCurfewInternetKillEnabled
    }

    fun saveInstanceState(outState: Bundle) {
        outState.putInt(KEY_START_HOUR, selectedStartHour)
        outState.putInt(KEY_START_MINUTE, selectedStartMinute)
        outState.putInt(KEY_END_HOUR, selectedEndHour)
        outState.putInt(KEY_END_MINUTE, selectedEndMinute)
        outState.putInt(KEY_WEEKEND_START_HOUR, selectedWeekendStartHour)
        outState.putInt(KEY_WEEKEND_START_MINUTE, selectedWeekendStartMinute)
        outState.putInt(KEY_WEEKEND_END_HOUR, selectedWeekendEndHour)
        outState.putInt(KEY_WEEKEND_END_MINUTE, selectedWeekendEndMinute)
        outState.putBoolean(KEY_CURFEW_ENABLED, isCurfewEnabled)
        outState.putBoolean(KEY_WEEKEND_ENABLED, isWeekendCurfewEnabled)
        outState.putBoolean(KEY_INTERNET_KILL_ENABLED, isCurfewInternetKillEnabled)
        outState.putStringArrayList(KEY_WHITELIST, ArrayList(selectedCurfewWhitelist))
        outState.putStringArrayList(KEY_VPN_BYPASS, ArrayList(selectedCurfewVpnBypass))
    }

    fun restoreInstanceState(savedInstanceState: Bundle) {
        if (!savedInstanceState.containsKey(KEY_CURFEW_ENABLED)) return
        selectedStartHour = savedInstanceState.getInt(KEY_START_HOUR, selectedStartHour)
        selectedStartMinute = savedInstanceState.getInt(KEY_START_MINUTE, selectedStartMinute)
        selectedEndHour = savedInstanceState.getInt(KEY_END_HOUR, selectedEndHour)
        selectedEndMinute = savedInstanceState.getInt(KEY_END_MINUTE, selectedEndMinute)
        selectedWeekendStartHour = savedInstanceState.getInt(KEY_WEEKEND_START_HOUR, selectedWeekendStartHour)
        selectedWeekendStartMinute = savedInstanceState.getInt(KEY_WEEKEND_START_MINUTE, selectedWeekendStartMinute)
        selectedWeekendEndHour = savedInstanceState.getInt(KEY_WEEKEND_END_HOUR, selectedWeekendEndHour)
        selectedWeekendEndMinute = savedInstanceState.getInt(KEY_WEEKEND_END_MINUTE, selectedWeekendEndMinute)
        isCurfewEnabled = savedInstanceState.getBoolean(KEY_CURFEW_ENABLED, isCurfewEnabled)
        isWeekendCurfewEnabled = savedInstanceState.getBoolean(KEY_WEEKEND_ENABLED, isWeekendCurfewEnabled)
        isCurfewInternetKillEnabled = savedInstanceState.getBoolean(KEY_INTERNET_KILL_ENABLED, isCurfewInternetKillEnabled)
        savedInstanceState.getStringArrayList(KEY_WHITELIST)?.let { selectedCurfewWhitelist = it.toSet() }
        savedInstanceState.getStringArrayList(KEY_VPN_BYPASS)?.let { selectedCurfewVpnBypass = it.toSet() }

        switchCurfewMaster.isChecked = isCurfewEnabled
        switchWeekendCurfew.isChecked = isWeekendCurfewEnabled
        layoutWeekendTimingContent.visibility = if (isWeekendCurfewEnabled) View.VISIBLE else View.GONE
        switchCurfewInternetKill.isChecked = isCurfewInternetKillEnabled
    }

    fun setupListeners(
        isRemote: Boolean,
        wardId: String,
        initialBlacklist: Set<String>,
        onDirtyStateChanged: () -> Unit,
        onSaveClicked: () -> Unit
    ) {
        reattachTimePickerListeners(onDirtyStateChanged)

        switchCurfewMaster.setOnCheckedChangeListener { _, isChecked ->
            isCurfewEnabled = isChecked
            onDirtyStateChanged()
        }

        switchWeekendCurfew.setOnCheckedChangeListener { _, isChecked ->
            isWeekendCurfewEnabled = isChecked
            layoutWeekendTimingContent.visibility = if (isChecked) View.VISIBLE else View.GONE
            onDirtyStateChanged()
        }

        switchCurfewInternetKill.setOnCheckedChangeListener { _, isChecked ->
            isCurfewInternetKillEnabled = isChecked
            onDirtyStateChanged()
        }

        btnChangeStartTime.setOnClickListener {
            showTimePicker(TAG_PICKER_START, selectedStartHour, selectedStartMinute, activity.getString(R.string.guardian_curfew_start_label), onDirtyStateChanged)
        }

        btnChangeEndTime.setOnClickListener {
            showTimePicker(TAG_PICKER_END, selectedEndHour, selectedEndMinute, activity.getString(R.string.guardian_curfew_end_label), onDirtyStateChanged)
        }

        btnChangeWeekendStartTime.setOnClickListener {
            showTimePicker(TAG_PICKER_WEEKEND_START, selectedWeekendStartHour, selectedWeekendStartMinute, activity.getString(R.string.guardian_weekend_curfew_start_label), onDirtyStateChanged)
        }

        btnChangeWeekendEndTime.setOnClickListener {
            showTimePicker(TAG_PICKER_WEEKEND_END, selectedWeekendEndHour, selectedWeekendEndMinute, activity.getString(R.string.guardian_weekend_curfew_end_label), onDirtyStateChanged)
        }

        btnSelectCurfewApps.setOnClickListener {
            if (activity.isFinishing || activity.isDestroyed || activity.supportFragmentManager.isStateSaved) {
                return@setOnClickListener
            }
            if (activity.supportFragmentManager.findFragmentByTag(CurfewAppPickerBottomSheet.TAG) != null) return@setOnClickListener
            val sheet = CurfewAppPickerBottomSheet.newInstance(
                isRemote = isRemote,
                wardId = wardId,
                initialWhitelist = selectedCurfewWhitelist,
                initialVpnBypass = selectedCurfewVpnBypass,
                blacklist = initialBlacklist
            )
            sheet.show(activity.supportFragmentManager, CurfewAppPickerBottomSheet.TAG)
        }

        btnSaveCurfewChanges.setOnClickListener {
            onSaveClicked()
        }
    }

    fun updateAllowedAppsLabel() {
        val allowedCount = selectedCurfewWhitelist.size
        val netCount = selectedCurfewVpnBypass.count { selectedCurfewWhitelist.contains(it) }
        tvCurfewAllowedAppsCount.text = when {
            allowedCount == 0 -> activity.getString(R.string.curfew_allowed_apps_none)
            netCount > 0 -> activity.getString(R.string.curfew_allowed_apps_count_with_net_format, allowedCount, netCount)
            else -> activity.getString(R.string.curfew_allowed_apps_count_format, allowedCount)
        }
    }

    fun updateTimingLabels() {
        val is24Hour = android.text.format.DateFormat.is24HourFormat(activity)
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

    fun hasUnsavedChanges(initialPolicy: DevicePolicyBundle): Boolean =
        isCurfewEnabled != initialPolicy.isCurfewEnabled ||
            selectedStartHour != initialPolicy.curfewStartHour ||
            selectedStartMinute != initialPolicy.curfewStartMinute ||
            selectedEndHour != initialPolicy.curfewEndHour ||
            selectedEndMinute != initialPolicy.curfewEndMinute ||
            isWeekendCurfewEnabled != initialPolicy.isWeekendCurfewEnabled ||
            selectedWeekendStartHour != initialPolicy.weekendCurfewStartHour ||
            selectedWeekendStartMinute != initialPolicy.weekendCurfewStartMinute ||
            selectedWeekendEndHour != initialPolicy.weekendCurfewEndHour ||
            selectedWeekendEndMinute != initialPolicy.weekendCurfewEndMinute ||
            isCurfewInternetKillEnabled != initialPolicy.isVpnBlackholeEnabled ||
            selectedCurfewWhitelist != initialPolicy.customCurfewWhitelistPackages ||
            selectedCurfewVpnBypass != initialPolicy.curfewVpnBypassPackages

    fun updateSaveButton(dirty: Boolean, isSaving: Boolean) {
        btnSaveCurfewChanges.isEnabled = dirty && !isSaving
        btnSaveCurfewChanges.alpha = if (dirty && !isSaving) 1.0f else 0.5f
    }

    fun buildUpdatedPolicy(initialPolicy: DevicePolicyBundle): DevicePolicyBundle =
        initialPolicy.copy(
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
            isVpnBlackholeEnabled = isCurfewInternetKillEnabled,
            customCurfewWhitelistPackages = selectedCurfewWhitelist,
            curfewVpnBypassPackages = selectedCurfewVpnBypass
        )

    private fun reattachTimePickerListeners(onDirtyStateChanged: () -> Unit) {
        val tags = listOf(TAG_PICKER_START, TAG_PICKER_END, TAG_PICKER_WEEKEND_START, TAG_PICKER_WEEKEND_END)
        for (tag in tags) {
            val existing = activity.supportFragmentManager.findFragmentByTag(tag) as? MaterialTimePicker ?: continue
            existing.clearOnPositiveButtonClickListeners()
            existing.addOnPositiveButtonClickListener {
                applyPickedTime(tag, existing.hour, existing.minute, onDirtyStateChanged)
            }
        }
    }

    private fun applyPickedTime(tag: String, hour: Int, minute: Int, onDirtyStateChanged: () -> Unit) {
        when (tag) {
            TAG_PICKER_START -> {
                selectedStartHour = hour
                selectedStartMinute = minute
            }
            TAG_PICKER_END -> {
                selectedEndHour = hour
                selectedEndMinute = minute
            }
            TAG_PICKER_WEEKEND_START -> {
                selectedWeekendStartHour = hour
                selectedWeekendStartMinute = minute
            }
            TAG_PICKER_WEEKEND_END -> {
                selectedWeekendEndHour = hour
                selectedWeekendEndMinute = minute
            }
            else -> return
        }
        updateTimingLabels()
        onDirtyStateChanged()
    }

    private fun showTimePicker(tag: String, hour: Int, minute: Int, title: String, onDirtyStateChanged: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed || activity.supportFragmentManager.isStateSaved) return
        if (activity.supportFragmentManager.findFragmentByTag(tag) != null) return
        val is24Hour = android.text.format.DateFormat.is24HourFormat(activity)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (is24Hour) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText(title)
            .build()

        picker.addOnPositiveButtonClickListener {
            applyPickedTime(tag, picker.hour, picker.minute, onDirtyStateChanged)
        }
        picker.show(activity.supportFragmentManager, tag)
    }

    companion object {
        private const val TAG_PICKER_START = "picker_start"
        private const val TAG_PICKER_END = "picker_end"
        private const val TAG_PICKER_WEEKEND_START = "picker_weekend_start"
        private const val TAG_PICKER_WEEKEND_END = "picker_weekend_end"

        private const val KEY_START_HOUR = "draft_start_hour"
        private const val KEY_START_MINUTE = "draft_start_minute"
        private const val KEY_END_HOUR = "draft_end_hour"
        private const val KEY_END_MINUTE = "draft_end_minute"
        private const val KEY_WEEKEND_START_HOUR = "draft_weekend_start_hour"
        private const val KEY_WEEKEND_START_MINUTE = "draft_weekend_start_minute"
        private const val KEY_WEEKEND_END_HOUR = "draft_weekend_end_hour"
        private const val KEY_WEEKEND_END_MINUTE = "draft_weekend_end_minute"
        private const val KEY_CURFEW_ENABLED = "draft_curfew_enabled"
        private const val KEY_WEEKEND_ENABLED = "draft_weekend_enabled"
        private const val KEY_INTERNET_KILL_ENABLED = "draft_internet_kill_enabled"
        private const val KEY_WHITELIST = "draft_whitelist"
        private const val KEY_VPN_BYPASS = "draft_vpn_bypass"
    }
}
