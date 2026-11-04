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
 * Accountability Hub Fragment embedded in the main navigation hub.
 * Presents pairing codes, protected wards, guardians, and emergency breakout grace periods.
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

        // 1. Display Name
        if (state.myDisplayName.isNotBlank()) {
            binding.tvMyDisplayName.text = state.myDisplayName
        }

        // 2. Account Security
        if (state.isGoogleLinked && !state.googleAccountEmail.isNullOrBlank()) {
            binding.tvAccountSecurityStatus.text = getString(R.string.account_secured_google_email, state.googleAccountEmail)
            binding.btnSecureAccount.text = getString(R.string.account_secured_google)
            binding.btnSecureAccount.isEnabled = false
        } else {
            binding.tvAccountSecurityStatus.text = getString(R.string.account_unsecured_warning)
            binding.btnSecureAccount.text = getString(R.string.btn_secure_with_google)
            binding.btnSecureAccount.isEnabled = true
        }

        // 3. Supervised & Breakout Mode
        if (state.isSupervised) {
            binding.cardSupervisedBanner.visibility = View.VISIBLE
            binding.cardMyPairingCode.visibility = View.GONE

            if (state.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING && state.breakoutUnlockAt != null) {
                binding.cardEmergencyBreakout.visibility = View.VISIBLE
                binding.btnInitiateBreakout.visibility = View.GONE

                val remainingMs = state.breakoutRemainingMs
                val hours = remainingMs / (1000 * 3600)
                val mins = (remainingMs % (1000 * 3600)) / (1000 * 60)
                val secs = (remainingMs % (1000 * 60)) / 1000
                val formatted = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
                binding.tvBreakoutCountdown.text = getString(R.string.breakout_pending_time_remaining, formatted)

                binding.btnCompleteBreakout.visibility = if (remainingMs <= 0L) View.VISIBLE else View.GONE
            } else {
                binding.cardEmergencyBreakout.visibility = View.GONE
                binding.btnInitiateBreakout.visibility = View.VISIBLE
            }
        } else {
            binding.cardSupervisedBanner.visibility = View.GONE
            binding.cardEmergencyBreakout.visibility = View.GONE
            binding.btnInitiateBreakout.visibility = View.GONE
            binding.cardMyPairingCode.visibility = View.VISIBLE

            // 4. Pairing Code
            if (state.isPairingCodeLocked || state.myPairingCode == AccountabilityRepository.CODE_LOCKED) {
                binding.cardSupervisedBanner.visibility = View.VISIBLE
                binding.cardMyPairingCode.visibility = View.GONE
            } else {
                binding.tvMyPairingCode.text = state.myPairingCode.ifEmpty { "------" }
                binding.btnCopyCode.visibility = if (state.myPairingCode.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

        // 5. Partners
        renderWards(state.wards)
        renderGuardians(state.guardians)
    }

    private fun setupListeners() {
        binding.btnSecureAccount.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSecureAccountClickTime < 1000L) return@setOnClickListener
            lastSecureAccountClickTime = now
            binding.btnSecureAccount.isEnabled = false
            handleGoogleSecurityLinking()
        }

        binding.btnInitiateBreakout.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBreakoutClickTime < 1000L) return@setOnClickListener
            lastBreakoutClickTime = now
            showEmergencyBreakoutDialog()
        }

        binding.btnCancelBreakout.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCancelBreakoutClickTime < 1000L) return@setOnClickListener
            lastCancelBreakoutClickTime = now
            binding.btnCancelBreakout.isEnabled = false
            handleCancelBreakout()
        }

        binding.btnCompleteBreakout.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCompleteBreakoutClickTime < 1000L) return@setOnClickListener
            lastCompleteBreakoutClickTime = now
            binding.btnCompleteBreakout.isEnabled = false
            handleCompleteBreakout()
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

        binding.btnEditMyName.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEditNameClickTime < 800L) return@setOnClickListener
            lastEditNameClickTime = now
            showEditMyNameDialog()
        }

        binding.btnHubOpenSelfControl.setOnClickListener {
            navListener?.onNavigateToSelfControl() ?: (activity as? com.drmohammed.antiporn.MainActivity)?.onNavigateToSelfControl()
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
                binding.btnInitiateBreakout.isEnabled = false
                viewModel.requestEmergencyBreakout { result ->
                    if (!isAdded || _binding == null) return@requestEmergencyBreakout
                    binding.btnInitiateBreakout.isEnabled = true
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

    private fun handleCancelBreakout() {
        viewModel.cancelEmergencyBreakout { result ->
            if (!isAdded || _binding == null) return@cancelEmergencyBreakout
            binding.btnCancelBreakout.isEnabled = true
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

    private fun handleCompleteBreakout() {
        viewModel.executeEmergencyBreakout { result ->
            if (!isAdded || _binding == null) return@executeEmergencyBreakout
            binding.btnCompleteBreakout.isEnabled = true
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

    private fun renderWards(wards: List<PartnerEntity>) {
        val container = _binding?.layoutWardsList ?: return
        val emptyText = _binding?.tvEmptyWards ?: return
        container.removeAllViews()
        if (wards.isEmpty()) {
            container.addView(emptyText)
        } else {
            for (ward in wards) {
                val card = createPartnerCard(ward, isWard = true) ?: continue
                container.addView(card)
            }
        }
    }

    private fun renderGuardians(guardians: List<PartnerEntity>) {
        val container = _binding?.layoutGuardiansList ?: return
        val emptyText = _binding?.tvEmptyGuardians ?: return
        container.removeAllViews()
        if (guardians.isEmpty()) {
            container.addView(emptyText)
        } else {
            for (guardian in guardians) {
                val card = createPartnerCard(guardian, isWard = false) ?: continue
                container.addView(card)
            }
        }
    }

    private fun createPartnerCard(partner: PartnerEntity, isWard: Boolean): View? {
        val ctx = context ?: return null
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

    private fun showEditMyNameDialog() {
        val ctx = context ?: return
        val currentName = viewModel.uiState.value.myDisplayName
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
                    viewModel.setMyDisplayName(newName) { result ->
                        if (!isAdded || _binding == null) return@setMyDisplayName
                        result.onSuccess {
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
        _binding = null
    }

    companion object {
        private const val TAG = "AntiPorn_AccFrag"
    }
}
