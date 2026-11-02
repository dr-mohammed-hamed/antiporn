package com.drmohammed.antiporn.ui.accountability

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * View binder responsible for timezone controls, dropdown selection,
 * auto-detection, preview display, and collapsible header in Guardian control panel.
 */
class GuardianTimezoneViewBinder(
    private val activity: AppCompatActivity,
    private val layoutTimezoneHeader: View,
    private val ivTimezoneExpand: ImageView,
    private val layoutTimezoneContent: LinearLayout,
    private val tvTimezoneBadge: TextView,
    private val tilTimezonePicker: TextInputLayout,
    private val actvTimezonePicker: MaterialAutoCompleteTextView,
    private val tvTimezonePreview: TextView,
    private val btnResetToMakkah: MaterialButton,
    private val btnAutoDetectTimezone: MaterialButton,
    private val onTimezoneChanged: (String) -> Unit
) {
    private var lastAutoDetectClickTime = 0L

    init {
        val timezoneOptions = IslamicTimeZoneRegistry.getAllOptions()
        val adapter = ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, timezoneOptions)
        actvTimezonePicker.setAdapter(adapter)
    }

    fun setupListeners(getTimeZoneId: () -> String) {
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
            if (option != null && option.id != getTimeZoneId()) {
                onTimezoneChanged(option.id)
            }
        }

        btnAutoDetectTimezone.setOnClickListener {
            if (activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            val now = SystemClock.elapsedRealtime()
            if (now - lastAutoDetectClickTime < 1000L) return@setOnClickListener
            lastAutoDetectClickTime = now
            val detected = IslamicTimeZoneRegistry.detectCurrentDeviceOption()
            if (getTimeZoneId() != detected.id) {
                onTimezoneChanged(detected.id)
            }
            Toast.makeText(
                activity,
                activity.getString(R.string.timezone_detected_toast, detected.displayText),
                Toast.LENGTH_SHORT
            ).show()
        }

        btnResetToMakkah.setOnClickListener {
            if (getTimeZoneId() != CurfewConfig.DEFAULT_TIMEZONE_ID) {
                onTimezoneChanged(CurfewConfig.DEFAULT_TIMEZONE_ID)
            }
        }
    }

    fun updateTimezoneViews(selectedTimeZoneId: String) {
        val option = IslamicTimeZoneRegistry.findByIanaId(selectedTimeZoneId)
        val cityName = option.majorCities.split("/").firstOrNull()?.trim() ?: option.majorCities
        tvTimezoneBadge.text = activity.getString(R.string.guardian_timezone_badge_format, option.getLiveOffsetLabel(), cityName)
        actvTimezonePicker.setText(option.displayText, false)

        try {
            val tz = TimeZone.getTimeZone(option.ianaTimeZoneId)
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault()).apply {
                timeZone = tz
            }
            val formattedTime = sdf.format(Date())
            val timeWithOffset = activity.getString(R.string.guardian_timezone_preview_time_format, formattedTime, option.getLiveOffsetLabel())
            tvTimezonePreview.text = activity.getString(
                R.string.guardian_timezone_preview_format,
                timeWithOffset
            )
        } catch (e: Exception) {
            tvTimezonePreview.text = activity.getString(
                R.string.guardian_timezone_preview_format,
                option.gmtOffsetLabel
            )
        }
    }

    fun setInputsEnabled(enabled: Boolean) {
        tilTimezonePicker.isEnabled = enabled
        actvTimezonePicker.isEnabled = enabled
        btnResetToMakkah.isEnabled = enabled
        btnAutoDetectTimezone.isEnabled = enabled
    }

    companion object {
        private const val TAG = "GuardianTimezoneBinder"
    }
}
