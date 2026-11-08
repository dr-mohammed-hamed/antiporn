package com.drmohammed.antiporn.waqfa.ui.gate

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.ActivityWaqfaGateBinding
import com.drmohammed.antiporn.waqfa.engine.WaqfaReactionExecutor
import com.drmohammed.antiporn.waqfa.model.WaqfaIntentType
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * شاشة الاعتراض الإجبارية لنظام «وَقْفَة» (Waqfa Mindful Intervention Gate).
 * صارمة وغير قابلة للتجاوز (Strict Non-Bypassable):
 * - زر الرجوع أو الإيماءات يخرج المستخدم فوراً إلى شاشة الهاتف الرئيسية (Home Screen).
 * - لا يمكن الدخول إلى التطبيق المستهدف إلا بإتمام بوابة الوعي وتحديد النية والمدة.
 */
class WaqfaGateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWaqfaGateBinding
    private val viewModel: WaqfaGateViewModel by viewModel()

    private var packageName: String = ""
    private var appName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWaqfaGateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        appName = intent.getStringExtra(EXTRA_APP_NAME) ?: packageName

        if (packageName.isBlank()) {
            WaqfaReactionExecutor.kickToHomeScreen(this)
            finish()
            return
        }

        setupAntiBypassBackGuard()
        setupViews()
        observeUiState()

        viewModel.init(packageName, appName)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val newPkg = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val newApp = intent.getStringExtra(EXTRA_APP_NAME).takeUnless { it.isNullOrBlank() } ?: newPkg

        if (newPkg.isBlank()) {
            WaqfaReactionExecutor.kickToHomeScreen(this)
            finish()
            return
        }

        packageName = newPkg
        appName = newApp
        binding.tvTargetApp.text = getString(R.string.waqfa_attempting_open, appName)
        viewModel.init(newPkg, newApp)
    }

    private fun setupAntiBypassBackGuard() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // إرجاع فوري للشاشة الرئيسية لمنع تسرب أي رؤية أو تفاعل مع التطبيق المستهدف
                WaqfaReactionExecutor.kickToHomeScreen(this@WaqfaGateActivity)
                finish()
            }
        })
    }

    private fun setupViews() {
        binding.tvTargetApp.text = getString(R.string.waqfa_attempting_open, appName)

        binding.btnRetreatHome.setOnClickListener {
            WaqfaReactionExecutor.kickToHomeScreen(this)
            finish()
        }

        binding.btnIntentPurposeful.setOnClickListener {
            viewModel.setIntentType(WaqfaIntentType.PURPOSEFUL)
        }

        binding.btnIntentEscapism.setOnClickListener {
            viewModel.setIntentType(WaqfaIntentType.ESCAPISM)
        }

        binding.etReason.doAfterTextChanged {
            viewModel.setReasonText(it?.toString().orEmpty())
        }

        binding.btnStartSession.setOnClickListener {
            viewModel.submitSession {
                if (isFinishing || isDestroyed) return@submitSession
                // عند نجاح تفعيل الجلسة، نغلق النافذة ليدخل المستخدم للتطبيق المعتمد
                finish()
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

    private fun renderUi(state: WaqfaGateUiState) {
        // 1. تلوين أزرار النية وفقاً لمعايير Material 3 الدلالية والوضع الفاتح/الداكن
        val primaryColor = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
        val primaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer)
        val onPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer)
        val secondaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSecondaryContainer)
        val onSecondaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSecondaryContainer)
        val surfaceContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val defaultBorder = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant)
        val onSurfaceColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)

        if (state.intentType == WaqfaIntentType.PURPOSEFUL) {
            binding.btnIntentPurposeful.strokeColor = ColorStateList.valueOf(primaryColor)
            binding.btnIntentPurposeful.strokeWidth = 3
            binding.btnIntentPurposeful.backgroundTintList = ColorStateList.valueOf(primaryContainer)
            binding.btnIntentPurposeful.setTextColor(onPrimaryContainer)

            binding.btnIntentEscapism.strokeColor = ColorStateList.valueOf(defaultBorder)
            binding.btnIntentEscapism.strokeWidth = 1
            binding.btnIntentEscapism.backgroundTintList = ColorStateList.valueOf(surfaceContainer)
            binding.btnIntentEscapism.setTextColor(onSurfaceVariant)
            binding.tvReflectionPrompt.visibility = View.GONE
        } else {
            binding.btnIntentEscapism.strokeColor = ColorStateList.valueOf(primaryColor)
            binding.btnIntentEscapism.strokeWidth = 3
            binding.btnIntentEscapism.backgroundTintList = ColorStateList.valueOf(secondaryContainer)
            binding.btnIntentEscapism.setTextColor(onSecondaryContainer)

            binding.btnIntentPurposeful.strokeColor = ColorStateList.valueOf(defaultBorder)
            binding.btnIntentPurposeful.strokeWidth = 1
            binding.btnIntentPurposeful.backgroundTintList = ColorStateList.valueOf(surfaceContainer)
            binding.btnIntentPurposeful.setTextColor(onSurfaceVariant)
            binding.tvReflectionPrompt.visibility = View.VISIBLE
        }

        // 2. تحديث خطأ حقل السبب
        binding.tilReason.error = state.reasonError

        // 3. بناء شرائح المدد السريعة إذا لم تكن مبنية
        if (binding.cgDurations.childCount == 0 && state.availableDurations.isNotEmpty()) {
            for (duration in state.availableDurations) {
                val chip = Chip(this).apply {
                    id = View.generateViewId()
                    text = getString(R.string.waqfa_minutes_format, duration)
                    isCheckable = true
                    isChecked = (duration == state.selectedDurationMinutes)
                    setTextColor(
                        ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(onPrimaryContainer, onSurfaceColor)
                        )
                    )
                    chipBackgroundColor = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(primaryContainer, surfaceContainer)
                    )
                    chipStrokeColor = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(primaryColor, defaultBorder)
                    )
                    chipStrokeWidth = 2f
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            viewModel.setDurationMinutes(duration)
                        }
                    }
                }
                binding.cgDurations.addView(chip)
            }
        }

        // 4. حالة زر المتابعة
        binding.btnStartSession.isEnabled = !state.isSubmitting
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"
    }
}
