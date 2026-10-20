package com.drmohammed.antiporn.ui.accountability

import com.google.android.material.color.MaterialColors
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// ARCHITECTURAL NOTE: Successfully upgraded to Material 3 (MaterialSwitch)
// in full alignment with Theme.Material3.

/**
 * Control panel enabling a Guardian to configure protection timings, VPN blackhole,
 * browser restrictions, and approve/reject newly installed apps on their Ward's device remotely.
 */
class GuardianControlActivity : AppCompatActivity() {

    private lateinit var wardId: String
    private lateinit var wardName: String
    private var isSavingPolicy = false

    private lateinit var tvWardName: TextView
    private lateinit var cardOfflineBanner: MaterialCardView
    private lateinit var tvOfflineStatus: TextView
    private lateinit var btnRetryLoadPolicy: MaterialButton
    private lateinit var tvCurfewStartTime: TextView
    private lateinit var tvCurfewEndTime: TextView
    private lateinit var btnChangeStartTime: MaterialButton
    private lateinit var btnChangeEndTime: MaterialButton
    private lateinit var switchVpnBlackhole: MaterialSwitch
    private lateinit var switchIncognitoBlocked: MaterialSwitch
    private lateinit var switchLockNewInstalls: MaterialSwitch
    private lateinit var tilDnsHost: TextInputLayout
    private lateinit var etDnsHost: TextInputEditText
    private lateinit var btnPresetCloudflare: MaterialButton
    private lateinit var btnPresetNextDns: MaterialButton
    private lateinit var layoutPendingApps: LinearLayout
    private lateinit var tvNoPendingApps: TextView
    private lateinit var btnSavePolicyChanges: MaterialButton

    private var currentPolicy = DevicePolicyBundle.defaultPolicy()
    private var selectedStartHour = 20
    private var selectedStartMinute = 30
    private var selectedEndHour = 7
    private var selectedEndMinute = 0
    private val processingPackages = mutableSetOf<String>()
    private var hasLoadedInitialPolicy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian_control)

        findViewById<View>(R.id.scrollGuardianControl)?.let { scrollView ->
            ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
                )
                view.setPadding(
                    insets.left,
                    insets.top,
                    insets.right,
                    insets.bottom
                )
                windowInsets
            }
        }

        wardId = intent.getStringExtra(EXTRA_WARD_ID) ?: ""
        wardName = intent.getStringExtra(EXTRA_WARD_NAME) ?: getString(R.string.status_ward_badge)

        if (wardId.isBlank()) {
            finish()
            return
        }

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        if (!hasLoadedInitialPolicy) {
            hasLoadedInitialPolicy = true
            loadWardPolicy()
        }
        loadPendingApprovals()
    }

    private fun initViews() {
        tvWardName = findViewById(R.id.tvWardName)
        tvWardName.text = getString(R.string.guardian_control_ward_name, wardName)

        cardOfflineBanner = findViewById(R.id.cardOfflineBanner)
        tvOfflineStatus = findViewById(R.id.tvOfflineStatus)
        btnRetryLoadPolicy = findViewById(R.id.btnRetryLoadPolicy)

        tvCurfewStartTime = findViewById(R.id.tvCurfewStartTime)
        tvCurfewEndTime = findViewById(R.id.tvCurfewEndTime)
        btnChangeStartTime = findViewById(R.id.btnChangeStartTime)
        btnChangeEndTime = findViewById(R.id.btnChangeEndTime)

        switchVpnBlackhole = findViewById(R.id.switchVpnBlackhole)
        switchIncognitoBlocked = findViewById(R.id.switchIncognitoBlocked)
        switchLockNewInstalls = findViewById(R.id.switchLockNewInstalls)

        tilDnsHost = findViewById(R.id.tilDnsHost)
        etDnsHost = findViewById(R.id.etDnsHost)
        btnPresetCloudflare = findViewById(R.id.btnPresetCloudflare)
        btnPresetNextDns = findViewById(R.id.btnPresetNextDns)

        layoutPendingApps = findViewById(R.id.layoutPendingApps)
        tvNoPendingApps = findViewById(R.id.tvNoPendingApps)
        btnSavePolicyChanges = findViewById(R.id.btnSavePolicyChanges)

        setInputsEnabled(false)
    }

    private fun setInputsEnabled(enabled: Boolean) {
        btnChangeStartTime.isEnabled = enabled
        btnChangeEndTime.isEnabled = enabled
        switchVpnBlackhole.isEnabled = enabled
        switchIncognitoBlocked.isEnabled = enabled
        switchLockNewInstalls.isEnabled = enabled
        etDnsHost.isEnabled = enabled
        btnPresetCloudflare.isEnabled = enabled
        btnPresetNextDns.isEnabled = enabled
        btnSavePolicyChanges.isEnabled = enabled
        btnRetryLoadPolicy.isEnabled = enabled
    }

    private fun setupListeners() {
        btnChangeStartTime.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            if (supportFragmentManager.findFragmentByTag("start_time_picker") != null) return@setOnClickListener
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(selectedStartHour)
                .setMinute(selectedStartMinute)
                .setTitleText(R.string.guardian_curfew_start_label)
                .build()

            picker.addOnPositiveButtonClickListener {
                selectedStartHour = picker.hour
                selectedStartMinute = picker.minute
                updateTimingLabels()
            }
            picker.show(supportFragmentManager, "start_time_picker")
        }

        btnChangeEndTime.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            if (supportFragmentManager.findFragmentByTag("end_time_picker") != null) return@setOnClickListener
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(selectedEndHour)
                .setMinute(selectedEndMinute)
                .setTitleText(R.string.guardian_curfew_end_label)
                .build()

            picker.addOnPositiveButtonClickListener {
                selectedEndHour = picker.hour
                selectedEndMinute = picker.minute
                updateTimingLabels()
            }
            picker.show(supportFragmentManager, "end_time_picker")
        }

        btnPresetCloudflare.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST)
            tilDnsHost.error = null
        }

        btnPresetNextDns.setOnClickListener {
            etDnsHost.setText(DevicePolicyBundle.LEGACY_DEFAULT_NEXTDNS_HOST)
            tilDnsHost.error = null
        }

        btnSavePolicyChanges.setOnClickListener {
            saveAndPushPolicy()
        }

        btnRetryLoadPolicy.setOnClickListener {
            btnRetryLoadPolicy.isEnabled = false
            loadWardPolicy()
            loadPendingApprovals()
        }
    }

    private fun updateTimingLabels() {
        tvCurfewStartTime.text = getString(R.string.guardian_curfew_time_format, selectedStartHour, selectedStartMinute)
        tvCurfewEndTime.text = getString(R.string.guardian_curfew_time_format, selectedEndHour, selectedEndMinute)
    }

    private fun loadWardPolicy() {
        setInputsEnabled(false)
        lifecycleScope.launch(Dispatchers.IO) {
            if (isFinishing || isDestroyed) return@launch
            var isOffline = false
            var snapshot: com.google.firebase.firestore.DocumentSnapshot? = null
            try {
                val firestore = FirebaseFirestore.getInstance()
                try {
                    snapshot = firestore.collection("policies").document(wardId).get().await()
                } catch (netEx: Exception) {
                    if (netEx is kotlinx.coroutines.CancellationException) throw netEx
                    Log.w(TAG, "Network fetch failed for ward policy $wardId, attempting offline cache", netEx)
                    isOffline = true
                    try {
                        snapshot = firestore.collection("policies").document(wardId).get(Source.CACHE).await()
                    } catch (cacheEx: Exception) {
                        Log.w(TAG, "Cache fetch also failed or empty for ward $wardId", cacheEx)
                    }
                }

                if (snapshot != null && snapshot.exists() && snapshot.data != null) {
                    currentPolicy = DevicePolicyBundle.fromMap(snapshot.data!!)
                } else {
                    currentPolicy = DevicePolicyBundle.defaultPolicy()
                }

                selectedStartHour = currentPolicy.curfewStartHour
                selectedStartMinute = currentPolicy.curfewStartMinute
                selectedEndHour = currentPolicy.curfewEndHour
                selectedEndMinute = currentPolicy.curfewEndMinute

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    updateTimingLabels()
                    switchVpnBlackhole.isChecked = currentPolicy.isVpnBlackholeEnabled
                    switchIncognitoBlocked.isChecked = currentPolicy.isIncognitoBlocked
                    switchLockNewInstalls.isChecked = currentPolicy.lockNewInstallsUntilApproved
                    etDnsHost.setText(currentPolicy.dnsHost)
                    tilDnsHost.error = null
                    cardOfflineBanner.visibility = if (isOffline) View.VISIBLE else View.GONE
                    btnRetryLoadPolicy.isEnabled = true
                    setInputsEnabled(true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isFinishing || isDestroyed) return@launch
                Log.e(TAG, "Error loading ward policy for $wardId", e)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    // Resilience fallback: allow viewing and editing default policy even if offline
                    updateTimingLabels()
                    switchVpnBlackhole.isChecked = currentPolicy.isVpnBlackholeEnabled
                    switchIncognitoBlocked.isChecked = currentPolicy.isIncognitoBlocked
                    switchLockNewInstalls.isChecked = currentPolicy.lockNewInstallsUntilApproved
                    etDnsHost.setText(currentPolicy.dnsHost)
                    tilDnsHost.error = null
                    cardOfflineBanner.visibility = View.VISIBLE
                    btnRetryLoadPolicy.isEnabled = true
                    setInputsEnabled(true)
                    Toast.makeText(this@GuardianControlActivity, R.string.toast_load_policy_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadPendingApprovals() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (isFinishing || isDestroyed) return@launch
            try {
                val firestore = FirebaseFirestore.getInstance()
                val snapshot = firestore.collection("approvals")
                    .whereEqualTo("wardUid", wardId)
                    .whereEqualTo("status", "PENDING")
                    .get()
                    .await()

                val pendingList = mutableListOf<Pair<String, String>>()
                for (doc in snapshot.documents) {
                    val pkg = doc.getString("packageName") ?: ""
                    val appName = doc.getString("appName") ?: pkg
                    if (pkg.isNotBlank()) {
                        pendingList.add(pkg to appName)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    renderPendingApprovals(pendingList)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isFinishing || isDestroyed) return@launch
                Log.e(TAG, "Error loading pending approvals for ward $wardId", e)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    layoutPendingApps.removeAllViews()
                    val errorView = TextView(this@GuardianControlActivity).apply {
                        text = getString(R.string.error_loading_pending_apps)
                        setTextColor(getColor(R.color.status_orange))
                        textSize = 13f
                        val pad = (12 * resources.displayMetrics.density).toInt()
                        setPadding(0, pad, 0, pad)
                    }
                    layoutPendingApps.addView(errorView)
                    Toast.makeText(this@GuardianControlActivity, R.string.error_loading_pending_apps, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderPendingApprovals(items: List<Pair<String, String>>) {
        layoutPendingApps.removeAllViews()
        if (items.isEmpty()) {
            layoutPendingApps.addView(tvNoPendingApps)
            return
        }

        for ((pkg, name) in items) {
            val card = MaterialCardView(this).apply {
                radius = resources.displayMetrics.density * 8
                cardElevation = resources.displayMetrics.density * 1
                setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer))
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
                strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
                layoutParams = lp
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            val titleView = TextView(this).apply {
                text = name
                textSize = 14f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            container.addView(titleView)

            val pkgView = TextView(this).apply {
                text = pkg
                textSize = 11f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
            }
            container.addView(pkgView)

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }

            var approveBtn: MaterialButton? = null
            var rejectBtn: MaterialButton? = null

            approveBtn = MaterialButton(this).apply {
                text = getString(R.string.btn_approve_app)
                textSize = 12f
                cornerRadius = (6 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    resolveApproval(pkg, approve = true, approveBtn = approveBtn, rejectBtn = rejectBtn)
                }
            }
            btnRow.addView(approveBtn)

            rejectBtn = MaterialButton(this).apply {
                text = getString(R.string.btn_reject_app)
                textSize = 12f
                setTextColor(getColor(R.color.status_orange))
                strokeWidth = 0
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    resolveApproval(pkg, approve = false, approveBtn = approveBtn, rejectBtn = rejectBtn)
                }
            }

            btnRow.addView(rejectBtn)

            container.addView(btnRow)
            card.addView(container)
            layoutPendingApps.addView(card)
        }
    }

    private fun resolveApproval(
        packageName: String,
        approve: Boolean,
        approveBtn: MaterialButton? = null,
        rejectBtn: MaterialButton? = null
    ) {
        if (processingPackages.contains(packageName)) return
        processingPackages.add(packageName)
        approveBtn?.isEnabled = false
        rejectBtn?.isEnabled = false

        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            try {
                val approvalRepo = ApprovalRepository.getInstance(applicationContext)
                val guardianName = getString(R.string.guardian_default_name)
                val success = approvalRepo.resolveWardApproval(wardId, packageName, approve, guardianName)
                if (isFinishing || isDestroyed) return@launch
                if (success) {
                    Toast.makeText(
                        this@GuardianControlActivity,
                        if (approve) getString(R.string.toast_approval_granted) else getString(R.string.toast_approval_rejected),
                        Toast.LENGTH_SHORT
                    ).show()
                    loadPendingApprovals()
                } else {
                    approveBtn?.isEnabled = true
                    rejectBtn?.isEnabled = true
                    Toast.makeText(
                        this@GuardianControlActivity,
                        getString(R.string.toast_approval_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isFinishing || isDestroyed) return@launch
                Log.e(TAG, "Error resolving approval for $packageName", e)
                approveBtn?.isEnabled = true
                rejectBtn?.isEnabled = true
                Toast.makeText(
                    this@GuardianControlActivity,
                    getString(R.string.toast_approval_failed),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                processingPackages.remove(packageName)
            }
        }
    }

    private fun saveAndPushPolicy() {
        var rawDns = etDnsHost.text?.toString()?.trim()?.lowercase() ?: ""
        rawDns = rawDns.removePrefix("https://").removePrefix("http://").removePrefix("tls://")
        if (rawDns.contains("/")) {
            rawDns = rawDns.substringBefore("/")
        }
        if (rawDns.contains(":")) {
            rawDns = rawDns.substringBefore(":")
        }

        if (!rawDns.matches(Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"))) {
            tilDnsHost.error = getString(R.string.error_invalid_dns_host)
            etDnsHost.requestFocus()
            return
        }
        tilDnsHost.error = null

        if (isSavingPolicy) return
        isSavingPolicy = true
        setInputsEnabled(false)
        val updatedPolicy = currentPolicy.copy(
            curfewStartHour = selectedStartHour,
            curfewStartMinute = selectedStartMinute,
            curfewEndHour = selectedEndHour,
            curfewEndMinute = selectedEndMinute,
            isVpnBlackholeEnabled = switchVpnBlackhole.isChecked,
            isIncognitoBlocked = switchIncognitoBlocked.isChecked,
            lockNewInstallsUntilApproved = switchLockNewInstalls.isChecked,
            dnsHost = rawDns,
            version = currentPolicy.version + 1,
            updatedAt = System.currentTimeMillis()
        )

        lifecycleScope.launch {
            if (isFinishing || isDestroyed) {
                isSavingPolicy = false
                return@launch
            }
            try {
                val policyRepo = PolicyRepository.getInstance(applicationContext)
                val success = policyRepo.pushPolicyToWard(wardId, updatedPolicy)
                if (isFinishing || isDestroyed) return@launch
                if (success) {
                    Toast.makeText(this@GuardianControlActivity, R.string.toast_policy_saved, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this@GuardianControlActivity, getString(R.string.toast_policy_push_failed), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isFinishing || isDestroyed) return@launch
                Log.e(TAG, "Error pushing policy to ward", e)
                Toast.makeText(this@GuardianControlActivity, getString(R.string.toast_policy_push_failed), Toast.LENGTH_LONG).show()
            } finally {
                isSavingPolicy = false
                if (!isFinishing && !isDestroyed) {
                    setInputsEnabled(true)
                }
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_GuardianCtrl"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_WARD_NAME = "extra_ward_name"
    }
}
