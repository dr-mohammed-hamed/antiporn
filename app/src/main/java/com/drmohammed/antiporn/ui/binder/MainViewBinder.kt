package com.drmohammed.antiporn.ui.binder

import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.curfew.NapController
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.BrowserShield
import com.drmohammed.antiporn.shields.CurfewShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.shields.SystemTamperShield
import com.drmohammed.antiporn.shields.VpnShield
import com.drmohammed.antiporn.ui.ProtectionUiState
import com.drmohammed.antiporn.ui.shields.ActiveShieldsBottomSheet
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Encapsulates view binding, styling, and status presentation for the dashboard.
 * Decouples view logic from MainActivity to prevent God Classes.
 */
class MainViewBinder(rootView: View) {

    private val context = rootView.context

    private val greenColor = ContextCompat.getColor(context, R.color.status_green)

    val layoutBrandHeader: View = rootView.findViewById(R.id.layoutBrandHeader)
    private var lastHeaderClickTime = 0L
    private val tvOverallStatusBadge: TextView = rootView.findViewById(R.id.tvOverallStatusBadge)
    private val tvOverallStatusDesc: TextView = rootView.findViewById(R.id.tvOverallStatusDesc)
    private val layoutMainStatus: LinearLayout = rootView.findViewById(R.id.layoutMainStatus)
    private val cardMainStatus: MaterialCardView = rootView.findViewById(R.id.cardMainStatus)

    val cardNapMode: MaterialCardView = rootView.findViewById(R.id.cardNapMode)
    val btnCurfewTestToggle: MaterialButton = rootView.findViewById(R.id.btnCurfewTestToggle)
    val btnGrantUsageAccess: MaterialButton = rootView.findViewById(R.id.btnGrantUsageAccess)
    private val tvNapCountdown: TextView = rootView.findViewById(R.id.tvNapCountdown)
    val btnNapStart: MaterialButton = rootView.findViewById(R.id.btnNapStart)
    val btnNapExtend: MaterialButton = rootView.findViewById(R.id.btnNapExtend)

    val cardAdbHelp: MaterialCardView = rootView.findViewById(R.id.cardAdbHelp)
    val btnOpenAccountabilityHub: MaterialButton = rootView.findViewById(R.id.btnOpenAccountabilityHub)
    val btnEnforceNow: MaterialButton = rootView.findViewById(R.id.btnEnforceNow)
    private val tvHeaderAyah: TextView? = rootView.findViewById(R.id.tvHeaderAyah)

    val cardOtaUpdate: MaterialCardView = rootView.findViewById(R.id.cardOtaUpdate)
    val tvOtaVersion: TextView = rootView.findViewById(R.id.tvOtaVersion)
    val tvOtaStatus: TextView = rootView.findViewById(R.id.tvOtaStatus)
    val pbOtaProgress: LinearProgressIndicator = rootView.findViewById(R.id.pbOtaProgress)
    val btnCheckUpdate: MaterialButton = rootView.findViewById(R.id.btnCheckUpdate)

    init {
        tvHeaderAyah?.let {
            it.text = HtmlCompat.fromHtml(
                context.getString(R.string.header_ayah_quran),
                HtmlCompat.FROM_HTML_MODE_LEGACY
            )
        }

        layoutBrandHeader.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastHeaderClickTime >= 600L) {
                lastHeaderClickTime = now
                ActiveShieldsBottomSheet.show(context)
            }
        }
    }

    /**
     * Binds the UI state to the views deterministically.
     */
    fun bind(state: ProtectionUiState) {
        when (state) {
            is ProtectionUiState.Loading -> {
                btnEnforceNow.isEnabled = false
                btnEnforceNow.setText(R.string.btn_enforcing)
            }
            is ProtectionUiState.Error -> {
                btnEnforceNow.isEnabled = true
                btnEnforceNow.setText(R.string.btn_enforce_now)
            }
            is ProtectionUiState.Success -> {
                renderSuccess(state)
            }
        }
    }

    /**
     * Updates the nap countdown text (mm:ss). Hides the counter when expired.
     */
    fun updateNapCountdown(remainingMs: Long) {
        if (remainingMs <= 0L) {
            tvNapCountdown.visibility = View.GONE
            return
        }
        val totalSec = (remainingMs / 1000L).toInt()
        val mm = totalSec / 60
        val ss = totalSec % 60
        tvNapCountdown.text = context.getString(R.string.nap_countdown_format, mm, ss)
        tvNapCountdown.setTextColor(greenColor)
        tvNapCountdown.visibility = View.VISIBLE
    }

    private fun renderSuccess(state: ProtectionUiState.Success) {
        btnEnforceNow.isEnabled = !state.isEnforcing
        btnEnforceNow.setText(if (state.isEnforcing) R.string.btn_enforcing else R.string.btn_enforce_now)

        if (state.isDeviceOwner) {
            cardAdbHelp.visibility = View.GONE

            val dnsActive = state.shields[DnsShield.id] is ShieldStatus.Active
            val vpnActive = state.shields[VpnShield.id] is ShieldStatus.Active
            val browserActive = state.shields[BrowserShield.id] is ShieldStatus.Active
            val tamperActive = state.shields[SystemTamperShield.id] is ShieldStatus.Active
            val appSuspensionActive = state.shields[AppSuspensionShield.id] is ShieldStatus.Active
            val curfewActive = state.shields[CurfewShield.id] is ShieldStatus.Active

            val allActive = dnsActive && vpnActive && browserActive && tamperActive && appSuspensionActive && curfewActive

            if (allActive) {
                cardMainStatus.visibility = View.GONE
                btnEnforceNow.visibility = View.GONE
            } else {
                cardMainStatus.visibility = View.VISIBLE
                btnEnforceNow.visibility = View.VISIBLE
                tvOverallStatusBadge.text = context.getString(R.string.status_need_enforce_title)
                tvOverallStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.shield_amber_calm))
                tvOverallStatusBadge.setBackgroundResource(R.drawable.bg_shield_status_badge_amber)
                tvOverallStatusDesc.text = context.getString(R.string.status_need_enforce_desc)
                layoutMainStatus.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                cardMainStatus.setCardBackgroundColor(
                    MaterialColors.getColor(cardMainStatus, com.google.android.material.R.attr.colorSurfaceContainer)
                )
                cardMainStatus.strokeColor = ContextCompat.getColor(context, R.color.shield_amber_calm)
            }

            // Bind Curfew & Nap Mode Controls
            val isScheduledCurfew = CurfewEnforcer.isCurfewActive(context)
            val isTestActive = CurfewEnforcer.isManualTestActive(context)
            btnCurfewTestToggle.visibility = View.VISIBLE

            if (isScheduledCurfew) {
                btnCurfewTestToggle.isEnabled = false
                btnCurfewTestToggle.setText(R.string.btn_curfew_night_locked)
            } else {
                btnCurfewTestToggle.isEnabled = true
                btnCurfewTestToggle.setText(if (isTestActive) R.string.btn_test_curfew_off else R.string.btn_test_curfew_on)
            }

            val hasUsageAccess = CurfewForegroundWatcher.hasUsageStatsPermission(context)
            btnGrantUsageAccess.visibility = if (!hasUsageAccess) View.VISIBLE else View.GONE

            // Bind Nap Mode
            val isNight = CurfewEnforcer.isCurfewActive(context)
            val isNap = NapController.isNapActive(context)
            btnNapStart.visibility = View.VISIBLE
            if (isNight) {
                btnNapStart.isEnabled = false
                btnNapStart.setText(R.string.btn_nap_night_locked)
                btnNapExtend.visibility = View.GONE
                btnNapExtend.isEnabled = false
                if (isNap) {
                    tvNapCountdown.setTextColor(greenColor)
                    tvNapCountdown.visibility = View.VISIBLE
                } else {
                    tvNapCountdown.visibility = View.GONE
                }
            } else if (isNap) {
                btnNapStart.isEnabled = false
                btnNapStart.setText(R.string.btn_nap_active)
                btnNapExtend.visibility = View.VISIBLE
                btnNapExtend.isEnabled = true
                btnNapExtend.setText(R.string.btn_nap_extend)
                tvNapCountdown.setTextColor(greenColor)
                tvNapCountdown.visibility = View.VISIBLE
            } else {
                btnNapStart.isEnabled = true
                btnNapStart.setText(R.string.btn_nap_start)
                btnNapExtend.visibility = View.GONE
                btnNapExtend.isEnabled = false
                tvNapCountdown.visibility = View.GONE
            }

        } else {
            // Not Device Owner
            cardAdbHelp.visibility = View.VISIBLE
            cardMainStatus.visibility = View.VISIBLE
            btnEnforceNow.visibility = View.VISIBLE
            tvOverallStatusBadge.text = context.getString(R.string.status_no_device_owner_title)
            tvOverallStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.shield_amber_calm))
            tvOverallStatusBadge.setBackgroundResource(R.drawable.bg_shield_status_badge_amber)
            tvOverallStatusDesc.text = context.getString(R.string.status_no_device_owner_desc)
            layoutMainStatus.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            cardMainStatus.setCardBackgroundColor(
                MaterialColors.getColor(cardMainStatus, com.google.android.material.R.attr.colorSurfaceContainer)
            )
            cardMainStatus.strokeColor = ContextCompat.getColor(context, R.color.shield_amber_calm)

            btnCurfewTestToggle.visibility = View.GONE
            btnCurfewTestToggle.isEnabled = false
            btnGrantUsageAccess.visibility = View.GONE
            btnNapStart.visibility = View.GONE
            btnNapStart.isEnabled = false
            btnNapExtend.visibility = View.GONE
            btnNapExtend.isEnabled = false
            tvNapCountdown.visibility = View.GONE
        }
    }

    /**
     * Binds the OTA update status to the update card views.
     */
    fun bindOtaState(state: OtaUpdateState, currentVersionName: String, currentVersionCode: Long) {
        tvOtaVersion.text = context.getString(R.string.card_ota_current_version, currentVersionName, currentVersionCode)

        when (state) {
            is OtaUpdateState.Idle -> {
                tvOtaStatus.text = ""
                tvOtaStatus.visibility = View.GONE
                pbOtaProgress.visibility = View.GONE
                btnCheckUpdate.isEnabled = true
                btnCheckUpdate.setText(R.string.btn_check_update)
            }
            is OtaUpdateState.Checking -> {
                tvOtaStatus.text = context.getString(R.string.btn_checking_update)
                tvOtaStatus.visibility = View.VISIBLE
                pbOtaProgress.visibility = View.VISIBLE
                pbOtaProgress.isIndeterminate = true
                btnCheckUpdate.isEnabled = false
                btnCheckUpdate.setText(R.string.btn_checking_update)
            }
            is OtaUpdateState.NoUpdateAvailable -> {
                tvOtaStatus.text = context.getString(R.string.ota_up_to_date)
                tvOtaStatus.visibility = View.VISIBLE
                pbOtaProgress.visibility = View.GONE
                btnCheckUpdate.isEnabled = true
                btnCheckUpdate.setText(R.string.btn_check_update)
            }
            is OtaUpdateState.UpdateAvailable -> {
                tvOtaStatus.text = context.getString(R.string.ota_update_available, state.info.versionName)
                tvOtaStatus.visibility = View.VISIBLE
                pbOtaProgress.visibility = View.GONE
                btnCheckUpdate.isEnabled = true
                btnCheckUpdate.setText(R.string.btn_download_update)
            }
            is OtaUpdateState.Downloading -> {
                val percent = state.progressPercent
                val statusText = if (percent >= 0) {
                    context.getString(R.string.ota_downloading, percent)
                } else {
                    context.getString(R.string.btn_checking_update)
                }
                tvOtaStatus.text = statusText
                tvOtaStatus.visibility = View.VISIBLE
                pbOtaProgress.visibility = View.VISIBLE
                if (percent >= 0) {
                    pbOtaProgress.isIndeterminate = false
                    pbOtaProgress.progress = percent
                } else {
                    pbOtaProgress.isIndeterminate = true
                }
                btnCheckUpdate.isEnabled = false
                btnCheckUpdate.text = statusText
            }
            is OtaUpdateState.Verifying -> {
                tvOtaStatus.text = context.getString(R.string.ota_verifying)
                tvOtaStatus.visibility = View.VISIBLE
                pbOtaProgress.visibility = View.VISIBLE
                pbOtaProgress.isIndeterminate = true
                btnCheckUpdate.isEnabled = false
                btnCheckUpdate.text = context.getString(R.string.btn_verifying_update)
            }
            is OtaUpdateState.ReadyToInstall -> {
                tvOtaStatus.text = context.getString(R.string.ota_installing)
                tvOtaStatus.visibility = View.VISIBLE
                pbOtaProgress.visibility = View.GONE
                btnCheckUpdate.isEnabled = true
                btnCheckUpdate.setText(R.string.btn_download_update)
            }
            is OtaUpdateState.Installing -> {
                tvOtaStatus.text = context.getString(R.string.ota_installing)
                tvOtaStatus.visibility = View.VISIBLE
                pbOtaProgress.visibility = View.VISIBLE
                pbOtaProgress.isIndeterminate = true
                btnCheckUpdate.isEnabled = false
                btnCheckUpdate.setText(R.string.ota_installing)
            }
            is OtaUpdateState.Error -> {
                tvOtaStatus.text = state.message
                tvOtaStatus.visibility = View.VISIBLE
                pbOtaProgress.visibility = View.GONE
                btnCheckUpdate.isEnabled = true
                btnCheckUpdate.setText(R.string.btn_check_update)
            }
        }
    }
}
