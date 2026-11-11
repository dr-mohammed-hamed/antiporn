package com.drmohammed.antiporn.ui.accountability

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.ui.control.curfew.CurfewSettingsActivity
import com.drmohammed.antiporn.ui.control.other.OtherSettingsActivity
import com.drmohammed.antiporn.ui.control.web.WebFilterSettingsActivity
import com.drmohammed.antiporn.waqfa.ui.settings.WaqfaSettingsActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Modern, Minimalist Guardian Control Hub Activity.
 * Mirrors the clean 5-tile architecture of SelfControlFragment, providing supervisors
 * with live sync monitoring and instant access to specialized ward configuration screens.
 */
class GuardianControlActivity : AppCompatActivity() {

    private val viewModel: GuardianControlViewModel by viewModel()

    private lateinit var wardId: String
    private lateinit var wardName: String

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvWardName: TextView
    private lateinit var btnEditWardName: MaterialButton
    private lateinit var layoutLiveSyncBadge: LinearLayout
    private lateinit var ivLiveSyncIcon: ImageView
    private lateinit var tvLiveSyncText: TextView
    private lateinit var cardOfflineBanner: MaterialCardView
    private lateinit var tvOfflineStatus: TextView
    private lateinit var btnRetryLoadPolicy: MaterialButton
    private lateinit var alertsViewBinder: GuardianAlertsViewBinder

    private lateinit var switchAllowSelfControl: MaterialSwitch

    private lateinit var layoutWardApprovalsSection: LinearLayout
    private lateinit var layoutPendingApps: LinearLayout
    private lateinit var tvNoPendingApps: TextView

    private lateinit var approvalsViewBinder: GuardianApprovalsViewBinder
    private lateinit var liveSyncViewBinder: GuardianLiveSyncViewBinder

    private var lastEditNameClickTime = 0L
    private var lastTileClickTime = 0L
    private var isBindingSwitch = false

    private val subScreenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.loadWardPolicy()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian_control)

        applyWindowInsets()

        wardId = intent.getStringExtra(EXTRA_WARD_ID) ?: ""
        wardName = intent.getStringExtra(EXTRA_WARD_NAME) ?: getString(R.string.status_ward_badge)

        if (wardId.isBlank()) {
            finish()
            return
        }

        initViews()
        setupListeners()
        observeViewModel()

        viewModel.initialize(wardId, wardName)
    }

    private fun applyWindowInsets() {
        findViewById<View>(R.id.layoutRootGuardianControl)?.let { rootView ->
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarGuardianControl)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        tvWardName = findViewById(R.id.tvWardName)
        btnEditWardName = findViewById(R.id.btnEditWardName)
        updateWardTitle(wardName)

        layoutLiveSyncBadge = findViewById(R.id.layoutLiveSyncBadge)
        ivLiveSyncIcon = findViewById(R.id.ivLiveSyncIcon)
        tvLiveSyncText = findViewById(R.id.tvLiveSyncText)
        liveSyncViewBinder = GuardianLiveSyncViewBinder(
            activity = this,
            layoutLiveSyncBadge = layoutLiveSyncBadge,
            ivLiveSyncIcon = ivLiveSyncIcon,
            tvLiveSyncText = tvLiveSyncText,
            wardIdProvider = { viewModel.uiState.value.wardId }
        )

        cardOfflineBanner = findViewById(R.id.cardOfflineBanner)
        tvOfflineStatus = findViewById(R.id.tvOfflineStatus)
        btnRetryLoadPolicy = findViewById(R.id.btnRetryLoadPolicy)

        alertsViewBinder = GuardianAlertsViewBinder(
            activity = this,
            cardSelfControlAlertBanner = findViewById(R.id.cardSelfControlAlertBanner),
            tvSelfControlAlertMsg = findViewById(R.id.tvSelfControlAlertMsg),
            btnRevertSelfControlChanges = findViewById(R.id.btnRevertSelfControlChanges),
            btnDismissSelfControlAlert = findViewById(R.id.btnDismissSelfControlAlert),
            cardBreakoutAlertBanner = findViewById(R.id.cardBreakoutAlertBanner),
            tvBreakoutAlertMsg = findViewById(R.id.tvBreakoutAlertMsg),
            btnRejectBreakout = findViewById(R.id.btnRejectBreakout),
            wardNameProvider = { viewModel.uiState.value.wardName },
            wardIdProvider = { viewModel.uiState.value.wardId },
            policyProvider = { viewModel.uiState.value.currentPolicy },
            isSelfControlAllowedProvider = { viewModel.uiState.value.isSelfControlAllowed },
            onPolicyReverted = { reverted -> viewModel.onPolicyReverted(reverted) },
            onPolicyAcknowledged = { acknowledged -> viewModel.onPolicyAcknowledged(acknowledged) }
        )

        switchAllowSelfControl = findViewById(R.id.switchAllowSelfControl)
        layoutWardApprovalsSection = findViewById(R.id.layoutWardApprovalsSection)
        layoutPendingApps = findViewById(R.id.layoutPendingApps)
        tvNoPendingApps = findViewById(R.id.tvNoPendingApps)

        approvalsViewBinder = GuardianApprovalsViewBinder(
            activity = this,
            layoutApprovalsContainer = layoutWardApprovalsSection,
            layoutPendingApps = layoutPendingApps,
            tvNoPendingApps = tvNoPendingApps,
            wardIdProvider = { viewModel.uiState.value.wardId },
            onReloadRequested = { approvalsViewBinder.loadPendingApprovals() }
        )
    }

    private fun setupListeners() {
        alertsViewBinder.setupListeners()

        btnEditWardName.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEditNameClickTime < 800L) return@setOnClickListener
            lastEditNameClickTime = now
            showEditWardNameDialog()
        }

        btnRetryLoadPolicy.setOnClickListener {
            btnRetryLoadPolicy.isEnabled = false
            viewModel.loadWardPolicy()
            approvalsViewBinder.loadPendingApprovals()
        }

        switchAllowSelfControl.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingSwitch) return@setOnCheckedChangeListener
            viewModel.updateSelfControlPermissionImmediately(isChecked)
        }

        // --- 5 Distributed Navigation Tiles ---

        // Tile 1: Night Curfew
        findViewById<View>(R.id.tileCurfew)?.setOnClickListener {
            launchSubScreen {
                val state = viewModel.uiState.value
                Intent(this, CurfewSettingsActivity::class.java).apply {
                    putExtra(CurfewSettingsActivity.EXTRA_IS_REMOTE, true)
                    putExtra(CurfewSettingsActivity.EXTRA_WARD_ID, state.wardId)
                    putExtra(CurfewSettingsActivity.EXTRA_POLICY_JSON, state.currentPolicy.toJson())
                }
            }
        }

        // Tile 2: Sites & Network
        findViewById<View>(R.id.tileWebFilter)?.setOnClickListener {
            launchSubScreen {
                val state = viewModel.uiState.value
                Intent(this, WebFilterSettingsActivity::class.java).apply {
                    putExtra(WebFilterSettingsActivity.EXTRA_IS_REMOTE, true)
                    putExtra(WebFilterSettingsActivity.EXTRA_WARD_ID, state.wardId)
                    putExtra(WebFilterSettingsActivity.EXTRA_POLICY_JSON, state.currentPolicy.toJson())
                }
            }
        }

        // Tile 3: App Management
        findViewById<View>(R.id.tileAppManager)?.setOnClickListener {
            launchSubScreen {
                val state = viewModel.uiState.value
                Intent(this, RemoteAppManagerActivity::class.java).apply {
                    putExtra(RemoteAppManagerActivity.EXTRA_IS_LOCAL, false)
                    putExtra(RemoteAppManagerActivity.EXTRA_WARD_ID, state.wardId)
                    putExtra(RemoteAppManagerActivity.EXTRA_WARD_NAME, state.wardName)
                }
            }
        }

        // Tile 4: Waqfa System
        findViewById<View>(R.id.tileWaqfa)?.setOnClickListener {
            launchSubScreen {
                val state = viewModel.uiState.value
                Intent(this, WaqfaSettingsActivity::class.java).apply {
                    putExtra(WaqfaSettingsActivity.EXTRA_IS_REMOTE, true)
                    putExtra(WaqfaSettingsActivity.EXTRA_WARD_ID, state.wardId)
                    putExtra(WaqfaSettingsActivity.EXTRA_POLICY_JSON, state.currentPolicy.toJson())
                }
            }
        }

        // Tile 5: Other Settings
        findViewById<View>(R.id.tileOtherSettings)?.setOnClickListener {
            launchSubScreen {
                val state = viewModel.uiState.value
                Intent(this, OtherSettingsActivity::class.java).apply {
                    putExtra(OtherSettingsActivity.EXTRA_IS_REMOTE, true)
                    putExtra(OtherSettingsActivity.EXTRA_WARD_ID, state.wardId)
                    putExtra(OtherSettingsActivity.EXTRA_POLICY_JSON, state.currentPolicy.toJson())
                }
            }
        }
    }

    private fun launchSubScreen(intentProvider: () -> Intent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTileClickTime < 600L) return
        lastTileClickTime = now
        subScreenLauncher.launch(intentProvider())
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderUiState(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        handleEvent(event)
                    }
                }
            }
        }
    }

    private fun renderUiState(state: GuardianControlUiState) {
        updateWardTitle(state.wardName)
        cardOfflineBanner.visibility = if (state.isOffline) View.VISIBLE else View.GONE
        btnRetryLoadPolicy.isEnabled = !state.isLoading

        isBindingSwitch = true
        switchAllowSelfControl.isChecked = state.isSelfControlAllowed
        isBindingSwitch = false

        alertsViewBinder.renderPolicyAlerts(state.currentPolicy)

        liveSyncViewBinder.updateLiveSyncBadge(
            version = state.version,
            acknowledgedVersion = state.acknowledgedVersion,
            lastAcknowledgedAt = state.lastAcknowledgedAt,
            lastHeartbeat = state.lastHeartbeat
        )
    }

    private fun updateWardTitle(name: String) {
        val displayName = name.trim()
        val title = if (displayName.isNotBlank()) {
            getString(R.string.guardian_control_ward_name, displayName)
        } else {
            getString(R.string.title_guardian_control)
        }
        toolbar.title = title
        tvWardName.text = title
    }

    private fun handleEvent(event: GuardianControlEvent) {
        when (event) {
            is GuardianControlEvent.ShowToastRes -> {
                val length = if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(this, event.resId, length).show()
            }
            is GuardianControlEvent.ShowToastString -> {
                val length = if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(this, event.message, length).show()
            }
            is GuardianControlEvent.DnsValidationError -> {
                // Not used directly in hub
            }
            is GuardianControlEvent.SaveSuccess -> {
                // Handled in sub-screens
            }
        }
    }

    private fun showEditWardNameDialog() {
        val currentName = viewModel.uiState.value.wardName
        val input = TextInputEditText(this).apply {
            setText(currentName)
            hint = getString(R.string.dialog_edit_ward_name_hint)
            setSingleLine()
            setSelection(text?.length ?: 0)
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
                viewModel.renameWard(newName)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        viewModel.startPolicyListener()
        approvalsViewBinder.listenForPendingApprovals()
    }

    override fun onPause() {
        viewModel.stopPolicyListener()
        approvalsViewBinder.stopListening()
        super.onPause()
    }

    override fun onDestroy() {
        viewModel.stopPolicyListener()
        approvalsViewBinder.stopListening()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_WARD_NAME = "extra_ward_name"
    }
}
