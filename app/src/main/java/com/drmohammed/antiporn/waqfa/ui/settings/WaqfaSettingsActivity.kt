package com.drmohammed.antiporn.waqfa.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.ActivityWaqfaSettingsBinding
import com.drmohammed.antiporn.waqfa.model.WaqfaTimeoutReactionType
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * شاشة إعدادات وإدارة نظام «وَقْفَة».
 * تمكّن المستخدم في لوحة التحكم الذاتي أو المشرف عن بعد من تخصيص التطبيقات المعترضة وردود الفعل وشروط الدخول.
 */
class WaqfaSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWaqfaSettingsBinding
    private val viewModel: WaqfaSettingsViewModel by viewModel()

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
            viewModel.setWaqfaEnabled(isChecked)
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
            viewModel.setReasonRequired(isChecked)
        }

        binding.rgTimeoutReaction.setOnCheckedChangeListener { _, checkedId ->
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
            val mins = it?.toString()?.toIntOrNull() ?: 3
            viewModel.setCooldownMinutes(mins)
        }

        binding.btnSaveWaqfaSettings.setOnClickListener {
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
        val config = state.config

        if (binding.swWaqfaMaster.isChecked != config.isEnabled) {
            binding.swWaqfaMaster.isChecked = config.isEnabled
        }

        val count = config.targetPackages.size
        binding.tvTargetAppsCount.text = getString(R.string.waqfa_target_apps_desc, count)

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
    }

    companion object {
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
    }
}
