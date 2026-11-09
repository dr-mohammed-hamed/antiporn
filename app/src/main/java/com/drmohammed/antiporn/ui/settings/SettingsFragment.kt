package com.drmohammed.antiporn.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
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
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.databinding.BottomSheetDiagnosticLogsBinding
import com.drmohammed.antiporn.databinding.FragmentSettingsBinding
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.ui.control.qr.QrScannerBottomSheet
import com.drmohammed.antiporn.provisioning.ui.ProvisioningWizardActivity
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.google.android.material.bottomsheet.BottomSheetDialog
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
    private val binding get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    private val settingsViewModel: SettingsViewModel by viewModel()
    private val viewModel get() = settingsViewModel

    private var lastViewLogsClickTime = 0L
    private var lastEditNameClickTime = 0L
    private var lastDeviceOwnerClickTime = 0L

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

        // 5. Device Owner Deactivation
        renderDeactivationSection(state, ctx)
    }

    private fun renderAiSection(state: SettingsUiState, ctx: Context) {
        binding.switchAiShield.isChecked = state.isAiRunning

        if (state.isAiRunning) {
            if (state.aiInitError.isNotEmpty()) {
                binding.tvAiStatusSubtext.text = state.aiInitError
                binding.tvAiStatusSubtext.setTextColor(ContextCompat.getColor(ctx, R.color.status_orange))
            } else {
                binding.tvAiStatusSubtext.setText(R.string.ai_shield_status_running_short)
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
            binding.tvAiStatusSubtext.setText(R.string.ai_shield_status_stopped_short)
            val onSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
            binding.tvAiStatusSubtext.setTextColor(onSurfaceVariant)
            binding.tvAiMetrics.visibility = View.GONE
        }
    }

    private fun renderOtaSection(state: SettingsUiState, ctx: Context) {
        binding.tvOtaStatus.visibility = View.GONE

        when (val ota = state.otaState) {
            is OtaUpdateState.Idle -> {
                binding.tvOtaVersion.text = getString(
                    R.string.card_ota_status_compact,
                    state.appVersionName,
                    getString(R.string.ota_status_up_to_date_short)
                )
                binding.pbOtaProgress.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_check_update_short)
            }
            is OtaUpdateState.Checking -> {
                binding.tvOtaVersion.text = getString(
                    R.string.card_ota_status_compact,
                    state.appVersionName,
                    getString(R.string.ota_status_checking_short)
                )
                binding.pbOtaProgress.visibility = View.VISIBLE
                binding.pbOtaProgress.isIndeterminate = true
                binding.btnCheckUpdate.isEnabled = false
                binding.btnCheckUpdate.setText(R.string.btn_checking_update)
            }
            is OtaUpdateState.NoUpdateAvailable -> {
                binding.tvOtaVersion.text = getString(
                    R.string.card_ota_status_compact,
                    state.appVersionName,
                    getString(R.string.ota_status_up_to_date_short)
                )
                binding.pbOtaProgress.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_check_update_short)
            }
            is OtaUpdateState.UpdateAvailable -> {
                binding.tvOtaVersion.text = getString(
                    R.string.card_ota_status_compact,
                    state.appVersionName,
                    getString(R.string.ota_status_available_short, ota.info.versionName)
                )
                binding.pbOtaProgress.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_download_update)
            }
            is OtaUpdateState.Downloading -> {
                binding.tvOtaVersion.text = getString(
                    R.string.card_ota_status_compact,
                    state.appVersionName,
                    getString(R.string.ota_status_downloading_short, ota.progressPercent)
                )
                binding.pbOtaProgress.visibility = View.VISIBLE
                binding.pbOtaProgress.isIndeterminate = ota.progressPercent < 0
                if (ota.progressPercent >= 0) {
                    binding.pbOtaProgress.progress = ota.progressPercent
                }
                binding.btnCheckUpdate.isEnabled = false
                binding.btnCheckUpdate.setText(R.string.btn_download_update)
            }
            is OtaUpdateState.Verifying -> {
                binding.tvOtaVersion.text = getString(
                    R.string.card_ota_status_compact,
                    state.appVersionName,
                    getString(R.string.btn_verifying_update)
                )
                binding.pbOtaProgress.visibility = View.VISIBLE
                binding.pbOtaProgress.isIndeterminate = true
                binding.btnCheckUpdate.isEnabled = false
                binding.btnCheckUpdate.setText(R.string.btn_verifying_update)
            }
            is OtaUpdateState.ReadyToInstall -> {
                binding.tvOtaVersion.text = getString(
                    R.string.card_ota_status_compact,
                    state.appVersionName,
                    getString(R.string.ota_status_installing_short)
                )
                binding.pbOtaProgress.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_download_update)
            }
            is OtaUpdateState.Installing -> {
                binding.tvOtaVersion.text = getString(
                    R.string.card_ota_status_compact,
                    state.appVersionName,
                    getString(R.string.ota_status_installing_short)
                )
                binding.pbOtaProgress.visibility = View.VISIBLE
                binding.pbOtaProgress.isIndeterminate = true
                binding.btnCheckUpdate.isEnabled = false
                binding.btnCheckUpdate.setText(R.string.ota_installing)
            }
            is OtaUpdateState.Error -> {
                binding.tvOtaVersion.text = getString(
                    R.string.card_ota_status_compact,
                    state.appVersionName,
                    getString(R.string.ota_status_error_short)
                )
                binding.pbOtaProgress.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.setText(R.string.btn_check_update_short)
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

    private fun renderDeactivationSection(state: SettingsUiState, ctx: Context) {
        if (!state.isDeviceOwner) {
            binding.cardDeviceOwnerDeactivation.visibility = View.GONE
            return
        }
        binding.cardDeviceOwnerDeactivation.visibility = View.VISIBLE

        if (state.isDeactivationPending) {
            binding.btnRequestDeactivation.visibility = View.GONE
            binding.layoutDeactivationPending.visibility = View.VISIBLE

            binding.pbDeactivationProgress.visibility = if (state.isDeactivating) View.VISIBLE else View.GONE
            binding.btnCancelDeactivation.isEnabled = !state.isDeactivating

            val remaining = state.remainingDeactivationMs
            if (remaining <= 0L) {
                binding.tvDeactivationCountdown.text = getString(R.string.toast_deactivation_success)
                binding.tvDeactivationCountdown.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
                binding.btnExecuteDeactivation.isEnabled = !state.isDeactivating
            } else {
                val totalMinutes = remaining / (60 * 1000L)
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                val timeStr = if (hours > 0) {
                    getString(R.string.time_format_hours_minutes, hours, minutes)
                } else {
                    getString(R.string.time_format_minutes, minutes)
                }
                binding.tvDeactivationCountdown.text = getString(R.string.deactivation_countdown_format, timeStr)
                binding.tvDeactivationCountdown.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
                binding.btnExecuteDeactivation.isEnabled = false
            }
        } else {
            binding.btnRequestDeactivation.visibility = View.VISIBLE
            binding.layoutDeactivationPending.visibility = View.GONE
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

        binding.layoutDeviceOwnerPreference.setOnClickListener {
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastDeviceOwnerClickTime < 800L) return@setOnClickListener
            lastDeviceOwnerClickTime = currentTime

            val ctx = context ?: return@setOnClickListener
            if (!ProtectionEngine.isDeviceOwner(ctx)) {
                val intent = Intent(ctx, ProvisioningWizardActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            } else {
                Toast.makeText(ctx, R.string.toast_device_owner_already_active, Toast.LENGTH_SHORT).show()
            }
        }

        binding.layoutDeviceOwnerPreference.setOnLongClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showDiagnosticLogsBottomSheet()
            true
        }

        binding.btnRequestDeactivation.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.dialog_deactivation_title)
                .setMessage(R.string.dialog_deactivation_message)
                .setPositiveButton(R.string.btn_start_48h) { _, _ ->
                    viewModel.requestDeactivation(hasQr = false) { result ->
                        val currentCtx = context ?: return@requestDeactivation
                        if (!isAdded || viewLifecycleOwner.lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED) return@requestDeactivation
                        if (result.isSuccess) {
                            Toast.makeText(currentCtx, R.string.toast_deactivation_requested, Toast.LENGTH_LONG).show()
                        } else {
                            val err = result.exceptionOrNull()?.message ?: ""
                            Toast.makeText(currentCtx, getString(R.string.error_prefix, err), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNeutralButton(R.string.btn_scan_qr_24h) { _, _ ->
                    val requestKey = "deactivation_qr_request"
                    parentFragmentManager.setFragmentResultListener(requestKey, viewLifecycleOwner) { _, bundle ->
                        val verified = bundle.getBoolean(QrScannerBottomSheet.RESULT_KEY_VERIFIED, false)
                        if (verified) {
                            viewModel.requestDeactivation(hasQr = true) { result ->
                                val currentCtx = context ?: return@requestDeactivation
                                if (!isAdded || viewLifecycleOwner.lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED) return@requestDeactivation
                                if (result.isSuccess) {
                                    Toast.makeText(currentCtx, R.string.toast_deactivation_requested, Toast.LENGTH_LONG).show()
                                } else {
                                    val err = result.exceptionOrNull()?.message ?: ""
                                    Toast.makeText(currentCtx, getString(R.string.error_prefix, err), Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            val currentCtx = context ?: return@setFragmentResultListener
                            Toast.makeText(currentCtx, R.string.toast_qr_verify_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                    QrScannerBottomSheet.newInstance(requestKey).show(parentFragmentManager, requestKey)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnCancelDeactivation.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.btn_cancel_deactivation)
                .setMessage(R.string.dialog_deactivation_cancel_confirm)
                .setPositiveButton(R.string.btn_confirm_cancel_deactivation) { _, _ ->
                    viewModel.cancelDeactivation { result ->
                        val currentCtx = context ?: return@cancelDeactivation
                        if (!isAdded || viewLifecycleOwner.lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED) return@cancelDeactivation
                        if (result.isSuccess) {
                            Toast.makeText(currentCtx, R.string.toast_deactivation_cancelled, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnExecuteDeactivation.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.btn_execute_deactivation)
                .setMessage(R.string.dialog_deactivation_execute_confirm)
                .setPositiveButton(R.string.btn_confirm_execute_deactivation) { _, _ ->
                    viewModel.executeDeactivation { result ->
                        val currentCtx = context ?: return@executeDeactivation
                        if (!isAdded || viewLifecycleOwner.lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED) return@executeDeactivation
                        if (result.isSuccess) {
                            Toast.makeText(currentCtx, R.string.toast_deactivation_success, Toast.LENGTH_LONG).show()
                        } else {
                            val err = result.exceptionOrNull()?.message ?: ""
                            Toast.makeText(currentCtx, getString(R.string.error_deactivation_execution, err), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showDiagnosticLogsBottomSheet() {
        val ctx = context ?: return
        val bottomSheetDialog = BottomSheetDialog(ctx)
        val sheetBinding = BottomSheetDiagnosticLogsBinding.inflate(layoutInflater)
        bottomSheetDialog.setContentView(sheetBinding.root)

        sheetBinding.cardViewLogs.setOnClickListener {
            bottomSheetDialog.dismiss()
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastViewLogsClickTime < 1000L) return@setOnClickListener
            lastViewLogsClickTime = currentTime
            val currentCtx = context ?: return@setOnClickListener
            val intent = Intent(currentCtx, DiagnosticLogViewerActivity::class.java)
            startActivity(intent)
        }

        sheetBinding.cardShareTelemetry.setOnClickListener {
            bottomSheetDialog.dismiss()
            val currentCtx = context ?: return@setOnClickListener
            val appContext = currentCtx.applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val reportFile = withContext(Dispatchers.IO) {
                        AiTelemetryReporter.exportReportToFile(appContext)
                    }
                    if (!isAdded || _binding == null) return@launch

                    try {
                        val chooser = AiTelemetryReporter.createShareFileIntent(appContext, reportFile)
                        startActivity(chooser)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error launching share chooser")
                        Toast.makeText(appContext, R.string.error_share_report_failed, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error generating telemetry report file")
                    Toast.makeText(appContext, R.string.error_share_report_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        bottomSheetDialog.show()
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
