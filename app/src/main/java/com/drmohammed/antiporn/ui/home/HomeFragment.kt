package com.drmohammed.antiporn.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.curfew.NapController
import com.drmohammed.antiporn.databinding.FragmentHomeBinding
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.BrowserShield
import com.drmohammed.antiporn.shields.CurfewShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.shields.SystemTamperShield
import com.drmohammed.antiporn.shields.VpnShield
import com.drmohammed.antiporn.ui.ProtectionUiState
import com.drmohammed.antiporn.ui.shields.ActiveShieldsBottomSheet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.android.ext.android.inject
import com.drmohammed.antiporn.notification.repository.NotificationRepository
import com.drmohammed.antiporn.ui.notification.NotificationCenterActivity

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

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by viewModel()
    private val notificationRepo: NotificationRepository by inject()

    private var lastShieldClickTime = 0L
    private var lastNapTapTime = 0L
    private var lastEnforceClickTime = 0L
    private var lastBellClickTime = 0L

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
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        homeViewModel.refreshProtectionState(isEnforcing = false)
        homeViewModel.tickNap()
        checkUsageAccess()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    homeViewModel.uiState.collect { state ->
                        render(state)
                    }
                }
                launch {
                    notificationRepo.unreadCountFlow.collect { count ->
                        if (count > 0) {
                            binding.tvNotificationBadge.visibility = View.VISIBLE
                            binding.tvNotificationBadge.text = if (count > 99) "99+" else count.toString()
                        } else {
                            binding.tvNotificationBadge.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun render(state: HomeUiState) {
        if (_binding == null) return

        updateProtectionState(state.protectionState)
        updateNapCountdown(state.napRemainingMs, state.isNapActive, state.isCurfewActive)

        binding.btnGrantUsageAccess.visibility = if (!state.hasUsageAccess) View.VISIBLE else View.GONE
    }

    private fun setupListeners() {
        binding.btnNotificationBell.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBellClickTime < 500L) return@setOnClickListener
            lastBellClickTime = now
            val intent = Intent(requireContext(), NotificationCenterActivity::class.java)
            startActivity(intent)
        }

        binding.layoutHeroShield.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastShieldClickTime >= 600L) {
                lastShieldClickTime = now
                context?.let { ctx -> ActiveShieldsBottomSheet.show(ctx) }
            }
        }

        binding.btnHeroNapStart.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNapTapTime < 800L) return@setOnClickListener
            lastNapTapTime = now

            val ctx = context ?: return@setOnClickListener
            if (CurfewEnforcer.isCurfewActive(ctx)) {
                Toast.makeText(ctx, R.string.card_curfew_night_locked_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.dialog_nap_confirm_title)
                .setMessage(R.string.dialog_nap_confirm_msg)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                    _binding?.btnHeroNapStart?.isEnabled = false
                    _binding?.btnHeroNapExtend?.isEnabled = false
                    homeViewModel.startNap { success ->
                        if (isAdded && _binding != null) {
                            binding.btnHeroNapStart.isEnabled = true
                            binding.btnHeroNapExtend.isEnabled = true
                            if (success) {
                                Toast.makeText(ctx, R.string.toast_nap_started, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnHeroNapExtend.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNapTapTime < 800L) return@setOnClickListener
            lastNapTapTime = now

            val ctx = context ?: return@setOnClickListener
            if (!NapController.isNapActive(ctx)) return@setOnClickListener

            binding.btnHeroNapExtend.isEnabled = false
            homeViewModel.extendNap { success ->
                if (isAdded && _binding != null) {
                    binding.btnHeroNapExtend.isEnabled = true
                    if (success) {
                        Toast.makeText(ctx, R.string.toast_nap_extended, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnEnforceNow.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEnforceClickTime < 1000L) return@setOnClickListener
            lastEnforceClickTime = now

            binding.btnEnforceNow.isEnabled = false
            homeViewModel.toggleProtection { success, errorMsg ->
                if (isAdded && _binding != null) {
                    binding.btnEnforceNow.isEnabled = true
                    val ctx = context ?: return@toggleProtection
                    val message = if (success) {
                        getString(R.string.toast_enforce_success, DnsShield.getEffectiveDnsHost(ctx))
                    } else {
                        errorMsg ?: getString(R.string.status_need_enforce_desc)
                    }
                    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnGrantUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    fun updateProtectionState(state: ProtectionUiState) {
        if (!isAdded || _binding == null) return

        val ctx = context ?: return
        when (state) {
            is ProtectionUiState.Loading -> {
                binding.btnEnforceNow.isEnabled = false
                binding.btnEnforceNow.setText(R.string.btn_enforcing)
            }
            is ProtectionUiState.Error -> {
                binding.btnEnforceNow.isEnabled = true
                binding.btnEnforceNow.setText(R.string.btn_enforce_now)
            }
            is ProtectionUiState.Success -> {
                binding.btnEnforceNow.isEnabled = !state.isEnforcing
                binding.btnEnforceNow.setText(if (state.isEnforcing) R.string.btn_enforcing else R.string.btn_enforce_now)

                if (state.isDeviceOwner) {
                    binding.cardAdbHelp.visibility = View.GONE

                    val dnsActive = state.shields[DnsShield.id] is ShieldStatus.Active
                    val vpnActive = state.shields[VpnShield.id] is ShieldStatus.Active
                    val browserActive = state.shields[BrowserShield.id] is ShieldStatus.Active
                    val tamperActive = state.shields[SystemTamperShield.id] is ShieldStatus.Active
                    val appSuspensionActive = state.shields[AppSuspensionShield.id] is ShieldStatus.Active
                    val curfewActive = state.shields[CurfewShield.id] is ShieldStatus.Active

                    val allActive = dnsActive && vpnActive && browserActive && tamperActive && appSuspensionActive && curfewActive

                    if (allActive) {
                        binding.cardHomeNotice.visibility = View.GONE
                        binding.tvHeroShieldStatus.text = getString(R.string.home_shield_active)
                        binding.tvHeroShieldStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_emerald_radiant))
                        binding.tvHeroShieldStatus.setBackgroundResource(R.drawable.bg_shield_status_badge)
                        binding.frameHeroShieldGlow.setBackgroundResource(R.drawable.bg_hero_shield_glow)
                    } else {
                        binding.cardHomeNotice.visibility = View.VISIBLE
                        binding.tvHomeNoticeTitle.text = getString(R.string.status_need_enforce_title)
                        binding.tvHomeNoticeDesc.text = getString(R.string.status_need_enforce_desc)
                        binding.tvHeroShieldStatus.text = getString(R.string.home_shield_inactive)
                        binding.tvHeroShieldStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
                        binding.tvHeroShieldStatus.setBackgroundResource(R.drawable.bg_shield_status_badge_amber)
                        binding.frameHeroShieldGlow.setBackgroundResource(R.drawable.bg_hero_shield_inactive)
                    }
                } else {
                    binding.cardAdbHelp.visibility = View.VISIBLE
                    binding.cardHomeNotice.visibility = View.VISIBLE
                    binding.tvHomeNoticeTitle.text = getString(R.string.status_no_device_owner_title)
                    binding.tvHomeNoticeDesc.text = getString(R.string.status_no_device_owner_desc)

                    binding.tvHeroShieldStatus.text = getString(R.string.home_shield_inactive)
                    binding.tvHeroShieldStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
                    binding.tvHeroShieldStatus.setBackgroundResource(R.drawable.bg_shield_status_badge_amber)
                    binding.frameHeroShieldGlow.setBackgroundResource(R.drawable.bg_hero_shield_inactive)
                }
            }
        }
    }

    /**
     * Ticks the countdown timer.
     */
    fun updateNapCountdown(remainingMs: Long) {
        val ctx = context ?: return
        val isNight = CurfewEnforcer.isCurfewActive(ctx)
        val isNap = remainingMs > 0L
        updateNapCountdown(remainingMs, isNap, isNight)
    }

    private fun updateNapCountdown(remainingMs: Long, isNap: Boolean, isNight: Boolean) {
        if (!isAdded || _binding == null) return

        if (isNight) {
            binding.btnHeroNapStart.isEnabled = false
            binding.btnHeroNapStart.setText(R.string.btn_nap_night_locked)
            binding.layoutNapActiveControls.visibility = View.GONE
        } else if (isNap) {
            binding.btnHeroNapStart.isEnabled = false
            binding.btnHeroNapStart.setText(R.string.btn_nap_active)
            binding.layoutNapActiveControls.visibility = View.VISIBLE

            val totalSec = (remainingMs / 1000L).toInt()
            val mm = totalSec / 60
            val ss = totalSec % 60
            binding.tvHeroNapCountdown.text = getString(R.string.nap_countdown_format, mm, ss)
        } else {
            binding.btnHeroNapStart.isEnabled = true
            binding.btnHeroNapStart.setText(R.string.btn_hero_nap_start)
            binding.layoutNapActiveControls.visibility = View.GONE
        }
    }

    private fun updateNapDisplay() {
        homeViewModel.tickNap()
    }

    private fun checkUsageAccess() {
        val ctx = context ?: return
        val hasUsage = CurfewForegroundWatcher.hasUsageStatsPermission(ctx)
        _binding?.btnGrantUsageAccess?.visibility = if (!hasUsage) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
