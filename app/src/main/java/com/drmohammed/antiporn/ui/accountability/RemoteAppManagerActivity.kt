package com.drmohammed.antiporn.ui.accountability

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.data.model.InstalledAppDto
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Activity enabling a Guardian (or Ward in Local Mode) to inspect and manage all installed applications.
 * Refactored to Clean MVVM pattern: delegates state and business logic entirely to [RemoteAppManagerViewModel].
 */
class RemoteAppManagerActivity : AppCompatActivity() {

    private val viewModel: RemoteAppManagerViewModel by viewModel()

    private var isLocalMode = false
    private lateinit var wardId: String
    private lateinit var wardName: String

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvWardName: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var switchLockNewInstalls: MaterialSwitch
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var rvAppsList: RecyclerView
    private lateinit var btnSaveAppManagerChanges: MaterialButton

    private lateinit var adapter: RemoteAppsAdapter

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (viewModel.uiState.value.hasUnsavedChanges) {
                showDiscardConfirmationDialog()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_app_manager)

        applyWindowInsets()

        isLocalMode = intent.getBooleanExtra(EXTRA_IS_LOCAL, false)
        wardId = intent.getStringExtra(EXTRA_WARD_ID) ?: ""
        wardName = intent.getStringExtra(EXTRA_WARD_NAME) ?: getString(R.string.status_ward_badge)

        if (isLocalMode && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!isLocalMode && wardId.isBlank()) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        initViews()
        setupListeners()
        observeViewModel()

        viewModel.initialize(isLocalMode, wardId, wardName)
    }

    private fun applyWindowInsets() {
        findViewById<View>(R.id.layoutRootAppManager)?.let { rootView ->
            val innerBottomBar = findViewById<View>(R.id.layoutBottomBarAppManagerInner)
            val baseBottomPadding = innerBottomBar?.paddingBottom ?: 0
            val baseLeftPadding = innerBottomBar?.paddingLeft ?: 0
            val baseRightPadding = innerBottomBar?.paddingRight ?: 0
            val baseTopPadding = innerBottomBar?.paddingTop ?: 0

            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
                )
                view.setPadding(insets.left, insets.top, insets.right, 0)
                innerBottomBar?.setPadding(
                    baseLeftPadding,
                    baseTopPadding,
                    baseRightPadding,
                    baseBottomPadding + insets.bottom
                )
                windowInsets
            }
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarAppManager)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        if (isLocalMode) {
            toolbar.inflateMenu(R.menu.menu_remote_app_manager)
            toolbar.setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == R.id.action_repair_stuck_apps) {
                    viewModel.executeRepairStuckApps()
                    true
                } else {
                    false
                }
            }
        }

        tvWardName = findViewById(R.id.tvWardName)
        tvWardName.text = if (isLocalMode) {
            getString(R.string.self_control_local_app_manager_title)
        } else {
            getString(R.string.guardian_control_ward_name, wardName)
        }

        etSearch = findViewById(R.id.etSearch)
        switchLockNewInstalls = findViewById(R.id.switchLockNewInstalls)
        progressBar = findViewById(R.id.progressBar)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        rvAppsList = findViewById(R.id.rvAppsList)
        btnSaveAppManagerChanges = findViewById(R.id.btnSaveAppManagerChanges)

        rvAppsList.layoutManager = LinearLayoutManager(this)
        adapter = RemoteAppsAdapter(
            isLocalMode = isLocalMode,
            onFreezeToggled = { pkg, isFrozen -> viewModel.setFreeze247(pkg, isFrozen) },
            onCurfewWhitelistToggled = { pkg, isAllowed -> viewModel.setCurfewWhitelist(pkg, isAllowed) },
            onCurfewVpnBypassToggled = { pkg, isBypassed -> viewModel.setCurfewVpnBypass(pkg, isBypassed) },
            onProtectToggled = { pkg, isProtected -> viewModel.setProtectedApp(pkg, isProtected) },
            onQuickUnsuspendClicked = { app -> confirmAndExecuteQuickUnsuspend(app) }
        )
        rvAppsList.adapter = adapter
    }

    private fun setupListeners() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        switchLockNewInstalls.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setLockNewInstalls(isChecked)
        }
        tvEmptyState.setOnClickListener { viewModel.loadData() }
        btnSaveAppManagerChanges.setOnClickListener { viewModel.saveChanges() }
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
                launch {
                    PolicyRepository.getInstance(applicationContext).currentPolicyFlow.collect {
                        if (isFinishing || isDestroyed) return@collect
                        if (isLocalMode && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this@RemoteAppManagerActivity)) {
                            Toast.makeText(this@RemoteAppManagerActivity, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun renderUiState(state: RemoteAppManagerUiState) {
        progressBar.visibility = if (state.isLoading || state.isSaving || state.isRepairing) View.VISIBLE else View.GONE
        tvEmptyState.visibility = if (state.isEmptyState) View.VISIBLE else View.GONE
        rvAppsList.visibility = if (state.isEmptyState) View.GONE else View.VISIBLE

        val dirty = state.hasUnsavedChanges
        btnSaveAppManagerChanges.isEnabled = state.isSaveEnabled
        btnSaveAppManagerChanges.alpha = if (dirty) 1.0f else 0.5f
        backPressedCallback.isEnabled = dirty

        switchLockNewInstalls.setOnCheckedChangeListener(null)
        switchLockNewInstalls.isChecked = state.lockNewInstallsUntilApproved
        switchLockNewInstalls.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setLockNewInstalls(isChecked)
        }

        adapter.submitData(
            apps = state.displayedApps,
            customBlacklist = state.customBlacklist,
            customCurfewWhitelist = state.customCurfewWhitelist,
            curfewVpnBypass = state.curfewVpnBypass,
            protectedApps = state.protectedApps,
            initialCurfewWhitelist = state.initialCurfewWhitelist,
            initialCurfewVpnBypass = state.initialCurfewVpnBypass
        )
    }

    private fun handleEvent(event: RemoteAppManagerEvent) {
        when (event) {
            is RemoteAppManagerEvent.ShowToastRes -> {
                val length = if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(this, event.resId, length).show()
            }
            is RemoteAppManagerEvent.ShowToastString -> {
                val length = if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(this, event.message, length).show()
            }
            is RemoteAppManagerEvent.SaveSuccess -> {
                backPressedCallback.isEnabled = false
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun showDiscardConfirmationDialog() {
        if (isFinishing || isDestroyed) return
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

    private fun confirmAndExecuteQuickUnsuspend(app: InstalledAppDto) {
        if (isFinishing || isDestroyed) return
        val pkg = app.packageName
        val displayName = app.appName.ifBlank { pkg }

        val title = getString(R.string.dialog_quick_unsuspend_title)
        val message = if (isLocalMode) {
            getString(R.string.dialog_quick_unsuspend_msg_local, displayName)
        } else {
            getString(R.string.dialog_quick_unsuspend_msg_remote, displayName, wardName)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(R.string.btn_unsuspend_action) { dialog, _ ->
                dialog.dismiss()
                viewModel.executeQuickUnsuspend(app)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        if (isLocalMode && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_WARD_NAME = "extra_ward_name"
        const val EXTRA_IS_LOCAL = "extra_is_local"
    }

    /**
     * Clean RecyclerView Adapter displaying installed applications with reactive state bindings.
     */
    private class RemoteAppsAdapter(
        private val isLocalMode: Boolean,
        private val onFreezeToggled: (String, Boolean) -> Unit,
        private val onCurfewWhitelistToggled: (String, Boolean) -> Unit,
        private val onCurfewVpnBypassToggled: (String, Boolean) -> Unit,
        private val onProtectToggled: (String, Boolean) -> Unit,
        private val onQuickUnsuspendClicked: (InstalledAppDto) -> Unit
    ) : RecyclerView.Adapter<RemoteAppsAdapter.AppViewHolder>() {

        private var apps: List<InstalledAppDto> = emptyList()
        private var customBlacklist: Set<String> = emptySet()
        private var customCurfewWhitelist: Set<String> = emptySet()
        private var curfewVpnBypass: Set<String> = emptySet()
        private var protectedApps: Set<String> = emptySet()
        private var initialCurfewWhitelist: Set<String> = emptySet()
        private var initialCurfewVpnBypass: Set<String> = emptySet()

        fun submitData(
            apps: List<InstalledAppDto>,
            customBlacklist: Set<String>,
            customCurfewWhitelist: Set<String>,
            curfewVpnBypass: Set<String>,
            protectedApps: Set<String>,
            initialCurfewWhitelist: Set<String>,
            initialCurfewVpnBypass: Set<String>
        ) {
            this.apps = apps
            this.customBlacklist = customBlacklist
            this.customCurfewWhitelist = customCurfewWhitelist
            this.curfewVpnBypass = curfewVpnBypass
            this.protectedApps = protectedApps
            this.initialCurfewWhitelist = initialCurfewWhitelist
            this.initialCurfewVpnBypass = initialCurfewVpnBypass
            notifyDataSetChanged()
        }

        class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivAppIcon: ImageView = view.findViewById(R.id.ivAppIcon)
            val tvAppName: TextView = view.findViewById(R.id.tvAppName)
            val tvPackageName: TextView = view.findViewById(R.id.tvPackageName)
            val layoutPhysicalSuspensionAlert: View = view.findViewById(R.id.layoutPhysicalSuspensionAlert)
            val tvPhysicalStatus: TextView = view.findViewById(R.id.tvPhysicalStatus)
            val btnQuickUnsuspend: MaterialButton = view.findViewById(R.id.btnQuickUnsuspend)
            val switchFreeze247: MaterialSwitch = view.findViewById(R.id.switchFreeze247)
            val switchAllowInCurfew: MaterialSwitch = view.findViewById(R.id.switchAllowInCurfew)
            val tvCurfewDisabledHint: TextView = view.findViewById(R.id.tvCurfewDisabledHint)
            val switchAllowCurfewInternet: MaterialSwitch = view.findViewById(R.id.switchAllowCurfewInternet)
            val tvCurfewInternetDisabledHint: TextView = view.findViewById(R.id.tvCurfewInternetDisabledHint)
            val switchProtect: MaterialSwitch = view.findViewById(R.id.switchProtect)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                R.layout.item_remote_app_card,
                parent,
                false
            )
            return AppViewHolder(view)
        }

        override fun getItemCount(): Int = apps.size

        override fun onViewRecycled(holder: AppViewHolder) {
            super.onViewRecycled(holder)
            AppIconResolver.cancelLoad(holder.ivAppIcon)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val app = apps[position]
            val pkg = app.packageName
            val displayName = app.appName.ifBlank { pkg }

            holder.tvAppName.text = displayName
            holder.tvPackageName.text = pkg

            AppIconResolver.loadIcon(
                imageView = holder.ivAppIcon,
                packageName = pkg,
                appName = displayName,
                isLocalMode = isLocalMode
            )

            holder.switchFreeze247.setOnCheckedChangeListener(null)
            holder.switchAllowInCurfew.setOnCheckedChangeListener(null)
            holder.switchAllowCurfewInternet.setOnCheckedChangeListener(null)
            holder.switchProtect.setOnCheckedChangeListener(null)

            val isFrozen = customBlacklist.contains(pkg)
            holder.switchFreeze247.isChecked = isFrozen

            val isPhysicallySuspended = app.isSuspended
            val canQuickUnsuspend = isPhysicallySuspended && !isFrozen && !AppSuspensionShield.isBlacklisted(pkg)

            if (canQuickUnsuspend) {
                holder.layoutPhysicalSuspensionAlert.visibility = View.VISIBLE
                holder.btnQuickUnsuspend.visibility = View.VISIBLE
                holder.btnQuickUnsuspend.setOnClickListener { onQuickUnsuspendClicked(app) }
            } else if (isPhysicallySuspended && !isFrozen) {
                holder.layoutPhysicalSuspensionAlert.visibility = View.VISIBLE
                holder.btnQuickUnsuspend.visibility = View.GONE
                holder.btnQuickUnsuspend.setOnClickListener(null)
            } else {
                holder.layoutPhysicalSuspensionAlert.visibility = View.GONE
                holder.btnQuickUnsuspend.setOnClickListener(null)
            }

            val isCurfewAllowed = !isFrozen && customCurfewWhitelist.contains(pkg)

            if (isFrozen) {
                holder.switchAllowInCurfew.isChecked = false
                holder.switchAllowInCurfew.isEnabled = false
                holder.switchAllowInCurfew.alpha = 0.4f
                holder.tvCurfewDisabledHint.visibility = View.VISIBLE
            } else {
                holder.switchAllowInCurfew.isEnabled = true
                holder.switchAllowInCurfew.alpha = 1.0f
                holder.tvCurfewDisabledHint.visibility = View.GONE
                holder.switchAllowInCurfew.isChecked = customCurfewWhitelist.contains(pkg)
            }

            if (!isCurfewAllowed) {
                holder.switchAllowCurfewInternet.isChecked = false
                holder.switchAllowCurfewInternet.isEnabled = false
                holder.switchAllowCurfewInternet.alpha = 0.4f
                holder.tvCurfewInternetDisabledHint.visibility = View.VISIBLE
            } else {
                holder.switchAllowCurfewInternet.isEnabled = true
                holder.switchAllowCurfewInternet.alpha = 1.0f
                holder.tvCurfewInternetDisabledHint.visibility = View.GONE
                holder.switchAllowCurfewInternet.isChecked = curfewVpnBypass.contains(pkg)
            }

            holder.switchProtect.isChecked = protectedApps.contains(pkg)

            holder.switchFreeze247.setOnCheckedChangeListener { _, isChecked ->
                onFreezeToggled(pkg, isChecked)
            }

            holder.switchAllowInCurfew.setOnCheckedChangeListener { _, isChecked ->
                onCurfewWhitelistToggled(pkg, isChecked)
            }

            holder.switchAllowCurfewInternet.setOnCheckedChangeListener { _, isChecked ->
                onCurfewVpnBypassToggled(pkg, isChecked)
            }

            holder.switchProtect.setOnCheckedChangeListener { _, isChecked ->
                onProtectToggled(pkg, isChecked)
            }
        }
    }
}
