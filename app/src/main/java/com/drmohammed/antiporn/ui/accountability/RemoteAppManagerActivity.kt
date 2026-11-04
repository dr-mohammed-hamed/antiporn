package com.drmohammed.antiporn.ui.accountability

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.model.InstalledAppDto
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Activity enabling a Guardian to inspect and manage all installed applications
 * on a Ward's supervised device remotely.
 *
 * Supports:
 * - 24/7 Application Freezing (customBlacklistPackages)
 * - Curfew Exception/Whitelisting (customCurfewWhitelistPackages)
 * - Uninstall & Force-Stop Protection (protectedAppPackages)
 * - Real-time client-side search filtering
 * - Mutually exclusive 24/7 freeze vs curfew whitelist
 * - Unsaved changes dirty-state tracking with OnBackPressedCallback
 * - Docked sticky bottom bar for easy thumb reach
 */
class RemoteAppManagerActivity : AppCompatActivity() {

    private var isLocalMode = false
    private lateinit var wardId: String
    private lateinit var wardName: String

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvWardName: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var rvAppsList: RecyclerView
    private lateinit var btnSaveAppManagerChanges: MaterialButton

    private var currentPolicy = DevicePolicyBundle.defaultPolicy()
    private val customBlacklistPackages = mutableSetOf<String>()
    private val customCurfewWhitelistPackages = mutableSetOf<String>()
    private val curfewVpnBypassPackages = mutableSetOf<String>()
    private val protectedAppPackages = mutableSetOf<String>()

    private var initialBlacklist = emptySet<String>()
    private var initialCurfewWhitelist = emptySet<String>()
    private var initialCurfewVpnBypass = emptySet<String>()
    private var initialProtectedApps = emptySet<String>()

    private lateinit var adapter: RemoteAppsAdapter
    @Volatile
    private var isSaving = false
    private var inventoryListener: ListenerRegistration? = null

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
        setContentView(R.layout.activity_remote_app_manager)

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
                view.setPadding(
                    insets.left,
                    insets.top,
                    insets.right,
                    0
                )
                innerBottomBar?.setPadding(
                    baseLeftPadding,
                    baseTopPadding,
                    baseRightPadding,
                    baseBottomPadding + insets.bottom
                )
                windowInsets
            }
        }

        isLocalMode = intent.getBooleanExtra(EXTRA_IS_LOCAL, false)
        wardId = intent.getStringExtra(EXTRA_WARD_ID) ?: ""
        wardName = intent.getStringExtra(EXTRA_WARD_NAME) ?: getString(R.string.status_ward_badge)
        if (isLocalMode && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PolicyRepository.getInstance(applicationContext).currentPolicyFlow.collect {
                    if (isFinishing || isDestroyed) return@collect
                    if (isLocalMode && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this@RemoteAppManagerActivity)) {
                        Toast.makeText(this@RemoteAppManagerActivity, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }

        if (!isLocalMode && wardId.isBlank()) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        initViews()
        setupListeners()
        loadData()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarAppManager)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        tvWardName = findViewById(R.id.tvWardName)
        tvWardName.text = if (isLocalMode) {
            getString(R.string.self_control_local_app_manager_title)
        } else {
            getString(R.string.guardian_control_ward_name, wardName)
        }

        etSearch = findViewById(R.id.etSearch)
        progressBar = findViewById(R.id.progressBar)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        rvAppsList = findViewById(R.id.rvAppsList)
        btnSaveAppManagerChanges = findViewById(R.id.btnSaveAppManagerChanges)

        rvAppsList.layoutManager = LinearLayoutManager(this)
        adapter = RemoteAppsAdapter(
            customBlacklist = customBlacklistPackages,
            customCurfewWhitelist = customCurfewWhitelistPackages,
            curfewVpnBypass = curfewVpnBypassPackages,
            protectedApps = protectedAppPackages,
            getInitialCurfewWhitelist = { initialCurfewWhitelist },
            getInitialCurfewVpnBypass = { initialCurfewVpnBypass },
            onFilterUpdated = { isEmpty ->
                tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                rvAppsList.visibility = if (isEmpty) View.GONE else View.VISIBLE
            },
            onStateChanged = {
                updateSaveButtonState()
            }
        )
        rvAppsList.adapter = adapter
        updateSaveButtonState()
    }

    private fun setupListeners() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        tvEmptyState.setOnClickListener { loadData() }
        btnSaveAppManagerChanges.setOnClickListener {
            saveChanges()
        }
    }

    private fun hasUnsavedChanges(): Boolean {
        return customBlacklistPackages != initialBlacklist ||
                customCurfewWhitelistPackages != initialCurfewWhitelist ||
                curfewVpnBypassPackages != initialCurfewVpnBypass ||
                protectedAppPackages != initialProtectedApps
    }

    private fun updateSaveButtonState() {
        val dirty = hasUnsavedChanges()
        btnSaveAppManagerChanges.isEnabled = dirty && !isSaving
        btnSaveAppManagerChanges.alpha = if (dirty) 1.0f else 0.5f
        backPressedCallback.isEnabled = dirty
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

    private fun loadData() {
        progressBar.visibility = View.VISIBLE
        tvEmptyState.visibility = View.GONE
        btnSaveAppManagerChanges.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            if (isFinishing || isDestroyed) return@launch

            if (isLocalMode) {
                val policy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
                currentPolicy = policy

                customBlacklistPackages.clear()
                customBlacklistPackages.addAll(policy.customBlacklistPackages)

                customCurfewWhitelistPackages.clear()
                customCurfewWhitelistPackages.addAll(policy.customCurfewWhitelistPackages)

                curfewVpnBypassPackages.clear()
                curfewVpnBypassPackages.addAll(policy.curfewVpnBypassPackages)

                protectedAppPackages.clear()
                protectedAppPackages.addAll(policy.protectedAppPackages)

                initialBlacklist = customBlacklistPackages.toSet()
                initialCurfewWhitelist = customCurfewWhitelistPackages.toSet()
                initialCurfewVpnBypass = curfewVpnBypassPackages.toSet()
                initialProtectedApps = protectedAppPackages.toSet()

                val localApps = com.drmohammed.antiporn.data.repository.AppInventoryManager.getInstance(applicationContext).getInstalledApps()
                val sortedList = localApps.sortedWith(
                    compareBy({ it.appName.lowercase() }, { it.packageName })
                )

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (!isSaving) {
                        progressBar.visibility = View.GONE
                    }
                    updateSaveButtonState()
                    if (sortedList.isEmpty()) {
                        tvEmptyState.visibility = View.VISIBLE
                        rvAppsList.visibility = View.GONE
                    } else {
                        tvEmptyState.visibility = View.GONE
                        rvAppsList.visibility = View.VISIBLE
                        adapter.submitList(sortedList)
                    }
                }
                return@launch
            }

            val firestore = FirebaseFirestore.getInstance()

            // 1. Fetch current policy bundle
            var policySnapshot: DocumentSnapshot? = null
            try {
                policySnapshot = firestore.collection("policies").document(wardId).get().await()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Network fetch failed for ward policy $wardId, trying cache", e)
                try {
                    policySnapshot = firestore.collection("policies").document(wardId).get(Source.CACHE).await()
                } catch (cacheEx: Exception) {
                    Log.w(TAG, "Cache fetch failed for ward policy $wardId", cacheEx)
                }
            }

            val policyData = policySnapshot?.data
            if (policySnapshot != null && policySnapshot.exists() && policyData != null) {
                currentPolicy = DevicePolicyBundle.fromMap(policyData)
            } else {
                currentPolicy = DevicePolicyBundle.defaultPolicy()
            }

            customBlacklistPackages.clear()
            customBlacklistPackages.addAll(currentPolicy.customBlacklistPackages)

            customCurfewWhitelistPackages.clear()
            customCurfewWhitelistPackages.addAll(currentPolicy.customCurfewWhitelistPackages)

            curfewVpnBypassPackages.clear()
            curfewVpnBypassPackages.addAll(currentPolicy.curfewVpnBypassPackages)

            protectedAppPackages.clear()
            protectedAppPackages.addAll(currentPolicy.protectedAppPackages)

            initialBlacklist = customBlacklistPackages.toSet()
            initialCurfewWhitelist = customCurfewWhitelistPackages.toSet()
            initialCurfewVpnBypass = curfewVpnBypassPackages.toSet()
            initialProtectedApps = protectedAppPackages.toSet()

            // 2. Fetch inventory
            var inventoryDoc: DocumentSnapshot? = null
            try {
                inventoryDoc = firestore.collection("inventories").document(wardId).get().await()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Network fetch failed for inventory $wardId, trying cache", e)
                try {
                    inventoryDoc = firestore.collection("inventories").document(wardId).get(Source.CACHE).await()
                } catch (cacheEx: Exception) {
                    Log.w(TAG, "Cache fetch failed for inventory $wardId", cacheEx)
                }
            }

            @Suppress("UNCHECKED_CAST")
            val rawApps = (inventoryDoc?.get("apps") as? List<*>) ?: emptyList<Any?>()
            @Suppress("UNCHECKED_CAST")
            val parsedApps = rawApps.mapNotNull { raw ->
                (raw as? Map<String, Any?>)?.let { InstalledAppDto.fromMap(it) }
            }.filter { it.packageName.isNotBlank() }.toMutableList()

            // Merge any existing configured packages that may not be in inventory
            val existingPackages = parsedApps.map { it.packageName }.toSet()
            val extraPackages = (customBlacklistPackages + customCurfewWhitelistPackages + curfewVpnBypassPackages + protectedAppPackages)
                .filter { !existingPackages.contains(it) }

            for (orphan in extraPackages) {
                parsedApps.add(
                    InstalledAppDto(
                        packageName = orphan,
                        appName = orphan,
                        isSuspended = customBlacklistPackages.contains(orphan),
                        isCurfewWhitelisted = customCurfewWhitelistPackages.contains(orphan),
                        isCurfewVpnBypassed = curfewVpnBypassPackages.contains(orphan),
                        isProtected = protectedAppPackages.contains(orphan)
                    )
                )
            }

            val sortedList = parsedApps.sortedWith(
                compareBy({ it.appName.lowercase() }, { it.packageName })
            )

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (!isSaving) {
                    progressBar.visibility = View.GONE
                }
                updateSaveButtonState()
                if (sortedList.isEmpty()) {
                    tvEmptyState.visibility = View.VISIBLE
                    rvAppsList.visibility = View.GONE
                } else {
                    tvEmptyState.visibility = View.GONE
                    rvAppsList.visibility = View.VISIBLE
                    adapter.submitList(sortedList)
                }
                attachRealtimeInventoryListener(firestore)
            }
        }
    }

    private fun saveChanges() {
        if (isSaving || !hasUnsavedChanges()) return
        isSaving = true
        btnSaveAppManagerChanges.isEnabled = false
        progressBar.visibility = View.VISIBLE

        val myUid = if (!isLocalMode) AccountabilityRepository.getInstance(applicationContext).getCachedUid() else null
        val myName = if (!isLocalMode) AccountabilityRepository.getInstance(applicationContext).getMyDisplayName() else null
        val updatedPolicy = currentPolicy.copy(
            guardianUid = if (!isLocalMode) (currentPolicy.guardianUid?.takeIf { it.isNotBlank() } ?: myUid) else currentPolicy.guardianUid,
            guardianName = if (!isLocalMode) (currentPolicy.guardianName?.takeIf { it.isNotBlank() } ?: myName) else currentPolicy.guardianName,
            isSupervised = if (!isLocalMode) true else currentPolicy.isSupervised,
            customBlacklistPackages = customBlacklistPackages.toSet(),
            customCurfewWhitelistPackages = customCurfewWhitelistPackages.toSet(),
            curfewVpnBypassPackages = curfewVpnBypassPackages.toSet(),
            protectedAppPackages = protectedAppPackages.toSet(),
            version = currentPolicy.version + 1,
            updatedAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(applicationContext)
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val policyRepo = PolicyRepository.getInstance(applicationContext)
                val success = if (isLocalMode) {
                    policyRepo.saveAndEnforceSelfPolicy(updatedPolicy)
                } else {
                    policyRepo.pushPolicyToWard(wardId, updatedPolicy)
                }
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    progressBar.visibility = View.GONE
                    if (success) {
                        initialBlacklist = customBlacklistPackages.toSet()
                        initialCurfewWhitelist = customCurfewWhitelistPackages.toSet()
                        initialCurfewVpnBypass = curfewVpnBypassPackages.toSet()
                        initialProtectedApps = protectedAppPackages.toSet()
                        backPressedCallback.isEnabled = false
                        setResult(RESULT_OK)
                        Toast.makeText(
                            this@RemoteAppManagerActivity,
                            R.string.toast_app_manager_saved,
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    } else {
                        isSaving = false
                        updateSaveButtonState()
                        Toast.makeText(
                            this@RemoteAppManagerActivity,
                            R.string.toast_app_manager_save_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error pushing updated policies from RemoteAppManagerActivity", e)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    progressBar.visibility = View.GONE
                    isSaving = false
                    updateSaveButtonState()
                    Toast.makeText(
                        this@RemoteAppManagerActivity,
                        R.string.toast_app_manager_save_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                isSaving = false
            }
        }
    }

    private fun attachRealtimeInventoryListener(firestore: FirebaseFirestore) {
        inventoryListener?.remove()
        inventoryListener = firestore.collection("inventories").document(wardId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Real-time inventory listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists() && snapshot.data != null) {
                    processAndSubmitInventory(snapshot)
                }
            }
    }

    private fun processAndSubmitInventory(inventoryDoc: DocumentSnapshot) {
        @Suppress("UNCHECKED_CAST")
        val rawApps = (inventoryDoc.get("apps") as? List<*>) ?: emptyList<Any?>()
        @Suppress("UNCHECKED_CAST")
        val parsedApps = rawApps.mapNotNull { raw ->
            (raw as? Map<String, Any?>)?.let { InstalledAppDto.fromMap(it) }
        }.filter { it.packageName.isNotBlank() }.toMutableList()

        val existingPackages = parsedApps.map { it.packageName }.toSet()
        val extraPackages = (customBlacklistPackages + customCurfewWhitelistPackages + curfewVpnBypassPackages + protectedAppPackages)
            .filter { !existingPackages.contains(it) }

        for (orphan in extraPackages) {
            parsedApps.add(
                InstalledAppDto(
                    packageName = orphan,
                    appName = orphan,
                    isSuspended = customBlacklistPackages.contains(orphan),
                    isCurfewWhitelisted = customCurfewWhitelistPackages.contains(orphan),
                    isCurfewVpnBypassed = curfewVpnBypassPackages.contains(orphan),
                    isProtected = protectedAppPackages.contains(orphan)
                )
            )
        }

        val sortedList = parsedApps.sortedWith(
            compareBy({ it.appName.lowercase() }, { it.packageName })
        )

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (!isSaving) {
                progressBar.visibility = View.GONE
            }
            updateSaveButtonState()
            if (sortedList.isEmpty()) {
                tvEmptyState.visibility = View.VISIBLE
                rvAppsList.visibility = View.GONE
            } else {
                tvEmptyState.visibility = View.GONE
                rvAppsList.visibility = View.VISIBLE
                val activeQuery = etSearch.text?.toString().orEmpty()
                adapter.submitList(sortedList, activeQuery)
            }
        }
    }

    override fun onDestroy() {
        inventoryListener?.remove()
        inventoryListener = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AntiPorn_RemoteAppMgr"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_WARD_NAME = "extra_ward_name"
        const val EXTRA_IS_LOCAL = "extra_is_local"
    }

    /**
     * RecyclerView Adapter displaying installed applications with toggles for
     * 24/7 freeze, curfew whitelist, and uninstall protection.
     * Enforces mutual exclusivity: 24/7 freeze disables curfew exception.
     */
    private class RemoteAppsAdapter(
        private val customBlacklist: MutableSet<String>,
        private val customCurfewWhitelist: MutableSet<String>,
        private val curfewVpnBypass: MutableSet<String>,
        private val protectedApps: MutableSet<String>,
        private val getInitialCurfewWhitelist: () -> Set<String>,
        private val getInitialCurfewVpnBypass: () -> Set<String>,
        private val onFilterUpdated: (isEmpty: Boolean) -> Unit,
        private val onStateChanged: () -> Unit
    ) : RecyclerView.Adapter<RemoteAppsAdapter.AppViewHolder>() {

        private var allApps: List<InstalledAppDto> = emptyList()
        private var displayedApps: List<InstalledAppDto> = emptyList()

        fun submitList(apps: List<InstalledAppDto>, currentQuery: String = "") {
            allApps = apps
            val q = currentQuery.trim().lowercase()
            displayedApps = if (q.isEmpty()) {
                allApps
            } else {
                allApps.filter {
                    it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                }
            }
            notifyDataSetChanged()
            onFilterUpdated(displayedApps.isEmpty())
        }

        fun filter(query: String) {
            val q = query.trim().lowercase()
            displayedApps = if (q.isEmpty()) {
                allApps
            } else {
                allApps.filter {
                    it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                }
            }
            notifyDataSetChanged()
            onFilterUpdated(displayedApps.isEmpty())
        }

        class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvAppName: TextView = view.findViewById(R.id.tvAppName)
            val tvPackageName: TextView = view.findViewById(R.id.tvPackageName)
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

        override fun getItemCount(): Int = displayedApps.size

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val app = displayedApps[position]
            val pkg = app.packageName

            holder.tvAppName.text = app.appName.ifBlank { pkg }
            holder.tvPackageName.text = pkg

            // Clear listeners before toggling checked state to prevent recycler recycling bugs
            holder.switchFreeze247.setOnCheckedChangeListener(null)
            holder.switchAllowInCurfew.setOnCheckedChangeListener(null)
            holder.switchAllowCurfewInternet.setOnCheckedChangeListener(null)
            holder.switchProtect.setOnCheckedChangeListener(null)

            val isFrozen = customBlacklist.contains(pkg)
            holder.switchFreeze247.isChecked = isFrozen

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

            fun bindCurfewInternetSwitchState(allowInCurfew: Boolean, restoreInitial: Boolean = false) {
                holder.switchAllowCurfewInternet.setOnCheckedChangeListener(null)
                if (!allowInCurfew) {
                    curfewVpnBypass.remove(pkg)
                    holder.switchAllowCurfewInternet.isChecked = false
                    holder.switchAllowCurfewInternet.isEnabled = false
                    holder.switchAllowCurfewInternet.alpha = 0.4f
                    holder.tvCurfewInternetDisabledHint.visibility = View.VISIBLE
                } else {
                    holder.switchAllowCurfewInternet.isEnabled = true
                    holder.switchAllowCurfewInternet.alpha = 1.0f
                    holder.tvCurfewInternetDisabledHint.visibility = View.GONE
                    val isChecked = if (restoreInitial) {
                        val wasInitiallyInternetBypassed = getInitialCurfewVpnBypass().contains(pkg)
                        if (wasInitiallyInternetBypassed) {
                            curfewVpnBypass.add(pkg)
                        } else {
                            curfewVpnBypass.remove(pkg)
                        }
                        wasInitiallyInternetBypassed
                    } else {
                        curfewVpnBypass.contains(pkg)
                    }
                    holder.switchAllowCurfewInternet.isChecked = isChecked
                    holder.switchAllowCurfewInternet.setOnCheckedChangeListener { _, isInternetChecked ->
                        if (isInternetChecked) {
                            curfewVpnBypass.add(pkg)
                        } else {
                            curfewVpnBypass.remove(pkg)
                        }
                        onStateChanged()
                    }
                }
            }

            holder.switchFreeze247.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    customBlacklist.add(pkg)
                    customCurfewWhitelist.remove(pkg)
                    curfewVpnBypass.remove(pkg)

                    holder.switchAllowInCurfew.setOnCheckedChangeListener(null)
                    holder.switchAllowInCurfew.isChecked = false
                    holder.switchAllowInCurfew.isEnabled = false
                    holder.switchAllowInCurfew.alpha = 0.4f
                    holder.tvCurfewDisabledHint.visibility = View.VISIBLE

                    bindCurfewInternetSwitchState(false)
                } else {
                    customBlacklist.remove(pkg)
                    holder.switchAllowInCurfew.isEnabled = true
                    holder.switchAllowInCurfew.alpha = 1.0f
                    holder.tvCurfewDisabledHint.visibility = View.GONE

                    val wasInitiallyWhitelisted = getInitialCurfewWhitelist().contains(pkg)
                    holder.switchAllowInCurfew.setOnCheckedChangeListener(null)
                    holder.switchAllowInCurfew.isChecked = wasInitiallyWhitelisted
                    if (wasInitiallyWhitelisted) {
                        customCurfewWhitelist.add(pkg)
                    }

                    bindCurfewInternetSwitchState(wasInitiallyWhitelisted, restoreInitial = true)

                    holder.switchAllowInCurfew.setOnCheckedChangeListener { _, isCurfewChecked ->
                        if (isCurfewChecked) {
                            customCurfewWhitelist.add(pkg)
                        } else {
                            customCurfewWhitelist.remove(pkg)
                        }
                        bindCurfewInternetSwitchState(isCurfewChecked)
                        onStateChanged()
                    }
                }
                onStateChanged()
            }

            holder.switchAllowInCurfew.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    customCurfewWhitelist.add(pkg)
                } else {
                    customCurfewWhitelist.remove(pkg)
                }
                bindCurfewInternetSwitchState(isChecked, restoreInitial = false)
                onStateChanged()
            }

            holder.switchAllowCurfewInternet.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    curfewVpnBypass.add(pkg)
                } else {
                    curfewVpnBypass.remove(pkg)
                }
                onStateChanged()
            }

            holder.switchProtect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    protectedApps.add(pkg)
                } else {
                    protectedApps.remove(pkg)
                }
                onStateChanged()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        if (isLocalMode && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

}
