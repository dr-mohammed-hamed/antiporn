package com.drmohammed.antiporn.ui.accountability

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.cloud.auth.GoogleAuthManager
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Main Accountability Hub activity allowing users to view their unique pairing code,
 * view protected wards, view protecting guardians, secure their cloud account with Google,
 * and manage 24-hour emergency unpair grace periods.
 */
class AccountabilityHubActivity : AppCompatActivity() {

    private lateinit var accountabilityRepo: AccountabilityRepository
    private lateinit var googleAuthManager: GoogleAuthManager

    // Account Security Views
    private lateinit var cardAccountSecurity: MaterialCardView
    private lateinit var tvAccountSecurityStatus: TextView
    private lateinit var btnSecureAccount: MaterialButton

    // Supervision & Emergency Breakout Views
    private lateinit var cardSupervisedBanner: MaterialCardView
    private lateinit var cardEmergencyBreakout: MaterialCardView
    private lateinit var tvBreakoutCountdown: TextView
    private lateinit var btnCancelBreakout: MaterialButton
    private lateinit var btnCompleteBreakout: MaterialButton
    private lateinit var btnInitiateBreakout: MaterialButton

    // Profile & Pairing Code Views
    private lateinit var cardMyPairingCode: MaterialCardView
    private lateinit var tvMyDisplayName: TextView
    private lateinit var btnEditMyName: MaterialButton
    private lateinit var btnHubOpenSelfControl: MaterialButton
    private lateinit var tvMyPairingCode: TextView
    private lateinit var btnCopyCode: MaterialButton
    private lateinit var btnAddPartner: MaterialButton
    private lateinit var layoutWardsList: LinearLayout
    private lateinit var tvEmptyWards: TextView
    private lateinit var layoutGuardiansList: LinearLayout
    private lateinit var tvEmptyGuardians: TextView

    private var breakoutCountdownJob: Job? = null
    private var lastAddPartnerClickTime = 0L
    private var lastManageClickTime = 0L
    private var lastEditNameClickTime = 0L
    private var lastSelfControlClickTime = 0L
    private var lastBreakoutClickTime = 0L
    private var lastSecureAccountClickTime = 0L
    private var lastCancelBreakoutClickTime = 0L
    private var lastCompleteBreakoutClickTime = 0L
    private var isPairingInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accountability_hub)

        findViewById<View>(R.id.scrollAccountabilityHub)?.let { scrollView ->
            ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(
                    insets.left,
                    insets.top,
                    insets.right,
                    insets.bottom
                )
                windowInsets
            }
        }

        accountabilityRepo = AccountabilityRepository.getInstance(applicationContext)
        googleAuthManager = GoogleAuthManager.getInstance(applicationContext)

        initViews()
        setupListeners()
        updateAccountSecurityState()
        loadPairingCode()
        observePolicyChanges()
    }

    private fun observePolicyChanges() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PolicyRepository.getInstance(applicationContext)
                    .currentPolicyFlow.collect { policy ->
                        if (isFinishing || isDestroyed) return@collect
                        if (policy.isSupervised) {
                            cardSupervisedBanner.visibility = View.VISIBLE
                            cardMyPairingCode.visibility = View.GONE
                            renderBreakoutState(policy)
                        } else {
                            cardSupervisedBanner.visibility = View.GONE
                            cardEmergencyBreakout.visibility = View.GONE
                            btnInitiateBreakout.visibility = View.GONE
                            breakoutCountdownJob?.cancel()
                            cardMyPairingCode.visibility = View.VISIBLE
                            loadPairingCode()
                        }
                        updateAccountSecurityState()
                        loadPartners()
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        val policy = PolicyRepository.getInstance(applicationContext).currentPolicyFlow.value
        if (policy.isSupervised && breakoutCountdownJob == null) {
            renderBreakoutState(policy)
        }
        updateAccountSecurityState()
        loadPartners()
    }

    override fun onStop() {
        super.onStop()
        breakoutCountdownJob?.cancel()
        breakoutCountdownJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        breakoutCountdownJob?.cancel()
        breakoutCountdownJob = null
    }

    private fun initViews() {
        cardAccountSecurity = findViewById(R.id.cardAccountSecurity)
        tvAccountSecurityStatus = findViewById(R.id.tvAccountSecurityStatus)
        btnSecureAccount = findViewById(R.id.btnSecureAccount)

        cardSupervisedBanner = findViewById(R.id.cardSupervisedBanner)
        cardEmergencyBreakout = findViewById(R.id.cardEmergencyBreakout)
        tvBreakoutCountdown = findViewById(R.id.tvBreakoutCountdown)
        btnCancelBreakout = findViewById(R.id.btnCancelBreakout)
        btnCompleteBreakout = findViewById(R.id.btnCompleteBreakout)
        btnInitiateBreakout = findViewById(R.id.btnInitiateBreakout)

        cardMyPairingCode = findViewById(R.id.cardMyPairingCode)
        tvMyDisplayName = findViewById(R.id.tvMyDisplayName)
        btnEditMyName = findViewById(R.id.btnEditMyName)
        btnHubOpenSelfControl = findViewById(R.id.btnHubOpenSelfControl)
        tvMyDisplayName.text = accountabilityRepo.getMyDisplayName()
        tvMyPairingCode = findViewById(R.id.tvMyPairingCode)
        btnCopyCode = findViewById(R.id.btnCopyCode)
        btnAddPartner = findViewById(R.id.btnAddPartner)
        layoutWardsList = findViewById(R.id.layoutWardsList)
        tvEmptyWards = findViewById(R.id.tvEmptyWards)
        layoutGuardiansList = findViewById(R.id.layoutGuardiansList)
        tvEmptyGuardians = findViewById(R.id.tvEmptyGuardians)
    }

    private fun setupListeners() {
        btnSecureAccount.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSecureAccountClickTime < 1000L) return@setOnClickListener
            lastSecureAccountClickTime = now
            btnSecureAccount.isEnabled = false
            handleGoogleSecurityLinking()
        }

        btnInitiateBreakout.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBreakoutClickTime < 1000L) return@setOnClickListener
            lastBreakoutClickTime = now
            showEmergencyBreakoutDialog()
        }

        btnCancelBreakout.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastCancelBreakoutClickTime < 1000L) return@setOnClickListener
            lastCancelBreakoutClickTime = now
            btnCancelBreakout.isEnabled = false
            handleCancelBreakout()
        }

        btnCompleteBreakout.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastCompleteBreakoutClickTime < 1000L) return@setOnClickListener
            lastCompleteBreakoutClickTime = now
            btnCompleteBreakout.isEnabled = false
            handleCompleteBreakout()
        }

        btnCopyCode.setOnClickListener {
            val code = tvMyPairingCode.text.toString()
            if (code.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("Pairing Code", code)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, R.string.toast_code_copied, Toast.LENGTH_SHORT).show()
            }
        }

        btnEditMyName.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastEditNameClickTime < 800L) return@setOnClickListener
            lastEditNameClickTime = now
            showEditMyNameDialog()
        }

        btnHubOpenSelfControl.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSelfControlClickTime < 800L) return@setOnClickListener
            lastSelfControlClickTime = now

            val gate = com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.getActiveGate()
            if (gate.canAccess(this)) {
                startActivity(Intent(this, com.drmohammed.antiporn.ui.control.SelfControlActivity::class.java))
            } else {
                gate.requestAccess(this) {
                    if (!isFinishing && !isDestroyed) {
                        startActivity(Intent(this, com.drmohammed.antiporn.ui.control.SelfControlActivity::class.java))
                    }
                }
            }
        }

        btnAddPartner.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAddPartnerClickTime < 800L) return@setOnClickListener
            lastAddPartnerClickTime = now
            showAddPartnerDialog()
        }
    }

    private fun updateAccountSecurityState() {
        if (isFinishing || isDestroyed) return
        val isLinked = googleAuthManager.isLinkedWithGoogle()
        if (isLinked) {
            val email = googleAuthManager.getLinkedEmail() ?: ""
            tvAccountSecurityStatus.text = getString(R.string.account_secured_google) + " " + email
            btnSecureAccount.text = getString(R.string.account_secured_google)
            btnSecureAccount.isEnabled = false
        } else {
            tvAccountSecurityStatus.text = getString(R.string.account_unsecured_warning)
            btnSecureAccount.text = getString(R.string.btn_secure_with_google)
            btnSecureAccount.isEnabled = true
        }
    }

    private fun handleGoogleSecurityLinking() {
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            try {
                btnSecureAccount.isEnabled = false
                val result = googleAuthManager.signInOrLinkWithGoogle(this@AccountabilityHubActivity)
                if (isFinishing || isDestroyed) return@launch
                result.fold(
                    onSuccess = { user ->
                        Toast.makeText(this@AccountabilityHubActivity, R.string.toast_account_secured_success, Toast.LENGTH_SHORT).show()
                        updateAccountSecurityState()
                        loadPairingCode()
                        loadPartners()
                    },
                    onFailure = { err ->
                        if (err !is androidx.credentials.exceptions.GetCredentialCancellationException) {
                            Toast.makeText(this@AccountabilityHubActivity, getString(R.string.toast_account_secured_failed, err.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                )
            } finally {
                if (!isFinishing && !isDestroyed) {
                    updateAccountSecurityState()
                }
            }
        }
    }

    private fun renderBreakoutState(policy: DevicePolicyBundle) {
        breakoutCountdownJob?.cancel()
        if (policy.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING && policy.breakoutUnlockAt != null) {
            cardEmergencyBreakout.visibility = View.VISIBLE
            btnInitiateBreakout.visibility = View.GONE

            breakoutCountdownJob = lifecycleScope.launch {
                while (isActive) {
                    val remainingMs = (policy.breakoutUnlockAt - TrustedTimeProvider.currentTimeMillis(this@AccountabilityHubActivity)).coerceAtLeast(0L)
                    val hours = remainingMs / (1000 * 3600)
                    val mins = (remainingMs % (1000 * 3600)) / (1000 * 60)
                    val secs = (remainingMs % (1000 * 60)) / 1000
                    val formatted = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
                    tvBreakoutCountdown.text = getString(R.string.breakout_pending_time_remaining, formatted)

                    if (remainingMs <= 0L) {
                        btnCompleteBreakout.visibility = View.VISIBLE
                        break
                    } else {
                        btnCompleteBreakout.visibility = View.GONE
                    }
                    delay(1000L)
                }
            }
        } else {
            cardEmergencyBreakout.visibility = View.GONE
            btnInitiateBreakout.visibility = View.VISIBLE
        }
    }

    private fun showEmergencyBreakoutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_breakout_title)
            .setMessage(R.string.dialog_breakout_msg)
            .setPositiveButton(R.string.dialog_breakout_confirm) { dialog, _ ->
                dialog.dismiss()
                lifecycleScope.launch {
                    if (isFinishing || isDestroyed) return@launch
                    try {
                        btnInitiateBreakout.isEnabled = false
                        val result = accountabilityRepo.requestEmergencyBreakout()
                        if (isFinishing || isDestroyed) return@launch
                        result.fold(
                            onSuccess = {
                                Toast.makeText(this@AccountabilityHubActivity, R.string.toast_breakout_requested, Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { err ->
                                Toast.makeText(this@AccountabilityHubActivity, err.localizedMessage ?: getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
                            }
                        )
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Error requesting breakout", e)
                    } finally {
                        if (!isFinishing && !isDestroyed) {
                            btnInitiateBreakout.isEnabled = true
                        }
                    }
                }
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    private fun handleCancelBreakout() {
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            try {
                btnCancelBreakout.isEnabled = false
                val result = accountabilityRepo.cancelEmergencyBreakout()
                if (isFinishing || isDestroyed) return@launch
                result.fold(
                    onSuccess = {
                        Toast.makeText(this@AccountabilityHubActivity, R.string.toast_breakout_cancelled, Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(this@AccountabilityHubActivity, err.localizedMessage ?: getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
                    }
                )
            } finally {
                if (!isFinishing && !isDestroyed) {
                    btnCancelBreakout.isEnabled = true
                }
            }
        }
    }

    private fun handleCompleteBreakout() {
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            try {
                btnCompleteBreakout.isEnabled = false
                val result = accountabilityRepo.executeEmergencyBreakout()
                if (isFinishing || isDestroyed) return@launch
                result.fold(
                    onSuccess = {
                        Toast.makeText(this@AccountabilityHubActivity, R.string.toast_breakout_completed, Toast.LENGTH_SHORT).show()
                        loadPairingCode()
                        loadPartners()
                    },
                    onFailure = { err ->
                        Toast.makeText(this@AccountabilityHubActivity, err.localizedMessage ?: getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
                    }
                )
            } finally {
                if (!isFinishing && !isDestroyed) {
                    btnCompleteBreakout.isEnabled = true
                }
            }
        }
    }

    private fun loadPairingCode() {
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            try {
                val currentPolicy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
                if (currentPolicy.isSupervised) {
                    cardSupervisedBanner.visibility = View.VISIBLE
                    cardMyPairingCode.visibility = View.GONE
                    return@launch
                }
                val code = accountabilityRepo.getOrCreateMyPairingCode()
                if (isFinishing || isDestroyed) return@launch
                if (code == AccountabilityRepository.CODE_LOCKED) {
                    cardSupervisedBanner.visibility = View.VISIBLE
                    cardMyPairingCode.visibility = View.GONE
                } else {
                    cardSupervisedBanner.visibility = View.GONE
                    cardMyPairingCode.visibility = View.VISIBLE
                    tvMyPairingCode.text = code
                    btnCopyCode.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isFinishing || isDestroyed) return@launch
                Log.e(TAG, "Error loading pairing code", e)
                tvMyPairingCode.text = "------"
            }
        }
    }

    private fun loadPartners() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (isFinishing || isDestroyed) return@launch
            try {
                var wards = accountabilityRepo.getWards()
                var guardians = accountabilityRepo.getGuardians()

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    renderWards(wards)
                    renderGuardians(guardians)
                }

                val syncResult = accountabilityRepo.syncPartnersFromCloud().getOrNull()
                if (syncResult != null) {
                    wards = accountabilityRepo.getWards()
                    guardians = accountabilityRepo.getGuardians()
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        renderWards(wards)
                        renderGuardians(guardians)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isFinishing || isDestroyed) return@launch
                Log.e(TAG, "Error loading partners", e)
            }
        }
    }

    private fun renderWards(wards: List<PartnerEntity>) {
        layoutWardsList.removeAllViews()
        if (wards.isEmpty()) {
            layoutWardsList.addView(tvEmptyWards)
        } else {
            for (ward in wards) {
                val card = createPartnerCard(ward, isWard = true)
                layoutWardsList.addView(card)
            }
        }
    }

    private fun renderGuardians(guardians: List<PartnerEntity>) {
        layoutGuardiansList.removeAllViews()
        if (guardians.isEmpty()) {
            layoutGuardiansList.addView(tvEmptyGuardians)
        } else {
            for (guardian in guardians) {
                val card = createPartnerCard(guardian, isWard = false)
                layoutGuardiansList.addView(card)
            }
        }
    }

    private fun createPartnerCard(partner: PartnerEntity, isWard: Boolean): View {
        val card = MaterialCardView(this).apply {
            radius = resources.displayMetrics.density * 18
            cardElevation = 0f
            strokeWidth = (1 * resources.displayMetrics.density).toInt()
            strokeColor = MaterialColors.getColor(
                this@AccountabilityHubActivity,
                com.google.android.material.R.attr.colorOutlineVariant,
                android.graphics.Color.GRAY
            )
            setCardBackgroundColor(
                MaterialColors.getColor(
                    this@AccountabilityHubActivity,
                    com.google.android.material.R.attr.colorSurfaceContainer,
                    android.graphics.Color.WHITE
                )
            )
            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            this.layoutParams = layoutParams
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            if (isWard) {
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                setBackgroundResource(typedValue.resourceId)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastEditNameClickTime < 800L) return@setOnClickListener
                    lastEditNameClickTime = now
                    showEditWardNameDialog(partner)
                }
            }
        }

        val nameView = TextView(this).apply {
            text = partner.displayName
            textSize = 15f
            setTextColor(
                MaterialColors.getColor(
                    this@AccountabilityHubActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    android.graphics.Color.BLACK
                )
            )
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        nameRow.addView(nameView)

        if (isWard) {
            val editIcon = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_edit)
                val iconSize = (16 * resources.displayMetrics.density).toInt()
                val marginSide = (8 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    setMargins(marginSide, 0, marginSide, 0)
                }
                setColorFilter(
                    MaterialColors.getColor(
                        this@AccountabilityHubActivity,
                        androidx.appcompat.R.attr.colorPrimary,
                        android.graphics.Color.DKGRAY
                    )
                )
                contentDescription = getString(R.string.btn_edit)
            }
            nameRow.addView(editIcon)
        }
        container.addView(nameRow)

        val codeView = TextView(this).apply {
            text = if (isWard) {
                getString(R.string.partner_pairing_code_label, partner.pairingCode)
            } else {
                getString(R.string.guardian_status_active)
            }
            textSize = 12f
            setTextColor(
                if (isWard) {
                    MaterialColors.getColor(
                        this@AccountabilityHubActivity,
                        com.google.android.material.R.attr.colorOutline,
                        android.graphics.Color.GRAY
                    )
                } else {
                    MaterialColors.getColor(
                        this@AccountabilityHubActivity,
                        androidx.appcompat.R.attr.colorPrimary,
                        android.graphics.Color.BLUE
                    )
                }
            )
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        container.addView(codeView)

        // Live Heartbeat & Breakout status pill for Wards
        val statusPill = TextView(this).apply {
            textSize = 11f
            setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
            visibility = View.GONE
        }
        container.addView(statusPill)

        if (isWard) {
            lifecycleScope.launch {
                val wardPolicy = accountabilityRepo.fetchWardPolicy(partner.id)
                Log.i(TAG, "Ward ${partner.displayName} (${partner.id}) policy: isSupervised=${wardPolicy?.isSupervised}, heartbeat=${wardPolicy?.lastHeartbeat}")
                if (isFinishing || isDestroyed || wardPolicy == null) return@launch
                statusPill.visibility = View.VISIBLE
                if (wardPolicy.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING) {
                    statusPill.text = getString(R.string.guardian_breakout_alert_title)
                    statusPill.setTextColor(MaterialColors.getColor(this@AccountabilityHubActivity, androidx.appcompat.R.attr.colorError, getColor(R.color.status_orange)))
                } else if (wardPolicy.lastHeartbeat > 0L) {
                    val diff = System.currentTimeMillis() - wardPolicy.lastHeartbeat
                    if (diff < 24 * 3600 * 1000L) {
                        statusPill.text = getString(R.string.status_online_recently)
                        statusPill.setTextColor(getColor(R.color.status_green))
                    } else {
                        val days = (diff / (24 * 3600 * 1000L)).toInt().coerceAtLeast(1)
                        statusPill.text = getString(R.string.status_offline_days, days)
                        statusPill.setTextColor(MaterialColors.getColor(this@AccountabilityHubActivity, com.google.android.material.R.attr.colorOutline, android.graphics.Color.GRAY))
                    }
                } else {
                    statusPill.text = getString(R.string.guardian_status_active)
                    statusPill.setTextColor(getColor(R.color.status_green))
                }
            }
        }

        if (isWard) {
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }

            val manageBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.btn_manage_ward)
                textSize = 12f
                cornerRadius = (8 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastManageClickTime < 800L) return@setOnClickListener
                    lastManageClickTime = now
                    val intent = Intent(this@AccountabilityHubActivity, GuardianControlActivity::class.java).apply {
                        putExtra(GuardianControlActivity.EXTRA_WARD_ID, partner.id)
                        putExtra(GuardianControlActivity.EXTRA_WARD_NAME, partner.displayName)
                    }
                    startActivity(intent)
                }
            }
            btnRow.addView(manageBtn)

            val deleteBtn = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.btn_delete_partner)
                textSize = 12f
                setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError, getColor(R.color.status_orange)))
            }
            deleteBtn.setOnClickListener {
                MaterialAlertDialogBuilder(this@AccountabilityHubActivity)
                    .setTitle(R.string.dialog_revoke_ward_title)
                    .setMessage(getString(R.string.dialog_revoke_ward_msg, partner.displayName))
                    .setPositiveButton(R.string.btn_delete_partner) { dialog, _ ->
                        dialog.dismiss()
                        deleteBtn.isEnabled = false
                        lifecycleScope.launch {
                            if (isFinishing || isDestroyed) return@launch
                            try {
                                val result = accountabilityRepo.revokeSupervision(partner.id)
                                if (isFinishing || isDestroyed) return@launch
                                result.fold(
                                    onSuccess = {
                                        Toast.makeText(this@AccountabilityHubActivity, R.string.toast_supervision_revoked, Toast.LENGTH_SHORT).show()
                                        loadPartners()
                                    },
                                    onFailure = { err ->
                                        Toast.makeText(
                                            this@AccountabilityHubActivity,
                                            getString(R.string.toast_partner_delete_failed, err.localizedMessage ?: getString(R.string.error_unknown)),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                )
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                if (isFinishing || isDestroyed) return@launch
                                Log.e(TAG, "Error revoking ward supervision ${partner.id}", e)
                                Toast.makeText(
                                    this@AccountabilityHubActivity,
                                    getString(R.string.toast_partner_delete_failed, e.localizedMessage ?: getString(R.string.error_unknown)),
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                if (!isFinishing && !isDestroyed) {
                                    deleteBtn.isEnabled = true
                                }
                            }
                        }
                    }
                    .setNegativeButton(R.string.dialog_btn_cancel, null)
                    .show()
            }
            btnRow.addView(deleteBtn)
            container.addView(btnRow)
        }

        card.addView(container)
        return card
    }

    private fun showAddPartnerDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_partner, null)
        val tilCode = dialogView.findViewById<TextInputLayout>(R.id.tilPartnerCode)
        val etCode = dialogView.findViewById<EditText>(R.id.etPartnerCode)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_add_partner_title)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_btn_connect, null)
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (isPairingInProgress) return@setOnClickListener
            val code = etCode.text?.toString()?.trim() ?: ""
            val regex = Regex("^[A-Za-z0-9]{6}$")
            if (!regex.matches(code)) {
                tilCode?.error = getString(R.string.dialog_add_partner_code_hint)
                return@setOnClickListener
            }
            tilCode?.error = null
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.dismiss()
            performPairing(code, PartnerEntity.ROLE_WARD)
        }
    }

    private fun performPairing(code: String, role: String) {
        if (isPairingInProgress) return
        isPairingInProgress = true
        btnAddPartner.isEnabled = false
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            try {
                val result = accountabilityRepo.pairWithCode(code, role)
                if (isFinishing || isDestroyed) return@launch
                result.fold(
                    onSuccess = {
                        Toast.makeText(this@AccountabilityHubActivity, R.string.toast_partner_added, Toast.LENGTH_LONG).show()
                        loadPartners()
                        loadPairingCode()
                        if (!googleAuthManager.isLinkedWithGoogle()) {
                            promptAccountSecurityAfterPairing()
                        }
                    },
                    onFailure = { err ->
                        Toast.makeText(
                            this@AccountabilityHubActivity,
                            getString(R.string.toast_partner_failed, err.localizedMessage ?: getString(R.string.error_unknown)),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isFinishing || isDestroyed) return@launch
                Log.e(TAG, "Error performing pairing", e)
            } finally {
                btnAddPartner.isEnabled = true
                isPairingInProgress = false
            }
        }
    }

    private fun promptAccountSecurityAfterPairing() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_account_security)
            .setMessage(R.string.account_unsecured_warning)
            .setPositiveButton(R.string.btn_secure_with_google) { dialog, _ ->
                dialog.dismiss()
                handleGoogleSecurityLinking()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    private fun showEditMyNameDialog() {
        if (isFinishing || isDestroyed) return
        val currentName = accountabilityRepo.getMyDisplayName()
        val input = EditText(this).apply {
            setText(currentName)
            setSelection(text?.length ?: 0)
            hint = getString(R.string.dialog_edit_my_name_hint)
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(28))
        }
        val frame = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_edit_my_name_title)
            .setView(frame)
            .setPositiveButton(R.string.btn_save) { dialog, _ ->
                val newName = input.text?.toString()?.trim() ?: ""
                if (newName.isNotBlank() && newName != currentName) {
                    lifecycleScope.launch {
                        val result = accountabilityRepo.setMyDisplayName(newName)
                        if (isFinishing || isDestroyed) return@launch
                        result.onSuccess {
                            tvMyDisplayName.text = newName
                            Toast.makeText(this@AccountabilityHubActivity, R.string.toast_name_saved, Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            Log.e(TAG, "Failed to update display name", e)
                            Toast.makeText(this@AccountabilityHubActivity, getString(R.string.toast_partner_failed, e.localizedMessage), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    private fun showEditWardNameDialog(partner: PartnerEntity) {
        if (isFinishing || isDestroyed) return
        val input = EditText(this).apply {
            setText(partner.displayName)
            setSelection(text?.length ?: 0)
            hint = getString(R.string.dialog_edit_ward_name_hint)
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(28))
        }
        val frame = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_edit_ward_name_title)
            .setView(frame)
            .setPositiveButton(R.string.btn_save) { dialog, _ ->
                val newName = input.text?.toString()?.trim() ?: ""
                if (newName.isNotBlank() && newName != partner.displayName) {
                    lifecycleScope.launch {
                        val result = accountabilityRepo.renamePartner(partner.id, newName)
                        if (isFinishing || isDestroyed) return@launch
                        result.onSuccess {
                            loadPartners()
                            Toast.makeText(this@AccountabilityHubActivity, R.string.toast_name_saved, Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            Log.e(TAG, "Failed to rename partner", e)
                            Toast.makeText(this@AccountabilityHubActivity, getString(R.string.toast_partner_failed, e.localizedMessage), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    companion object {
        private const val TAG = "AntiPorn_AccHub"
    }
}
