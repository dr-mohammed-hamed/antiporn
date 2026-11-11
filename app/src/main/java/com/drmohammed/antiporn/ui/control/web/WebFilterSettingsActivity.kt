package com.drmohammed.antiporn.ui.control.web

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
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
import com.google.android.material.button.MaterialButtonToggleGroup
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
 * Features ultra-minimalist Material 3 UI with Segmented Buttons for Blocked/Allowed lists,
 * zero visual clutter, strict Whitelist Only mode, and full-system VPN filtering scope.
 */
class WebFilterSettingsActivity : AppCompatActivity() {

    private enum class UrlTab { BLOCKED, ALLOWED }

    private var isRemote = false
    private var wardId: String = ""
    private lateinit var initialPolicy: DevicePolicyBundle

    private var currentDnsHost = ""
    private var isIncognitoBlocked = true
    private var isTelegramWebBlocked = true
    private var isYouTubeRestricted = false
    private var isFullSystemFiltering = false
    private var isWhitelistOnlyMode = false
    private val blockedUrls = mutableSetOf<String>()
    private val allowedUrls = mutableSetOf<String>()
    private var currentTab = UrlTab.BLOCKED

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tilDnsHost: TextInputLayout
    private lateinit var etDnsHost: TextInputEditText
    private lateinit var rowDnsCloudflare: View
    private lateinit var rbDnsCloudflare: RadioButton
    private lateinit var rowDnsControlD: View
    private lateinit var rbDnsControlD: RadioButton
    private lateinit var rowDnsAdguard: View
    private lateinit var rbDnsAdguard: RadioButton
    private var isProgrammaticDnsChange = false

    private lateinit var switchBlockIncognito: MaterialSwitch
    private lateinit var switchBlockTelegramWeb: MaterialSwitch
    private lateinit var switchYouTubeRestricted: MaterialSwitch
    private lateinit var switchFullSystemFilter: MaterialSwitch

    private lateinit var toggleGroupUrlType: MaterialButtonToggleGroup
    private lateinit var btnTabBlocked: MaterialButton
    private lateinit var btnTabAllowed: MaterialButton
    private lateinit var layoutWhitelistOnlyMaster: View
    private lateinit var switchWhitelistOnly: MaterialSwitch

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
        updateTabUi()
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
        rowDnsCloudflare = findViewById(R.id.rowDnsCloudflare)
        rbDnsCloudflare = findViewById(R.id.rbDnsCloudflare)
        rowDnsControlD = findViewById(R.id.rowDnsControlD)
        rbDnsControlD = findViewById(R.id.rbDnsControlD)
        rowDnsAdguard = findViewById(R.id.rowDnsAdguard)
        rbDnsAdguard = findViewById(R.id.rbDnsAdguard)

        switchBlockIncognito = findViewById(R.id.switchBlockIncognito)
        switchBlockTelegramWeb = findViewById(R.id.switchBlockTelegramWeb)
        switchYouTubeRestricted = findViewById(R.id.switchYouTubeRestricted)
        switchFullSystemFilter = findViewById(R.id.switchFullSystemFilter)

        toggleGroupUrlType = findViewById(R.id.toggleGroupUrlType)
        btnTabBlocked = findViewById(R.id.btnTabBlocked)
        btnTabAllowed = findViewById(R.id.btnTabAllowed)
        layoutWhitelistOnlyMaster = findViewById(R.id.layoutWhitelistOnlyMaster)
        switchWhitelistOnly = findViewById(R.id.switchWhitelistOnly)

        btnAddBlockedUrl = findViewById(R.id.btnAddBlockedUrl)
        tvEmptyBlockedUrls = findViewById(R.id.tvEmptyBlockedUrls)
        layoutBlockedUrlsList = findViewById(R.id.layoutBlockedUrlsList)
        btnSaveWebFilterChanges = findViewById(R.id.btnSaveWebFilterChanges)
    }

    private fun bindInitialPolicy() {
        currentDnsHost = initialPolicy.dnsHost
        isIncognitoBlocked = initialPolicy.isIncognitoBlocked
        isTelegramWebBlocked = initialPolicy.isTelegramWebBlocked
        isYouTubeRestricted = initialPolicy.isYouTubeRestrictedModeEnabled
        isFullSystemFiltering = initialPolicy.isFullSystemFilteringEnabled
        isWhitelistOnlyMode = initialPolicy.browserFilterMode == DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY

        blockedUrls.clear()
        blockedUrls.addAll(initialPolicy.customBlockedUrls)
        allowedUrls.clear()
        allowedUrls.addAll(initialPolicy.customAllowedUrls)

        isProgrammaticDnsChange = true
        etDnsHost.setText(currentDnsHost)
        isProgrammaticDnsChange = false
        updateTableSelection(currentDnsHost)

        switchBlockIncognito.isChecked = isIncognitoBlocked
        switchBlockTelegramWeb.isChecked = isTelegramWebBlocked
        switchYouTubeRestricted.isChecked = isYouTubeRestricted
        switchFullSystemFilter.isChecked = isFullSystemFiltering
        switchWhitelistOnly.isChecked = isWhitelistOnlyMode

        if (isWhitelistOnlyMode && blockedUrls.isEmpty() && allowedUrls.isNotEmpty()) {
            currentTab = UrlTab.ALLOWED
            toggleGroupUrlType.check(R.id.btnTabAllowed)
        } else {
            currentTab = UrlTab.BLOCKED
            toggleGroupUrlType.check(R.id.btnTabBlocked)
        }
    }

    private fun updateTableSelection(host: String) {
        val clean = host.trim().lowercase()
        val isCloudflare = clean == DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST.lowercase()
        val isControlD = clean == DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST.lowercase() ||
                clean == DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST.lowercase()
        val isAdguard = clean == DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST.lowercase()

        rbDnsCloudflare.isChecked = isCloudflare
        rbDnsControlD.isChecked = isControlD
        rbDnsAdguard.isChecked = isAdguard

        rowDnsCloudflare.isActivated = isCloudflare
        rowDnsControlD.isActivated = isControlD
        rowDnsAdguard.isActivated = isAdguard
    }

    private fun setupListeners() {
        val selectPreset = { host: String ->
            isProgrammaticDnsChange = true
            etDnsHost.setText(host)
            etDnsHost.setSelection(host.length)
            isProgrammaticDnsChange = false
            updateTableSelection(host)
            currentDnsHost = host
            updateSaveButtonState()
        }

        rowDnsCloudflare.setOnClickListener {
            selectPreset(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST)
        }
        rowDnsControlD.setOnClickListener {
            selectPreset(DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST)
        }
        rowDnsAdguard.setOnClickListener {
            selectPreset(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST)
        }

        etDnsHost.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentDnsHost = s?.toString()?.trim().orEmpty()
                if (!isProgrammaticDnsChange) {
                    updateTableSelection(currentDnsHost)
                }
                updateSaveButtonState()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        switchBlockIncognito.setOnCheckedChangeListener { _, isChecked ->
            isIncognitoBlocked = isChecked
            updateSaveButtonState()
        }

        switchBlockTelegramWeb.setOnCheckedChangeListener { _, isChecked ->
            isTelegramWebBlocked = isChecked
            updateSaveButtonState()
        }

        switchYouTubeRestricted.setOnCheckedChangeListener { _, isChecked ->
            isYouTubeRestricted = isChecked
            updateSaveButtonState()
        }

        switchFullSystemFilter.setOnCheckedChangeListener { _, isChecked ->
            isFullSystemFiltering = isChecked
            updateSaveButtonState()
        }

        switchWhitelistOnly.setOnCheckedChangeListener { _, isChecked ->
            isWhitelistOnlyMode = isChecked
            updateSaveButtonState()
        }

        toggleGroupUrlType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentTab = if (checkedId == R.id.btnTabAllowed) UrlTab.ALLOWED else UrlTab.BLOCKED
            updateTabUi()
        }

        btnAddBlockedUrl.setOnClickListener {
            showAddUrlDialog()
        }

        btnSaveWebFilterChanges.setOnClickListener {
            saveChanges()
        }
    }

    private fun updateTabUi() {
        btnTabBlocked.text = getString(R.string.tab_blocked_websites, blockedUrls.size)
        btnTabAllowed.text = getString(R.string.tab_allowed_websites, allowedUrls.size)

        if (currentTab == UrlTab.ALLOWED) {
            layoutWhitelistOnlyMaster.visibility = View.VISIBLE
        } else {
            layoutWhitelistOnlyMaster.visibility = View.GONE
        }
        renderUrlsList()
    }

    private fun showAddUrlDialog() {
        val density = resources.displayMetrics.density
        val til = TextInputLayout(this).apply {
            hint = getString(R.string.dialog_add_url_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(12f * density, 12f * density, 12f * density, 12f * density)
        }
        val input = TextInputEditText(til.context).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.tajawal)
        }
        til.addView(input)

        val frame = FrameLayout(this).apply {
            val padHorizontal = (20 * density).toInt()
            val padTop = (8 * density).toInt()
            setPadding(padHorizontal, padTop, padHorizontal, 0)
            addView(til)
        }

        val titleRes = if (currentTab == UrlTab.ALLOWED) R.string.dialog_add_allowed_title else R.string.dialog_add_url_title
        val targetSet = if (currentTab == UrlTab.ALLOWED) allowedUrls else blockedUrls

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(frame)
            .setPositiveButton(R.string.browser_filter_btn_add) { dialog, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                val sanitized = BrowserUrlSanitizer.sanitize(raw)
                if (sanitized != null) {
                    if (!targetSet.contains(sanitized)) {
                        targetSet.add(sanitized)
                        // Ensure mutual exclusivity between blocked and allowed sets to prevent DNS vs Browser rule conflicts
                        if (targetSet === allowedUrls) {
                            blockedUrls.remove(sanitized)
                        } else {
                            allowedUrls.remove(sanitized)
                        }
                        updateTabUi()
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
            .create()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.performClick()
                true
            } else {
                false
            }
        }

        dialog.show()
    }

    private fun renderUrlsList() {
        layoutBlockedUrlsList.removeAllViews()
        val currentList = if (currentTab == UrlTab.ALLOWED) allowedUrls else blockedUrls

        if (currentList.isEmpty()) {
            tvEmptyBlockedUrls.text = if (currentTab == UrlTab.ALLOWED) {
                getString(R.string.empty_allowed_urls)
            } else {
                getString(R.string.empty_blocked_urls)
            }
            tvEmptyBlockedUrls.visibility = View.VISIBLE
            return
        }
        tvEmptyBlockedUrls.visibility = View.GONE

        val density = resources.displayMetrics.density
        val tajawalTypeface = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.tajawal)

        for (url in currentList.sorted()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            }

            val tvUrl = TextView(this).apply {
                text = url
                textSize = 13f
                typeface = tajawalTypeface
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvUrl)

            // Touch target size 48x48dp with ripple to strictly satisfy mobile accessibility standards
            val ivDelete = ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                val targetSize = (48 * density).toInt()
                val pad = (14 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(targetSize, targetSize)
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setColorFilter(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
                contentDescription = getString(R.string.browser_filter_item_delete_cd)
                setOnClickListener {
                    currentList.remove(url)
                    updateTabUi()
                    updateSaveButtonState()
                }
            }
            row.addView(ivDelete)

            layoutBlockedUrlsList.addView(row)
        }
    }

    private fun hasUnsavedChanges(): Boolean {
        val cleanInputDns = currentDnsHost.ifBlank { DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST }
        val initialWasWhitelist = initialPolicy.browserFilterMode == DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY
        return cleanInputDns != initialPolicy.dnsHost ||
                isIncognitoBlocked != initialPolicy.isIncognitoBlocked ||
                isTelegramWebBlocked != initialPolicy.isTelegramWebBlocked ||
                isYouTubeRestricted != initialPolicy.isYouTubeRestrictedModeEnabled ||
                isFullSystemFiltering != initialPolicy.isFullSystemFilteringEnabled ||
                isWhitelistOnlyMode != initialWasWhitelist ||
                blockedUrls != initialPolicy.customBlockedUrls ||
                allowedUrls != initialPolicy.customAllowedUrls
    }

    private fun updateSaveButtonState() {
        val dirty = hasUnsavedChanges()
        btnSaveWebFilterChanges.isEnabled = dirty && !isSaving
        btnSaveWebFilterChanges.alpha = if (dirty && !isSaving) 1.0f else 0.5f
        backPressedCallback.isEnabled = dirty
    }

    private fun saveChanges() {
        if (isSaving || !hasUnsavedChanges()) return

        // Guard against locking out connectivity when Whitelist Only is selected without allowed domains
        if (isWhitelistOnlyMode && allowedUrls.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_empty_whitelist_title)
                .setMessage(R.string.dialog_empty_whitelist_desc)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        isSaving = true
        updateSaveButtonState()

        val dnsToSave = currentDnsHost.ifBlank { DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val validationResult = DnsFilteringValidator.validateDnsHost(dnsToSave)
                if (validationResult is DnsFilteringValidator.ValidationResult.UnfilteredPorn) {
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
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

                // If full system filtering or custom blocked/allowed lists are active, ensure filterMode enables the filtering pipeline
                val filterMode = when {
                    isWhitelistOnlyMode -> DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY
                    isFullSystemFiltering || blockedUrls.isNotEmpty() || allowedUrls.isNotEmpty() -> DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST
                    else -> DevicePolicyBundle.BROWSER_FILTER_OFF
                }

                val updatedPolicy = initialPolicy.copy(
                    dnsHost = dnsToSave,
                    isIncognitoBlocked = isIncognitoBlocked,
                    isTelegramWebBlocked = isTelegramWebBlocked,
                    isYouTubeRestrictedModeEnabled = isYouTubeRestricted,
                    isFullSystemFilteringEnabled = isFullSystemFiltering,
                    customBlockedUrls = blockedUrls,
                    customAllowedUrls = allowedUrls,
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
                            if (!isFinishing && !isDestroyed) {
                                ProtectionEngine.applyAllAsync(app)
                            }
                        }
                    }
                    saved
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
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
                    if (isFinishing || isDestroyed) return@withContext
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
