package com.drmohammed.antiporn.ui.binder

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.BrowserShield
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.shields.CurfewShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.shields.SystemTamperShield
import com.drmohammed.antiporn.shields.VpnShield
import com.drmohammed.antiporn.ui.ProtectionUiState
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Encapsulates view binding, styling, and status presentation for the dashboard.
 * Decouples view logic from MainActivity to prevent God Classes.
 */
class MainViewBinder(rootView: View) {

    private val context = rootView.context

    private val greenColor = ContextCompat.getColor(context, R.color.status_green)
    private val greenBgColor = ContextCompat.getColor(context, R.color.status_green_bg)
    private val orangeColor = ContextCompat.getColor(context, R.color.status_orange)
    private val orangeBgColor = ContextCompat.getColor(context, R.color.status_orange_bg)
    private val secondaryTextColor = ContextCompat.getColor(context, R.color.text_secondary)

    private val tvOverallStatusBadge: TextView = rootView.findViewById(R.id.tvOverallStatusBadge)
    private val tvOverallStatusDesc: TextView = rootView.findViewById(R.id.tvOverallStatusDesc)
    private val layoutMainStatus: LinearLayout = rootView.findViewById(R.id.layoutMainStatus)
    private val cardMainStatus: MaterialCardView = rootView.findViewById(R.id.cardMainStatus)

    private val tvDeviceOwnerStatus: TextView = rootView.findViewById(R.id.tvDeviceOwnerStatus)
    private val tvDnsHostStatus: TextView = rootView.findViewById(R.id.tvDnsHostStatus)
    private val tvDnsLockSubtext: TextView = rootView.findViewById(R.id.tvDnsLockSubtext)
    private val tvVpnStatus: TextView = rootView.findViewById(R.id.tvVpnStatus)
    private val tvBrowserStatus: TextView = rootView.findViewById(R.id.tvBrowserStatus)
    private val tvBrowserSubtext: TextView = rootView.findViewById(R.id.tvBrowserSubtext)
    private val tvSystemTamperStatus: TextView = rootView.findViewById(R.id.tvSystemTamperStatus)
    private val tvSystemTamperSubtext: TextView = rootView.findViewById(R.id.tvSystemTamperSubtext)
    private val tvAppSuspensionStatus: TextView = rootView.findViewById(R.id.tvAppSuspensionStatus)
    private val tvAppSuspensionSubtext: TextView = rootView.findViewById(R.id.tvAppSuspensionSubtext)
    private val tvCurfewStatus: TextView = rootView.findViewById(R.id.tvCurfewStatus)
    private val tvCurfewSubtext: TextView = rootView.findViewById(R.id.tvCurfewSubtext)
    val btnCurfewTestToggle: MaterialButton = rootView.findViewById(R.id.btnCurfewTestToggle)
    val btnGrantUsageAccess: MaterialButton = rootView.findViewById(R.id.btnGrantUsageAccess)
    private val cardAdbHelp: MaterialCardView = rootView.findViewById(R.id.cardAdbHelp)
    val btnEnforceNow: MaterialButton = rootView.findViewById(R.id.btnEnforceNow)

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

    private fun renderSuccess(state: ProtectionUiState.Success) {
        btnEnforceNow.isEnabled = !state.isEnforcing
        btnEnforceNow.setText(if (state.isEnforcing) R.string.btn_enforcing else R.string.btn_enforce_now)

        if (state.isDeviceOwner) {
            cardAdbHelp.visibility = View.GONE
            tvDeviceOwnerStatus.text = context.getString(R.string.card_device_owner_active)
            tvDeviceOwnerStatus.setTextColor(greenColor)

            val dnsActive = state.shields[DnsShield.id] is ShieldStatus.Active
            val vpnActive = state.shields[VpnShield.id] is ShieldStatus.Active
            val browserActive = state.shields[BrowserShield.id] is ShieldStatus.Active
            val tamperActive = state.shields[SystemTamperShield.id] is ShieldStatus.Active
            val appSuspensionActive = state.shields[AppSuspensionShield.id] is ShieldStatus.Active
            val curfewActive = state.shields[CurfewShield.id] is ShieldStatus.Active

            val allActive = dnsActive && vpnActive && browserActive && tamperActive && appSuspensionActive && curfewActive

            if (allActive) {
                tvOverallStatusBadge.text = context.getString(R.string.status_active_title)
                tvOverallStatusBadge.setTextColor(greenColor)
                tvOverallStatusDesc.text = context.getString(R.string.status_active_desc)
                layoutMainStatus.setBackgroundColor(greenBgColor)
                cardMainStatus.strokeColor = greenColor
            } else {
                tvOverallStatusBadge.text = context.getString(R.string.status_need_enforce_title)
                tvOverallStatusBadge.setTextColor(orangeColor)
                tvOverallStatusDesc.text = context.getString(R.string.status_need_enforce_desc)
                layoutMainStatus.setBackgroundColor(orangeBgColor)
                cardMainStatus.strokeColor = orangeColor
            }

            // Bind DNS Shield
            if (dnsActive) {
                tvDnsHostStatus.text = context.getString(R.string.card_nextdns_active, DnsShield.NEXTDNS_HOST)
                tvDnsHostStatus.setTextColor(greenColor)
                tvDnsLockSubtext.text = context.getString(R.string.card_nextdns_locked)
            } else {
                val hostDisplay = state.currentDnsHost ?: context.getString(R.string.default_dns_text)
                tvDnsHostStatus.text = context.getString(R.string.card_nextdns_pending, hostDisplay, DnsShield.NEXTDNS_HOST)
                tvDnsHostStatus.setTextColor(orangeColor)
                tvDnsLockSubtext.text = context.getString(R.string.card_nextdns_unlocked)
            }

            // Bind VPN Shield
            if (vpnActive) {
                tvVpnStatus.text = context.getString(R.string.card_vpn_blocked)
                tvVpnStatus.setTextColor(greenColor)
            } else {
                tvVpnStatus.text = context.getString(R.string.card_vpn_unblocked)
                tvVpnStatus.setTextColor(orangeColor)
            }

            // Bind Browser Shield
            if (browserActive) {
                tvBrowserStatus.text = context.getString(R.string.card_browser_active)
                tvBrowserStatus.setTextColor(greenColor)
            } else {
                tvBrowserStatus.text = context.getString(R.string.card_browser_unblocked)
                tvBrowserStatus.setTextColor(orangeColor)
            }
            tvBrowserSubtext.text = context.getString(R.string.card_browser_subtext)

            // Bind System Tamper Shield
            if (tamperActive) {
                tvSystemTamperStatus.text = context.getString(R.string.card_system_tamper_active)
                tvSystemTamperStatus.setTextColor(greenColor)
            } else {
                tvSystemTamperStatus.text = context.getString(R.string.card_system_tamper_pending)
                tvSystemTamperStatus.setTextColor(orangeColor)
            }
            tvSystemTamperSubtext.text = context.getString(R.string.card_system_tamper_subtext)

            // Bind App Suspension Shield
            val appSuspensionStatus = state.shields[AppSuspensionShield.id]
            if (appSuspensionActive) {
                val detail = (appSuspensionStatus as? ShieldStatus.Active)?.detail
                tvAppSuspensionStatus.text = detail ?: context.getString(R.string.card_app_suspension_active)
                tvAppSuspensionStatus.setTextColor(greenColor)
            } else {
                val reason = (appSuspensionStatus as? ShieldStatus.NeedsEnforcement)?.reason
                tvAppSuspensionStatus.text = reason ?: context.getString(R.string.card_app_suspension_inactive)
                tvAppSuspensionStatus.setTextColor(orangeColor)
            }
            tvAppSuspensionSubtext.text = context.getString(R.string.card_app_suspension_subtext)

            // Bind Curfew Shield
            val isScheduledCurfew = CurfewEnforcer.isCurfewActive()
            val isTestActive = CurfewEnforcer.isManualTestActive(context)
            btnCurfewTestToggle.visibility = View.VISIBLE

            if (isScheduledCurfew) {
                // Scheduled night curfew is actively running (8:30 PM - 7:00 AM)
                // Lock the button in disabled state to prevent accidental or intentional bypass
                btnCurfewTestToggle.isEnabled = false
                btnCurfewTestToggle.setText(R.string.btn_curfew_night_locked)
                tvCurfewStatus.text = context.getString(R.string.card_curfew_active_desc)
                tvCurfewStatus.setTextColor(greenColor)
                tvCurfewSubtext.text = context.getString(R.string.card_curfew_night_locked_hint)
            } else {
                // Daytime hours: button enabled for manual testing
                btnCurfewTestToggle.isEnabled = true
                btnCurfewTestToggle.setText(if (isTestActive) R.string.btn_test_curfew_off else R.string.btn_test_curfew_on)
                tvCurfewSubtext.text = context.getString(R.string.card_curfew_subtext)

                if (isTestActive) {
                    tvCurfewStatus.text = context.getString(R.string.card_curfew_active_desc_test)
                    tvCurfewStatus.setTextColor(greenColor)
                } else if (curfewActive) {
                    tvCurfewStatus.text = context.getString(R.string.card_curfew_scheduled_desc)
                    tvCurfewStatus.setTextColor(greenColor)
                } else {
                    tvCurfewStatus.text = context.getString(R.string.card_curfew_inactive_desc)
                    tvCurfewStatus.setTextColor(orangeColor)
                }
            }

            val hasUsageAccess = CurfewForegroundWatcher.hasUsageStatsPermission(context)
            if (!hasUsageAccess) {
                btnGrantUsageAccess.visibility = View.VISIBLE
                tvCurfewSubtext.text = context.getString(R.string.card_curfew_usage_missing_subtext)
                tvCurfewSubtext.setTextColor(orangeColor)
            } else {
                btnGrantUsageAccess.visibility = View.GONE
                tvCurfewSubtext.setTextColor(secondaryTextColor)
            }

        } else {
            // Not Device Owner
            cardAdbHelp.visibility = View.VISIBLE
            tvOverallStatusBadge.text = context.getString(R.string.status_no_device_owner_title)
            tvOverallStatusBadge.setTextColor(orangeColor)
            tvOverallStatusDesc.text = context.getString(R.string.status_no_device_owner_desc)
            layoutMainStatus.setBackgroundColor(orangeBgColor)
            cardMainStatus.strokeColor = orangeColor

            tvDeviceOwnerStatus.text = context.getString(R.string.card_device_owner_inactive)
            tvDeviceOwnerStatus.setTextColor(orangeColor)

            tvDnsHostStatus.text = context.getString(R.string.card_nextdns_inactive)
            tvDnsHostStatus.setTextColor(orangeColor)

            tvVpnStatus.text = context.getString(R.string.card_vpn_inactive)
            tvVpnStatus.setTextColor(orangeColor)

            tvBrowserStatus.text = context.getString(R.string.card_browser_inactive)
            tvBrowserStatus.setTextColor(orangeColor)
            tvBrowserSubtext.text = context.getString(R.string.card_browser_subtext)

            tvSystemTamperStatus.text = context.getString(R.string.card_system_tamper_inactive)
            tvSystemTamperStatus.setTextColor(orangeColor)
            tvSystemTamperSubtext.text = context.getString(R.string.card_system_tamper_subtext)

            tvAppSuspensionStatus.text = context.getString(R.string.card_app_suspension_inactive)
            tvAppSuspensionStatus.setTextColor(orangeColor)
            tvAppSuspensionSubtext.text = context.getString(R.string.card_app_suspension_subtext)

            btnCurfewTestToggle.visibility = View.GONE
            btnCurfewTestToggle.isEnabled = false
            btnGrantUsageAccess.visibility = View.GONE
            tvCurfewStatus.text = context.getString(R.string.card_curfew_inactive_desc)
            tvCurfewStatus.setTextColor(orangeColor)
            tvCurfewSubtext.text = context.getString(R.string.card_curfew_subtext)
        }
    }
}
