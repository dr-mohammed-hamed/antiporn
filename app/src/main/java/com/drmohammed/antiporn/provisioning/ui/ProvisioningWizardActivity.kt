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
import android.util.Log
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
import com.drmohammed.antiporn.provisioning.engine.PromotionResult
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

/**
 * Onboarding Setup Wizard for non-technical users to grant Device Owner permission
 * directly from the device with minimum friction.
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
    private lateinit var step2Check: ImageView
    private lateinit var step2Label: TextView
    private lateinit var stepperLine2: View

    private lateinit var step3Circle: FrameLayout
    private lateinit var step3Number: TextView
    private lateinit var step3Check: ImageView
    private lateinit var step3Label: TextView

    // Step 2 Views
    private lateinit var cardStep2: MaterialCardView
    private lateinit var tvStep2Desc: TextView
    private lateinit var btnOpenDevOptions: MaterialButton

    // Step 3 Views
    private lateinit var cardStep3: MaterialCardView
    private lateinit var layoutRadarPill: View
    private lateinit var ivRadarIcon: ImageView
    private lateinit var tvRadarStatus: TextView
    private lateinit var etPairingCode: TextInputEditText
    private lateinit var btnPairAndActivate: MaterialButton

    // Shizuku Views
    private lateinit var cardShizuku: MaterialCardView
    private lateinit var btnShizukuActivate: MaterialButton

    private var discoveredPairingPort = 0
    private var discoveredConnectPort = 0
    private var isPromoting = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            AdbPairingNotificationService.start(this)
        }
    }

    private val provisioningReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AdbPairingNotificationService.ACTION_PORTS_DISCOVERED -> {
                    val pairingPort = intent.getIntExtra(AdbPairingNotificationService.EXTRA_PAIRING_PORT, 0)
                    val connectPort = intent.getIntExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, 0)
                    if (pairingPort > 0) discoveredPairingPort = pairingPort
                    if (connectPort > 0) discoveredConnectPort = connectPort
                    updateRadarUi()
                }
                PairingCodeReceiver.ACTION_PROVISIONING_RESULT -> {
                    if (isFinishing || isDestroyed) return
                    val success = intent.getBooleanExtra(PairingCodeReceiver.EXTRA_SUCCESS, false)
                    if (success) {
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
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }

        bindViews()
        setupListeners()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            findViewById<View>(R.id.layoutStepper)?.visibility = View.GONE
            cardStep2.visibility = View.GONE
            cardStep3.visibility = View.GONE
            findViewById<View>(R.id.cardLegacyPureSite)?.visibility = View.VISIBLE
            findViewById<View>(R.id.btnOpenPureSite)?.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://puresite.app/install")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open PureSite URL in browser", e)
                    Toast.makeText(this, R.string.provisioning_btn_open_puresite, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun bindViews() {
        // Stepper
        step1Circle = findViewById(R.id.step1Circle)
        step1Number = findViewById(R.id.step1Number)
        step1Check = findViewById(R.id.step1Check)
        step1Label = findViewById(R.id.step1Label)
        stepperLine1 = findViewById(R.id.stepperLine1)

        step2Circle = findViewById(R.id.step2Circle)
        step2Number = findViewById(R.id.step2Number)
        step2Check = findViewById(R.id.step2Check)
        step2Label = findViewById(R.id.step2Label)
        stepperLine2 = findViewById(R.id.stepperLine2)

        step3Circle = findViewById(R.id.step3Circle)
        step3Number = findViewById(R.id.step3Number)
        step3Check = findViewById(R.id.step3Check)
        step3Label = findViewById(R.id.step3Label)

        // Step 2
        cardStep2 = findViewById(R.id.cardStep2)
        tvStep2Desc = findViewById(R.id.tvStep2Desc)
        btnOpenDevOptions = findViewById(R.id.btnOpenDevOptions)

        // Step 3
        cardStep3 = findViewById(R.id.cardStep3)
        layoutRadarPill = findViewById(R.id.layoutRadarPill)
        ivRadarIcon = findViewById(R.id.ivRadarIcon)
        tvRadarStatus = findViewById(R.id.tvRadarStatus)
        etPairingCode = findViewById(R.id.etPairingCode)
        btnPairAndActivate = findViewById(R.id.btnPairAndActivate)

        // Shizuku
        cardShizuku = findViewById(R.id.cardShizuku)
        btnShizukuActivate = findViewById(R.id.btnShizukuActivate)

        etPairingCode.doAfterTextChanged {
            validateInputs()
        }
    }

    private fun setupListeners() {
        btnOpenDevOptions.setOnClickListener {
            if (!isDevOptionsEnabled()) {
                showEnableDevOptionsDialog()
                return@setOnClickListener
            }

            // Priority 1: Direct jump to Wireless Debugging sub-screen (Android 12+ QS Tile route)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val directIntent = Intent("android.service.quicksettings.action.QS_TILE_PREFERENCES").apply {
                        putExtra(
                            Intent.EXTRA_COMPONENT_NAME,
                            ComponentName("com.android.settings", "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging")
                        )
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(directIntent)
                    return@setOnClickListener
                } catch (e: Exception) {
                    Log.w(TAG, "Direct jump to Wireless Debugging failed, falling back to Development Settings: ${e.message}")
                }
            }

            // Priority 2: Standard Developer Options (starts at top of page)
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (ex: Exception) {
                Log.w(TAG, "Failed opening development settings directly, opening general settings", ex)
                // Priority 3: General Settings fallback
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (finalEx: Exception) {
                    Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnPairAndActivate.setOnClickListener {
            if (isPromoting) return@setOnClickListener

            val code = etPairingCode.text?.toString()?.trim() ?: ""
            if (code.length != 6) {
                Toast.makeText(this, R.string.provisioning_input_label, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (discoveredPairingPort == 0 || discoveredConnectPort == 0) {
                Toast.makeText(this, R.string.provisioning_radar_waiting, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            isPromoting = true
            btnPairAndActivate.isEnabled = false
            btnPairAndActivate.setText(R.string.provisioning_in_progress_title)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val result = DeviceOwnerPromoter.pairAndPromote(
                        context = this@ProvisioningWizardActivity,
                        pairingPort = discoveredPairingPort,
                        connectPort = discoveredConnectPort,
                        pairingCode = code
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

        btnShizukuActivate.setOnClickListener {
            attemptShizukuPromotion()
        }
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
        checkShizukuStatus()
        validateInputs()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(provisioningReceiver)
        } catch (e: Exception) {
            Log.d(TAG, "Provisioning receiver already unregistered: ${e.message}")
        }
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

    private fun isDevOptionsEnabled(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    private fun checkDevOptionsStatus() {
        val devEnabled = isDevOptionsEnabled()
        if (devEnabled) {
            btnOpenDevOptions.setText(R.string.provisioning_btn_open_dev_options)
            tvStep2Desc.setText(R.string.provisioning_step2_desc)
        } else {
            btnOpenDevOptions.setText(R.string.provisioning_btn_enable_dev_options)
            tvStep2Desc.setText(R.string.provisioning_step2_desc_disabled)
        }
        val portsDiscovered = discoveredPairingPort > 0
        updateStepperUi(devEnabled, portsDiscovered)
    }

    private fun showEnableDevOptionsDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_enable_dev_title)
            .setMessage(R.string.dialog_enable_dev_desc)
            .setPositiveButton(R.string.dialog_enable_dev_btn_open) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    } catch (ex: Exception) {
                        Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.btn_back, null)
            .show()
    }

    private fun checkShizukuStatus() {
        val isShizukuInstalled = try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

        if (isShizukuInstalled) {
            cardShizuku.visibility = View.VISIBLE
        } else {
            cardShizuku.visibility = View.GONE
        }
    }

    private fun updateStepperUi(
        devOptionsEnabled: Boolean,
        portsDiscovered: Boolean
    ) {
        val colorOnSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, ContextCompat.getColor(this, R.color.md_theme_light_onSurface))
        val colorOnSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(this, R.color.md_theme_light_onSurfaceVariant))
        val colorOutlineVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, ContextCompat.getColor(this, R.color.md_theme_light_outlineVariant))
        val colorPrimary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, ContextCompat.getColor(this, R.color.md_theme_light_primary))

        // Step 1: Wireless Debugging & Radar
        if (portsDiscovered) {
            step1Circle.setBackgroundResource(R.drawable.bg_stepper_circle_done)
            step1Number.visibility = View.GONE
            step1Check.visibility = View.VISIBLE
            step1Label.setTextColor(ContextCompat.getColor(this, R.color.status_green))

            stepperLine1.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green))
            cardStep2.strokeColor = ContextCompat.getColor(this, R.color.status_green)

            // Step 2: Pairing & Activate (Active)
            step2Circle.setBackgroundResource(R.drawable.bg_stepper_circle_active)
            step2Number.visibility = View.VISIBLE
            step2Number.setTextColor(ContextCompat.getColor(this, R.color.white))
            step2Check.visibility = View.GONE
            step2Label.setTextColor(colorPrimary)
            cardStep3.strokeColor = colorPrimary
        } else {
            step1Circle.setBackgroundResource(R.drawable.bg_stepper_circle_active)
            step1Number.visibility = View.VISIBLE
            step1Number.setTextColor(ContextCompat.getColor(this, R.color.white))
            step1Check.visibility = View.GONE
            step1Label.setTextColor(colorPrimary)

            stepperLine1.setBackgroundColor(colorOutlineVariant)
            cardStep2.strokeColor = colorPrimary

            // Step 2: Pairing & Activate (Inactive)
            step2Circle.setBackgroundResource(R.drawable.bg_stepper_circle_inactive)
            step2Number.visibility = View.VISIBLE
            step2Number.setTextColor(colorOnSurfaceVariant)
            step2Check.visibility = View.GONE
            step2Label.setTextColor(colorOnSurfaceVariant)
            cardStep3.strokeColor = colorOutlineVariant
        }
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
        val devOptionsEnabled = isDevOptionsEnabled()
        val portsDiscovered = discoveredPairingPort > 0
        updateStepperUi(devOptionsEnabled, portsDiscovered)
        validateInputs()
    }

    private fun validateInputs() {
        if (isPromoting) {
            btnPairAndActivate.isEnabled = false
            return
        }
        val codeLength = etPairingCode.text?.toString()?.trim()?.length ?: 0
        val portsDiscovered = discoveredPairingPort > 0 && discoveredConnectPort > 0

        btnPairAndActivate.isEnabled = (codeLength == 6) && portsDiscovered
    }

    private fun attemptShizukuPromotion() {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, getString(R.string.provisioning_shizuku_requesting), Toast.LENGTH_SHORT).show()
        // Shizuku execution pathway
    }

    private fun onProvisioningSuccessful() {
        if (isFinishing || isDestroyed) return
        AdbPairingNotificationService.stop(this)
        Toast.makeText(this, R.string.toast_provisioning_success, Toast.LENGTH_LONG).show()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    companion object {
        private const val TAG = "AntiPorn_WizardAct"
        const val EXTRA_FOCUS_ACCOUNTS = "extra_focus_accounts"
    }
}
