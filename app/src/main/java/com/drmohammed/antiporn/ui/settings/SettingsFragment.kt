package com.drmohammed.antiporn.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import com.drmohammed.antiporn.ai.service.ScreenAiProjectionService
import com.drmohammed.antiporn.cloud.auth.GoogleAuthManager
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.databinding.FragmentSettingsBinding
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

/**
 * Settings Fragment implementing modern Material 3 List Preference ergonomics:
 * Screen AI Shield inspection toggle, Google Identity protection,
 * OTA auto-update firmware installation, and operational telemetry export.
 */
class SettingsFragment : Fragment() {

    interface SettingsActionListener {
        fun onAiToggleRequested(enable: Boolean)
        fun onCheckUpdateRequested()
        fun onLinkGoogleRequested()
    }

    private var actionListener: SettingsActionListener? = null

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val settingsViewModel: SettingsViewModel by viewModel()
    private val viewModel get() = settingsViewModel

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is SettingsActionListener) {
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
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeViewModel()
        settingsViewModel.refreshAll()
    }

    override fun onResume() {
        super.onResume()
        settingsViewModel.refreshAll()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: SettingsUiState) {
        if (_binding == null) return
        val ctx = context ?: return

        // 1. Screen AI Inspection
        renderAiSection(state, ctx)

        // 2. OTA Updates
        renderOtaSection(state, ctx)

        // 3. Google Account
        renderGoogleSection(state, ctx)

        // 4. Device Owner Status
        renderDeviceOwnerSection(state, ctx)
    }

    private fun renderAiSection(state: SettingsUiState, ctx: Context) {
        binding.switchAiShield.isChecked = state.isAiRunning

        if (state.isAiRunning) {
            if (state.aiInitError.isNotEmpty()) {
                binding.tvAiStatusSubtext.text = state.aiInitError
                binding.tvAiStatusSubtext.setTextColor(ContextCompat.getColor(ctx, R.color.status_orange))
            } else {
                binding.tvAiStatusSubtext.setText(R.string.card_ai_shield_active_desc)
                binding.tvAiStatusSubtext.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            }
            binding.tvAiMetrics.visibility = View.VISIBLE
            val fpsStr = "%.1f".format(state.aiFps)
            val dropStr = state.aiDroppedFrames.toString()
            val confPercent = (state.aiLastConfidence * 100).toInt()
            binding.tvAiMetrics.text = getString(
                R.string.ai_metrics_format,
                fpsStr,
                dropStr,
                state.aiLastLabel.ifEmpty { "Idle" },
                confPercent
            )
        } else {
            binding.tvAiStatusSubtext.setText(R.string.card_ai_shield_inactive_desc)
            val onSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
            binding.tvAiStatusSubtext.setTextColor(onSurfaceVariant)
            binding.tvAiMetrics.visibility = View.GONE
        }
    }

    private fun renderOtaSection(state: SettingsUiState, ctx: Context) {
        binding.tvOtaVersion.text = getString(R.string.ota_current_version_format, state.versionName, state.versionCode)

        when (val otaState = state.otaState) {
            is OtaUpdateState.Idle -> {
                binding.pbOtaProgress.visibility = View.GONE
                binding.tvOtaStatus.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_check_update)
            }
            is OtaUpdateState.Checking -> {
                binding.pbOtaProgress.visibility = View.VISIBLE
                binding.pbOtaProgress.isIndeterminate = true
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.tvOtaStatus.setText(R.string.ota_status_checking)
                binding.btnCheckUpdate.isEnabled = false
            }
            is OtaUpdateState.Available -> {
                binding.pbOtaProgress.visibility = View.GONE
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.tvOtaStatus.text = getString(R.string.ota_status_available, otaState.info.versionName)
                binding.tvOtaStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_update_now)
            }
            is OtaUpdateState.Downloading -> {
                binding.pbOtaProgress.visibility = View.VISIBLE
                binding.pbOtaProgress.isIndeterminate = false
                binding.pbOtaProgress.progress = otaState.progress
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.tvOtaStatus.text = getString(R.string.ota_status_downloading, otaState.progress)
                binding.btnCheckUpdate.isEnabled = false
            }
            is OtaUpdateState.Downloaded -> {
                binding.pbOtaProgress.visibility = View.GONE
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.tvOtaStatus.setText(R.string.ota_status_ready_to_install)
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_install_update)
            }
            is OtaUpdateState.Installing -> {
                binding.pbOtaProgress.visibility = View.VISIBLE
                binding.pbOtaProgress.isIndeterminate = true
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.tvOtaStatus.setText(R.string.ota_status_installing)
                binding.btnCheckUpdate.isEnabled = false
            }
            is OtaUpdateState.UpToDate -> {
                binding.pbOtaProgress.visibility = View.GONE
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.tvOtaStatus.setText(R.string.ota_status_up_to_date)
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_check_update)
            }
            is OtaUpdateState.Error -> {
                binding.pbOtaProgress.visibility = View.GONE
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.tvOtaStatus.text = getString(R.string.ota_status_error, otaState.message)
                binding.tvOtaStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_red))
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_retry_update)
            }
            else -> {
                binding.pbOtaProgress.visibility = View.GONE
                binding.tvOtaStatus.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_check_update)
            }
        }
    }

    private fun renderGoogleSection(state: SettingsUiState, ctx: Context) {
        if (state.isGoogleLinked) {
            val email = state.googleAccountEmail.ifEmpty { getString(R.string.account_status_secured) }
            binding.tvGoogleAccountStatus.text = getString(R.string.account_secured_as, email)
            binding.tvGoogleAccountStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_emerald_radiant))
            binding.btnLinkGoogle.visibility = View.GONE
        } else {
            binding.tvGoogleAccountStatus.setText(R.string.account_status_unsecured)
            binding.tvGoogleAccountStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
            binding.btnLinkGoogle.visibility = View.VISIBLE
            binding.btnLinkGoogle.setText(R.string.btn_secure_with_google)
        }
    }

    private fun renderDeviceOwnerSection(state: SettingsUiState, ctx: Context) {
        if (state.isDeviceOwner) {
            binding.tvDeviceOwnerStatus.setText(R.string.card_device_owner_active)
            binding.tvDeviceOwnerStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_emerald_radiant))
        } else {
            binding.tvDeviceOwnerStatus.setText(R.string.card_device_owner_inactive)
            binding.tvDeviceOwnerStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
        }
    }

    private var lastViewLogsClickTime = 0L

    private fun setupListeners() {
        binding.switchAiShield.setOnClickListener {
            val targetState = binding.switchAiShield.isChecked
            actionListener?.onAiToggleRequested(targetState)
        }

        binding.btnCheckUpdate.setOnClickListener {
            actionListener?.onCheckUpdateRequested()
        }

        binding.btnLinkGoogle.setOnClickListener {
            actionListener?.onLinkGoogleRequested()
        }

        binding.btnViewLogs.setOnClickListener {
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastViewLogsClickTime < 1000L) return@setOnClickListener
            lastViewLogsClickTime = currentTime
            val ctx = context ?: return@setOnClickListener
            val intent = Intent(ctx, DiagnosticLogViewerActivity::class.java)
            startActivity(intent)
        }

        binding.btnShareTelemetry.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val appContext = ctx.applicationContext
            binding.btnShareTelemetry.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val report = withContext(Dispatchers.IO) {
                        AiTelemetryReporter.buildFullTelemetryReport(appContext)
                    }
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_telemetry_subject))
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    try {
                        val chooser = Intent.createChooser(sendIntent, getString(R.string.share_telemetry_chooser_title))
                        startActivity(chooser)
                    } catch (e: Exception) {
                        Timber.e(e, "Error launching share chooser")
                    }
                } finally {
                    _binding?.btnShareTelemetry?.isEnabled = true
                }
            }
        }
    }

    fun updateAllStates() {
        settingsViewModel.refreshAll()
    }

    fun updateAiUi() {
        settingsViewModel.refreshAiState()
    }

    fun updateGoogleAccountUi() {
        settingsViewModel.refreshGoogleAccountState()
    }

    fun updateDeviceOwnerUi() {
        settingsViewModel.refreshDeviceOwnerState()
    }

    fun bindOtaState(state: OtaUpdateState, versionName: String, versionCode: Long) {
        settingsViewModel.updateOtaState(state, versionName, versionCode)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "AntiPorn_SettingsFrag"
    }
}
