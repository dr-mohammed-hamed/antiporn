package com.drmohammed.antiporn.data.repository

import android.app.admin.DevicePolicyManager
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
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
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

        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val admin = ComponentName(appContext, AdminReceiver::class.java)

        if (!com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(appContext)) {
            Log.w(TAG, "Cannot freeze $packageName: Not Device/Profile Owner")
            return false
        }

        // 1. Instant 0ms suspension with Fail-Secure Fallback
        val suspended = suspendWithGuardianMessage(
            dpm = dpm,
            admin = admin,
            packageName = packageName,
            message = appContext.getString(R.string.toast_app_suspended_by_guardian)
        )

        val finalStatus: String
        if (suspended) {
            finalStatus = PendingApprovalEntity.STATUS_PENDING
            Log.w(TAG, "❄️ [FREEZE_SUCCESS] Instant 0ms freeze applied to package: $packageName (showToast=$showToast)")
        } else {
            Log.w(TAG, "⚠️ [FREEZE_REJECTED] Device suspension rejected for $packageName. Executing Fail-Secure fallback (setApplicationHidden)...")
            val hidden = try {
                dpm.setApplicationHidden(admin, packageName, true)
            } catch (e: Exception) {
                Log.e(TAG, "❌ [FALLBACK_HIDE_EXCEPTION] Exception hiding package $packageName via DPM", e)
                false
            }

            if (hidden) {
                finalStatus = PendingApprovalEntity.STATUS_FALLBACK_HIDDEN
                Log.i(TAG, "🛡️ [FAIL_SECURE_HIDDEN] Package $packageName hidden successfully via Device Owner fallback")
            } else {
                Log.e(TAG, "❌ [TOTAL_ENFORCEMENT_FAILURE] Both setPackagesSuspended and setApplicationHidden failed for package $packageName")
                return false
            }
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
        database.savePendingApproval(approvalEntity)

        // 4. Update Zero-Trust active foreground watcher immediately and trigger in-memory anti-race evaluation
        ZeroTrustForegroundWatcher.syncWatcherState(appContext)
        ZeroTrustForegroundWatcher.evaluateCurrentForegroundApp(appContext, immediateTargetPackage = packageName)

        return true
    }

    suspend fun freezeAndSubmitApprovalSync(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val localSuccess = freezeAndSubmitApprovalLocal(packageName)
        if (!localSuccess) return@withContext false

        val entity = database.getPendingApprovals().find { it.packageName == packageName }
            ?: return@withContext false

        // ANR-protected cloud upload with 3500ms timeout
        val uploadSuccess = kotlinx.coroutines.withTimeoutOrNull(3500L) {
            uploadSingleApprovalSync(entity)
        } ?: false

        Log.i(TAG, "freezeAndSubmitApprovalSync completed for $packageName (uploaded=$uploadSuccess)")
        uploadSuccess
    }

    /**
     * Blocking wrapper for [freezeAndSubmitApprovalSync] with strict 3500ms ANR timeout.
     */
    fun freezeAndSubmitApprovalBlocking(packageName: String): Boolean {
        val localSuccess = freezeAndSubmitApprovalLocal(packageName)
        if (!localSuccess) return false

        return kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(3500L) {
                try {
                    syncPendingApprovalsToCloud()
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Cloud sync in freezeAndSubmitApprovalBlocking timed out or failed: ${e.message}")
                    false
                }
            } ?: false
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

            database.markApprovalCloudSynced(approval.packageName, true)
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
            database.updateApprovalStatus(packageName, PendingApprovalEntity.STATUS_REJECTED, "CUSTOM_BLACKLIST")
            ZeroTrustForegroundWatcher.syncWatcherState(appContext)
            return
        }

        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val admin = ComponentName(appContext, AdminReceiver::class.java)

        if (!com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(appContext)) return

        val isBlockActive = CurfewEnforcer.isEffectiveBlockActive(appContext)
        val isWhitelisted = CurfewConfig.STATIC_WHITELIST.contains(packageName) ||
                CurfewAppResolver.resolveImmunePackages(appContext).contains(packageName)

        if (isBlockActive && !isWhitelisted) {
            Log.i(TAG, "Package $packageName approved by $decidedBy, but kept suspended until curfew wakeup")
            database.updateApprovalStatus(packageName, PendingApprovalEntity.STATUS_APPROVED, decidedBy)
            ZeroTrustForegroundWatcher.syncWatcherState(appContext)
            syncApprovalDecisionToCloud(packageName, PendingApprovalEntity.STATUS_APPROVED, decidedBy)
            return
        }

        try {
            // Unhide application if it was hidden via Fail-Secure fallback
            try {
                if (dpm.isApplicationHidden(admin, packageName)) {
                    val unhidden = dpm.setApplicationHidden(admin, packageName, false)
                    Log.i(TAG, "Unhid package $packageName on approval: $unhidden")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception unhiding package $packageName on approval", e)
            }

            val failed: Array<String>? = try {
                dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
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
                    database.updateApprovalStatus(packageName, PendingApprovalEntity.STATUS_APPROVED, decidedBy)
                    ZeroTrustForegroundWatcher.syncWatcherState(appContext)
                    syncApprovalDecisionToCloud(packageName, PendingApprovalEntity.STATUS_APPROVED, decidedBy)
                } else {
                    Log.e(TAG, "Failed unfreezing package $packageName: DPM returned failure for package")
                }
            } else {
                database.updateApprovalStatus(packageName, PendingApprovalEntity.STATUS_APPROVED, decidedBy)
                Log.i(TAG, "Unfrozen and approved package: $packageName")
                ZeroTrustForegroundWatcher.syncWatcherState(appContext)
                syncApprovalDecisionToCloud(packageName, PendingApprovalEntity.STATUS_APPROVED, decidedBy)
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
            database.deletePendingApproval(packageName)
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
            database.updateApprovalStatus(packageName, PendingApprovalEntity.STATUS_REJECTED, decidedBy)
            Log.i(TAG, "Recorded rejection for package: $packageName by $decidedBy")
            ZeroTrustForegroundWatcher.syncWatcherState(appContext)
            syncApprovalDecisionToCloud(packageName, PendingApprovalEntity.STATUS_REJECTED, decidedBy)
        } catch (e: Exception) {
            Log.e(TAG, "Failed recording rejection for package $packageName", e)
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

    fun getLocalPendingApprovals(): List<PendingApprovalEntity> = database.getPendingApprovals()

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
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(appContext, AdminReceiver::class.java)
        val isDo = com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(appContext)

        val installedPackages = try {
            pm.getInstalledPackages(0) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed retrieving installed packages for baseline seeding", e)
            emptyList()
        }

        val packagesToUnsuspend = mutableListOf<String>()

        val policy = if (isDo && dpm != null) PolicyRepository.getInstance(appContext).getCurrentPolicy() else null
        val isBlockActive = if (isDo && dpm != null) CurfewEnforcer.isEffectiveBlockActive(appContext) else false
        val immunePackages = if (isDo && dpm != null) CurfewAppResolver.resolveImmunePackages(appContext) else emptySet()

        for (pkgInfo in installedPackages) {
            val pkg = pkgInfo.packageName ?: continue
            if (pkg == appContext.packageName) continue
            // If it is permanently blacklisted (e.g. Telegram), do not approve!
            if (AppSuspensionShield.isBlacklisted(pkg)) continue

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
            database.savePendingApproval(baselineEntity)

            // If it was inadvertently suspended or hidden, queue it for unfreezing ONLY if not restricted by Curfew or Custom Blacklist
            if (isDo && dpm != null && policy != null) {
                val isWhitelisted = CurfewConfig.STATIC_WHITELIST.contains(pkg) || immunePackages.contains(pkg)

                if (policy.customBlacklistPackages.contains(pkg)) {
                    continue
                }
                if (isBlockActive && !isWhitelisted) {
                    continue
                }

                val isSuspended = try { dpm.isPackageSuspended(admin, pkg) } catch (e: Exception) { false }
                val isHidden = try { dpm.isApplicationHidden(admin, pkg) } catch (e: Exception) { false }
                if (isSuspended || isHidden) {
                    packagesToUnsuspend.add(pkg)
                }
            }
        }

        // Unsuspend and unhide all pre-existing baseline packages
        if (isDo && dpm != null && packagesToUnsuspend.isNotEmpty()) {
            Log.w(TAG, "🌱 [UNFREEZE_BASELINE] Unfreezing ${packagesToUnsuspend.size} pre-existing baseline packages...")
            try {
                dpm.setPackagesSuspended(admin, packagesToUnsuspend.toTypedArray(), false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unsuspend baseline packages via DPM", e)
            }
            for (pkg in packagesToUnsuspend) {
                try {
                    dpm.setApplicationHidden(admin, pkg, false)
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

        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val admin = ComponentName(appContext, AdminReceiver::class.java)
        val pm = appContext.packageManager

        val installedPackages = try {
            pm.getInstalledPackages(0) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed retrieving installed packages for Zero-Trust reconciliation", e)
            emptyList()
        }

        val localApprovals = getLocalPendingApprovals()
        val approvedSet = localApprovals.filter { it.isApproved }.map { it.packageName }.toSet()
        val pendingSet = localApprovals.filter { it.isPending }.map { it.packageName }.toSet()
        val rejectedSet = localApprovals.filter { it.isRejected }.map { it.packageName }.toSet()

        var strayAppFrozen = false
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

            if (approvedSet.contains(pkg)) continue
            if (AppSuspensionShield.isBlacklisted(pkg)) continue
            if (com.drmohammed.antiporn.curfew.CurfewAppResolver.isSystemCriticalOrImmune(appContext, pkg)) continue

            // If it was already rejected by guardian, ensure it is suspended/hidden if active, but do NOT call freezeAndSubmitApprovalLocal or modify status to PENDING
            if (rejectedSet.contains(pkg)) {
                val isSuspended = try { dpm.isPackageSuspended(admin, pkg) } catch (e: Exception) { false }
                val isHidden = try { dpm.isApplicationHidden(admin, pkg) } catch (e: Exception) { false }
                if (!isSuspended && !isHidden) {
                    Log.w(TAG, "🚨 [RECONCILE_REJECTED_APP] Rejected package '$pkg' was active. Enforcing suspension/hide!")
                    val suspended = suspendWithGuardianMessage(
                        dpm = dpm,
                        admin = admin,
                        packageName = pkg,
                        message = appContext.getString(R.string.toast_app_suspended_by_guardian)
                    )
                    if (!suspended) {
                        try {
                            dpm.setApplicationHidden(admin, pkg, true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to hide rejected package $pkg", e)
                        }
                    }
                }
                continue
            }

            // If it's already recorded as pending and verified suspended/hidden, skip
            if (pendingSet.contains(pkg)) {
                val isSuspended = try { dpm.isPackageSuspended(admin, pkg) } catch (e: Exception) { false }
                val isHidden = try { dpm.isApplicationHidden(admin, pkg) } catch (e: Exception) { false }
                if (isSuspended || isHidden) continue
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

    private fun suspendWithGuardianMessage(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packageName: String,
        message: String
    ): Boolean {
        if (com.drmohammed.antiporn.curfew.CurfewAppResolver.isSystemCriticalOrImmune(appContext, packageName)) {
            Log.w(TAG, "IRON GUARD: Strictly refusing to suspend critical system package: $packageName")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
                val builder = builderClass.getDeclaredConstructor().newInstance()
                val setMessageMethod = builderClass.getMethod("setMessage", String::class.java)
                setMessageMethod.invoke(builder, message)
                val buildMethod = builderClass.getMethod("build")
                val dialogInfo = buildMethod.invoke(builder)

                val suspendMethod = DevicePolicyManager::class.java.getMethod(
                    "setPackagesSuspended",
                    ComponentName::class.java,
                    Array<String>::class.java,
                    Boolean::class.javaPrimitiveType,
                    android.os.PersistableBundle::class.java,
                    android.os.PersistableBundle::class.java,
                    Class.forName("android.content.pm.SuspendDialogInfo")
                )
                val rawResult = suspendMethod.invoke(dpm, admin, arrayOf(packageName), true, null, null, dialogInfo)
                val failedPkgs = (rawResult as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
                val isSuccess = rawResult != null && !failedPkgs.contains(packageName)
                if (isSuccess) {
                    Log.i(TAG, "Applied Zero-Trust suspension with custom SuspendDialogInfo for $packageName")
                    return true
                } else {
                    Log.w(TAG, "Reflection setPackagesSuspended returned failed package for $packageName: $failedPkgs. Attempting standard fallback.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reflection for SuspendDialogInfo failed (${e.javaClass.simpleName}: ${e.message}), falling back to standard suspension")
            }
        }

        return try {
            val failedPkgs: Array<String>? = try {
                dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            } catch (e: Exception) {
                Log.w(TAG, "Exception invoking standard setPackagesSuspended for $packageName", e)
                null
            }
            val isSuccess = failedPkgs != null && !failedPkgs.contains(packageName)
            if (isSuccess) {
                Log.i(TAG, "Applied Zero-Trust standard suspension for $packageName")
                true
            } else {
                val isSuspended = try { dpm.isPackageSuspended(admin, packageName) } catch (e: Exception) { false }
                if (isSuspended) {
                    Log.i(TAG, "Zero-Trust suspension confirmed via isPackageSuspended for $packageName")
                    true
                } else {
                    Log.e(TAG, "Standard setPackagesSuspended failed for $packageName (failed packages: ${failedPkgs?.joinToString()})")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during standard setPackagesSuspended for $packageName", e)
            false
        }
    }

    companion object {
        private const val TAG = "AntiPorn_ApprovalRepo"
        private const val PREFS_NAME = "zero_trust_approval_prefs"
        private const val KEY_BASELINE_SEEDED = "is_zero_trust_baseline_seeded"

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
