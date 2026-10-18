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
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main Accountability Hub activity allowing users to view their unique pairing code,
 * view protected wards, view protecting guardians, and pair with new accountability partners.
 */
class AccountabilityHubActivity : AppCompatActivity() {

    private lateinit var accountabilityRepo: AccountabilityRepository

    private lateinit var tvMyPairingCode: TextView
    private lateinit var btnCopyCode: MaterialButton
    private lateinit var btnAddPartner: MaterialButton
    private lateinit var layoutWardsList: LinearLayout
    private lateinit var tvEmptyWards: TextView
    private lateinit var layoutGuardiansList: LinearLayout
    private lateinit var tvEmptyGuardians: TextView

    private var lastAddPartnerClickTime = 0L
    private var lastManageClickTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accountability_hub)

        accountabilityRepo = AccountabilityRepository.getInstance(applicationContext)

        initViews()
        setupListeners()
        loadPairingCode()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        loadPartners()
    }

    private fun initViews() {
        tvMyPairingCode = findViewById(R.id.tvMyPairingCode)
        btnCopyCode = findViewById(R.id.btnCopyCode)
        btnAddPartner = findViewById(R.id.btnAddPartner)
        layoutWardsList = findViewById(R.id.layoutWardsList)
        tvEmptyWards = findViewById(R.id.tvEmptyWards)
        layoutGuardiansList = findViewById(R.id.layoutGuardiansList)
        tvEmptyGuardians = findViewById(R.id.tvEmptyGuardians)
    }

    private fun setupListeners() {
        btnCopyCode.setOnClickListener {
            val code = tvMyPairingCode.text.toString()
            if (code.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("Pairing Code", code)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, R.string.toast_code_copied, Toast.LENGTH_SHORT).show()
            }
        }

        btnAddPartner.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAddPartnerClickTime < 800L) return@setOnClickListener
            lastAddPartnerClickTime = now
            showAddPartnerDialog()
        }
    }

    private fun loadPairingCode() {
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            try {
                val code = accountabilityRepo.getOrCreateMyPairingCode()
                if (isFinishing || isDestroyed) return@launch
                tvMyPairingCode.text = code
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
                val wards = accountabilityRepo.getWards()
                val guardians = accountabilityRepo.getGuardians()

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    renderWards(wards)
                    renderGuardians(guardians)
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
            radius = resources.displayMetrics.density * 12
            cardElevation = resources.displayMetrics.density * 1
            setCardBackgroundColor(getColor(R.color.card_bg))
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

        val nameView = TextView(this).apply {
            text = partner.displayName
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(nameView)

        val codeView = TextView(this).apply {
            text = getString(R.string.partner_pairing_code_label, partner.pairingCode)
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        container.addView(codeView)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        if (isWard) {
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
        }

        val deleteBtn = MaterialButton(this)
        deleteBtn.text = getString(R.string.btn_delete_partner)
        deleteBtn.textSize = 12f
        deleteBtn.setTextColor(getColor(R.color.status_orange))
        deleteBtn.strokeWidth = 0
        deleteBtn.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        deleteBtn.setOnClickListener {
            if (!isWard) {
                AlertDialog.Builder(this@AccountabilityHubActivity)
                    .setTitle(R.string.dialog_cannot_delete_guardian_title)
                    .setMessage(R.string.dialog_cannot_delete_guardian_msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this@AccountabilityHubActivity)
                .setTitle(R.string.dialog_delete_partner_title)
                .setMessage(getString(R.string.dialog_delete_partner_msg, partner.displayName))
                .setPositiveButton(R.string.btn_delete_partner) { dialog, _ ->
                    dialog.dismiss()
                    deleteBtn.isEnabled = false
                    lifecycleScope.launch {
                        if (isFinishing || isDestroyed) return@launch
                        try {
                            val result = accountabilityRepo.deletePartner(partner.id)
                            if (isFinishing || isDestroyed) return@launch
                            result.fold(
                                onSuccess = {
                                    Toast.makeText(this@AccountabilityHubActivity, R.string.toast_partner_deleted, Toast.LENGTH_SHORT).show()
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
                            Log.e(TAG, "Error deleting partner ${partner.id}", e)
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
        card.addView(container)
        return card
    }

    private fun showAddPartnerDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_partner, null)
        val etCode = dialogView.findViewById<EditText>(R.id.etPartnerCode)
        val rbWard = dialogView.findViewById<RadioButton>(R.id.rbAddAsWard)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_partner_title)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_btn_connect, null)
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val code = etCode.text?.toString()?.trim() ?: ""
            val regex = Regex("^[A-Za-z0-9]{6}$")
            if (!regex.matches(code)) {
                etCode.error = getString(R.string.dialog_add_partner_code_hint)
                Toast.makeText(this, R.string.dialog_add_partner_code_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            etCode.error = null
            dialog.dismiss()
            val role = if (rbWard.isChecked) PartnerEntity.ROLE_WARD else PartnerEntity.ROLE_GUARDIAN
            performPairing(code, role)
        }
    }

    private fun performPairing(code: String, role: String) {
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
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_AccHub"
    }
}
