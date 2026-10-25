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
import com.drmohammed.antiporn.provisioning.engine.AccountAuditor
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.PromotionResult
import com.drmohammed.antiporn.provisioning.receiver.PairingCodeReceiver
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Onboarding Setup Wizard for non-technical users to grant Device Owner permission
 * directly from the device with minimum friction.
 */
class ProvisioningWizardActivity : AppCompatActivity() {

    private lateinit var cardStep1: MaterialCardView
    private lateinit var tvStep1Status: TextView
    private lateinit var tvAccountsList: TextView
    private lateinit var btnOpenAccounts: MaterialButton

    private lateinit var btnOpenDevOptions: MaterialButton

    private lateinit var tvRadarStatus: TextView
    private lateinit var etPairingCode: TextInputEditText
    private lateinit var btnPairAndActivate: MaterialButton

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
                        val accounts = AccountAuditor.getActiveAccounts(this@ProvisioningWizardActivity)
                        val isAccountsIssue = accounts.isNotEmpty() && (
                            error?.contains("accounts", ignoreCase = true) == true ||
                            error?.contains("users", ignoreCase = true) == true ||
                            !AccountAuditor.isReadyForProvisioning(this@ProvisioningWizardActivity)
                        )

                        if (isAccountsIssue) {
                            Toast.makeText(
                                this@ProvisioningWizardActivity,
                                getString(R.string.provisioning_failed_accounts_desc, accounts.size),
                                Toast.LENGTH_LONG
                            ).show()
                            checkAccountsStatus()
                            this@ProvisioningWizardActivity.focusOnAccountsCard()
                        } else {
                            Toast.makeText(this@ProvisioningWizardActivity, getString(R.string.toast_provisioning_failed, error ?: ""), Toast.LENGTH_LONG).show()
                        }
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

        if (intent?.getBooleanExtra(EXTRA_FOCUS_ACCOUNTS, false) == true) {
            focusOnAccountsCard()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FOCUS_ACCOUNTS, false)) {
            focusOnAccountsCard()
        }
    }

    private fun focusOnAccountsCard() {
        cardStep1.post {
            val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollWizard)
            scrollView?.smoothScrollTo(0, cardStep1.top)
        }
    }

    private fun bindViews() {
        cardStep1 = findViewById(R.id.cardStep1)
        tvStep1Status = findViewById(R.id.tvStep1Status)
        tvAccountsList = findViewById(R.id.tvAccountsList)
        btnOpenAccounts = findViewById(R.id.btnOpenAccounts)

        btnOpenDevOptions = findViewById(R.id.btnOpenDevOptions)

        tvRadarStatus = findViewById(R.id.tvRadarStatus)
        etPairingCode = findViewById(R.id.etPairingCode)
        btnPairAndActivate = findViewById(R.id.btnPairAndActivate)

        cardShizuku = findViewById(R.id.cardShizuku)
        btnShizukuActivate = findViewById(R.id.btnShizukuActivate)

        etPairingCode.doAfterTextChanged {
            validateInputs()
        }
    }

    private fun setupListeners() {
        btnOpenAccounts.setOnClickListener {
            AccountAuditor.openAccountSettings(this)
        }

        btnOpenDevOptions.setOnClickListener {
            // Priority 1: Direct jump to Wireless Debugging sub-screen (Android 12+ QS Tile route)
            try {
                val directIntent = Intent("android.service.quicksettings.action.QS_TILE_PREFERENCES").apply {
                    putExtra(
                        Intent.EXTRA_COMPONENT_NAME,
                        ComponentName("com.android.settings", "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging")
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(directIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Direct jump to Wireless Debugging failed, falling back to Development Settings: ${e.message}")
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
        }

        btnPairAndActivate.setOnClickListener {
            if (isPromoting) return@setOnClickListener

            if (!AccountAuditor.isReadyForProvisioning(this)) {
                val count = AccountAuditor.getAccountCount(this)
                Toast.makeText(this, getString(R.string.provisioning_failed_accounts_desc, count), Toast.LENGTH_LONG).show()
                focusOnAccountsCard()
                AccountAuditor.openAccountSettings(this)
                return@setOnClickListener
            }

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
                            val accounts = AccountAuditor.getActiveAccounts(this@ProvisioningWizardActivity)
                            val isAccountsIssue = accounts.isNotEmpty() && (
                                result.message.contains("accounts", ignoreCase = true) ||
                                result.message.contains("users", ignoreCase = true) ||
                                !AccountAuditor.isReadyForProvisioning(this@ProvisioningWizardActivity)
                            )
                            if (isAccountsIssue) {
                                Toast.makeText(
                                    this@ProvisioningWizardActivity,
                                    getString(R.string.provisioning_failed_accounts_desc, accounts.size),
                                    Toast.LENGTH_LONG
                                ).show()
                                checkAccountsStatus()
                                focusOnAccountsCard()
                            } else {
                                Toast.makeText(this@ProvisioningWizardActivity, getString(R.string.toast_provisioning_failed, result.message), Toast.LENGTH_LONG).show()
                            }
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

        checkNotificationPermission()
        AdbPairingNotificationService.start(this)
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

        checkAccountsStatus()
        checkShizukuStatus()
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
        if (!ProtectionEngine.isDeviceOwner(this)) {
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

    private fun checkAccountsStatus() {
        val accounts = AccountAuditor.getActiveAccounts(this)
        if (accounts.isEmpty()) {
            tvStep1Status.setText(R.string.provisioning_step1_ready)
            tvStep1Status.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            tvAccountsList.visibility = View.GONE
            cardStep1.strokeColor = ContextCompat.getColor(this, R.color.status_green)
        } else {
            tvStep1Status.text = getString(R.string.provisioning_step1_accounts_found, accounts.size)
            tvStep1Status.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
            tvAccountsList.text = AccountAuditor.getAccountSummaryList(this).joinToString("\n• ", prefix = "• ")
            tvAccountsList.visibility = View.VISIBLE
            cardStep1.strokeColor = ContextCompat.getColor(this, R.color.status_orange)
        }
        validateInputs()
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

    private fun updateRadarUi() {
        if (discoveredPairingPort > 0) {
            tvRadarStatus.text = getString(R.string.provisioning_radar_port_found, discoveredPairingPort)
            tvRadarStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            tvRadarStatus.setText(R.string.provisioning_radar_waiting)
            tvRadarStatus.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
        }
        validateInputs()
    }

    private fun validateInputs() {
        if (isPromoting) {
            btnPairAndActivate.isEnabled = false
            return
        }
        val accountsReady = AccountAuditor.isReadyForProvisioning(this)
        val codeLength = etPairingCode.text?.toString()?.trim()?.length ?: 0
        val portsDiscovered = discoveredPairingPort > 0 && discoveredConnectPort > 0

        btnPairAndActivate.isEnabled = accountsReady && (codeLength == 6) && portsDiscovered
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
