package com.drmohammed.antiporn.ui.accountability

import android.text.format.DateFormat
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.drmohammed.antiporn.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * View binder responsible for curfew time pickers, button click wiring,
 * and timing label formatting for Guardian control panel.
 */
class GuardianCurfewViewBinder(
    private val activity: AppCompatActivity,
    private val tvCurfewStartTime: TextView,
    private val tvCurfewEndTime: TextView,
    private val btnChangeStartTime: MaterialButton,
    private val btnChangeEndTime: MaterialButton,
    private val tvWeekendStartTime: TextView,
    private val tvWeekendEndTime: TextView,
    private val btnChangeWeekendStartTime: MaterialButton,
    private val btnChangeWeekendEndTime: MaterialButton
) {
    fun setupListeners(
        getStartHour: () -> Int,
        getStartMinute: () -> Int,
        onStartTimeChanged: (hour: Int, minute: Int) -> Unit,
        getEndHour: () -> Int,
        getEndMinute: () -> Int,
        onEndTimeChanged: (hour: Int, minute: Int) -> Unit,
        getWeekendStartHour: () -> Int,
        getWeekendStartMinute: () -> Int,
        onWeekendStartTimeChanged: (hour: Int, minute: Int) -> Unit,
        getWeekendEndHour: () -> Int,
        getWeekendEndMinute: () -> Int,
        onWeekendEndTimeChanged: (hour: Int, minute: Int) -> Unit
    ) {
        btnChangeStartTime.setOnClickListener {
            showStartTimePicker(getStartHour(), getStartMinute(), onStartTimeChanged)
        }
        btnChangeEndTime.setOnClickListener {
            showEndTimePicker(getEndHour(), getEndMinute(), onEndTimeChanged)
        }
        btnChangeWeekendStartTime.setOnClickListener {
            showWeekendStartTimePicker(getWeekendStartHour(), getWeekendStartMinute(), onWeekendStartTimeChanged)
        }
        btnChangeWeekendEndTime.setOnClickListener {
            showWeekendEndTimePicker(getWeekendEndHour(), getWeekendEndMinute(), onWeekendEndTimeChanged)
        }
    }

    fun showStartTimePicker(currentHour: Int, currentMinute: Int, onSelected: (Int, Int) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity.supportFragmentManager.findFragmentByTag("start_time_picker") != null) return
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(activity)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(currentHour)
            .setMinute(currentMinute)
            .setTitleText(R.string.guardian_curfew_start_label)
            .build()

        picker.addOnPositiveButtonClickListener {
            onSelected(picker.hour, picker.minute)
        }
        picker.show(activity.supportFragmentManager, "start_time_picker")
    }

    fun showEndTimePicker(currentHour: Int, currentMinute: Int, onSelected: (Int, Int) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity.supportFragmentManager.findFragmentByTag("end_time_picker") != null) return
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(activity)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(currentHour)
            .setMinute(currentMinute)
            .setTitleText(R.string.guardian_curfew_end_label)
            .build()

        picker.addOnPositiveButtonClickListener {
            onSelected(picker.hour, picker.minute)
        }
        picker.show(activity.supportFragmentManager, "end_time_picker")
    }

    fun showWeekendStartTimePicker(currentHour: Int, currentMinute: Int, onSelected: (Int, Int) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity.supportFragmentManager.findFragmentByTag("weekend_start_time_picker") != null) return
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(activity)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(currentHour)
            .setMinute(currentMinute)
            .setTitleText(R.string.guardian_weekend_curfew_start_label)
            .build()

        picker.addOnPositiveButtonClickListener {
            onSelected(picker.hour, picker.minute)
        }
        picker.show(activity.supportFragmentManager, "weekend_start_time_picker")
    }

    fun showWeekendEndTimePicker(currentHour: Int, currentMinute: Int, onSelected: (Int, Int) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity.supportFragmentManager.findFragmentByTag("weekend_end_time_picker") != null) return
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(activity)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(currentHour)
            .setMinute(currentMinute)
            .setTitleText(R.string.guardian_weekend_curfew_end_label)
            .build()

        picker.addOnPositiveButtonClickListener {
            onSelected(picker.hour, picker.minute)
        }
        picker.show(activity.supportFragmentManager, "weekend_end_time_picker")
    }

    fun updateTimingLabels(
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        weekendStartHour: Int,
        weekendStartMinute: Int,
        weekendEndHour: Int,
        weekendEndMinute: Int
    ) {
        val is24Hour = DateFormat.is24HourFormat(activity)
        val formatTime = { hour: Int, minute: Int ->
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
            }
            if (is24Hour) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
            } else {
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time)
            }
        }
        val startFormatted = formatTime(startHour, startMinute)
        tvCurfewStartTime.text = startFormatted
        btnChangeStartTime.text = startFormatted

        val endFormatted = formatTime(endHour, endMinute)
        tvCurfewEndTime.text = endFormatted
        btnChangeEndTime.text = endFormatted

        val weekendStartFormatted = formatTime(weekendStartHour, weekendStartMinute)
        tvWeekendStartTime.text = weekendStartFormatted
        btnChangeWeekendStartTime.text = weekendStartFormatted

        val weekendEndFormatted = formatTime(weekendEndHour, weekendEndMinute)
        tvWeekendEndTime.text = weekendEndFormatted
        btnChangeWeekendEndTime.text = weekendEndFormatted
    }

    fun setInputsEnabled(enabled: Boolean) {
        btnChangeStartTime.isEnabled = enabled
        btnChangeEndTime.isEnabled = enabled
        btnChangeWeekendStartTime.isEnabled = enabled
        btnChangeWeekendEndTime.isEnabled = enabled
    }
}
