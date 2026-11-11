package com.drmohammed.antiporn.waqfa.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.ActivityWaqfaSettingsBinding
import com.drmohammed.antiporn.waqfa.model.WaqfaTimeoutReactionType
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * شاشة إعدادات وإدارة نظام «وَقْفَة».
 * تمكّن المستخدم في لوحة التحكم الذاتي أو المشرف عن بعد من تخصيص التطبيقات المعترضة وردود الفعل وشروط الدخول.
 */
class WaqfaSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWaqfaSettingsBinding
    private val viewModel: WaqfaSettingsViewModel by viewModel()
    private var isBindingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWaqfaSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        val wardId = intent.getStringExtra(EXTRA_WARD_ID)
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)

        setupToolbar(isRemote)
        setupListeners()
        observeUiState()

        viewModel.initialize(isRemote, wardId, policyJson)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEngineStatus()
    }

    private fun setupToolbar(isRemote: Boolean) {
        if (isRemote) {
            binding.toolbar.title = getString(R.string.waqfa_guardian_card_title)
        }
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupListeners() {
        binding.swWaqfaMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            viewModel.setWaqfaEnabled(isChecked)
        }

        binding.tvEngineStatus.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isRemote) {
                Toast.makeText(this, getString(R.string.waqfa_engine_status_remote_toast), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            when (state.engineType) {
                WaqfaEngineType.INSTANT_0MS -> {
                    Toast.makeText(this, getString(R.string.waqfa_engine_status_instant_toast), Toast.LENGTH_SHORT).show()
                }
                WaqfaEngineType.USAGE_STATS_FALLBACK,
                WaqfaEngineType.DORMANT -> {
                    if (!state.config.isEnabled) {
                        viewModel.setWaqfaEnabled(true)
                    }
                    navigateToAccessibilitySettings()
                }
            }
        }

        binding.btnSelectApps.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag("WaqfaAppPicker") != null) return@setOnClickListener
            val currentTargets = viewModel.uiState.value.config.targetPackages
            val sheet = WaqfaAppPickerBottomSheet(currentTargets) { selected ->
                viewModel.setTargetPackages(selected)
            }
            sheet.show(supportFragmentManager, "WaqfaAppPicker")
        }

        binding.swReasonRequired.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            viewModel.setReasonRequired(isChecked)
        }

        binding.rgTimeoutReaction.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.rbExitOnly -> {
                    viewModel.setTimeoutReactionType(WaqfaTimeoutReactionType.EXIT_ONLY)
                    binding.layoutCooldownDuration.visibility = View.GONE
                }
                R.id.rbExitAndFreeze -> {
                    viewModel.setTimeoutReactionType(WaqfaTimeoutReactionType.EXIT_AND_FREEZE)
                    binding.layoutCooldownDuration.visibility = View.VISIBLE
                }
                R.id.rbNotifyOnly -> {
                    viewModel.setTimeoutReactionType(WaqfaTimeoutReactionType.NOTIFY_ONLY)
                    binding.layoutCooldownDuration.visibility = View.GONE
                }
            }
        }

        binding.etCooldownMinutes.doAfterTextChanged {
            if (isBindingUi) return@doAfterTextChanged
            val mins = it?.toString()?.toIntOrNull() ?: 3
            viewModel.setCooldownMinutes(mins)
        }

        binding.btnSaveWaqfaSettings.setOnClickListener {
            binding.btnSaveWaqfaSettings.isEnabled = false
            viewModel.saveSettings { success, updatedPolicy ->
                if (isFinishing || isDestroyed) return@saveSettings
                if (success) {
                    if (updatedPolicy != null) {
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_POLICY_JSON, updatedPolicy.toJson())
                        )
                    }
                    Toast.makeText(this, getString(R.string.waqfa_settings_saved_toast), Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    binding.btnSaveWaqfaSettings.isEnabled = true
                    Toast.makeText(this, getString(R.string.waqfa_settings_save_failed_toast), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUi(state)
                }
            }
        }
    }

    private fun renderUi(state: WaqfaSettingsUiState) {
        isBindingUi = true
        try {
            val config = state.config

            if (binding.swWaqfaMaster.isChecked != config.isEnabled) {
                binding.swWaqfaMaster.isChecked = config.isEnabled
            }

            val count = config.targetPackages.size
            binding.tvTargetAppsCount.text = getString(R.string.waqfa_target_apps_desc, count)

            // عرض شارة حالة محرك الرصد اللحظي (0ms) / الاحتياطي
            when (state.engineType) {
                WaqfaEngineType.INSTANT_0MS -> {
                    binding.tvEngineStatus.text = getString(R.string.waqfa_engine_status_instant)
                    binding.tvEngineStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                    binding.tvEngineStatus.visibility = View.VISIBLE
                }
                WaqfaEngineType.USAGE_STATS_FALLBACK -> {
                    val statusText = if (state.isRemote) {
                        getString(R.string.waqfa_engine_status_fallback)
                    } else {
                        getString(R.string.waqfa_engine_status_fallback_clickable)
                    }
                    binding.tvEngineStatus.text = statusText
                    binding.tvEngineStatus.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
                    binding.tvEngineStatus.visibility = View.VISIBLE
                }
                WaqfaEngineType.DORMANT -> {
                    val statusText = if (state.isRemote) {
                        getString(R.string.waqfa_engine_status_dormant)
                    } else {
                        getString(R.string.waqfa_engine_status_dormant_clickable)
                    }
                    binding.tvEngineStatus.text = statusText
                    val dormantColor = MaterialColors.getColor(
                        binding.tvEngineStatus,
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                    binding.tvEngineStatus.setTextColor(dormantColor)
                    binding.tvEngineStatus.visibility = View.VISIBLE
                }
            }

            if (binding.swReasonRequired.isChecked != config.isReasonRequired) {
                binding.swReasonRequired.isChecked = config.isReasonRequired
            }

            when (config.timeoutReactionType) {
                WaqfaTimeoutReactionType.EXIT_ONLY -> {
                    binding.rbExitOnly.isChecked = true
                    binding.layoutCooldownDuration.visibility = View.GONE
                }
                WaqfaTimeoutReactionType.EXIT_AND_FREEZE -> {
                    binding.rbExitAndFreeze.isChecked = true
                    binding.layoutCooldownDuration.visibility = View.VISIBLE
                }
                WaqfaTimeoutReactionType.NOTIFY_ONLY -> {
                    binding.rbNotifyOnly.isChecked = true
                    binding.layoutCooldownDuration.visibility = View.GONE
                }
            }

            val cooldownStr = config.cooldownMinutes.toString()
            if (binding.etCooldownMinutes.text?.toString() != cooldownStr) {
                binding.etCooldownMinutes.setText(cooldownStr)
            }

            binding.btnSaveWaqfaSettings.isEnabled = !state.isLoading

            if (state.showPermissionDialog) {
                showPermissionGuidanceDialog()
                viewModel.dismissPermissionDialog()
            }
        } finally {
            isBindingUi = false
        }
    }

    private fun showPermissionGuidanceDialog() {
        if (isFinishing || isDestroyed) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.waqfa_permission_dialog_title)
            .setMessage(R.string.waqfa_permission_dialog_desc)
            .setPositiveButton(R.string.waqfa_permission_dialog_enable_btn) { _, _ ->
                navigateToAccessibilitySettings()
            }
            .setNegativeButton(R.string.waqfa_permission_dialog_fallback_btn) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.waqfa_could_not_open_settings), Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.waqfa_permission_dialog_skip_btn, null)
            .show()
    }

    private fun navigateToAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (ignored: Exception) {
            android.util.Log.w("WaqfaSettingsActivity", "Direct accessibility settings intent failed, falling back to general settings", ignored)
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
                android.util.Log.e("WaqfaSettingsActivity", "Could not open system settings", e)
                Toast.makeText(this, getString(R.string.waqfa_could_not_open_settings), Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
    }
}
