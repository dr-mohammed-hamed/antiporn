package com.drmohammed.antiporn.data.repository

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Repository responsible for managing Zero-Trust app installation approvals.
 * Coordinates immediate local suspension, cloud queue logging, and remote unfreezing.
 */
class ApprovalRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val database = AntiPornDatabase.getInstance(appContext)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var approvalsListener: ListenerRegistration? = null

    /**
     * Intercepts a newly installed package:
     * Freezes it in 0ms via DevicePolicyManager, records it locally, and submits a cloud approval ticket.
     */
    fun freezeAndSubmitApproval(packageName: String) {
        if (packageName == appContext.packageName ||
            AppPolicyRegistry.isImmune(packageName) ||
            CurfewAppResolver.resolveImmunePackages(appContext).contains(packageName)
        ) {
            Log.i(TAG, "Package $packageName is immune/self. Skipping freeze and approval submission.")
            return
        }

        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val admin = ComponentName(appContext, AdminReceiver::class.java)

        if (!dpm.isDeviceOwnerApp(appContext.packageName)) {
            Log.w(TAG, "Cannot freeze $packageName: Not Device Owner")
            return
        }

        // 1. Instant 0ms suspension
        try {
            dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            Log.i(TAG, "Instant 0ms freeze applied to package: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed instant freeze for $packageName", e)
        }

        // 2. Resolve human-readable application label
        val appName = try {
            val pm = appContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        val approvalEntity = PendingApprovalEntity(
            packageName = packageName,
            appName = appName,
            requestedAt = System.currentTimeMillis(),
            status = PendingApprovalEntity.STATUS_PENDING,
            isCloudSynced = false
        )

        // 3. Save locally in SQLite
        database.savePendingApproval(approvalEntity)

        // 4. Push to Cloud Firestore for Guardian review
        scope.launch {
            uploadSingleApproval(approvalEntity)
        }
    }

    /**
     * Uploads all locally stored pending approvals that have not yet reached Firestore (e.g. created while offline).
     */
    suspend fun syncPendingApprovalsToCloud() = withContext(Dispatchers.IO) {
        try {
            val unsynced = database.getUnsyncedApprovals()
            if (unsynced.isEmpty()) return@withContext
            Log.i(TAG, "Syncing ${unsynced.size} unsynced pending approvals to cloud")
            for (approval in unsynced) {
                uploadSingleApproval(approval)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncPendingApprovalsToCloud", e)
        }
    }

    private suspend fun uploadSingleApproval(approval: PendingApprovalEntity) {
        try {
            val uid = AccountabilityRepository.getInstance(appContext).getCachedUid()
            val firestore = FirebaseFirestore.getInstance()
            val data = mapOf(
                "packageName" to approval.packageName,
                "appName" to approval.appName,
                "wardUid" to uid,
                "status" to approval.status,
                "requestedAt" to approval.requestedAt
            )
            firestore.collection("approvals").document("${uid}_${approval.packageName}").set(data).await()
            database.markApprovalCloudSynced(approval.packageName, true)
            Log.i(TAG, "Uploaded approval ticket to cloud for ${approval.packageName} with UID $uid")
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading approval ticket to Firestore for ${approval.packageName}", e)
        }
    }

    /**
     * Unfreezes and approves a pending application.
     * Guaranteed Invariants:
     * 1. Permanently blacklisted applications (Telegram, TikTok, etc.) are NEVER unsuspended.
     * 2. If Night Curfew or Nap is currently active and the app is not whitelisted, the approval
     *    is recorded in the database, but suspension remains active until morning curfew wakeup.
     */
    fun unsuspendAndApprove(packageName: String, decidedBy: String? = null) {
        if (AppSuspensionShield.isBlacklisted(packageName)) {
            Log.w(TAG, "Security Alert: Cannot unsuspend $packageName - permanently blacklisted!")
            database.updateApprovalStatus(packageName, PendingApprovalEntity.STATUS_REJECTED, "SECURITY_GUARD")
            return
        }

        val policy = PolicyRepository.getInstance(appContext).getCurrentPolicy()
        if (policy.customBlacklistPackages.contains(packageName)) {
            Log.w(TAG, "Security Alert: Cannot unsuspend $packageName - in custom blacklist!")
            database.updateApprovalStatus(packageName, PendingApprovalEntity.STATUS_REJECTED, "CUSTOM_BLACKLIST")
            return
        }

        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val admin = ComponentName(appContext, AdminReceiver::class.java)

        if (!dpm.isDeviceOwnerApp(appContext.packageName)) return

        val isBlockActive = CurfewEnforcer.isEffectiveBlockActive(appContext)
        val isWhitelisted = CurfewConfig.STATIC_WHITELIST.contains(packageName) ||
                CurfewAppResolver.resolveImmunePackages(appContext).contains(packageName)

        if (isBlockActive && !isWhitelisted) {
            Log.i(TAG, "Package $packageName approved by $decidedBy, but kept suspended until curfew wakeup")
            database.updateApprovalStatus(packageName, PendingApprovalEntity.STATUS_APPROVED, decidedBy)
            return
        }

        try {
            dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
            database.updateApprovalStatus(packageName, PendingApprovalEntity.STATUS_APPROVED, decidedBy)
            Log.i(TAG, "Unfrozen and approved package: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed unfreezing package $packageName", e)
        }
    }

    /**
     * Records a rejection for a pending application in local database.
     */
    fun recordRejection(packageName: String, decidedBy: String? = null) {
        try {
            database.updateApprovalStatus(packageName, PendingApprovalEntity.STATUS_REJECTED, decidedBy)
            Log.i(TAG, "Recorded rejection for package: $packageName by $decidedBy")
        } catch (e: Exception) {
            Log.e(TAG, "Failed recording rejection for package $packageName", e)
        }
    }

    /**
     * Starts listening to Firestore for real-time approvals for this ward device.
     * When an approval snapshot arrives, unsuspends the app locally and marks it approved.
     */
    fun startApprovalsSync(wardUid: String) {
        approvalsListener?.remove()
        try {
            val firestore = FirebaseFirestore.getInstance()
            approvalsListener = firestore.collection("approvals")
                .whereEqualTo("wardUid", wardUid)
                .whereEqualTo("status", "APPROVED")
                .addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.w(TAG, "Error listening for approval updates: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshots != null && !snapshots.isEmpty) {
                        scope.launch {
                            try {
                                for (doc in snapshots.documents) {
                                    val packageName = doc.getString("packageName") ?: continue
                                    val decidedBy = doc.getString("decidedBy")
                                    val pendingList = getLocalPendingApprovals()
                                    val local = pendingList.find { it.packageName == packageName }
                                    if (local == null || local.isPending) {
                                        Log.i(TAG, "Real-time approval received for $packageName from $decidedBy")
                                        unsuspendAndApprove(packageName, decidedBy)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling approval snapshot update", e)
                            }
                        }
                    }
                }
            Log.i(TAG, "Started approvals sync for ward $wardUid")
            scope.launch {
                syncPendingApprovalsToCloud()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting approvals sync", e)
        }
    }

    /**
     * Submits an approval or rejection from a Guardian to a Ward.
     */
    suspend fun resolveWardApproval(
        wardUid: String,
        packageName: String,
        approve: Boolean,
        guardianName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val firestore = FirebaseFirestore.getInstance()
            val docRef = firestore.collection("approvals").document("${wardUid}_${packageName}")
            val newStatus = if (approve) "APPROVED" else "REJECTED"
            val updates = mapOf(
                "status" to newStatus,
                "decidedBy" to guardianName,
                "decidedAt" to System.currentTimeMillis()
            )
            docRef.update(updates).await()
            Log.i(TAG, "Resolved ward approval for $packageName: $newStatus")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving ward approval", e)
            false
        }
    }

    fun getLocalPendingApprovals(): List<PendingApprovalEntity> = database.getPendingApprovals()

    companion object {
        private const val TAG = "AntiPorn_ApprovalRepo"

        @Volatile
        private var instance: ApprovalRepository? = null

        fun getInstance(context: Context): ApprovalRepository {
            return instance ?: synchronized(this) {
                instance ?: ApprovalRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
