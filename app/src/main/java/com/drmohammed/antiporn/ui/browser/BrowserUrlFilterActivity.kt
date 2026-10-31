package com.drmohammed.antiporn.ui.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.core.util.BrowserUrlSanitizer
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity for configuring Chromium-based browser URL filtering policies.
 * Supports OFF (default), BLOCKLIST (selective blocking), and WHITELIST (strict kiosk mode).
 * Works for both Self-Control (local enforcement) and Guardian-Control (remote policy sync).
 */
class BrowserUrlFilterActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var rgFilterMode: RadioGroup
    private lateinit var rbModeOff: MaterialRadioButton
    private lateinit var rbModeBlocklist: MaterialRadioButton
    private lateinit var rbModeWhitelist: MaterialRadioButton
    private lateinit var tvModeDescription: TextView

    private lateinit var cardPresets: MaterialCardView
    private lateinit var btnPresetSocial: MaterialButton

    private lateinit var cardAddDomain: MaterialCardView
    private lateinit var tilDomainInput: TextInputLayout
    private lateinit var etDomainInput: TextInputEditText
    private lateinit var btnAddDomain: MaterialButton

    private lateinit var cardUrlList: MaterialCardView
    private lateinit var tvListTitle: TextView
    private lateinit var tvListCount: TextView
    private lateinit var tvEmptyList: TextView
    private lateinit var rvFilteredUrls: RecyclerView

    private lateinit var btnSaveBrowserPolicy: MaterialButton

    private var isRemote: Boolean = false
    private var wardDeviceId: String? = null
    private lateinit var basePolicy: DevicePolicyBundle

    private var selectedMode: Int = DevicePolicyBundle.BROWSER_FILTER_OFF
    private val blockedUrls = mutableListOf<String>()
    private val allowedUrls = mutableListOf<String>()

    private var activeSnackbar: Snackbar? = null
    private var lastAddClickTime = 0L
    private var lastPresetClickTime = 0L
    private var isSavingPolicy = false

    private lateinit var adapter: FilteredUrlAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser_url_filter)

        setupWindowInsets()
        parseIntentData()
        initViews()
        setupListeners()
        populateFromPolicy()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isSavingPolicy) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupWindowInsets() {
        findViewById<View>(R.id.layoutRootBrowserUrlFilter)?.let { rootView ->
            val bottomBarInner = findViewById<View>(R.id.layoutBottomBarBrowserFilterInner)
            val baseBottomPadding = bottomBarInner?.paddingBottom ?: 0
            val baseLeftPadding = bottomBarInner?.paddingLeft ?: 0
            val baseRightPadding = bottomBarInner?.paddingRight ?: 0
            val baseTopPadding = bottomBarInner?.paddingTop ?: 0

            ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
                )
                rootView.setPadding(insets.left, insets.top, insets.right, 0)
                bottomBarInner?.setPadding(
                    baseLeftPadding,
                    baseTopPadding,
                    baseRightPadding,
                    baseBottomPadding + insets.bottom
                )
                windowInsets
            }
        }
    }

    private fun parseIntentData() {
        isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        wardDeviceId = intent.getStringExtra(EXTRA_WARD_DEVICE_ID)

        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)
        basePolicy = if (!policyJson.isNullOrBlank()) {
            DevicePolicyBundle.fromJson(policyJson)
        } else {
            PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
        }

        selectedMode = basePolicy.browserFilterMode
        blockedUrls.clear()
        blockedUrls.addAll(basePolicy.customBlockedUrls.sorted())
        allowedUrls.clear()
        allowedUrls.addAll(basePolicy.customAllowedUrls.sorted())
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarBrowserUrlFilter)
        rgFilterMode = findViewById(R.id.rgFilterMode)
        rbModeOff = findViewById(R.id.rbModeOff)
        rbModeBlocklist = findViewById(R.id.rbModeBlocklist)
        rbModeWhitelist = findViewById(R.id.rbModeWhitelist)
        tvModeDescription = findViewById(R.id.tvModeDescription)

        cardPresets = findViewById(R.id.cardPresets)
        btnPresetSocial = findViewById(R.id.btnPresetSocial)

        cardAddDomain = findViewById(R.id.cardAddDomain)
        tilDomainInput = findViewById(R.id.tilDomainInput)
        etDomainInput = findViewById(R.id.etDomainInput)
        btnAddDomain = findViewById(R.id.btnAddDomain)

        cardUrlList = findViewById(R.id.cardUrlList)
        tvListTitle = findViewById(R.id.tvListTitle)
        tvListCount = findViewById(R.id.tvListCount)
        tvEmptyList = findViewById(R.id.tvEmptyList)
        rvFilteredUrls = findViewById(R.id.rvFilteredUrls)

        btnSaveBrowserPolicy = findViewById(R.id.btnSaveBrowserPolicy)

        adapter = FilteredUrlAdapter(getCurrentActiveList()) { domain, position ->
            handleDeleteUrl(domain, position)
        }
        rvFilteredUrls.layoutManager = LinearLayoutManager(this)
        rvFilteredUrls.adapter = adapter
    }

    private fun setupListeners() {
        toolbar.setNavigationOnClickListener {
            if (!isSavingPolicy) {
                finish()
            }
        }

        rgFilterMode.setOnCheckedChangeListener { _, checkedId ->
            activeSnackbar?.dismiss()
            activeSnackbar = null
            selectedMode = when (checkedId) {
                R.id.rbModeBlocklist -> DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST
                R.id.rbModeWhitelist -> DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY
                else -> DevicePolicyBundle.BROWSER_FILTER_OFF
            }
            tilDomainInput.error = null
            updateModeUi()
        }

        btnAddDomain.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAddClickTime < 300L) return@setOnClickListener
            lastAddClickTime = now
            addDomainFromInput()
        }

        etDomainInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastAddClickTime >= 300L) {
                    lastAddClickTime = now
                    addDomainFromInput()
                }
                true
            } else {
                false
            }
        }

        btnPresetSocial.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPresetClickTime < 500L) return@setOnClickListener
            lastPresetClickTime = now
            applySocialPresets()
        }

        btnSaveBrowserPolicy.setOnClickListener {
            saveAndApplyPolicy()
        }
    }

    private fun populateFromPolicy() {
        when (selectedMode) {
            DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST -> rbModeBlocklist.isChecked = true
            DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY -> rbModeWhitelist.isChecked = true
            else -> rbModeOff.isChecked = true
        }
        updateModeUi()
    }

    private fun getCurrentActiveList(): MutableList<String> {
        return if (selectedMode == DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY) {
            allowedUrls
        } else {
            blockedUrls
        }
    }

    private fun updateModeUi() {
        activeSnackbar?.dismiss()
        activeSnackbar = null
        val activeList = getCurrentActiveList()
        adapter.updateList(activeList)

        when (selectedMode) {
            DevicePolicyBundle.BROWSER_FILTER_OFF -> {
                tvModeDescription.text = getString(R.string.browser_filter_desc_off)
                cardPresets.visibility = View.GONE
                cardAddDomain.visibility = View.GONE
                cardUrlList.visibility = View.GONE
            }
            DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST -> {
                tvModeDescription.text = getString(R.string.browser_filter_desc_blocklist)
                cardPresets.visibility = View.VISIBLE
                cardAddDomain.visibility = View.VISIBLE
                cardUrlList.visibility = View.VISIBLE
                tvEmptyList.text = getString(R.string.browser_filter_empty_blocklist)
                updateListCountAndEmptyState()
            }
            DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY -> {
                tvModeDescription.text = getString(R.string.browser_filter_desc_whitelist)
                cardPresets.visibility = View.GONE
                cardAddDomain.visibility = View.VISIBLE
                cardUrlList.visibility = View.VISIBLE
                tvEmptyList.text = getString(R.string.browser_filter_empty_whitelist)
                updateListCountAndEmptyState()
            }
        }
    }

    private fun updateListCountAndEmptyState() {
        val activeList = getCurrentActiveList()
        tvListCount.text = getString(R.string.browser_filter_count_format, activeList.size)
        if (activeList.isEmpty()) {
            tvEmptyList.visibility = View.VISIBLE
            rvFilteredUrls.visibility = View.GONE
        } else {
            tvEmptyList.visibility = View.GONE
            rvFilteredUrls.visibility = View.VISIBLE
        }
    }

    private fun addDomainFromInput() {
        val rawInput = etDomainInput.text?.toString()
        if (rawInput.isNullOrBlank()) return

        val sanitized = BrowserUrlSanitizer.sanitize(rawInput)

        if (sanitized == null) {
            tilDomainInput.error = getString(R.string.browser_filter_error_invalid_url)
            etDomainInput.requestFocus()
            return
        }

        val activeList = getCurrentActiveList()
        if (activeList.contains(sanitized)) {
            tilDomainInput.error = getString(R.string.browser_filter_error_duplicate_url)
            etDomainInput.requestFocus()
            return
        }

        tilDomainInput.error = null
        activeList.add(0, sanitized)
        adapter.notifyItemInserted(0)
        rvFilteredUrls.scrollToPosition(0)
        etDomainInput.text?.clear()
        updateListCountAndEmptyState()
    }

    private fun applySocialPresets() {
        var addedCount = 0
        for (preset in BrowserUrlSanitizer.SOCIAL_MEDIA_PRESETS) {
            val sanitized = BrowserUrlSanitizer.sanitize(preset) ?: preset
            if (!blockedUrls.contains(sanitized)) {
                blockedUrls.add(0, sanitized)
                addedCount++
            }
        }

        if (addedCount > 0) {
            adapter.notifyDataSetChanged()
            rvFilteredUrls.scrollToPosition(0)
            updateListCountAndEmptyState()
            Toast.makeText(
                this,
                getString(R.string.browser_filter_social_added, addedCount),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                R.string.browser_filter_error_duplicate_url,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleDeleteUrl(domain: String, position: Int) {
        val modeWhenDeleted = selectedMode
        val targetList = getCurrentActiveList()
        if (position in targetList.indices && targetList[position] == domain) {
            targetList.removeAt(position)
            adapter.notifyItemRemoved(position)
            adapter.notifyItemRangeChanged(position, targetList.size - position)
            updateListCountAndEmptyState()

            activeSnackbar?.dismiss()
            val snackbar = Snackbar.make(
                findViewById(R.id.layoutRootBrowserUrlFilter),
                getString(R.string.browser_filter_url_deleted, domain),
                Snackbar.LENGTH_LONG
            ).setAction(R.string.browser_filter_undo) {
                val restoreIndex = position.coerceAtMost(targetList.size)
                targetList.add(restoreIndex, domain)
                if (selectedMode == modeWhenDeleted) {
                    adapter.notifyItemInserted(restoreIndex)
                    updateListCountAndEmptyState()
                }
            }
            activeSnackbar = snackbar
            snackbar.show()
        }
    }

    private fun saveAndApplyPolicy() {
        if (selectedMode == DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY && allowedUrls.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_browser_url_filter)
                .setMessage(R.string.browser_filter_whitelist_empty_warning)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val updated = basePolicy.copy(
            browserFilterMode = selectedMode,
            customBlockedUrls = blockedUrls.toSet(),
            customAllowedUrls = allowedUrls.toSet(),
            version = basePolicy.version + 1,
            updatedAt = TrustedTimeProvider.currentTimeMillis(applicationContext)
        )

        if (isRemote) {
            val resultIntent = Intent().apply {
                putExtra(EXTRA_UPDATED_POLICY_JSON, updated.toJson())
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } else {
            isSavingPolicy = true
            btnSaveBrowserPolicy.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val success = PolicyRepository.getInstance(applicationContext).saveAndEnforceSelfPolicy(updated)
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        isSavingPolicy = false
                        btnSaveBrowserPolicy.isEnabled = true
                        if (success) {
                            Toast.makeText(
                                this@BrowserUrlFilterActivity,
                                R.string.browser_filter_saved_success,
                                Toast.LENGTH_SHORT
                            ).show()
                            val resultIntent = Intent().apply {
                                putExtra(EXTRA_UPDATED_POLICY_JSON, updated.toJson())
                            }
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        } else {
                            Toast.makeText(
                                this@BrowserUrlFilterActivity,
                                R.string.toast_self_policy_save_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        isSavingPolicy = false
                        btnSaveBrowserPolicy.isEnabled = true
                        Toast.makeText(
                            this@BrowserUrlFilterActivity,
                            R.string.toast_self_policy_save_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private class FilteredUrlAdapter(
        private var items: MutableList<String>,
        private val onDeleteClick: (String, Int) -> Unit
    ) : RecyclerView.Adapter<FilteredUrlAdapter.ViewHolder>() {

        fun updateList(newItems: MutableList<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_filtered_url, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val domain = items[position]
            holder.tvUrlDomain.text = domain
            holder.btnDeleteUrl.setOnClickListener {
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION && currentPos in items.indices) {
                    onDeleteClick(items[currentPos], currentPos)
                }
            }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivGlobeIcon: ImageView = itemView.findViewById(R.id.ivGlobeIcon)
            val tvUrlDomain: TextView = itemView.findViewById(R.id.tvUrlDomain)
            val btnDeleteUrl: ImageButton = itemView.findViewById(R.id.btnDeleteUrl)
        }
    }

    companion object {
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_DEVICE_ID = "extra_ward_device_id"
        const val EXTRA_UPDATED_POLICY_JSON = "extra_updated_policy_json"
    }
}
