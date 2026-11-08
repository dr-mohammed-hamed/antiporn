package com.drmohammed.antiporn.ui.control.web

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import com.drmohammed.antiporn.core.util.BrowserUrlSanitizer
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Sub-Screen for Web Filter, DNS, and Browsing Restrictions.
 * Replaces the cluttered radio-button browser filter and integrates Telegram web block & DNS presets.
 */
class WebFilterSettingsActivity : AppCompatActivity() {

    private var isRemote = false
    private var wardId: String = ""
    private lateinit var initialPolicy: DevicePolicyBundle

    private var currentDnsHost = ""
    private var isIncognitoBlocked = true
    private var isTelegramWebBlocked = true
    private val blockedUrls = mutableSetOf<String>()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tilDnsHost: TextInputLayout
    private lateinit var etDnsHost: TextInputEditText
    private lateinit var btnPresetControlD: MaterialButton
    private lateinit var btnPresetAdguard: MaterialButton
    private lateinit var btnPresetCloudflare: MaterialButton
    private lateinit var btnPresetNextDns: MaterialButton
    private lateinit var switchBlockIncognito: MaterialSwitch
    private lateinit var switchBlockTelegramWeb: MaterialSwitch
    private lateinit var btnAddBlockedUrl: MaterialButton
    private lateinit var tvEmptyBlockedUrls: TextView
    private lateinit var layoutBlockedUrlsList: LinearLayout
    private lateinit var btnSaveWebFilterChanges: MaterialButton

    private var isSaving = false

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (hasUnsavedChanges()) {
                showDiscardConfirmationDialog()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_filter_settings)

        applyWindowInsets()

        isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        wardId = intent.getStringExtra(EXTRA_WARD_ID).orEmpty()
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)

        if (!isRemote && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initialPolicy = if (isRemote && !policyJson.isNullOrBlank()) {
            DevicePolicyBundle.fromJson(policyJson)
        } else {
            PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        initViews()
        bindInitialPolicy()
        setupListeners()
        renderBlockedUrls()
        updateSaveButtonState()
    }

    override fun onResume() {
        super.onResume()
        if (!isRemote && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun applyWindowInsets() {
        findViewById<View>(R.id.layoutRootWebFilterSettings)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarWebFilter)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        tilDnsHost = findViewById(R.id.tilDnsHost)
        etDnsHost = findViewById(R.id.etDnsHost)
        btnPresetControlD = findViewById(R.id.btnPresetControlD)
        btnPresetAdguard = findViewById(R.id.btnPresetAdguard)
        btnPresetCloudflare = findViewById(R.id.btnPresetCloudflare)
        btnPresetNextDns = findViewById(R.id.btnPresetNextDns)
        switchBlockIncognito = findViewById(R.id.switchBlockIncognito)
        switchBlockTelegramWeb = findViewById(R.id.switchBlockTelegramWeb)
        btnAddBlockedUrl = findViewById(R.id.btnAddBlockedUrl)
        tvEmptyBlockedUrls = findViewById(R.id.tvEmptyBlockedUrls)
        layoutBlockedUrlsList = findViewById(R.id.layoutBlockedUrlsList)
        btnSaveWebFilterChanges = findViewById(R.id.btnSaveWebFilterChanges)
    }

    private fun bindInitialPolicy() {
        currentDnsHost = initialPolicy.dnsHost
        isIncognitoBlocked = initialPolicy.isIncognitoBlocked
        isTelegramWebBlocked = initialPolicy.isTelegramWebBlocked
        blockedUrls.clear()
        blockedUrls.addAll(initialPolicy.customBlockedUrls)

        etDnsHost.setText(currentDnsHost)
        switchBlockIncognito.isChecked = isIncognitoBlocked
        switchBlockTelegramWeb.isChecked = isTelegramWebBlocked
    }

    private fun setupListeners() {
        etDnsHost.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentDnsHost = s?.toString()?.trim().orEmpty()
                updateSaveButtonState()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnPresetControlD.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST)
        }
        btnPresetAdguard.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST)
        }
        btnPresetCloudflare.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST)
        }
        btnPresetNextDns.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.LEGACY_DEFAULT_NEXTDNS_HOST)
        }

        switchBlockIncognito.setOnCheckedChangeListener { _, isChecked ->
            isIncognitoBlocked = isChecked
            updateSaveButtonState()
        }

        switchBlockTelegramWeb.setOnCheckedChangeListener { _, isChecked ->
            isTelegramWebBlocked = isChecked
            updateSaveButtonState()
        }

        btnAddBlockedUrl.setOnClickListener {
            showAddUrlDialog()
        }

        btnSaveWebFilterChanges.setOnClickListener {
            saveChanges()
        }
    }

    private fun showAddUrlDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.dialog_add_url_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val frame = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_add_url_title)
            .setView(frame)
            .setPositiveButton(R.string.browser_filter_btn_add) { dialog, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                val sanitized = BrowserUrlSanitizer.sanitize(raw)
                if (sanitized != null) {
                    if (!blockedUrls.contains(sanitized)) {
                        blockedUrls.add(sanitized)
                        renderBlockedUrls()
                        updateSaveButtonState()
                    } else {
                        Toast.makeText(this, R.string.browser_filter_error_duplicate_url, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, R.string.browser_filter_error_invalid_url, Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderBlockedUrls() {
        layoutBlockedUrlsList.removeAllViews()
        if (blockedUrls.isEmpty()) {
            tvEmptyBlockedUrls.visibility = View.VISIBLE
            return
        }
        tvEmptyBlockedUrls.visibility = View.GONE

        val density = resources.displayMetrics.density
        for (url in blockedUrls) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            }

            val tvUrl = TextView(this).apply {
                text = url
                textSize = 13f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvUrl)

            val ivDelete = ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                val iconSize = (20 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                setColorFilter(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
                contentDescription = getString(R.string.browser_filter_item_delete_cd)
                setOnClickListener {
                    blockedUrls.remove(url)
                    renderBlockedUrls()
                    updateSaveButtonState()
                }
            }
            row.addView(ivDelete)

            layoutBlockedUrlsList.addView(row)
        }
    }

    private fun hasUnsavedChanges(): Boolean {
        val cleanInputDns = currentDnsHost.ifBlank { DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST }
        return cleanInputDns != initialPolicy.dnsHost ||
                isIncognitoBlocked != initialPolicy.isIncognitoBlocked ||
                isTelegramWebBlocked != initialPolicy.isTelegramWebBlocked ||
                blockedUrls != initialPolicy.customBlockedUrls
    }

    private fun updateSaveButtonState() {
        val dirty = hasUnsavedChanges()
        btnSaveWebFilterChanges.isEnabled = dirty && !isSaving
        btnSaveWebFilterChanges.alpha = if (dirty && !isSaving) 1.0f else 0.5f
        backPressedCallback.isEnabled = dirty
    }

    private fun saveChanges() {
        if (isSaving || !hasUnsavedChanges()) return
        isSaving = true
        updateSaveButtonState()

        val dnsToSave = currentDnsHost.ifBlank { DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val validationResult = DnsFilteringValidator.validateDnsHost(dnsToSave)
                if (validationResult is DnsFilteringValidator.ValidationResult.UnfilteredPorn) {
                    withContext(Dispatchers.Main) {
                        isSaving = false
                        updateSaveButtonState()
                        tilDnsHost.error = getString(R.string.dns_probe_error_not_blocking)
                        etDnsHost.requestFocus()
                        MaterialAlertDialogBuilder(this@WebFilterSettingsActivity)
                            .setTitle(R.string.dns_probe_dialog_failed_title)
                            .setMessage(getString(R.string.dns_probe_error_not_blocking_detail, validationResult.domain, validationResult.resolvedIp))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    return@launch
                }

                val filterMode = if (blockedUrls.isNotEmpty()) DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST else DevicePolicyBundle.BROWSER_FILTER_OFF

                val updatedPolicy = initialPolicy.copy(
                    dnsHost = dnsToSave,
                    isIncognitoBlocked = isIncognitoBlocked,
                    isTelegramWebBlocked = isTelegramWebBlocked,
                    customBlockedUrls = blockedUrls,
                    browserFilterMode = filterMode
                )

                val app = applicationContext
                val policyRepo = PolicyRepository.getInstance(app)

                val success = if (isRemote && wardId.isNotBlank()) {
                    policyRepo.pushPolicyToWard(wardId, updatedPolicy)
                } else {
                    val saved = policyRepo.saveAndEnforceSelfPolicy(updatedPolicy)
                    if (saved) {
                        withContext(Dispatchers.Main) {
                            ProtectionEngine.applyAllAsync(app)
                        }
                    }
                    saved
                }

                withContext(Dispatchers.Main) {
                    isSaving = false
                    if (success) {
                        Toast.makeText(this@WebFilterSettingsActivity, R.string.toast_self_policy_saved, Toast.LENGTH_SHORT).show()
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_UPDATED_POLICY_JSON, updatedPolicy.toJson())
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        backPressedCallback.isEnabled = false
                        finish()
                    } else {
                        updateSaveButtonState()
                        Toast.makeText(this@WebFilterSettingsActivity, R.string.toast_self_policy_save_failed, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("WebFilterSettings", "Error saving web filter settings", e)
                withContext(Dispatchers.Main) {
                    isSaving = false
                    updateSaveButtonState()
                    Toast.makeText(this@WebFilterSettingsActivity, R.string.toast_self_policy_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showDiscardConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_unsaved_changes_title)
            .setMessage(R.string.dialog_unsaved_changes_msg)
            .setNegativeButton(R.string.btn_keep_editing) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton(R.string.btn_discard_changes) { dialog, _ ->
                dialog.dismiss()
                backPressedCallback.isEnabled = false
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        const val EXTRA_UPDATED_POLICY_JSON = "extra_updated_policy_json"
    }
}
