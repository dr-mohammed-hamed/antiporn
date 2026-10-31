package com.drmohammed.antiporn.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.ai.service.ScreenAiProjectionService
import com.drmohammed.antiporn.cloud.auth.GoogleAuthManager
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Settings Fragment implementing modern Material 3 List Preference ergonomics:
 * 1. Screen AI content inspection toggle & live metrics.
 * 2. OTA App update checker & download progress.
 * 3. Google cloud account linking & sync state.
 * 4. Device Owner system status.
 */
class SettingsFragment : Fragment() {

    interface SettingsActionListener {
        fun onToggleAiRequested(enable: Boolean)
        fun onCheckOtaRequested()
        fun onLinkGoogleRequested()
    }

    private var actionListener: SettingsActionListener? = null

    // AI Views
    private var switchAiShield: MaterialSwitch? = null
    private var tvAiStatusSubtext: TextView? = null
    private var tvAiMetrics: TextView? = null

    // OTA Views
    private var tvOtaVersion: TextView? = null
    private var tvOtaStatus: TextView? = null
    private var pbOtaProgress: LinearProgressIndicator? = null
    private var btnCheckUpdate: MaterialButton? = null

    // Google Views
    private var tvGoogleAccountStatus: TextView? = null
    private var btnLinkGoogle: MaterialButton? = null

    // Device Owner Views
    private var tvDeviceOwnerStatus: TextView? = null

    private var lastOtaState: OtaUpdateState? = null
    private var currentVersionName: String = "1.1"
    private var currentVersionCode: Long = 2

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
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        initViews(view)
        setupListeners()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateAllStates()
    }

    override fun onResume() {
        super.onResume()
        updateAllStates()
    }

    private fun initViews(root: View) {
        switchAiShield = root.findViewById(R.id.switchAiShield)
        tvAiStatusSubtext = root.findViewById(R.id.tvAiStatusSubtext)
        tvAiMetrics = root.findViewById(R.id.tvAiMetrics)

        tvOtaVersion = root.findViewById(R.id.tvOtaVersion)
        tvOtaStatus = root.findViewById(R.id.tvOtaStatus)
        pbOtaProgress = root.findViewById(R.id.pbOtaProgress)
        btnCheckUpdate = root.findViewById(R.id.btnCheckUpdate)

        tvGoogleAccountStatus = root.findViewById(R.id.tvGoogleAccountStatus)
        btnLinkGoogle = root.findViewById(R.id.btnLinkGoogle)

        tvDeviceOwnerStatus = root.findViewById(R.id.tvDeviceOwnerStatus)
    }

    private fun setupListeners() {
        switchAiShield?.setOnCheckedChangeListener { _, isChecked ->
            // Prevent recursive trigger when updating switch programmatically
            if (switchAiShield?.isPressed == true) {
                actionListener?.onToggleAiRequested(isChecked)
            }
        }

        btnCheckUpdate?.setOnClickListener {
            actionListener?.onCheckOtaRequested()
        }

        btnLinkGoogle?.setOnClickListener {
            actionListener?.onLinkGoogleRequested()
        }
    }

    fun updateAllStates() {
        updateAiUi()
        updateGoogleUi()
        updateDeviceOwnerUi()
        lastOtaState?.let { bindOtaState(it, currentVersionName, currentVersionCode) }
    }

    fun updateAiUi() {
        if (!isAdded || view == null) return
        val ctx = context ?: return

        val isRunning = ScreenAiProjectionService.isRunning
        val initError = ScreenAiProjectionService.initError
        val hasInitError = initError.isNotEmpty()

        switchAiShield?.isChecked = isRunning

        if (isRunning) {
            if (hasInitError) {
                tvAiStatusSubtext?.text = initError
                tvAiStatusSubtext?.setTextColor(ContextCompat.getColor(ctx, R.color.status_orange))
            } else {
                tvAiStatusSubtext?.setText(R.string.card_ai_shield_active_desc)
                tvAiStatusSubtext?.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            }

            val fps = ScreenAiProjectionService.currentFps
            val snapshot = ScreenAiProjectionService.lastDetection
            val lastLabel = snapshot.label
            val lastConf = snapshot.confidence

            if (lastLabel.isNotEmpty()) {
                val confPercent = (lastConf * 100).toInt()
                val fpsText = getString(R.string.ai_fps_format, fps)
                val detText = getString(R.string.ai_last_detection_format, lastLabel, confPercent)
                val modelText = getString(R.string.ai_model_active_format, ScreenAiProjectionService.modelName)
                tvAiMetrics?.text = getString(R.string.ai_metrics_full_format, fpsText, detText, modelText)
                tvAiMetrics?.setTextColor(ContextCompat.getColor(ctx, R.color.status_orange))
            } else {
                val fpsText = getString(R.string.ai_fps_format, fps)
                val modelText = getString(R.string.ai_model_active_format, ScreenAiProjectionService.modelName)
                tvAiMetrics?.text = getString(R.string.ai_metrics_simple_format, fpsText, modelText)
                tvAiMetrics?.setTextColor(com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(ctx, R.color.shield_hero_desc)))
            }
        } else {
            tvAiStatusSubtext?.setText(R.string.settings_ai_desc)
            tvAiStatusSubtext?.setTextColor(com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(ctx, R.color.shield_hero_desc)))
            tvAiMetrics?.setText(R.string.ai_no_detections_yet)
            tvAiMetrics?.setTextColor(com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(ctx, R.color.shield_hero_desc)))
        }
    }

    fun updateGoogleUi() {
        if (!isAdded || view == null) return
        val ctx = context ?: return

        val googleAuthManager = GoogleAuthManager.getInstance(ctx)
        val isLinked = googleAuthManager.isLinkedWithGoogle()
        val email = googleAuthManager.getLinkedEmail()

        if (isLinked && !email.isNullOrBlank()) {
            tvGoogleAccountStatus?.text = getString(R.string.settings_google_linked, email)
            tvGoogleAccountStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            btnLinkGoogle?.visibility = View.GONE
        } else {
            tvGoogleAccountStatus?.setText(R.string.settings_google_unlinked)
            tvGoogleAccountStatus?.setTextColor(com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(ctx, R.color.shield_hero_desc)))
            btnLinkGoogle?.visibility = View.VISIBLE
            btnLinkGoogle?.setText(R.string.btn_secure_with_google)
        }
    }

    fun updateDeviceOwnerUi() {
        if (!isAdded || view == null) return
        val ctx = context ?: return

        val isOwner = ProtectionEngine.isDeviceOwner(ctx)
        if (isOwner) {
            tvDeviceOwnerStatus?.setText(R.string.card_device_owner_active)
            tvDeviceOwnerStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.shield_emerald_radiant))
        } else {
            tvDeviceOwnerStatus?.setText(R.string.card_device_owner_inactive)
            tvDeviceOwnerStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
        }
    }

    fun bindOtaState(state: OtaUpdateState, versionName: String, versionCode: Long) {
        lastOtaState = state
        currentVersionName = versionName
        currentVersionCode = versionCode

        if (!isAdded || view == null) return
        val ctx = context ?: return

        tvOtaVersion?.text = getString(R.string.card_ota_current_version, versionName, versionCode)

        when (state) {
            is OtaUpdateState.Idle -> {
                tvOtaStatus?.visibility = View.GONE
                pbOtaProgress?.visibility = View.GONE
                btnCheckUpdate?.isEnabled = true
                btnCheckUpdate?.setText(R.string.btn_check_update)
            }
            is OtaUpdateState.Checking -> {
                tvOtaStatus?.text = getString(R.string.btn_checking_update)
                tvOtaStatus?.visibility = View.VISIBLE
                pbOtaProgress?.visibility = View.VISIBLE
                pbOtaProgress?.isIndeterminate = true
                btnCheckUpdate?.isEnabled = false
                btnCheckUpdate?.setText(R.string.btn_checking_update)
            }
            is OtaUpdateState.NoUpdateAvailable -> {
                tvOtaStatus?.text = getString(R.string.ota_up_to_date)
                tvOtaStatus?.visibility = View.VISIBLE
                pbOtaProgress?.visibility = View.GONE
                btnCheckUpdate?.isEnabled = true
                btnCheckUpdate?.setText(R.string.btn_check_update)
            }
            is OtaUpdateState.UpdateAvailable -> {
                tvOtaStatus?.text = getString(R.string.ota_update_available, state.info.versionName)
                tvOtaStatus?.visibility = View.VISIBLE
                pbOtaProgress?.visibility = View.GONE
                btnCheckUpdate?.isEnabled = true
                btnCheckUpdate?.setText(R.string.btn_download_update)
            }
            is OtaUpdateState.Downloading -> {
                val percent = state.progressPercent
                val statusText = if (percent >= 0) {
                    getString(R.string.ota_downloading, percent)
                } else {
                    getString(R.string.btn_checking_update)
                }
                tvOtaStatus?.text = statusText
                tvOtaStatus?.visibility = View.VISIBLE
                pbOtaProgress?.visibility = View.VISIBLE
                if (percent >= 0) {
                    pbOtaProgress?.isIndeterminate = false
                    pbOtaProgress?.progress = percent
                } else {
                    pbOtaProgress?.isIndeterminate = true
                }
                btnCheckUpdate?.isEnabled = false
                btnCheckUpdate?.text = statusText
            }
            is OtaUpdateState.Verifying -> {
                tvOtaStatus?.text = getString(R.string.ota_verifying)
                tvOtaStatus?.visibility = View.VISIBLE
                pbOtaProgress?.visibility = View.VISIBLE
                pbOtaProgress?.isIndeterminate = true
                btnCheckUpdate?.isEnabled = false
                btnCheckUpdate?.setText(R.string.btn_verifying_update)
            }
            is OtaUpdateState.ReadyToInstall -> {
                tvOtaStatus?.text = getString(R.string.ota_installing)
                tvOtaStatus?.visibility = View.VISIBLE
                pbOtaProgress?.visibility = View.GONE
                btnCheckUpdate?.isEnabled = true
                btnCheckUpdate?.setText(R.string.btn_download_update)
            }
            is OtaUpdateState.Installing -> {
                tvOtaStatus?.text = getString(R.string.ota_installing)
                tvOtaStatus?.visibility = View.VISIBLE
                pbOtaProgress?.visibility = View.VISIBLE
                pbOtaProgress?.isIndeterminate = true
                btnCheckUpdate?.isEnabled = false
                btnCheckUpdate?.setText(R.string.ota_installing)
            }
            is OtaUpdateState.Error -> {
                tvOtaStatus?.text = state.message
                tvOtaStatus?.visibility = View.VISIBLE
                pbOtaProgress?.visibility = View.GONE
                btnCheckUpdate?.isEnabled = true
                btnCheckUpdate?.setText(R.string.btn_check_update)
            }
        }
    }

    override fun onDestroyView() {
        switchAiShield = null
        tvAiStatusSubtext = null
        tvAiMetrics = null
        tvOtaVersion = null
        tvOtaStatus = null
        pbOtaProgress = null
        btnCheckUpdate = null
        tvGoogleAccountStatus = null
        btnLinkGoogle = null
        tvDeviceOwnerStatus = null
        super.onDestroyView()
    }
}
