package com.drmohammed.antiporn.ui.accountability

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
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
 * Accountability Hub Fragment embedded in the main navigation hub.
 * Presents pairing codes, protected wards, guardians, and emergency breakout grace periods.
 */
class AccountabilityFragment : Fragment() {

    interface AccountabilityNavigationListener {
        fun onNavigateToSelfControl()
    }

    private var navListener: AccountabilityNavigationListener? = null

    private lateinit var accountabilityRepo: AccountabilityRepository
    private lateinit var googleAuthManager: GoogleAuthManager

    // Views
    private var cardAccountSecurity: MaterialCardView? = null
    private var tvAccountSecurityStatus: TextView? = null
    private var btnSecureAccount: MaterialButton? = null

    private var cardSupervisedBanner: MaterialCardView? = null
    private var cardEmergencyBreakout: MaterialCardView? = null
    private var tvBreakoutCountdown: TextView? = null
    private var btnCancelBreakout: MaterialButton? = null
    private var btnCompleteBreakout: MaterialButton? = null
    private var btnInitiateBreakout: MaterialButton? = null

    private var cardMyPairingCode: MaterialCardView? = null
    private var tvMyDisplayName: TextView? = null
    private var btnEditMyName: MaterialButton? = null
    private var btnHubOpenSelfControl: MaterialButton? = null
    private var tvMyPairingCode: TextView? = null
    private var btnCopyCode: MaterialButton? = null
    private var btnAddPartner: MaterialButton? = null
    private var layoutWardsList: LinearLayout? = null
    private var tvEmptyWards: TextView? = null
    private var layoutGuardiansList: LinearLayout? = null
    private var tvEmptyGuardians: TextView? = null

    private var breakoutCountdownJob: Job? = null
    private var lastAddPartnerClickTime = 0L
    private var lastManageClickTime = 0L
    private var lastEditNameClickTime = 0L
    private var lastBreakoutClickTime = 0L
    private var lastSecureAccountClickTime = 0L
    private var lastCancelBreakoutClickTime = 0L
    private var lastCompleteBreakoutClickTime = 0L
    private var isPairingInProgress = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is AccountabilityNavigationListener) {
            navListener = context
        }
    }

    override fun onDetach() {
        super.onDetach()
        navListener = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.activity_accountability_hub, container, false)
        val ctx = root.context.applicationContext
        accountabilityRepo = AccountabilityRepository.getInstance(ctx)
        googleAuthManager = GoogleAuthManager.getInstance(ctx)

        initViews(root)
        setupListeners()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observePolicyChanges()
        updateAccountSecurityState()
        loadPairingCode()
        loadPartners()
    }

    override fun onResume() {
        super.onResume()
        val ctx = context?.applicationContext ?: return
        val policy = PolicyRepository.getInstance(ctx).currentPolicyFlow.value
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

    private fun initViews(root: View) {
        cardAccountSecurity = root.findViewById(R.id.cardAccountSecurity)
        tvAccountSecurityStatus = root.findViewById(R.id.tvAccountSecurityStatus)
        btnSecureAccount = root.findViewById(R.id.btnSecureAccount)

        cardSupervisedBanner = root.findViewById(R.id.cardSupervisedBanner)
        cardEmergencyBreakout = root.findViewById(R.id.cardEmergencyBreakout)
        tvBreakoutCountdown = root.findViewById(R.id.tvBreakoutCountdown)
        btnCancelBreakout = root.findViewById(R.id.btnCancelBreakout)
        btnCompleteBreakout = root.findViewById(R.id.btnCompleteBreakout)
        btnInitiateBreakout = root.findViewById(R.id.btnInitiateBreakout)

        cardMyPairingCode = root.findViewById(R.id.cardMyPairingCode)
        tvMyDisplayName = root.findViewById(R.id.tvMyDisplayName)
        btnEditMyName = root.findViewById(R.id.btnEditMyName)
        btnHubOpenSelfControl = root.findViewById(R.id.btnHubOpenSelfControl)
        tvMyDisplayName?.text = accountabilityRepo.getMyDisplayName()

        tvMyPairingCode = root.findViewById(R.id.tvMyPairingCode)
        btnCopyCode = root.findViewById(R.id.btnCopyCode)
        btnAddPartner = root.findViewById(R.id.btnAddPartner)
        layoutWardsList = root.findViewById(R.id.layoutWardsList)
        tvEmptyWards = root.findViewById(R.id.tvEmptyWards)
        layoutGuardiansList = root.findViewById(R.id.layoutGuardiansList)
        tvEmptyGuardians = root.findViewById(R.id.tvEmptyGuardians)
    }

    private fun setupListeners() {
        btnSecureAccount?.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSecureAccountClickTime < 1000L) return@setOnClickListener
            lastSecureAccountClickTime = now
            btnSecureAccount?.isEnabled = false
            handleGoogleSecurityLinking()
        }

        btnInitiateBreakout?.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBreakoutClickTime < 1000L) return@setOnClickListener
            lastBreakoutClickTime = now
            showEmergencyBreakoutDialog()
        }

        btnCancelBreakout?.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCancelBreakoutClickTime < 1000L) return@setOnClickListener
            lastCancelBreakoutClickTime = now
            btnCancelBreakout?.isEnabled = false
            handleCancelBreakout()
        }

        btnCompleteBreakout?.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCompleteBreakoutClickTime < 1000L) return@setOnClickListener
            lastCompleteBreakoutClickTime = now
            btnCompleteBreakout?.isEnabled = false
            handleCompleteBreakout()
        }

        btnCopyCode?.setOnClickListener {
            val code = tvMyPairingCode?.text?.toString().orEmpty()
            if (code.isNotBlank()) {
                val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("Pairing Code", code)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(context, R.string.toast_code_copied, Toast.LENGTH_SHORT).show()
            }
        }

        btnEditMyName?.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEditNameClickTime < 800L) return@setOnClickListener
            lastEditNameClickTime = now
            showEditMyNameDialog()
        }

        btnHubOpenSelfControl?.setOnClickListener {
            if (navListener != null) {
                navListener?.onNavigateToSelfControl()
            } else {
                context?.let { ctx -> startActivity(Intent(ctx, com.drmohammed.antiporn.ui.control.SelfControlActivity::class.java)) }
            }
        }

        btnAddPartner?.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAddPartnerClickTime < 800L) return@setOnClickListener
            lastAddPartnerClickTime = now
            showAddPartnerDialog()
        }
    }

    private fun observePolicyChanges() {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PolicyRepository.getInstance(ctx)
                    .currentPolicyFlow.collect { policy ->
                        if (!isAdded || view == null) return@collect
                        if (policy.isSupervised) {
                            cardSupervisedBanner?.visibility = View.VISIBLE
                            cardMyPairingCode?.visibility = View.GONE
                            renderBreakoutState(policy)
                        } else {
                            cardSupervisedBanner?.visibility = View.GONE
                            cardEmergencyBreakout?.visibility = View.GONE
                            btnInitiateBreakout?.visibility = View.GONE
                            breakoutCountdownJob?.cancel()
                            cardMyPairingCode?.visibility = View.VISIBLE
                            loadPairingCode()
                        }
                        updateAccountSecurityState()
                        loadPartners()
                    }
            }
        }
    }

    private fun updateAccountSecurityState() {
        if (!isAdded || view == null) return
        val isLinked = googleAuthManager.isLinkedWithGoogle()
        if (isLinked) {
            val email = googleAuthManager.getLinkedEmail() ?: ""
            tvAccountSecurityStatus?.text = getString(R.string.account_secured_google_email, email)
            btnSecureAccount?.text = getString(R.string.account_secured_google)
            btnSecureAccount?.isEnabled = false
        } else {
            tvAccountSecurityStatus?.text = getString(R.string.account_unsecured_warning)
            btnSecureAccount?.text = getString(R.string.btn_secure_with_google)
            btnSecureAccount?.isEnabled = true
        }
    }

    private fun handleGoogleSecurityLinking() {
        val act = activity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                btnSecureAccount?.isEnabled = false
                val result = googleAuthManager.signInOrLinkWithGoogle(act)
                if (!isAdded || view == null) return@launch
                result.fold(
                    onSuccess = {
                        Toast.makeText(context, R.string.toast_account_secured_success, Toast.LENGTH_SHORT).show()
                        updateAccountSecurityState()
                        loadPairingCode()
                        loadPartners()
                    },
                    onFailure = { err ->
                        if (err !is androidx.credentials.exceptions.GetCredentialCancellationException) {
                            Toast.makeText(context, getString(R.string.toast_account_secured_failed, err.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                )
            } finally {
                if (isAdded && view != null) {
                    updateAccountSecurityState()
                }
            }
        }
    }

    private fun renderBreakoutState(policy: DevicePolicyBundle) {
        breakoutCountdownJob?.cancel()
        val ctx = context ?: return
        if (policy.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING && policy.breakoutUnlockAt != null) {
            cardEmergencyBreakout?.visibility = View.VISIBLE
            btnInitiateBreakout?.visibility = View.GONE

            breakoutCountdownJob = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    val remainingMs = (policy.breakoutUnlockAt - TrustedTimeProvider.currentTimeMillis(ctx)).coerceAtLeast(0L)
                    val hours = remainingMs / (1000 * 3600)
                    val mins = (remainingMs % (1000 * 3600)) / (1000 * 60)
                    val secs = (remainingMs % (1000 * 60)) / 1000
                    val formatted = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
                    tvBreakoutCountdown?.text = getString(R.string.breakout_pending_time_remaining, formatted)

                    if (remainingMs <= 0L) {
                        btnCompleteBreakout?.visibility = View.VISIBLE
                        break
                    } else {
                        btnCompleteBreakout?.visibility = View.GONE
                    }
                    delay(1000L)
                }
            }
        } else {
            cardEmergencyBreakout?.visibility = View.GONE
            btnInitiateBreakout?.visibility = View.VISIBLE
        }
    }

    private fun showEmergencyBreakoutDialog() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_breakout_title)
            .setMessage(R.string.dialog_breakout_msg)
            .setPositiveButton(R.string.dialog_breakout_confirm) { dialog, _ ->
                dialog.dismiss()
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        btnInitiateBreakout?.isEnabled = false
                        val result = accountabilityRepo.requestEmergencyBreakout()
                        if (!isAdded || view == null) return@launch
                        result.fold(
                            onSuccess = {
                                Toast.makeText(context, R.string.toast_breakout_requested, Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { err ->
                                Toast.makeText(context, err.localizedMessage ?: getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
                            }
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error requesting breakout", e)
                    } finally {
                        if (isAdded && view != null) {
                            btnInitiateBreakout?.isEnabled = true
                        }
                    }
                }
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    private fun handleCancelBreakout() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                btnCancelBreakout?.isEnabled = false
                val result = accountabilityRepo.cancelEmergencyBreakout()
                if (!isAdded || view == null) return@launch
                result.fold(
                    onSuccess = {
                        Toast.makeText(context, R.string.toast_breakout_cancelled, Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(context, err.localizedMessage ?: getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
                    }
                )
            } finally {
                if (isAdded && view != null) {
                    btnCancelBreakout?.isEnabled = true
                }
            }
        }
    }

    private fun handleCompleteBreakout() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                btnCompleteBreakout?.isEnabled = false
                val result = accountabilityRepo.executeEmergencyBreakout()
                if (!isAdded || view == null) return@launch
                result.fold(
                    onSuccess = {
                        Toast.makeText(context, R.string.toast_breakout_completed, Toast.LENGTH_SHORT).show()
                        loadPairingCode()
                        loadPartners()
                    },
                    onFailure = { err ->
                        Toast.makeText(context, err.localizedMessage ?: getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
                    }
                )
            } finally {
                if (isAdded && view != null) {
                    btnCompleteBreakout?.isEnabled = true
                }
            }
        }
    }

    private fun loadPairingCode() {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val currentPolicy = PolicyRepository.getInstance(ctx).getCurrentPolicy()
                if (currentPolicy.isSupervised) {
                    cardSupervisedBanner?.visibility = View.VISIBLE
                    cardMyPairingCode?.visibility = View.GONE
                    return@launch
                }
                val code = accountabilityRepo.getOrCreateMyPairingCode()
                if (!isAdded || view == null) return@launch
                if (code == AccountabilityRepository.CODE_LOCKED) {
                    cardSupervisedBanner?.visibility = View.VISIBLE
                    cardMyPairingCode?.visibility = View.GONE
                } else {
                    cardSupervisedBanner?.visibility = View.GONE
                    cardMyPairingCode?.visibility = View.VISIBLE
                    tvMyPairingCode?.text = code
                    btnCopyCode?.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading pairing code", e)
                tvMyPairingCode?.text = "------"
            }
        }
    }

    private fun loadPartners() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                var wards = accountabilityRepo.getWards()
                var guardians = accountabilityRepo.getGuardians()

                withContext(Dispatchers.Main) {
                    if (!isAdded || view == null) return@withContext
                    renderWards(wards)
                    renderGuardians(guardians)
                }

                val syncResult = accountabilityRepo.syncPartnersFromCloud().getOrNull()
                if (syncResult != null) {
                    wards = accountabilityRepo.getWards()
                    guardians = accountabilityRepo.getGuardians()
                    withContext(Dispatchers.Main) {
                        if (!isAdded || view == null) return@withContext
                        renderWards(wards)
                        renderGuardians(guardians)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading partners", e)
            }
        }
    }

    private fun renderWards(wards: List<PartnerEntity>) {
        val container = layoutWardsList ?: return
        val emptyText = tvEmptyWards ?: return
        container.removeAllViews()
        if (wards.isEmpty()) {
            container.addView(emptyText)
        } else {
            for (ward in wards) {
                val card = createPartnerCard(ward, isWard = true)
                container.addView(card)
            }
        }
    }

    private fun renderGuardians(guardians: List<PartnerEntity>) {
        val container = layoutGuardiansList ?: return
        val emptyText = tvEmptyGuardians ?: return
        container.removeAllViews()
        if (guardians.isEmpty()) {
            container.addView(emptyText)
        } else {
            for (guardian in guardians) {
                val card = createPartnerCard(guardian, isWard = false)
                container.addView(card)
            }
        }
    }

    private fun createPartnerCard(partner: PartnerEntity, isWard: Boolean): View {
        val ctx = context ?: return View(requireContext())
        val card = MaterialCardView(ctx).apply {
            radius = resources.displayMetrics.density * 18
            cardElevation = 0f
            strokeWidth = (1 * resources.displayMetrics.density).toInt()
            strokeColor = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)
            setCardBackgroundColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceContainer, Color.WHITE))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val nameRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            if (isWard) {
                val typedValue = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                setBackgroundResource(typedValue.resourceId)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEditNameClickTime < 800L) return@setOnClickListener
                    lastEditNameClickTime = now
                    showEditWardNameDialog(partner)
                }
            }
        }

        val nameView = TextView(ctx).apply {
            text = partner.displayName
            textSize = 15f
            setTextColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        nameRow.addView(nameView)

        if (isWard) {
            val editIcon = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_edit)
                val iconSize = (16 * resources.displayMetrics.density).toInt()
                val marginSide = (8 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    setMargins(marginSide, 0, marginSide, 0)
                }
                setColorFilter(MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, Color.DKGRAY))
                contentDescription = getString(R.string.btn_edit)
            }
            nameRow.addView(editIcon)
        }
        container.addView(nameRow)

        val codeView = TextView(ctx).apply {
            text = if (isWard) {
                getString(R.string.partner_pairing_code_label, partner.pairingCode)
            } else {
                getString(R.string.guardian_status_active)
            }
            textSize = 12f
            setTextColor(
                if (isWard) {
                    MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutline, Color.GRAY)
                } else {
                    MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
                }
            )
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        container.addView(codeView)

        val statusPill = TextView(ctx).apply {
            textSize = 11f
            setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
            visibility = View.GONE
        }
        container.addView(statusPill)

        if (isWard) {
            viewLifecycleOwner.lifecycleScope.launch {
                val wardPolicy = accountabilityRepo.fetchWardPolicy(partner.id)
                if (!isAdded || view == null || wardPolicy == null) return@launch
                statusPill.visibility = View.VISIBLE
                if (wardPolicy.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING) {
                    statusPill.text = getString(R.string.guardian_breakout_alert_title)
                    statusPill.setTextColor(ContextCompat.getColor(ctx, R.color.status_orange))
                } else if (wardPolicy.lastHeartbeat > 0L) {
                    val diff = System.currentTimeMillis() - wardPolicy.lastHeartbeat
                    if (diff < 24 * 3600 * 1000L) {
                        statusPill.text = getString(R.string.status_online_recently)
                        statusPill.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
                    } else {
                        val days = (diff / (24 * 3600 * 1000L)).toInt().coerceAtLeast(1)
                        statusPill.text = getString(R.string.status_offline_days, days)
                        statusPill.setTextColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutline, Color.GRAY))
                    }
                } else {
                    statusPill.text = getString(R.string.guardian_status_active)
                    statusPill.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
                }
            }
        }

        if (isWard) {
            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }

            val manageBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.btn_manage_ward)
                textSize = 12f
                cornerRadius = (8 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastManageClickTime < 800L) return@setOnClickListener
                    lastManageClickTime = now
                    val intent = Intent(ctx, GuardianControlActivity::class.java).apply {
                        putExtra(GuardianControlActivity.EXTRA_WARD_ID, partner.id)
                        putExtra(GuardianControlActivity.EXTRA_WARD_NAME, partner.displayName)
                    }
                    startActivity(intent)
                }
            }
            btnRow.addView(manageBtn)

            val deleteBtn = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.btn_delete_partner)
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.status_orange))
            }
            deleteBtn.setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.dialog_revoke_ward_title)
                    .setMessage(getString(R.string.dialog_revoke_ward_msg, partner.displayName))
                    .setPositiveButton(R.string.btn_delete_partner) { dialog, _ ->
                        dialog.dismiss()
                        deleteBtn.isEnabled = false
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val result = accountabilityRepo.revokeSupervision(partner.id)
                                if (!isAdded || view == null) return@launch
                                result.fold(
                                    onSuccess = {
                                        Toast.makeText(context, R.string.toast_supervision_revoked, Toast.LENGTH_SHORT).show()
                                        loadPartners()
                                    },
                                    onFailure = { err ->
                                        Toast.makeText(
                                            context,
                                            getString(R.string.toast_partner_delete_failed, err.localizedMessage ?: getString(R.string.error_unknown)),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                )
                            } catch (e: Exception) {
                                if (isAdded && view != null) {
                                    Toast.makeText(
                                        context,
                                        getString(R.string.toast_partner_delete_failed, e.localizedMessage ?: getString(R.string.error_unknown)),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } finally {
                                if (isAdded && view != null) {
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
        val ctx = context ?: return
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_partner, null)
        val tilCode = dialogView.findViewById<TextInputLayout>(R.id.tilPartnerCode)
        val etCode = dialogView.findViewById<EditText>(R.id.etPartnerCode)

        val dialog = MaterialAlertDialogBuilder(ctx)
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
        btnAddPartner?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = accountabilityRepo.pairWithCode(code, role)
                if (!isAdded || view == null) return@launch
                result.fold(
                    onSuccess = {
                        Toast.makeText(context, R.string.toast_partner_added, Toast.LENGTH_LONG).show()
                        loadPartners()
                        loadPairingCode()
                        if (!googleAuthManager.isLinkedWithGoogle()) {
                            promptAccountSecurityAfterPairing()
                        }
                    },
                    onFailure = { err ->
                        Toast.makeText(
                            context,
                            getString(R.string.toast_partner_failed, err.localizedMessage ?: getString(R.string.error_unknown)),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error performing pairing", e)
            } finally {
                if (isAdded && view != null) {
                    btnAddPartner?.isEnabled = true
                    isPairingInProgress = false
                }
            }
        }
    }

    private fun promptAccountSecurityAfterPairing() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
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
        val ctx = context ?: return
        val currentName = accountabilityRepo.getMyDisplayName()
        val input = EditText(ctx).apply {
            setText(currentName)
            setSelection(text?.length ?: 0)
            hint = getString(R.string.dialog_edit_my_name_hint)
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(28))
        }
        val frame = android.widget.FrameLayout(ctx).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_edit_my_name_title)
            .setView(frame)
            .setPositiveButton(R.string.btn_save) { dialog, _ ->
                val newName = input.text?.toString()?.trim() ?: ""
                if (newName.isNotBlank() && newName != currentName) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = accountabilityRepo.setMyDisplayName(newName)
                        if (!isAdded || view == null) return@launch
                        result.onSuccess {
                            tvMyDisplayName?.text = newName
                            Toast.makeText(context, R.string.toast_name_saved, Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            Log.e(TAG, "Failed to update display name", e)
                            Toast.makeText(context, getString(R.string.toast_partner_failed, e.localizedMessage), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    private fun showEditWardNameDialog(partner: PartnerEntity) {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            setText(partner.displayName)
            setSelection(text?.length ?: 0)
            hint = getString(R.string.dialog_edit_ward_name_hint)
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(28))
        }
        val frame = android.widget.FrameLayout(ctx).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_edit_ward_name_title)
            .setView(frame)
            .setPositiveButton(R.string.btn_save) { dialog, _ ->
                val newName = input.text?.toString()?.trim() ?: ""
                if (newName.isNotBlank() && newName != partner.displayName) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = accountabilityRepo.renamePartner(partner.id, newName)
                        if (!isAdded || view == null) return@launch
                        result.onSuccess {
                            loadPartners()
                            Toast.makeText(context, R.string.toast_name_saved, Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            Log.e(TAG, "Failed to rename partner", e)
                            Toast.makeText(context, getString(R.string.toast_partner_failed, e.localizedMessage), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        cardAccountSecurity = null
        tvAccountSecurityStatus = null
        btnSecureAccount = null
        cardSupervisedBanner = null
        cardEmergencyBreakout = null
        tvBreakoutCountdown = null
        btnCancelBreakout = null
        btnCompleteBreakout = null
        btnInitiateBreakout = null
        cardMyPairingCode = null
        tvMyDisplayName = null
        btnEditMyName = null
        btnHubOpenSelfControl = null
        tvMyPairingCode = null
        btnCopyCode = null
        btnAddPartner = null
        layoutWardsList = null
        tvEmptyWards = null
        layoutGuardiansList = null
        tvEmptyGuardians = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "AntiPorn_AccFrag"
    }
}
