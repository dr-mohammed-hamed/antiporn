package com.drmohammed.antiporn.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.FragmentSettingsBinding
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

/**
 * Settings Fragment implementing modern Material 3 List Preference ergonomics:
 * 1. Screen AI content inspection toggle & live metrics.
 * 2. OTA App update checker & download progress.
 * 3. Google cloud account linking & sync state.
 * 4. Device Owner system status.
 * 5. Black Box Diagnostic Log Viewer & Telemetry export.
 */
class SettingsFragment : Fragment() {

    interface SettingsActionListener {
        fun onToggleAiRequested(enable: Boolean)
        fun onCheckOtaRequested()
        fun onLinkGoogleRequested()
    }

    private var actionListener: SettingsActionListener? = null

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val settingsViewModel: SettingsViewModel by viewModel()
    private val viewModel get() = settingsViewModel

    private var lastViewLogsClickTime = 0L
    private var lastEditNameClickTime = 0L

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

        // 0. Device Display Name
        binding.tvDeviceDisplayName.text = state.myDisplayName.ifBlank { getString(R.string.device_name_not_set) }

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

            if (state.aiLastDetectionLabel.isNotEmpty()) {
                val confPercent = (state.aiLastDetectionConfidence * 100).toInt()
                val fpsText = getString(R.string.ai_fps_format, state.aiFps)
                val detText = getString(R.string.ai_last_detection_format, state.aiLastDetectionLabel, confPercent)
                val modelText = getString(R.string.ai_model_active_format, state.aiModelName)
                binding.tvAiMetrics.text = getString(R.string.ai_metrics_full_format, fpsText, detText, modelText)
                binding.tvAiMetrics.setTextColor(ContextCompat.getColor(ctx, R.color.status_orange))
            } else {
                val fpsText = getString(R.string.ai_fps_format, state.aiFps)
                val modelText = getString(R.string.ai_model_active_format, state.aiModelName)
                binding.tvAiMetrics.text = getString(R.string.ai_metrics_simple_format, fpsText, modelText)
                val onSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
                binding.tvAiMetrics.setTextColor(onSurfaceVariant)
            }
            binding.tvAiMetrics.visibility = View.VISIBLE
        } else {
            binding.tvAiStatusSubtext.setText(R.string.card_ai_shield_idle_desc)
            val onSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
            binding.tvAiStatusSubtext.setTextColor(onSurfaceVariant)
            binding.tvAiMetrics.visibility = View.GONE
        }
    }

    private fun renderOtaSection(state: SettingsUiState, ctx: Context) {
        binding.tvOtaVersion.text = getString(R.string.card_ota_current_version, state.appVersionName, state.appVersionCode)

        when (val ota = state.otaState) {
            is OtaUpdateState.Idle -> {
                binding.tvOtaStatus.visibility = View.GONE
                binding.pbOtaProgress.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_check_update)
            }
            is OtaUpdateState.Checking -> {
                binding.tvOtaStatus.setText(R.string.btn_checking_update)
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.pbOtaProgress.visibility = View.VISIBLE
                binding.pbOtaProgress.isIndeterminate = true
                binding.btnCheckUpdate.isEnabled = false
                binding.btnCheckUpdate.setText(R.string.btn_checking_update)
            }
            is OtaUpdateState.NoUpdateAvailable -> {
                binding.tvOtaStatus.setText(R.string.ota_up_to_date)
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.pbOtaProgress.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_check_update)
            }
            is OtaUpdateState.UpdateAvailable -> {
                binding.tvOtaStatus.text = getString(R.string.ota_update_available, ota.info.versionName)
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.pbOtaProgress.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_download_update)
            }
            is OtaUpdateState.Downloading -> {
                binding.tvOtaStatus.text = getString(R.string.ota_downloading, ota.progressPercent)
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.pbOtaProgress.visibility = View.VISIBLE
                binding.pbOtaProgress.isIndeterminate = ota.progressPercent < 0
                if (ota.progressPercent >= 0) {
                    binding.pbOtaProgress.progress = ota.progressPercent
                }
                binding.btnCheckUpdate.isEnabled = false
                binding.btnCheckUpdate.setText(R.string.btn_download_update)
            }
            is OtaUpdateState.Verifying -> {
                binding.tvOtaStatus.setText(R.string.ota_verifying)
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.pbOtaProgress.visibility = View.VISIBLE
                binding.pbOtaProgress.isIndeterminate = true
                binding.btnCheckUpdate.isEnabled = false
                binding.btnCheckUpdate.setText(R.string.btn_verifying_update)
            }
            is OtaUpdateState.ReadyToInstall -> {
                binding.tvOtaStatus.setText(R.string.ota_installing)
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.pbOtaProgress.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_download_update)
            }
            is OtaUpdateState.Installing -> {
                binding.tvOtaStatus.setText(R.string.ota_installing)
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.pbOtaProgress.visibility = View.VISIBLE
                binding.pbOtaProgress.isIndeterminate = true
                binding.btnCheckUpdate.isEnabled = false
                binding.btnCheckUpdate.setText(R.string.ota_installing)
            }
            is OtaUpdateState.Error -> {
                binding.tvOtaStatus.text = ota.message
                binding.tvOtaStatus.visibility = View.VISIBLE
                binding.pbOtaProgress.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_check_update)
            }
        }
    }

    private fun renderGoogleSection(state: SettingsUiState, ctx: Context) {
        if (state.isGoogleLinked && !state.googleAccountEmail.isNullOrBlank()) {
            binding.tvGoogleAccountStatus.text = getString(R.string.settings_google_linked, state.googleAccountEmail)
            binding.tvGoogleAccountStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            binding.btnLinkGoogle.visibility = View.GONE
        } else {
            binding.tvGoogleAccountStatus.setText(R.string.settings_google_unlinked)
            binding.tvGoogleAccountStatus.setTextColor(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(ctx, R.color.shield_hero_desc))
            )
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

    private fun setupListeners() {
        binding.layoutDeviceNamePreference.setOnClickListener {
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastEditNameClickTime < 800L) return@setOnClickListener
            lastEditNameClickTime = currentTime
            showEditDeviceNameDialog()
        }

        binding.switchAiShield.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchAiShield.isPressed) {
                actionListener?.onToggleAiRequested(isChecked)
            }
        }

        binding.btnCheckUpdate.setOnClickListener {
            actionListener?.onCheckOtaRequested()
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
                        AiTelemetryReporter.generateMarkdownReport(appContext)
                    }
                    if (!isAdded || _binding == null) return@launch

                    AiTelemetryReporter.copyReportToClipboard(appContext, report)
                    Toast.makeText(appContext, R.string.toast_telemetry_copied, Toast.LENGTH_SHORT).show()

                    try {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_SUBJECT, "AntiPorn Diagnostic Report")
                            putExtra(Intent.EXTRA_TEXT, report)
                            type = "text/plain"
                        }
                        val chooser = Intent.createChooser(sendIntent, getString(R.string.share_telemetry_chooser_title))
                        startActivity(chooser)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error launching share chooser", e)
                    }
                } finally {
                    _binding?.btnShareTelemetry?.isEnabled = true
                }
            }
        }
    }

    private fun showEditDeviceNameDialog() {
        val ctx = context ?: return
        val currentName = viewModel.uiState.value.myDisplayName
        val input = EditText(ctx).apply {
            setText(currentName)
            setSelection(text?.length ?: 0)
            hint = getString(R.string.dialog_edit_device_name_hint)
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(28))
        }
        val frame = android.widget.FrameLayout(ctx).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_edit_device_name_title)
            .setView(frame)
            .setPositiveButton(R.string.btn_save) { dialog, _ ->
                val newName = input.text?.toString()?.trim() ?: ""
                if (newName.isNotBlank() && newName != currentName) {
                    viewModel.setMyDisplayName(newName) { result ->
                        if (!isAdded || _binding == null) return@setMyDisplayName
                        result.onSuccess {
                            Toast.makeText(context, R.string.toast_name_saved, Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            Timber.e(e, "Failed to update display name")
                            val errMsg = e.localizedMessage ?: getString(R.string.error_unknown)
                            Toast.makeText(context, getString(R.string.toast_partner_failed, errMsg), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    // Public legacy update hooks for backward compatibility
    fun updateAllStates() {
        settingsViewModel.refreshAll()
    }

    fun updateAiUi() {
        settingsViewModel.refreshAiState()
    }

    fun updateGoogleUi() {
        settingsViewModel.refreshGoogleState()
    }

    fun updateDeviceOwnerUi() {
        settingsViewModel.refreshDeviceOwnerState()
    }

    fun bindOtaState(state: OtaUpdateState, versionName: String, versionCode: Long) {
        settingsViewModel.refreshAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "AntiPorn_SettingsFrag"
    }
}
