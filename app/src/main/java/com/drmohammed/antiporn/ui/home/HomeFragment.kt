package com.drmohammed.antiporn.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.curfew.NapController
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

/**
 * Modern Hero Dashboard Fragment.
 * Presents the Al-Hosn radiant shield emblem with interactive bottom sheet access,
 * and the centerpiece 1-Hour Nap mode with live countdown synchronization.
 */
class HomeFragment : Fragment() {

    interface HomeActionListener {
        fun onEnforceRequested()
    }

    private var actionListener: HomeActionListener? = null

    private var layoutHeroShield: View? = null
    private var ivHeroShieldLogo: ImageView? = null
    private var frameHeroShieldGlow: FrameLayout? = null
    private var tvHeroShieldStatus: TextView? = null
    private var tvHeroShieldTapHint: TextView? = null

    private var btnHeroNapStart: MaterialButton? = null
    private var layoutNapActiveControls: LinearLayout? = null
    private var tvHeroNapCountdown: TextView? = null
    private var btnHeroNapExtend: MaterialButton? = null
    private var btnGrantUsageAccess: MaterialButton? = null

    private var cardHomeNotice: MaterialCardView? = null
    private var tvHomeNoticeTitle: TextView? = null
    private var tvHomeNoticeDesc: TextView? = null
    private var btnEnforceNow: MaterialButton? = null

    private var cardAdbHelp: MaterialCardView? = null
    private var tvAdbHelpCmd: TextView? = null

    private var lastShieldClickTime = 0L
    private var lastNapTapTime = 0L
    private var lastState: ProtectionUiState? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is HomeActionListener) {
            actionListener = context
        }
    }

    override fun onDetach() {
        super.onDetach()
        actionListener = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        initViews(view)
        setupListeners()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lastState?.let { updateProtectionState(it) }
        updateNapDisplay()
    }

    override fun onResume() {
        super.onResume()
        updateNapDisplay()
        checkUsageAccess()
    }

    private fun initViews(root: View) {
        layoutHeroShield = root.findViewById(R.id.layoutHeroShield)
        ivHeroShieldLogo = root.findViewById(R.id.ivHeroShieldLogo)
        frameHeroShieldGlow = root.findViewById(R.id.frameHeroShieldGlow)
        tvHeroShieldStatus = root.findViewById(R.id.tvHeroShieldStatus)
        tvHeroShieldTapHint = root.findViewById(R.id.tvHeroShieldTapHint)

        btnHeroNapStart = root.findViewById(R.id.btnHeroNapStart)
        layoutNapActiveControls = root.findViewById(R.id.layoutNapActiveControls)
        tvHeroNapCountdown = root.findViewById(R.id.tvHeroNapCountdown)
        btnHeroNapExtend = root.findViewById(R.id.btnHeroNapExtend)
        btnGrantUsageAccess = root.findViewById(R.id.btnGrantUsageAccess)

        cardHomeNotice = root.findViewById(R.id.cardHomeNotice)
        tvHomeNoticeTitle = root.findViewById(R.id.tvHomeNoticeTitle)
        tvHomeNoticeDesc = root.findViewById(R.id.tvHomeNoticeDesc)
        btnEnforceNow = root.findViewById(R.id.btnEnforceNow)

        cardAdbHelp = root.findViewById(R.id.cardAdbHelp)
        tvAdbHelpCmd = root.findViewById(R.id.tvAdbHelpCmd)
    }

    private fun setupListeners() {
        layoutHeroShield?.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastShieldClickTime >= 600L) {
                lastShieldClickTime = now
                context?.let { ctx -> ActiveShieldsBottomSheet.show(ctx) }
            }
        }

        btnHeroNapStart?.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNapTapTime < 800L) return@setOnClickListener
            lastNapTapTime = now

            val ctx = context ?: return@setOnClickListener
            if (CurfewEnforcer.isCurfewActive(ctx)) return@setOnClickListener

            NapController.startNap(ctx)
            updateNapDisplay()
        }

        btnHeroNapExtend?.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNapTapTime < 800L) return@setOnClickListener
            lastNapTapTime = now

            val ctx = context ?: return@setOnClickListener
            NapController.extendNap(ctx)
            updateNapDisplay()
        }

        btnEnforceNow?.setOnClickListener {
            actionListener?.onEnforceRequested()
        }

        btnGrantUsageAccess?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    fun updateProtectionState(state: ProtectionUiState) {
        lastState = state
        if (!isAdded || view == null) return

        val ctx = context ?: return
        when (state) {
            is ProtectionUiState.Loading -> {
                btnEnforceNow?.isEnabled = false
                btnEnforceNow?.setText(R.string.btn_enforcing)
            }
            is ProtectionUiState.Error -> {
                btnEnforceNow?.isEnabled = true
                btnEnforceNow?.setText(R.string.btn_enforce_now)
            }
            is ProtectionUiState.Success -> {
                btnEnforceNow?.isEnabled = !state.isEnforcing
                btnEnforceNow?.setText(if (state.isEnforcing) R.string.btn_enforcing else R.string.btn_enforce_now)

                if (state.isDeviceOwner) {
                    cardAdbHelp?.visibility = View.GONE

                    val dnsActive = state.shields[DnsShield.id] is ShieldStatus.Active
                    val vpnActive = state.shields[VpnShield.id] is ShieldStatus.Active
                    val browserActive = state.shields[BrowserShield.id] is ShieldStatus.Active
                    val tamperActive = state.shields[SystemTamperShield.id] is ShieldStatus.Active
                    val appSuspensionActive = state.shields[AppSuspensionShield.id] is ShieldStatus.Active
                    val curfewActive = state.shields[CurfewShield.id] is ShieldStatus.Active

                    val allActive = dnsActive && vpnActive && browserActive && tamperActive && appSuspensionActive && curfewActive

                    if (allActive) {
                        cardHomeNotice?.visibility = View.GONE
                        tvHeroShieldStatus?.text = getString(R.string.home_shield_active)
                        tvHeroShieldStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.shield_emerald_radiant))
                        tvHeroShieldStatus?.setBackgroundResource(R.drawable.bg_shield_status_badge)
                        frameHeroShieldGlow?.setBackgroundResource(R.drawable.bg_hero_shield_glow)
                    } else {
                        cardHomeNotice?.visibility = View.VISIBLE
                        tvHomeNoticeTitle?.text = getString(R.string.status_need_enforce_title)
                        tvHomeNoticeDesc?.text = getString(R.string.status_need_enforce_desc)

                        tvHeroShieldStatus?.text = getString(R.string.home_shield_inactive)
                        tvHeroShieldStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
                        tvHeroShieldStatus?.setBackgroundResource(R.drawable.bg_shield_status_badge_amber)
                    }
                } else {
                    cardAdbHelp?.visibility = View.VISIBLE
                    cardHomeNotice?.visibility = View.VISIBLE
                    tvHomeNoticeTitle?.text = getString(R.string.status_no_device_owner_title)
                    tvHomeNoticeDesc?.text = getString(R.string.status_no_device_owner_desc)

                    tvHeroShieldStatus?.text = getString(R.string.home_shield_inactive)
                    tvHeroShieldStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
                    tvHeroShieldStatus?.setBackgroundResource(R.drawable.bg_shield_status_badge_amber)
                }
            }
        }
    }

    /**
     * Ticks the countdown timer every second from MainActivity ticker.
     */
    fun updateNapCountdown(remainingMs: Long) {
        if (!isAdded || view == null) return
        val ctx = context ?: return

        val isNight = CurfewEnforcer.isCurfewActive(ctx)
        val isNap = remainingMs > 0L

        if (isNight) {
            btnHeroNapStart?.isEnabled = false
            btnHeroNapStart?.setText(R.string.btn_nap_night_locked)
            layoutNapActiveControls?.visibility = View.GONE
        } else if (isNap) {
            btnHeroNapStart?.isEnabled = false
            btnHeroNapStart?.setText(R.string.btn_nap_active)
            layoutNapActiveControls?.visibility = View.VISIBLE

            val totalSec = (remainingMs / 1000L).toInt()
            val mm = totalSec / 60
            val ss = totalSec % 60
            tvHeroNapCountdown?.text = getString(R.string.nap_countdown_format, mm, ss)
        } else {
            btnHeroNapStart?.isEnabled = true
            btnHeroNapStart?.setText(R.string.btn_hero_nap_start)
            layoutNapActiveControls?.visibility = View.GONE
        }
    }

    private fun updateNapDisplay() {
        val ctx = context ?: return
        val remaining = NapController.remainingMs(ctx)
        updateNapCountdown(remaining)
    }

    private fun checkUsageAccess() {
        val ctx = context ?: return
        val hasUsage = CurfewForegroundWatcher.hasUsageStatsPermission(ctx)
        btnGrantUsageAccess?.visibility = if (!hasUsage) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        layoutHeroShield = null
        ivHeroShieldLogo = null
        frameHeroShieldGlow = null
        tvHeroShieldStatus = null
        tvHeroShieldTapHint = null
        btnHeroNapStart = null
        layoutNapActiveControls = null
        tvHeroNapCountdown = null
        btnHeroNapExtend = null
        btnGrantUsageAccess = null
        cardHomeNotice = null
        tvHomeNoticeTitle = null
        tvHomeNoticeDesc = null
        btnEnforceNow = null
        cardAdbHelp = null
        tvAdbHelpCmd = null
        super.onDestroyView()
    }
}
