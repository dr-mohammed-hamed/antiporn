package com.drmohammed.antiporn.ui.accountability

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GuardianApprovalsHubActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabLayout: TabLayout
    private lateinit var rvApprovals: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var ivEmptyIcon: ImageView
    private lateinit var tvEmptyTitle: TextView

    private lateinit var adapter: GuardianApprovalsAdapter

    private var targetWardId: String? = null
    private var targetPackage: String? = null
    private var canonicalTargetWardId: String? = null
    private var hasScrolledToTarget = false

    private val allApprovals = mutableListOf<WardApprovalModel>()
    private val wardMap = mutableMapOf<String, String>()
    private val firestoreListeners = mutableListOf<ListenerRegistration>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian_approvals_hub)

        targetWardId = intent.getStringExtra(EXTRA_WARD_ID)
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        hasScrolledToTarget = false

        applyWindowInsets()
        initViews()
        setupRecyclerView()
        setupListeners()
        loadWardsAndListenApprovals()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetWardId = intent.getStringExtra(EXTRA_WARD_ID)
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        canonicalTargetWardId = null
        hasScrolledToTarget = false
        loadWardsAndListenApprovals()
    }

    private fun applyWindowInsets() {
        findViewById<View>(R.id.layoutRootApprovalsHub)?.let { root ->
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
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tabLayout = findViewById(R.id.tabLayoutApprovals)
        rvApprovals = findViewById(R.id.rvApprovals)
        progressBar = findViewById(R.id.progressBar)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        ivEmptyIcon = findViewById(R.id.ivEmptyIcon)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
    }

    private fun setupRecyclerView() {
        adapter = GuardianApprovalsAdapter(
            onApproveClick = { item, callback -> resolveApproval(item, approve = true, onComplete = callback) },
            onRejectClick = { item, callback -> resolveApproval(item, approve = false, onComplete = callback) },
            onRevokeClick = { item, callback -> resolveApproval(item, approve = false, onComplete = callback) },
            onReApproveClick = { item, callback -> resolveApproval(item, approve = true, onComplete = callback) }
        )
        rvApprovals.layoutManager = LinearLayoutManager(this)
        rvApprovals.adapter = adapter
    }

    private fun setupListeners() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                filterAndDisplay()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadWardsAndListenApprovals() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val accountabilityRepo = AccountabilityRepository.getInstance(applicationContext)
            val partnerDao = HisnRoomDatabase.getInstance(applicationContext).partnerDao()
            val localWards = partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_WARD).toMutableList()

            // If a specific targetWardId was passed from a notification, ensure it is included
            targetWardId?.let { twId ->
                if (twId.isNotBlank() && localWards.none { it.id == twId }) {
                    val specificPartner = partnerDao.getPartnerById(twId)
                    if (specificPartner != null) {
                        localWards.add(specificPartner)
                    } else {
                        localWards.add(
                            PartnerEntity(
                                id = twId,
                                pairingCode = "",
                                displayName = getString(R.string.status_ward_badge),
                                role = PartnerEntity.ROLE_WARD,
                                status = PartnerEntity.STATUS_ACTIVE,
                                createdAt = TrustedTimeProvider.currentTimeMillis(applicationContext)
                            )
                        )
                    }
                }
            }

            val resolvedTarget = targetWardId?.let { twId ->
                if (twId.isNotBlank()) accountabilityRepo.resolveActivePartnerUid(twId, null) else null
            }

            // Resolve each ward's active canonical UID before querying Firestore
            val resolvedWards = localWards.map { ward ->
                val canonicalUid = accountabilityRepo.resolveActivePartnerUid(ward.id, ward.pairingCode)
                if (canonicalUid != ward.id) {
                    ward.copy(id = canonicalUid)
                } else {
                    ward
                }
            }.distinctBy { it.id }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                canonicalTargetWardId = resolvedTarget
                wardMap.clear()
                for (ward in resolvedWards) {
                    wardMap[ward.id] = ward.displayName
                }

                if (resolvedWards.isEmpty()) {
                    progressBar.visibility = View.GONE
                    filterAndDisplay()
                    return@withContext
                }

                attachFirestoreListeners(resolvedWards)
            }
        }
    }

    private fun attachFirestoreListeners(wards: List<PartnerEntity>) {
        detachFirestoreListeners()
        val firestore = FirebaseFirestore.getInstance()

        val wardsToListen = wards

        for (ward in wardsToListen) {
            val wardId = ward.id
            val wardDisplayName = ward.displayName

            try {
                val listener = firestore.collection("policies").document(wardId)
                    .collection("approvals")
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "Error in approvals listener for ward $wardId", error)
                            return@addSnapshotListener
                        }
                        if (isFinishing || isDestroyed || snapshot == null) return@addSnapshotListener

                        lifecycleScope.launch(Dispatchers.Default) {
                            val itemsForWard = mutableListOf<WardApprovalModel>()
                            for (doc in snapshot.documents) {
                                val pkg = doc.getString("packageName") ?: doc.id
                                val name = doc.getString("appName") ?: pkg
                                val status = doc.getString("status") ?: "PENDING"
                                val requestedAt = doc.getLong("requestedAt") ?: TrustedTimeProvider.currentTimeMillis(applicationContext)
                                val decidedBy = doc.getString("decidedBy")
                                val decidedAt = doc.getLong("decidedAt")
                                val isHighlighted = (pkg == targetPackage && (targetWardId.isNullOrBlank() || wardId == targetWardId || wardId == canonicalTargetWardId))

                                if (pkg.isNotBlank()) {
                                    itemsForWard.add(
                                        WardApprovalModel(
                                            packageName = pkg,
                                            appName = name,
                                            wardId = wardId,
                                            wardName = wardDisplayName,
                                            status = status,
                                            requestedAt = requestedAt,
                                            decidedBy = decidedBy,
                                            decidedAt = decidedAt,
                                            isHighlighted = isHighlighted
                                        )
                                    )
                                }
                            }

                            withContext(Dispatchers.Main) {
                                if (isFinishing || isDestroyed) return@withContext
                                progressBar.visibility = View.GONE
                                allApprovals.removeAll { it.wardId == wardId }
                                allApprovals.addAll(itemsForWard)
                                updateTabBadges()
                                filterAndDisplay()
                            }
                        }
                    }
                firestoreListeners.add(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed attaching approvals listener for $wardId", e)
            }
        }
    }

    private fun detachFirestoreListeners() {
        for (l in firestoreListeners) {
            l.remove()
        }
        firestoreListeners.clear()
    }

    private fun updateTabBadges() {
        val pendingCount = allApprovals.count { it.status.equals("PENDING", ignoreCase = true) }
        val approvedCount = allApprovals.count { it.status.equals("APPROVED", ignoreCase = true) }
        val rejectedCount = allApprovals.count { it.status.equals("REJECTED", ignoreCase = true) }

        tabLayout.getTabAt(0)?.text = if (pendingCount > 0) {
            "${getString(R.string.tab_approvals_pending)} ($pendingCount)"
        } else {
            getString(R.string.tab_approvals_pending)
        }

        tabLayout.getTabAt(1)?.text = if (approvedCount > 0) {
            "${getString(R.string.tab_approvals_approved)} ($approvedCount)"
        } else {
            getString(R.string.tab_approvals_approved)
        }

        tabLayout.getTabAt(2)?.text = if (rejectedCount > 0) {
            "${getString(R.string.tab_approvals_rejected)} ($rejectedCount)"
        } else {
            getString(R.string.tab_approvals_rejected)
        }
    }

    private fun filterAndDisplay() {
        val selectedTab = tabLayout.selectedTabPosition
        val targetStatus = when (selectedTab) {
            0 -> "PENDING"
            1 -> "APPROVED"
            2 -> "REJECTED"
            else -> "PENDING"
        }

        val filtered = allApprovals.filter { it.status.equals(targetStatus, ignoreCase = true) }
            .sortedByDescending { it.requestedAt }

        if (filtered.isEmpty()) {
            rvApprovals.visibility = View.GONE
            layoutEmptyState.visibility = View.VISIBLE
            tvEmptyTitle.text = when (selectedTab) {
                0 -> getString(R.string.empty_approvals_pending)
                1 -> getString(R.string.empty_approvals_approved)
                else -> getString(R.string.empty_approvals_rejected)
            }
        } else {
            layoutEmptyState.visibility = View.GONE
            rvApprovals.visibility = View.VISIBLE
            adapter.submitList(filtered) {
                // If there is a highlighted target package, scroll directly to it once
                val targetIndex = filtered.indexOfFirst {
                    it.packageName == targetPackage && (targetWardId.isNullOrBlank() || it.wardId == targetWardId || it.wardId == canonicalTargetWardId)
                }
                if (!hasScrolledToTarget && targetIndex >= 0) {
                    hasScrolledToTarget = true
                    rvApprovals.smoothScrollToPosition(targetIndex)
                }
            }
        }
    }

    private fun resolveApproval(item: WardApprovalModel, approve: Boolean, onComplete: (Boolean) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                val approvalRepo = ApprovalRepository.getInstance(applicationContext)
                val guardianName = AccountabilityRepository.getInstance(applicationContext).getMyDisplayName()
                approvalRepo.resolveWardApproval(
                    wardUid = item.wardId,
                    packageName = item.packageName,
                    approve = approve,
                    guardianName = guardianName
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error resolving approval for ${item.packageName}", e)
                false
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                onComplete(success)
                if (success) {
                    val msg = if (approve) {
                        getString(R.string.toast_approval_granted)
                    } else {
                        getString(R.string.toast_approval_rejected)
                    }
                    Toast.makeText(this@GuardianApprovalsHubActivity, msg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this@GuardianApprovalsHubActivity,
                        getString(R.string.toast_approval_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        detachFirestoreListeners()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        private const val TAG = "ApprovalsHubActivity"
    }
}
