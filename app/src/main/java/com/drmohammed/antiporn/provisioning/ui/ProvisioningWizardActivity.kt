package com.drmohammed.antiporn.provisioning.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.OemProvisioningHelper
import com.drmohammed.antiporn.provisioning.engine.PromotionResult
import com.drmohammed.antiporn.provisioning.engine.ProvisioningWifiWatcher
import com.drmohammed.antiporn.provisioning.receiver.PairingCodeReceiver
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Onboarding Setup Wizard for non-technical users to grant Device Owner permission
 * directly from the device with minimum friction.
 * Adheres strictly to Material 3, Idiomatic Kotlin, and < 400 lines per file.
 */
class ProvisioningWizardActivity : AppCompatActivity() {

    // Stepper Views
    private lateinit var step1Circle: FrameLayout
    private lateinit var step1Number: TextView
    private lateinit var step1Check: ImageView
    private lateinit var step1Label: TextView
    private lateinit var stepperLine1: View

    private lateinit var step2Circle: FrameLayout
    private lateinit var step2Number: TextView
    private lateinit var step2Label: TextView

    // Gate & OEM Cards
    private lateinit var cardWifiWarning: MaterialCardView
    private lateinit var btnConnectWifi: MaterialButton
    private lateinit var cardOemWarning: MaterialCardView
    private lateinit var tvOemWarningTitle: TextView
    private lateinit var tvOemWarningDesc: TextView

    // Stepper Cards & Inputs
    private lateinit var cardStep2: MaterialCardView
    private lateinit var tvStep2Desc: TextView
    private lateinit var btnOpenDevOptions: MaterialButton
    private lateinit var cardStep3: MaterialCardView
    private lateinit var tvRadarStatus: TextView
    private lateinit var ivRadarIcon: ImageView
    private lateinit var etPairingCode: TextInputEditText
    private lateinit var btnPairAndActivate: MaterialButton

    private var discoveredPairingPort = 0
    private var discoveredConnectPort = 0
    private var isPromoting = false
    private var isWifiValid = false

    private val wifiWatcher by lazy {
        ProvisioningWifiWatcher(this) { isConnected -> onWifiStateChanged(isConnected) }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) AdbPairingNotificationService.start(this)
    }

    private val provisioningReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AdbPairingNotificationService.ACTION_PORTS_DISCOVERED -> {
                    discoveredPairingPort = intent.getIntExtra(AdbPairingNotificationService.EXTRA_PAIRING_PORT, 0).takeIf { it > 0 } ?: discoveredPairingPort
                    discoveredConnectPort = intent.getIntExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, 0).takeIf { it > 0 } ?: discoveredConnectPort
                    updateRadarUi()
                }
                PairingCodeReceiver.ACTION_PROVISIONING_RESULT -> {
                    if (isFinishing || isDestroyed) return
                    if (intent.getBooleanExtra(PairingCodeReceiver.EXTRA_SUCCESS, false)) {
                        onProvisioningSuccessful()
                    } else {
                        val error = intent.getStringExtra(PairingCodeReceiver.EXTRA_ERROR_MESSAGE)
                        Toast.makeText(this@ProvisioningWizardActivity, getString(R.string.toast_provisioning_failed, error ?: ""), Toast.LENGTH_LONG).show()
                        isPromoting = false
                        btnPairAndActivate.setText(R.string.provisioning_btn_pair_and_activate)
                        validateInputs()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provisioning_wizard)

        findViewById<View>(R.id.scrollWizard)?.let { scrollView ->
            ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }

        bindViews()
        setupListeners()
        setupOemGuidance()
        setupLegacyPureSite()
    }

    private fun bindViews() {
        step1Circle = findViewById(R.id.step1Circle)
        step1Number = findViewById(R.id.step1Number)
        step1Check = findViewById(R.id.step1Check)
        step1Label = findViewById(R.id.step1Label)
        stepperLine1 = findViewById(R.id.stepperLine1)

        step2Circle = findViewById(R.id.step2Circle)
        step2Number = findViewById(R.id.step2Number)
        step2Label = findViewById(R.id.step2Label)

        cardWifiWarning = findViewById(R.id.cardWifiWarning)
        btnConnectWifi = findViewById(R.id.btnConnectWifi)
        cardOemWarning = findViewById(R.id.cardOemWarning)
        tvOemWarningTitle = findViewById(R.id.tvOemWarningTitle)
        tvOemWarningDesc = findViewById(R.id.tvOemWarningDesc)

        cardStep2 = findViewById(R.id.cardStep2)
        tvStep2Desc = findViewById(R.id.tvStep2Desc)
        btnOpenDevOptions = findViewById(R.id.btnOpenDevOptions)
        cardStep3 = findViewById(R.id.cardStep3)
        ivRadarIcon = findViewById(R.id.ivRadarIcon)
        tvRadarStatus = findViewById(R.id.tvRadarStatus)
        etPairingCode = findViewById(R.id.etPairingCode)
        btnPairAndActivate = findViewById(R.id.btnPairAndActivate)

        etPairingCode.doAfterTextChanged { validateInputs() }
    }

    private fun setupListeners() {
        btnConnectWifi.setOnClickListener {
            try {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnOpenDevOptions.setOnClickListener {
            if (!isWifiValid) {
                Toast.makeText(this, R.string.provisioning_wifi_required_title, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isDevOptionsEnabled()) {
                showEnableDevOptionsDialog()
                return@setOnClickListener
            }
            launchWirelessDebuggingScreen()
        }

        btnPairAndActivate.setOnClickListener {
            if (isPromoting) return@setOnClickListener
            val code = PairingCodeReceiver.sanitizePairingCode(etPairingCode.text?.toString())
            if (code.length != 6 || discoveredPairingPort == 0 || discoveredConnectPort == 0) {
                Toast.makeText(this, R.string.provisioning_input_label, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            isPromoting = true
            btnPairAndActivate.isEnabled = false
            btnPairAndActivate.setText(R.string.provisioning_in_progress_title)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val result = DeviceOwnerPromoter.pairAndPromote(
                        this@ProvisioningWizardActivity, discoveredPairingPort, discoveredConnectPort, code
                    )
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        if (result is PromotionResult.Success) {
                            onProvisioningSuccessful()
                        } else if (result is PromotionResult.Failure) {
                            Toast.makeText(this@ProvisioningWizardActivity, getString(R.string.toast_provisioning_failed, result.message), Toast.LENGTH_LONG).show()
                            btnPairAndActivate.setText(R.string.provisioning_btn_pair_and_activate)
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            isPromoting = false
                            validateInputs()
                        }
                    }
                }
            }
        }
    }

    private fun launchWirelessDebuggingScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val directIntent = Intent("android.service.quicksettings.action.QS_TILE_PREFERENCES").apply {
                    putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName("com.android.settings", "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging"))
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(directIntent)
                return
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Direct jump to Wireless Debugging failed: ${e.message}")
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            } catch (_: Exception) {
                Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupOemGuidance() {
        val warning = OemProvisioningHelper.getOemWarning(this)
        if (warning != null) {
            cardOemWarning.visibility = View.VISIBLE
            tvOemWarningTitle.setText(warning.titleRes)
            tvOemWarningDesc.setText(warning.descRes)
        } else {
            cardOemWarning.visibility = View.GONE
        }
    }

    private fun setupLegacyPureSite() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            findViewById<View>(R.id.layoutStepper)?.visibility = View.GONE
            cardStep2.visibility = View.GONE
            cardStep3.visibility = View.GONE
            findViewById<View>(R.id.cardLegacyPureSite)?.visibility = View.VISIBLE
            findViewById<View>(R.id.btnOpenPureSite)?.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://puresite.app/install")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed opening PureSite")
                }
            }
        }
    }

    private fun onWifiStateChanged(isValid: Boolean) {
        isWifiValid = isValid
        cardWifiWarning.visibility = if (isValid) View.GONE else View.VISIBLE
        btnOpenDevOptions.isEnabled = isValid
        validateInputs()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(AdbPairingNotificationService.ACTION_PORTS_DISCOVERED)
            addAction(PairingCodeReceiver.ACTION_PROVISIONING_RESULT)
        }
        ContextCompat.registerReceiver(this, provisioningReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            checkNotificationPermission()
            AdbPairingNotificationService.start(this)
        }
        wifiWatcher.startWatching()
    }

    override fun onResume() {
        super.onResume()
        if (ProtectionEngine.isDeviceOwner(this)) {
            onProvisioningSuccessful()
            return
        }
        if (AdbPairingNotificationService.capturedPairingPort > 0) {
            discoveredPairingPort = AdbPairingNotificationService.capturedPairingPort
            discoveredConnectPort = AdbPairingNotificationService.capturedConnectPort
            updateRadarUi()
        }
        checkDevOptionsStatus()
        validateInputs()
    }

    override fun onStop() {
        super.onStop()
        wifiWatcher.stopWatching()
        try { unregisterReceiver(provisioningReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && !ProtectionEngine.isDeviceOwner(this)) {
            AdbPairingNotificationService.stop(this)
        }
        super.onDestroy()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun isDevOptionsEnabled(): Boolean = try {
        Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    } catch (_: Exception) { false }

    private fun checkDevOptionsStatus() {
        val devEnabled = isDevOptionsEnabled()
        btnOpenDevOptions.setText(if (devEnabled) R.string.provisioning_btn_open_dev_options else R.string.provisioning_btn_enable_dev_options)
        tvStep2Desc.setText(if (devEnabled) R.string.provisioning_step2_desc else R.string.provisioning_step2_desc_disabled)
        updateStepperUi(devEnabled, discoveredPairingPort > 0)
    }

    private fun showEnableDevOptionsDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_enable_dev_title)
            .setMessage(OemProvisioningHelper.getDevOptionsDialogDescription())
            .setPositiveButton(R.string.dialog_enable_dev_btn_open) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) }
                    catch (_: Exception) { Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton(R.string.btn_back, null)
            .show()
    }

    private fun updateStepperUi(devOptionsEnabled: Boolean, portsDiscovered: Boolean) {
        val colorPrimary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, ContextCompat.getColor(this, R.color.md_theme_light_primary))
        val colorOnPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary, ContextCompat.getColor(this, R.color.white))
        val colorOnSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(this, R.color.md_theme_light_onSurfaceVariant))
        val colorOutlineVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, ContextCompat.getColor(this, R.color.md_theme_light_outlineVariant))
        val colorGreen = ContextCompat.getColor(this, R.color.status_green)

        step1Circle.setBackgroundResource(if (portsDiscovered) R.drawable.bg_stepper_circle_done else R.drawable.bg_stepper_circle_active)
        step1Number.visibility = if (portsDiscovered) View.GONE else View.VISIBLE
        step1Check.visibility = if (portsDiscovered) View.VISIBLE else View.GONE
        step1Label.setTextColor(if (portsDiscovered) colorGreen else colorPrimary)
        stepperLine1.setBackgroundColor(if (portsDiscovered) colorGreen else colorOutlineVariant)
        cardStep2.strokeColor = if (portsDiscovered) colorGreen else colorPrimary

        step2Circle.setBackgroundResource(if (portsDiscovered) R.drawable.bg_stepper_circle_active else R.drawable.bg_stepper_circle_inactive)
        step2Number.setTextColor(if (portsDiscovered) colorOnPrimary else colorOnSurfaceVariant)
        step2Label.setTextColor(if (portsDiscovered) colorPrimary else colorOnSurfaceVariant)
        cardStep3.strokeColor = if (portsDiscovered) colorPrimary else colorOutlineVariant
    }

    private fun updateRadarUi() {
        if (discoveredPairingPort > 0) {
            tvRadarStatus.text = getString(R.string.provisioning_radar_port_found, discoveredPairingPort)
            tvRadarStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            ivRadarIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_green))
        } else {
            tvRadarStatus.setText(R.string.provisioning_radar_waiting)
            tvRadarStatus.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
            ivRadarIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_orange))
        }
        updateStepperUi(isDevOptionsEnabled(), discoveredPairingPort > 0)
        validateInputs()
    }

    private fun validateInputs() {
        if (isPromoting) {
            btnPairAndActivate.isEnabled = false
            return
        }
        val sanitizedCode = PairingCodeReceiver.sanitizePairingCode(etPairingCode.text?.toString())
        val codeLength = sanitizedCode.length
        btnPairAndActivate.isEnabled = (codeLength == 6) && (discoveredPairingPort > 0 && discoveredConnectPort > 0) && isWifiValid
    }

    private fun onProvisioningSuccessful() {
        if (isFinishing || isDestroyed) return
        AdbPairingNotificationService.stop(this)
        Toast.makeText(this, R.string.toast_provisioning_success, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    companion object {
        private const val TAG = "AntiPorn_WizardAct"
    }
}
