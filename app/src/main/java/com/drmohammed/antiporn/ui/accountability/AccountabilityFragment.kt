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
import com.drmohammed.antiporn.databinding.FragmentAccountabilityBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale

/**
 * Clean & uncluttered Accountability Hub Fragment.
 * Organizes supervisor connection, ward management, and conditional cloud linking.
 */
class AccountabilityFragment : Fragment() {

    interface AccountabilityNavigationListener {
        fun onNavigateToSelfControl()
    }

    private var navListener: AccountabilityNavigationListener? = null

    private var _binding: FragmentAccountabilityBinding? = null
    private val binding get() = _binding!!

    private val accountabilityViewModel: AccountabilityViewModel by viewModel()
    private val viewModel get() = accountabilityViewModel
    private lateinit var googleAuthManager: GoogleAuthManager

    private var lastAddPartnerClickTime = 0L
    private var lastManageClickTime = 0L
    private var lastEditNameClickTime = 0L
    private var lastBreakoutClickTime = 0L
    private var lastApprovalsHubClickTime = 0L
    private var lastSecureAccountClickTime = 0L
    private var lastCancelBreakoutClickTime = 0L
    private var lastCompleteBreakoutClickTime = 0L
    private var isPairingInProgress = false

    // Smooth ticker references to avoid full-tree re-render jitter
    private var breakoutCountdownTextView: TextView? = null
    private var breakoutCompleteBtn: MaterialButton? = null
    private var lastRenderedGuardians: List<PartnerEntity> = emptyList()
    private var lastRenderedWards: List<PartnerEntity> = emptyList()
    private var lastBreakoutPendingState: Boolean? = null

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
    ): View {
        _binding = FragmentAccountabilityBinding.inflate(inflater, container, false)
        val ctx = requireContext().applicationContext
        googleAuthManager = GoogleAuthManager.getInstance(ctx)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        accountabilityViewModel.refreshAll()
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

    private fun render(state: AccountabilityUiState) {
        if (_binding == null) return

        // 1. Conditional Google Account Security (Only if user has wards AND has not linked Google yet)
        if (state.wards.isNotEmpty() && !state.isGoogleLinked) {
            binding.cardAccountSecurity.visibility = View.VISIBLE
            binding.tvAccountSecurityStatus.text = getString(R.string.account_unsecured_warning)
            binding.btnSecureAccount.text = getString(R.string.btn_secure_with_google)
            binding.btnSecureAccount.isEnabled = true
        } else {
            binding.cardAccountSecurity.visibility = View.GONE
        }

        // 2. Supervisors / Guardians Section
        renderGuardians(state.guardians, state)

        // 3. Pairing Code (Shown only if NOT supervised)
        if (state.isSupervised || state.isPairingCodeLocked || state.myPairingCode == AccountabilityRepository.CODE_LOCKED) {
            binding.cardMyPairingCode.visibility = View.GONE
        } else {
            binding.cardMyPairingCode.visibility = View.VISIBLE
            binding.tvMyPairingCode.text = state.myPairingCode.ifEmpty { "------" }
            binding.btnCopyCode.visibility = if (state.myPairingCode.isNotEmpty()) View.VISIBLE else View.GONE
        }

        // 4. Wards Section
        renderWards(state.wards)

        // 5. Approvals Hub Badge
        if (state.pendingApprovalsCount > 0) {
            binding.tvApprovalsBadge.visibility = View.VISIBLE
            binding.tvApprovalsBadge.text = if (state.pendingApprovalsCount > 99) "99+" else state.pendingApprovalsCount.toString()
        } else {
            binding.tvApprovalsBadge.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        binding.btnApprovalsHub.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastApprovalsHubClickTime < 800L) return@setOnClickListener
            lastApprovalsHubClickTime = now
            val intent = Intent(requireContext(), GuardianApprovalsHubActivity::class.java)
            startActivity(intent)
        }

        binding.btnSecureAccount.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSecureAccountClickTime < 1000L) return@setOnClickListener
            lastSecureAccountClickTime = now
            binding.btnSecureAccount.isEnabled = false
            handleGoogleSecurityLinking()
        }

        binding.btnCopyCode.setOnClickListener {
            val code = binding.tvMyPairingCode.text?.toString().orEmpty()
            if (code.isNotBlank()) {
                val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("Pairing Code", code)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(context, R.string.toast_code_copied, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAddPartner.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAddPartnerClickTime < 800L) return@setOnClickListener
            lastAddPartnerClickTime = now
            showAddPartnerDialog()
        }
    }

    private fun handleGoogleSecurityLinking() {
        val act = activity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                binding.btnSecureAccount.isEnabled = false
                val result = googleAuthManager.signInOrLinkWithGoogle(act)
                if (!isAdded || _binding == null) return@launch
                result.fold(
                    onSuccess = {
                        Toast.makeText(context, R.string.toast_account_secured_success, Toast.LENGTH_SHORT).show()
                        viewModel.refreshAll()
                    },
                    onFailure = { err ->
                        if (err !is androidx.credentials.exceptions.GetCredentialCancellationException) {
                            Toast.makeText(context, getString(R.string.toast_account_secured_failed, err.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                )
            } finally {
                if (isAdded && _binding != null) {
                    viewModel.updateGoogleSecurityState()
                }
            }
        }
    }

    private fun showEmergencyBreakoutDialog() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_breakout_title)
            .setMessage(R.string.dialog_breakout_msg)
            .setPositiveButton(R.string.dialog_breakout_confirm) { dialog, _ ->
                dialog.dismiss()
                viewModel.requestEmergencyBreakout { result ->
                    if (!isAdded || _binding == null) return@requestEmergencyBreakout
                    result.fold(
                        onSuccess = {
                            Toast.makeText(context, R.string.toast_breakout_requested, Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { err ->
                            Toast.makeText(context, err.localizedMessage ?: getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    private fun handleCancelBreakout(cancelBtn: View? = null) {
        cancelBtn?.isEnabled = false
        viewModel.cancelEmergencyBreakout { result ->
            if (!isAdded || _binding == null) return@cancelEmergencyBreakout
            cancelBtn?.isEnabled = true
            result.fold(
                onSuccess = {
                    Toast.makeText(context, R.string.toast_breakout_cancelled, Toast.LENGTH_SHORT).show()
                },
                onFailure = { err ->
                    Toast.makeText(context, err.localizedMessage ?: getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun handleCompleteBreakout(completeBtn: View? = null) {
        completeBtn?.isEnabled = false
        viewModel.executeEmergencyBreakout { result ->
            if (!isAdded || _binding == null) return@executeEmergencyBreakout
            completeBtn?.isEnabled = true
            result.fold(
                onSuccess = {
                    Toast.makeText(context, R.string.toast_breakout_completed, Toast.LENGTH_SHORT).show()
                },
                onFailure = { err ->
                    Toast.makeText(context, err.localizedMessage ?: getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun renderGuardians(guardians: List<PartnerEntity>, state: AccountabilityUiState) {
        val container = _binding?.layoutGuardiansList ?: return
        val emptyText = _binding?.tvEmptyGuardians ?: return

        val isBreakoutPending = (state.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING && state.breakoutUnlockAt != null)
        val guardiansUnchanged = (lastRenderedGuardians == guardians)
        val breakoutStateUnchanged = (lastBreakoutPendingState == isBreakoutPending)

        // Fast-path 1: Active breakout live countdown tick - smoothly update text without rebuilding
        if (guardiansUnchanged && breakoutStateUnchanged && breakoutCountdownTextView != null && isBreakoutPending) {
            val remainingMs = state.breakoutRemainingMs
            val hours = remainingMs / (1000 * 3600)
            val mins = (remainingMs % (1000 * 3600)) / (1000 * 60)
            val secs = (remainingMs % (1000 * 60)) / 1000
            val formatted = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
            breakoutCountdownTextView?.text = getString(R.string.breakout_pending_time_remaining, formatted)
            breakoutCompleteBtn?.visibility = if (remainingMs <= 0L) View.VISIBLE else View.GONE
            return
        }

        // Fast-path 2: Guardians and breakout state are unchanged and views are already populated
        if (guardiansUnchanged && breakoutStateUnchanged && !isBreakoutPending && container.childCount > 0) {
            return
        }

        lastRenderedGuardians = guardians
        lastBreakoutPendingState = isBreakoutPending
        breakoutCountdownTextView = null
        breakoutCompleteBtn = null

        container.removeAllViews()
        if (guardians.isEmpty()) {
            container.addView(emptyText)
        } else {
            for (guardian in guardians) {
                val card = createGuardianCard(guardian, state) ?: continue
                container.addView(card)
            }
        }
    }

    private fun createGuardianCard(guardian: PartnerEntity, state: AccountabilityUiState): View? {
        val ctx = context ?: return null
        val density = resources.displayMetrics.density

        val card = MaterialCardView(ctx).apply {
            radius = density * 20
            cardElevation = 0f
            strokeWidth = (1 * density).toInt()
            strokeColor = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)
            setCardBackgroundColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceContainer, Color.WHITE))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // Header Row: Icon + Supervisor Name + Status Subtitle
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val icon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_shield_device_owner)
            val iconSize = (32 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = (12 * density).toInt()
            }
            setColorFilter(MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, Color.BLUE))
        }
        headerRow.addView(icon)

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameView = TextView(ctx).apply {
            text = guardian.displayName
            textSize = 16f
            setTextColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
            typeface = Typeface.DEFAULT_BOLD
        }
        textCol.addView(nameView)

        val statusView = TextView(ctx).apply {
            text = getString(R.string.guardian_status_active_verified)
            textSize = 12f
            setTextColor(MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, Color.BLUE))
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        textCol.addView(statusView)

        headerRow.addView(textCol)
        container.addView(headerRow)

        // Integrated Emergency Breakout Section
        val isBreakoutPending = (state.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING && state.breakoutUnlockAt != null)

        if (isBreakoutPending) {
            val breakoutBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val boxPad = (12 * density).toInt()
                setPadding(boxPad, boxPad, boxPad, boxPad)
                setBackgroundResource(R.drawable.bg_shield_status_badge_amber)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (12 * density).toInt()
                }
            }

            val breakoutTitle = TextView(ctx).apply {
                text = getString(R.string.breakout_pending_title)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(ctx, R.color.status_red))
            }
            breakoutBox.addView(breakoutTitle)

            val remainingMs = state.breakoutRemainingMs
            val hours = remainingMs / (1000 * 3600)
            val mins = (remainingMs % (1000 * 3600)) / (1000 * 60)
            val secs = (remainingMs % (1000 * 60)) / 1000
            val formatted = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)

            val countdownView = TextView(ctx).apply {
                text = getString(R.string.breakout_pending_time_remaining, formatted)
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setTextColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
                setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
            }
            breakoutCountdownTextView = countdownView
            breakoutBox.addView(countdownView)

            val actionRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val cancelBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.btn_cancel_breakout)
                textSize = 12f
                cornerRadius = (10 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(0, (38 * density).toInt(), 1f)
                setOnClickListener {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastCancelBreakoutClickTime < 1000L) return@setOnClickListener
                    lastCancelBreakoutClickTime = now
                    handleCancelBreakout(this)
                }
            }
            actionRow.addView(cancelBtn)

            val completeBtn = MaterialButton(ctx).apply {
                text = getString(R.string.btn_complete_breakout)
                textSize = 12f
                cornerRadius = (10 * density).toInt()
                visibility = if (remainingMs <= 0L) View.VISIBLE else View.GONE
                layoutParams = LinearLayout.LayoutParams(0, (38 * density).toInt(), 1f).apply {
                    marginStart = (8 * density).toInt()
                }
                setOnClickListener {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastCompleteBreakoutClickTime < 1000L) return@setOnClickListener
                    lastCompleteBreakoutClickTime = now
                    handleCompleteBreakout(this)
                }
            }
            breakoutCompleteBtn = completeBtn
            actionRow.addView(completeBtn)

            breakoutBox.addView(actionRow)
            container.addView(breakoutBox)
        } else {
            // Button to initiate emergency breakout
            val initiateBreakoutBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.btn_request_unlink)
                textSize = 12f
                cornerRadius = (10 * density).toInt()
                setIconResource(R.drawable.ic_lock)
                iconSize = (16 * density).toInt()
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (38 * density).toInt()
                ).apply {
                    topMargin = (12 * density).toInt()
                }
                setOnClickListener {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastBreakoutClickTime < 1000L) return@setOnClickListener
                    lastBreakoutClickTime = now
                    showEmergencyBreakoutDialog()
                }
            }
            container.addView(initiateBreakoutBtn)
        }

        card.addView(container)
        return card
    }

    private fun renderWards(wards: List<PartnerEntity>) {
        val container = _binding?.layoutWardsList ?: return
        val emptyText = _binding?.tvEmptyWards ?: return

        // Fast-path: Avoid destroying and recreating cards on every 1-second state tick
        if (lastRenderedWards == wards && container.childCount > 0) {
            return
        }
        lastRenderedWards = wards

        container.removeAllViews()
        if (wards.isEmpty()) {
            container.addView(emptyText)
        } else {
            for (ward in wards) {
                val card = createWardCard(ward) ?: continue
                container.addView(card)
            }
        }
    }

    private fun createWardCard(partner: PartnerEntity): View? {
        val ctx = context ?: return null
        val density = resources.displayMetrics.density

        val card = MaterialCardView(ctx).apply {
            radius = density * 18
            cardElevation = 0f
            strokeWidth = (1 * density).toInt()
            strokeColor = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)
            setCardBackgroundColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceContainer, Color.WHITE))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val nameRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = (44 * density).toInt()
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

        val editIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_edit)
            val iconSize = (16 * density).toInt()
            val marginSide = (8 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                setMargins(marginSide, 0, marginSide, 0)
            }
            setColorFilter(MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, Color.DKGRAY))
            contentDescription = getString(R.string.btn_edit)
        }
        nameRow.addView(editIcon)
        container.addView(nameRow)

        val codeView = TextView(ctx).apply {
            text = getString(R.string.partner_pairing_code_label, partner.pairingCode)
            textSize = 12f
            setTextColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutline, Color.GRAY))
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        container.addView(codeView)

        val statusPill = TextView(ctx).apply {
            textSize = 11f
            setPadding(0, (2 * density).toInt(), 0, 0)
            visibility = View.GONE
        }
        container.addView(statusPill)

        viewLifecycleOwner.lifecycleScope.launch {
            val wardPolicy = viewModel.fetchWardPolicy(partner.id)
            if (!isAdded || _binding == null || wardPolicy == null) return@launch
            statusPill.visibility = View.VISIBLE
            if (wardPolicy.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING) {
                statusPill.text = getString(R.string.guardian_breakout_alert_title)
                statusPill.setTextColor(ContextCompat.getColor(ctx, R.color.status_orange))
            } else if (wardPolicy.lastHeartbeat > 0L) {
                val diff = TrustedTimeProvider.currentTimeMillis(ctx) - wardPolicy.lastHeartbeat
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

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, 0)
        }

        val manageBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.btn_manage_ward)
            textSize = 12f
            cornerRadius = (8 * density).toInt()
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
                    viewModel.revokeSupervision(partner.id) { result ->
                        if (!isAdded || _binding == null) return@revokeSupervision
                        deleteBtn.isEnabled = true
                        result.fold(
                            onSuccess = {
                                Toast.makeText(context, R.string.toast_supervision_revoked, Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { err ->
                                Toast.makeText(
                                    context,
                                    getString(R.string.toast_partner_delete_failed, err.localizedMessage ?: getString(R.string.error_unknown)),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                }
                .setNegativeButton(R.string.dialog_btn_cancel, null)
                .show()
        }
        btnRow.addView(deleteBtn)
        container.addView(btnRow)

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
        binding.btnAddPartner.isEnabled = false
        viewModel.pairWithCode(code, role) { result ->
            if (!isAdded || _binding == null) return@pairWithCode
            binding.btnAddPartner.isEnabled = true
            isPairingInProgress = false
            result.fold(
                onSuccess = {
                    Toast.makeText(context, R.string.toast_partner_added, Toast.LENGTH_LONG).show()
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
                    viewModel.renamePartner(partner.id, newName) { result ->
                        if (!isAdded || _binding == null) return@renamePartner
                        result.onSuccess {
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
        super.onDestroyView()
        breakoutCountdownTextView = null
        breakoutCompleteBtn = null
        lastRenderedGuardians = emptyList()
        lastRenderedWards = emptyList()
        lastBreakoutPendingState = null
        _binding = null
    }

    companion object {
        private const val TAG = "AntiPorn_AccFrag"
    }
}
