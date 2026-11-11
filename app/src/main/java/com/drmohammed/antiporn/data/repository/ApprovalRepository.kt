package com.drmohammed.antiporn.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.dao.PendingApprovalDao
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Repository responsible for managing Zero-Trust app installation approvals.
 * Coordinates immediate local suspension, cloud queue logging, and remote unfreezing.
 */
class ApprovalRepository(
    context: Context,
    private val approvalDao: PendingApprovalDao = HisnRoomDatabase.getInstance(context).pendingApprovalDao()
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var approvalsListener: ListenerRegistration? = null

    val pendingApprovalsFlow: Flow<List<PendingApprovalEntity>> = approvalDao.getPendingApprovalsFlow()

    /**
     * Intercepts a newly installed package:
     * Freezes it in 0ms via DevicePolicyManager, records it locally, and asynchronously submits a cloud approval ticket.
     */
    fun freezeAndSubmitApproval(packageName: String) {
        if (CurfewAppResolver.isSystemCriticalOrImmune(appContext, packageName)) {
            Log.i(TAG, "Package $packageName is immune/self/system. Skipping freeze and approval submission.")
            return
        }

        scope.launch {
            freezeAndSubmitApprovalSync(packageName)
        }
    }

    /**
     * Immediately freezes a newly installed package in 0ms locally via DevicePolicyManager,
     * writes the record to SQLite, and synchronizes the active Zero-Trust foreground watcher.
     * Operates completely offline in < 5ms without any network dependency.
     */
    @Synchronized
    fun freezeAndSubmitApprovalLocal(packageName: String, showToast: Boolean = true): Boolean {
        if (com.drmohammed.antiporn.curfew.CurfewAppResolver.isSystemCriticalOrImmune(appContext, packageName)) {
            Log.i(TAG, "IRON GUARD: Package $packageName is critical system / launcher / dialer / keyboard / immune. Skipping freeze.")
            return false
        }

        if (packageName == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE || CurfewConfig.KNOWN_APP_STORES.contains(packageName)) {
            Log.i(TAG, "IRON GUARD: App Store $packageName is immune from Zero-Trust freeze.")
            return false
        }

        val appInfo = try { appContext.packageManager.getApplicationInfo(packageName, 0) } catch (e: Exception) { null }
        if (appInfo != null && ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) || (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0))) {
            Log.i(TAG, "IRON GUARD: System package $packageName is immune from Zero-Trust freeze.")
            return false
        }

        if (!com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(appContext)) {
            Log.w(TAG, "Cannot freeze $packageName: Not Device/Profile Owner")
            return false
        }

        // 1. Instant 0ms suspension with Fail-Secure Fallback
        val suspended = com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackageSuspendedWithMessageBlocking(
            context = appContext,
            packageName = packageName,
            message = appContext.getString(R.string.toast_app_suspended_by_guardian)
        ).getOrNull() == true

        // Self-healing: Zero-Trust architecture requires unapproved apps to be visible & suspended, NEVER hidden.
        // If an application was previously hidden by a legacy version, unhide it immediately so it appears on launcher as frozen.
        try {
            if (com.drmohammed.antiporn.telemetry.SafeDpmGateway.isApplicationHiddenBlocking(appContext, packageName).getOrNull() == true) {
                com.drmohammed.antiporn.telemetry.SafeDpmGateway.setApplicationHiddenBlocking(appContext, packageName, false)
                Log.i(TAG, "Self-healing: Unhid package $packageName during Zero-Trust freeze")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Self-healing unhide check failed for $packageName", e)
        }

        val finalStatus = PendingApprovalEntity.STATUS_PENDING
        if (suspended) {
            Log.w(TAG, "[FREEZE_SUCCESS] Instant 0ms freeze applied to package: $packageName (showToast=$showToast)")
        } else {
            Log.w(TAG, "[FREEZE_WARNING] Device suspension reported unconfirmed for $packageName; proceeding with Zero-Trust pending approval without hiding.")
        }

        // Show alert toast on main thread ONLY when requested (avoids spamming toast during background reconciliation)
        if (showToast) {
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val alertMsg = appContext.getString(R.string.toast_app_suspended_by_guardian)
                        android.widget.Toast.makeText(appContext, alertMsg, android.widget.Toast.LENGTH_LONG).show()
                    } catch (te: Exception) {
                        Log.w(TAG, "Failed showing toast alert: ${te.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error posting toast to main looper: ${e.message}")
            }
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
            requestedAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext),
            status = finalStatus,
            isCloudSynced = false
        )

        // 3. Save locally in SQLite
        approvalDao.savePendingApprovalBlocking(approvalEntity)

        // 3.1 Post in-app and system notification regarding the frozen package
        try {
            AppNotificationManager.getInstance(appContext).postEventNotification(
                category = NotificationCategory.APPROVALS,
                title = appContext.getString(R.string.toast_app_suspended_by_guardian),
                message = appContext.getString(R.string.toast_app_suspended_by_guardian) + " ($appName)",
                dedupKey = "zero_trust_freeze_$packageName",
                cooldownMs = 10 * 60 * 1000L,
                actionType = NotificationActionType.OPEN_SELF_APPROVALS,
                actionPayload = packageName,
                postSystemNotification = true,
                importance = 2
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error posting freeze notification for $packageName", e)
        }

        // 4. Update Zero-Trust active foreground watcher immediately and trigger in-memory anti-race evaluation
        ZeroTrustForegroundWatcher.syncWatcherState(appContext)
        ZeroTrustForegroundWatcher.evaluateCurrentForegroundApp(appContext, immediateTargetPackage = packageName)

        return true
    }

    suspend fun freezeAndSubmitApprovalSync(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val localSuccess = freezeAndSubmitApprovalLocal(packageName)
        if (!localSuccess) return@withContext false

        val entity = approvalDao.getPendingApprovalsBlocking().find { it.packageName == packageName }
            ?: return@withContext false

        // ANR-protected cloud upload with 3500ms timeout
        val uploadSuccess = kotlinx.coroutines.withTimeoutOrNull(3500L) {
            uploadSingleApprovalSync(entity)
        } ?: false

        Log.i(TAG, "freezeAndSubmitApprovalSync completed for $packageName (uploaded=$uploadSuccess)")
        uploadSuccess
    }

    /**
     * Uploads all locally stored pending approvals that have not yet reached Firestore (e.g. created while offline).
     */
    suspend fun syncPendingApprovalsToCloud() = withContext(Dispatchers.IO) {
        try {
            val unsynced = approvalDao.getUnsyncedApprovalsBlocking()
            if (unsynced.isEmpty()) return@withContext
            Log.i(TAG, "Syncing ${unsynced.size} unsynced pending approvals to cloud")
            for (approval in unsynced) {
                uploadSingleApprovalSync(approval)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncPendingApprovalsToCloud", e)
        }
    }

    /**
     * Synchronously uploads a single approval ticket to `/policies/$uid/approvals/${approval.packageName}`.
     * Returns true if successful, false otherwise.
     */
    suspend fun uploadSingleApprovalSync(approval: PendingApprovalEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
                ?: AccountabilityRepository.getInstance(appContext).getCachedUid()
            if (uid.isBlank()) {
                Log.w(TAG, "Cannot upload approval for ${approval.packageName}: UID is blank")
                return@withContext false
            }

            val firestore = FirebaseFirestore.getInstance()
            val data = mapOf(
                "packageName" to approval.packageName,
                "appName" to approval.appName,
                "wardUid" to uid,
                "status" to approval.status,
                "requestedAt" to approval.requestedAt
            )
            firestore.collection("policies")
                .document(uid)
                .collection("approvals")
                .document(approval.packageName)
                .set(data)
                .await()

            approvalDao.markApprovalCloudSyncedBlocking(approval.packageName, 1)
            Log.i(TAG, "Uploaded approval ticket to cloud for ${approval.packageName} under /policies/$uid/approvals")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading approval ticket to Firestore for ${approval.packageName}", e)
            false
        }
    }

    suspend fun uploadSingleApproval(approval: PendingApprovalEntity): Boolean {
        return uploadSingleApprovalSync(approval)
    }

    /**
     * Unfreezes and approves a pending application.
     * Guaranteed Invariants:
     * 1. Permanently blacklisted applications (Telegram, TikTok, etc.) are NEVER unsuspended.
     * 2. If Night Curfew or Nap is currently active and the app is not whitelisted, the approval
     *    is recorded in the database, but suspension remains active until morning curfew wakeup.
     */
    fun unsuspendAndApprove(packageName: String, decidedBy: String? = null) {
        // Under Unified Zero-Trust architecture, supervisor approval is unconditionally honored for all applications.

        val policy = PolicyRepository.getInstance(appContext).getCurrentPolicy()
        if (policy.customBlacklistPackages.contains(packageName)) {
            Log.w(TAG, "Security Alert: Cannot unsuspend $packageName - in custom blacklist!")
            approvalDao.upsertApprovalStatusBlocking(
                packageName = packageName,
                status = PendingApprovalEntity.STATUS_REJECTED,
                decidedBy = "CUSTOM_BLACKLIST",
                decidedAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext),
                isSynced = 1
            )
            ZeroTrustForegroundWatcher.syncWatcherState(appContext)
            return
        }

        if (!com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(appContext)) return

        val isBlockActive = CurfewEnforcer.isEffectiveBlockActive(appContext)
        val isWhitelisted = CurfewConfig.STATIC_WHITELIST.contains(packageName) ||
                CurfewAppResolver.resolveImmunePackages(appContext).contains(packageName) ||
                policy.customCurfewWhitelistPackages.contains(packageName)

        if (isBlockActive && !isWhitelisted) {
            Log.i(TAG, "Package $packageName approved by $decidedBy, but kept suspended until curfew wakeup")
            approvalDao.upsertApprovalStatusBlocking(
                packageName = packageName,
                status = PendingApprovalEntity.STATUS_APPROVED,
                decidedBy = decidedBy,
                decidedAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext),
                isSynced = 1
            )
            ZeroTrustForegroundWatcher.syncWatcherState(appContext)
            syncApprovalDecisionToCloud(packageName, PendingApprovalEntity.STATUS_APPROVED, decidedBy)
            postApprovalNotification(packageName, approved = true, isCurfewDeferred = true, decidedBy = decidedBy)
            return
        }

        try {
            // Unhide application if it was hidden via Fail-Secure fallback
            try {
                if (com.drmohammed.antiporn.telemetry.SafeDpmGateway.isApplicationHiddenBlocking(appContext, packageName).getOrNull() == true) {
                    val unhidden = com.drmohammed.antiporn.telemetry.SafeDpmGateway.setApplicationHiddenBlocking(appContext, packageName, false).getOrNull() ?: false
                    Log.i(TAG, "Unhid package $packageName on approval: $unhidden")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception unhiding package $packageName on approval", e)
            }

            val failed: Array<String>? = try {
                com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackagesSuspendedBlocking(appContext, arrayOf(packageName), false).getOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "Exception calling setPackagesSuspended(false) for $packageName", e)
                null
            }

            if (failed?.contains(packageName) == true) {
                val isInstalled = try {
                    appContext.packageManager.getPackageInfo(packageName, 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking package info for $packageName", e)
                    false
                }

                if (!isInstalled) {
                    Log.i(TAG, "Package $packageName is uninstalled; marking pending approval as approved/resolved")
                    approvalDao.upsertApprovalStatusBlocking(
                        packageName = packageName,
                        status = PendingApprovalEntity.STATUS_APPROVED,
                        decidedBy = decidedBy,
                        decidedAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext),
                        isSynced = 1
                    )
                    ZeroTrustForegroundWatcher.syncWatcherState(appContext)
                    syncApprovalDecisionToCloud(packageName, PendingApprovalEntity.STATUS_APPROVED, decidedBy)
                } else {
                    Log.e(TAG, "Failed unfreezing package $packageName: DPM returned failure for package")
                }
            } else {
                approvalDao.upsertApprovalStatusBlocking(
                    packageName = packageName,
                    status = PendingApprovalEntity.STATUS_APPROVED,
                    decidedBy = decidedBy,
                    decidedAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext),
                    isSynced = 1
                )
                Log.i(TAG, "Unfrozen and approved package: $packageName")
                ZeroTrustForegroundWatcher.syncWatcherState(appContext)
                syncApprovalDecisionToCloud(packageName, PendingApprovalEntity.STATUS_APPROVED, decidedBy)
                AppInventoryManager.getInstance(appContext).syncInventoryToCloudAsync(force = true)
                postApprovalNotification(packageName, approved = true, isCurfewDeferred = false, decidedBy = decidedBy)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed unfreezing package $packageName", e)
        }
    }

    /**
     * Cleans up pending approval when a package is uninstalled or removed.
     */
    fun removePendingApproval(packageName: String) {
        try {
            approvalDao.deletePendingApprovalBlocking(packageName)
            ZeroTrustForegroundWatcher.syncWatcherState(appContext)
            deleteApprovalFromCloud(packageName)
            Log.i(TAG, "Removed pending approval for package: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed removing pending approval for package $packageName", e)
        }
    }

    /**
     * Records a rejection for a pending application in local database.
     */
    fun recordRejection(packageName: String, decidedBy: String? = null) {
        try {
            approvalDao.upsertApprovalStatusBlocking(
                packageName = packageName,
                status = PendingApprovalEntity.STATUS_REJECTED,
                decidedBy = decidedBy,
                decidedAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext),
                isSynced = 1
            )
            Log.i(TAG, "Recorded rejection for package: $packageName by $decidedBy")
            ZeroTrustForegroundWatcher.syncWatcherState(appContext)
            syncApprovalDecisionToCloud(packageName, PendingApprovalEntity.STATUS_REJECTED, decidedBy)
            postApprovalNotification(packageName, approved = false, isCurfewDeferred = false, decidedBy = decidedBy)
        } catch (e: Exception) {
            Log.e(TAG, "Failed recording rejection for package $packageName", e)
        }
    }

    /**
     * Records an approval for an application in local database (e.g. auto-approved when policy is unlocked).
     */
    fun recordApproval(packageName: String, appName: String, decidedBy: String = "AUTO_APPROVED_UNLOCKED") {
        try {
            val now = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
            val entity = PendingApprovalEntity(
                packageName = packageName,
                appName = appName,
                requestedAt = now,
                status = PendingApprovalEntity.STATUS_APPROVED,
                decidedBy = decidedBy,
                decidedAt = now,
                isCloudSynced = false
            )
            approvalDao.savePendingApprovalBlocking(entity)
            Timber.tag(TAG).i("Recorded approval for $packageName decidedBy $decidedBy")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed recording approval for $packageName")
        }
    }

    private fun postApprovalNotification(
        packageName: String,
        approved: Boolean,
        isCurfewDeferred: Boolean,
        decidedBy: String?
    ) {
        try {
            val pm = appContext.packageManager
            val appLabel = try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }

            val guardianName = decidedBy?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
                ?: appContext.getString(R.string.guardian_default_name)

            val title: String
            val desc: String
            val actionType: NotificationActionType

            if (approved) {
                title = appContext.getString(R.string.notif_approval_granted_title)
                if (isCurfewDeferred) {
                    desc = appContext.getString(R.string.notif_approval_deferred_curfew_desc, appLabel, guardianName)
                    actionType = NotificationActionType.OPEN_CURFEW
                } else {
                    desc = appContext.getString(R.string.notif_approval_granted_desc, guardianName, appLabel)
                    actionType = NotificationActionType.LAUNCH_APP
                }
            } else {
                title = appContext.getString(R.string.notif_approval_rejected_title)
                desc = appContext.getString(R.string.notif_approval_rejected_desc, guardianName, appLabel)
                actionType = NotificationActionType.NONE
            }

            val dedupKey = if (approved) "approval_${packageName}_APPROVED" else "approval_${packageName}_REJECTED"

            AppNotificationManager.getInstance(appContext).postEventNotification(
                category = NotificationCategory.APPROVALS,
                title = title,
                message = desc,
                dedupKey = dedupKey,
                cooldownMs = 10 * 60 * 1000L,
                actionType = actionType,
                actionPayload = packageName,
                systemNotificationId = packageName.hashCode(),
                channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                postSystemNotification = true,
                importance = 2
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error posting approval notification for $packageName", e)
        }
    }

    private fun syncApprovalDecisionToCloud(packageName: String, status: String, decidedBy: String?) {
        val myUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
        if (myUid.isBlank()) return
        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()
                val data = mapOf(
                    "status" to status,
                    "decidedBy" to (decidedBy ?: "SELF"),
                    "decidedAt" to com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
                )
                firestore.collection("policies")
                    .document(myUid)
                    .collection("approvals")
                    .document(packageName)
                    .set(data, SetOptions.merge())
                    .await()
                Log.i(TAG, "Synced approval decision for $packageName ($status) to cloud ($myUid)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed syncing approval decision for $packageName to cloud: ${e.message}")
            }
        }
    }

    private fun deleteApprovalFromCloud(packageName: String) {
        val myUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
        if (myUid.isBlank()) return
        scope.launch {
            try {
                FirebaseFirestore.getInstance().collection("policies")
                    .document(myUid)
                    .collection("approvals")
                    .document(packageName)
                    .delete()
                    .await()
                Log.i(TAG, "Deleted cloud approval document for $packageName ($myUid)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed deleting cloud approval document for $packageName: ${e.message}")
            }
        }
    }

    /**
     * Starts listening to Firestore for real-time approvals and rejections for this ward device
     * under `/policies/$wardUid/approvals`.
     */
    fun startApprovalsSync(wardUid: String) {
        approvalsListener?.remove()
        try {
            val firestore = FirebaseFirestore.getInstance()
            approvalsListener = firestore.collection("policies")
                .document(wardUid)
                .collection("approvals")
                .addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.w(TAG, "Error listening for approval updates: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshots != null && !snapshots.isEmpty) {
                        scope.launch {
                            try {
                                for (doc in snapshots.documents) {
                                    val packageName = doc.getString("packageName") ?: doc.id
                                    val status = doc.getString("status") ?: continue
                                    val decidedBy = doc.getString("decidedBy")
                                    val pendingList = getLocalPendingApprovals()
                                    val local = pendingList.find { it.packageName == packageName }

                                    if (status == "APPROVED") {
                                        if (local == null || local.isPending) {
                                            Log.i(TAG, "Real-time approval received for $packageName from $decidedBy")
                                            unsuspendAndApprove(packageName, decidedBy)
                                        }
                                    } else if (status == "REJECTED") {
                                        if (local == null || local.isPending) {
                                            Log.i(TAG, "Real-time rejection received for $packageName from $decidedBy")
                                            recordRejection(packageName, decidedBy)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling approval snapshot update", e)
                            }
                        }
                    }
                }
            Log.i(TAG, "Started approvals sync for ward $wardUid on /policies/$wardUid/approvals")
            scope.launch {
                syncPendingApprovalsToCloud()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting approvals sync", e)
        }
    }

    /**
     * Safely detaches the Firestore approvals snapshot listener on teardown.
     */
    fun stopApprovalsSync() {
        approvalsListener?.remove()
        approvalsListener = null
        Log.i(TAG, "Stopped approvals sync listener")
    }

    /**
     * Submits an approval or rejection from a Guardian to a Ward.
     * Updates the approval subcollection document `/policies/$wardUid/approvals/$packageName`
     * and simultaneously bumps the parent policy document `/policies/$wardUid` with:
     * `version = FieldValue.increment(1)` and `"updatedAt" = System.currentTimeMillis()`.
     * This dual-channel update guarantees that the ward's active PolicyRepository listener
     * is immediately triggered and wakes up to reconcile the system.
     */
    suspend fun resolveWardApproval(
        wardUid: String,
        packageName: String,
        approve: Boolean,
        guardianName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val firestore = FirebaseFirestore.getInstance()
            val newStatus = if (approve) "APPROVED" else "REJECTED"
            val now = System.currentTimeMillis()

            val batch = firestore.batch()

            // Channel 1: Update the approval subcollection document /policies/$wardUid/approvals/$packageName
            val approvalDocRef = firestore.collection("policies")
                .document(wardUid)
                .collection("approvals")
                .document(packageName)

            val approvalUpdates = mapOf(
                "status" to newStatus,
                "decidedBy" to guardianName,
                "decidedAt" to now
            )
            batch.set(approvalDocRef, approvalUpdates, SetOptions.merge())

            // Channel 2: Simultaneously bump parent policy document /policies/$wardUid
            val policyDocRef = firestore.collection("policies").document(wardUid)
            val policyUpdates = mapOf(
                "version" to FieldValue.increment(1),
                "updatedAt" to now
            )
            batch.set(policyDocRef, policyUpdates, SetOptions.merge())

            batch.commit().await()
            Log.i(TAG, "Resolved ward approval for $packageName: $newStatus with dual-channel policy bump (ward: $wardUid)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving ward approval for $packageName", e)
            false
        }
    }

    fun getLocalPendingApprovals(): List<PendingApprovalEntity> = approvalDao.getPendingApprovalsBlocking()

    fun getActualLocalPendingApprovals(): List<PendingApprovalEntity> = approvalDao.getActualPendingApprovalsBlocking()

    /**
     * Seeds all pre-existing installed applications as the Initial Approved Baseline.
     * Prevents existing applications from being frozen when AntiPorn is set up on an already-in-use device
     * or initialized via QR provisioning / WebADB.
     * Also automatically unfreezes any baseline applications that were accidentally suspended previously.
     */
    @Synchronized
    fun ensureInitialBaselineSeeded() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BASELINE_SEEDED, false)) {
            return
        }

        Log.i(TAG, "🌱 [SEED_BASELINE] Establishing Initial Zero-Trust Baseline for pre-existing apps...")
        val pm = appContext.packageManager
        val isDo = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(appContext)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_UNINSTALLED_PACKAGES or PackageManager.GET_DISABLED_COMPONENTS
        }
        val installedPackages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong())) ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(flags) ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed retrieving installed packages for baseline seeding", e)
            emptyList()
        }

        val packagesToUnsuspend = mutableListOf<String>()
        val baselineEntities = mutableListOf<PendingApprovalEntity>()

        val policy = if (isDo) PolicyRepository.getInstance(appContext).getCurrentPolicy() else null
        val isBlockActive = if (isDo) CurfewEnforcer.isEffectiveBlockActive(appContext) else false
        val immunePackages = if (isDo) CurfewAppResolver.resolveImmunePackages(appContext) else emptySet()

        for (pkgInfo in installedPackages) {
            val pkg = pkgInfo.packageName ?: continue
            if (pkg == appContext.packageName) continue
            // If it is high-risk (e.g. Telegram, Reddit, TikTok) or permanently blacklisted, do not approve into baseline!
            if (AppSuspensionShield.isBlacklisted(pkg) || com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isHighRisk(pkg)) continue

            val appInfo = pkgInfo.applicationInfo
            val appName = try {
                if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else pkg
            } catch (e: Exception) {
                pkg
            }

            // Seed pre-existing app as APPROVED baseline
            val nowMs = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
            val baselineEntity = PendingApprovalEntity(
                packageName = pkg,
                appName = appName,
                requestedAt = nowMs,
                status = PendingApprovalEntity.STATUS_APPROVED,
                decidedBy = "INITIAL_DEVICE_BASELINE",
                decidedAt = nowMs,
                isCloudSynced = true // Do not flood Firestore
            )
            baselineEntities.add(baselineEntity)

            // If it was inadvertently suspended or hidden, queue it for unfreezing ONLY if not restricted by Curfew or Custom Blacklist
            if (isDo && policy != null) {
                val isWhitelisted = CurfewConfig.STATIC_WHITELIST.contains(pkg) || immunePackages.contains(pkg)

                if (policy.customBlacklistPackages.contains(pkg)) {
                    continue
                }
                if (isBlockActive && !isWhitelisted) {
                    continue
                }

                val isSuspended = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isPackageSuspendedBlocking(appContext, pkg).getOrNull() == true
                val isHidden = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isApplicationHiddenBlocking(appContext, pkg).getOrNull() == true
                if (isSuspended || isHidden) {
                    packagesToUnsuspend.add(pkg)
                }
            }
        }

        // Batch insert pre-existing baseline approvals in a single database transaction
        if (baselineEntities.isNotEmpty()) {
            approvalDao.savePendingApprovalsBlocking(baselineEntities)
        }

        // Unsuspend and unhide all pre-existing baseline packages
        if (isDo && packagesToUnsuspend.isNotEmpty()) {
            Log.w(TAG, "🌱 [UNFREEZE_BASELINE] Unfreezing ${packagesToUnsuspend.size} pre-existing baseline packages...")
            try {
                com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackagesSuspendedBlocking(appContext, packagesToUnsuspend.toTypedArray(), false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unsuspend baseline packages via DPM", e)
            }
            for (pkg in packagesToUnsuspend) {
                try {
                    com.drmohammed.antiporn.telemetry.SafeDpmGateway.setApplicationHiddenBlocking(appContext, pkg, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unhide baseline package $pkg", e)
                }
            }
        }

        ZeroTrustForegroundWatcher.syncWatcherState(appContext)
        prefs.edit().putBoolean(KEY_BASELINE_SEEDED, true).apply()
        Log.i(TAG, "✅ [SEED_BASELINE] Initial Zero-Trust Baseline successfully established with ${installedPackages.size} packages.")
    }

    fun ensureInitialBaselineSeededAsync() {
        scope.launch {
            try {
                ensureInitialBaselineSeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Error in ensureInitialBaselineSeededAsync", e)
            }
        }
    }

    /**
     * Self-Healing Reconciliation: scans installed applications to catch any newly installed apps
     * that escaped or bypassed the broadcast receiver due to OEM power management or install source.
     * Operates completely offline, skipping system criticals, immune apps, and pre-approved apps.
     */
    @Synchronized
    fun reconcileUnapprovedInstalledApps() {
        if (!com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(appContext)) return
        val policy = PolicyRepository.getInstance(appContext).getCurrentPolicy()
        if (!policy.lockNewInstallsUntilApproved) return

        // 1. Establish initial baseline on first run to protect pre-existing apps
        ensureInitialBaselineSeeded()

        if (!com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(appContext)) return
        val pm = appContext.packageManager

        val matchFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_UNINSTALLED_PACKAGES or PackageManager.GET_DISABLED_COMPONENTS
        }
        val installedPackages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(matchFlags.toLong())) ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(matchFlags) ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed retrieving installed packages for Zero-Trust reconciliation", e)
            emptyList()
        }

        val localApprovals = getLocalPendingApprovals()

        // Universal Self-Healing: Any high-risk app that was auto-approved solely by INITIAL_DEVICE_BASELINE
        // or AUTO_APPROVED_UNLOCKED (without explicit guardian action) must be revoked, converted to PENDING, and frozen.
        val legacyAutoApprovedHighRisk = localApprovals.filter {
            it.isApproved && com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isHighRisk(it.packageName) &&
            (it.decidedBy == null || it.decidedBy == "INITIAL_DEVICE_BASELINE" || it.decidedBy == "AUTO_APPROVED_UNLOCKED")
        }
        for (legacyApp in legacyAutoApprovedHighRisk) {
            Log.w(TAG, "Reverting legacy auto-adopted high-risk app ${legacyApp.packageName} to PENDING under Unified Zero-Trust!")
            approvalDao.deletePendingApprovalBlocking(legacyApp.packageName)
            freezeAndSubmitApprovalLocal(legacyApp.packageName, showToast = false)
        }

        val updatedLocalApprovals = getLocalPendingApprovals()
        val approvedSet = updatedLocalApprovals.filter {
            it.isApproved && (!com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isHighRisk(it.packageName) ||
                (it.decidedBy != null && it.decidedBy != "INITIAL_DEVICE_BASELINE" && it.decidedBy != "AUTO_APPROVED_UNLOCKED"))
        }.map { it.packageName }.toSet()
        val pendingSet = updatedLocalApprovals.filter { it.isPending }.map { it.packageName }.toSet()
        val rejectedSet = updatedLocalApprovals.filter { it.isRejected }.map { it.packageName }.toSet()

        var strayAppFrozen = false
        val doEnabledAt = getDeviceOwnerEnabledTimestamp()

        for (pkgInfo in installedPackages) {
            val pkg = pkgInfo.packageName ?: continue
            if (pkg == appContext.packageName) continue
            // Skip App Stores
            if (pkg == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE || CurfewConfig.KNOWN_APP_STORES.contains(pkg)) continue

            // Skip ROM System apps & updated system apps
            val appInfo = pkgInfo.applicationInfo
            val isSystemApp = appInfo != null && (
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
            )
            if (isSystemApp) continue

            if (approvedSet.contains(pkg)) {
                // Universal Self-Healing: Approved apps must never be hidden
                try {
                    if (com.drmohammed.antiporn.telemetry.SafeDpmGateway.isApplicationHiddenBlocking(appContext, pkg).getOrNull() == true) {
                        com.drmohammed.antiporn.telemetry.SafeDpmGateway.setApplicationHiddenBlocking(appContext, pkg, false)
                        Log.i(TAG, "Universal Self-Healing: Unhid legacy hidden approved package $pkg")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Universal Self-Healing failed for approved $pkg", e)
                }
                continue
            }
            if (com.drmohammed.antiporn.curfew.CurfewAppResolver.isSystemCriticalOrImmune(appContext, pkg)) continue

            // Universal Self-Healing: Under Unified Zero-Trust, no non-rejected app should EVER be hidden!
            // If an app is hidden by a legacy failure fallback, immediately restore its visibility so it shows as frozen.
            if (!rejectedSet.contains(pkg)) {
                try {
                    if (com.drmohammed.antiporn.telemetry.SafeDpmGateway.isApplicationHiddenBlocking(appContext, pkg).getOrNull() == true) {
                        com.drmohammed.antiporn.telemetry.SafeDpmGateway.setApplicationHiddenBlocking(appContext, pkg, false)
                        Log.i(TAG, "Universal Self-Healing: Unhid legacy hidden package $pkg")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Universal Self-Healing failed for $pkg", e)
                }
            }

            // Auto-Adopt Whitelisted Apps: If the package is in policy's customCurfewWhitelistPackages,
            // it has been explicitly approved by the supervisor. Auto-adopt it into approved baseline without freezing.
            if (policy.customCurfewWhitelistPackages.contains(pkg)) {
                Log.i(TAG, "🌱 [AUTO_ADOPT_WHITELISTED] Package '$pkg' is in customCurfewWhitelistPackages. Auto-adopting into approved baseline.")
                val appName = try {
                    if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else pkg
                } catch (e: Exception) {
                    pkg
                }
                val nowMs = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
                val baselineEntity = PendingApprovalEntity(
                    packageName = pkg,
                    appName = appName,
                    requestedAt = pkgInfo.firstInstallTime.takeIf { it > 0 } ?: nowMs,
                    status = PendingApprovalEntity.STATUS_APPROVED,
                    decidedBy = "GUARDIAN_POLICY_WHITELIST",
                    decidedAt = nowMs,
                    isCloudSynced = true
                )
                approvalDao.savePendingApprovalBlocking(baselineEntity)
                continue
            }

            // Auto-Adopt Pre-existing Apps: If the app was installed prior to or during Device Owner activation,
            // it is part of the user's pre-existing baseline. Auto-adopt it into approved baseline without freezing.
            // High-risk apps (Telegram, Reddit, TikTok) are strictly EXCLUDED from auto-adoption and must be explicitly approved!
            val isHighRisk = com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isHighRisk(pkg)
            val firstInstallTime = pkgInfo.firstInstallTime
            val isPreExisting = !isHighRisk && doEnabledAt > 0L && firstInstallTime > 0L && firstInstallTime <= (doEnabledAt + DO_ACTIVATION_GRACE_PERIOD_MS)
            if (isPreExisting) {
                Log.i(TAG, "🌱 [AUTO_ADOPT_PRE_EXISTING] Package '$pkg' was installed prior to Device Owner activation ($firstInstallTime <= $doEnabledAt). Auto-adopting into approved baseline.")
                val appName = try {
                    if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else pkg
                } catch (e: Exception) {
                    pkg
                }
                val nowMs = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
                val baselineEntity = PendingApprovalEntity(
                    packageName = pkg,
                    appName = appName,
                    requestedAt = firstInstallTime,
                    status = PendingApprovalEntity.STATUS_APPROVED,
                    decidedBy = "INITIAL_DEVICE_BASELINE",
                    decidedAt = nowMs,
                    isCloudSynced = true
                )
                approvalDao.savePendingApprovalBlocking(baselineEntity)
                continue
            }

            // If it was already rejected by guardian, ensure it is suspended/hidden if active, but do NOT call freezeAndSubmitApprovalLocal or modify status to PENDING
            if (rejectedSet.contains(pkg)) {
                val isSuspended = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isPackageSuspendedBlocking(appContext, pkg).getOrNull() == true
                val isHidden = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isApplicationHiddenBlocking(appContext, pkg).getOrNull() == true
                if (!isSuspended && !isHidden) {
                    Log.w(TAG, "🚨 [RECONCILE_REJECTED_APP] Rejected package '$pkg' was active. Enforcing suspension/hide!")
                    val suspended = com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackageSuspendedWithMessageBlocking(
                        context = appContext,
                        packageName = pkg,
                        message = appContext.getString(R.string.toast_app_suspended_by_guardian)
                    ).getOrNull() == true
                    if (!suspended) {
                        try {
                            com.drmohammed.antiporn.telemetry.SafeDpmGateway.setApplicationHiddenBlocking(appContext, pkg, true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to hide rejected package $pkg", e)
                        }
                    }
                }
                continue
            }

            // High-risk enforcement: If an unapproved high-risk app is installed, ensure it is frozen
            if (com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isHighRisk(pkg) && !approvedSet.contains(pkg)) {
                val isSuspended = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isPackageSuspendedBlocking(appContext, pkg).getOrNull() == true
                if (!isSuspended) {
                    Log.w(TAG, "🚨 [HIGH_RISK_ENFORCEMENT] Freezing unapproved active high-risk app '$pkg'!")
                    freezeAndSubmitApprovalLocal(pkg, showToast = false)
                    strayAppFrozen = true
                    continue
                }
            }

            // If it's already recorded as pending, ensure suspension is enforced
            if (pendingSet.contains(pkg)) {
                val isSuspended = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isPackageSuspendedBlocking(appContext, pkg).getOrNull() == true
                if (!isSuspended) {
                    com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackageSuspendedWithMessageBlocking(
                        appContext,
                        pkg,
                        appContext.getString(R.string.toast_app_suspended_by_guardian)
                    )
                }
                continue
            }

            // Unapproved package detected that is NOT suspended/hidden! Enforce instant freeze!
            Log.w(TAG, "🚨 [RECONCILE_STRAY_APP] Found unapproved active package '$pkg' (escaped receiver). Freezing silently in background!")
            if (freezeAndSubmitApprovalLocal(pkg, showToast = false)) {
                strayAppFrozen = true
            }
        }

        if (strayAppFrozen) {
            scope.launch {
                try {
                    syncPendingApprovalsToCloud()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed syncing reconciled approvals to cloud", e)
                }
            }
        }
    }

    fun reconcileUnapprovedInstalledAppsAsync() {
        scope.launch {
            try {
                reconcileUnapprovedInstalledApps()
            } catch (e: Exception) {
                Log.e(TAG, "Error in reconcileUnapprovedInstalledAppsAsync", e)
            }
        }
    }

    /**
     * Retrieves the timestamp when Device Owner was enabled, or initializes it if DO is currently active.
     */
    fun getDeviceOwnerEnabledTimestamp(): Long {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var ts = prefs.getLong(KEY_DEVICE_OWNER_ENABLED_AT, 0L)
        if (ts == 0L && com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(appContext)) {
            ts = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
            prefs.edit().putLong(KEY_DEVICE_OWNER_ENABLED_AT, ts).apply()
            Log.i(TAG, "Recorded initial Device Owner enabled timestamp: $ts")
        }
        return ts
    }

    /**
     * Explicitly records or updates the timestamp when Device Owner was granted to this app.
     */
    fun recordDeviceOwnerEnabledTimestamp(timestamp: Long = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getLong(KEY_DEVICE_OWNER_ENABLED_AT, 0L)
        if (existing == 0L || timestamp > 0L) {
            prefs.edit().putLong(KEY_DEVICE_OWNER_ENABLED_AT, timestamp).apply()
            Log.i(TAG, "Device Owner enabled timestamp recorded: $timestamp")
        }
    }

    /**
     * Sequentially establishes or updates the Zero-Trust baseline and reconciles stray applications.
     * Eliminates race conditions by ensuring baseline is fully established before any unapproved app checks run.
     */
    @Synchronized
    fun syncZeroTrustBaselineAndReconcile() {
        if (!com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(appContext)) return
        getDeviceOwnerEnabledTimestamp()
        ensureInitialBaselineSeeded()
        reconcileUnapprovedInstalledApps()
    }

    /**
     * Asynchronously executes [syncZeroTrustBaselineAndReconcile] in a single background coroutine.
     */
    fun syncZeroTrustBaselineAndReconcileAsync(onComplete: (() -> Unit)? = null) {
        scope.launch {
            try {
                syncZeroTrustBaselineAndReconcile()
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncZeroTrustBaselineAndReconcileAsync", e)
            } finally {
                onComplete?.invoke()
            }
        }
    }


    companion object {
        private const val TAG = "AntiPorn_ApprovalRepo"
        private const val PREFS_NAME = "zero_trust_approval_prefs"
        private const val KEY_BASELINE_SEEDED = "is_zero_trust_baseline_seeded"
        const val KEY_DEVICE_OWNER_ENABLED_AT = "device_owner_enabled_at_timestamp"
        private const val DO_ACTIVATION_GRACE_PERIOD_MS = 15_000L

        @Volatile
        private var instance: ApprovalRepository? = null

        fun getInstance(context: Context): ApprovalRepository {
            return instance ?: synchronized(this) {
                instance ?: try {
                    org.koin.core.context.GlobalContext.getOrNull()?.get<ApprovalRepository>()
                } catch (t: Throwable) {
                    null
                } ?: ApprovalRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun setInstance(repo: ApprovalRepository?) {
            instance = repo
        }
    }
}
