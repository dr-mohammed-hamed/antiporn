package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Unified Single Source of Truth (SSOT) repository for device protection policies.
 * Manages local persistence in SQLite and real-time bidirectional synchronization with Firestore.
 */
class PolicyRepository(
    context: Context,
    private val policyDao: DevicePolicyDao = HisnRoomDatabase.getInstance(context).devicePolicyDao(),
    private val partnerDao: PartnerDao = HisnRoomDatabase.getInstance(context).partnerDao()
) {

    private val appContext = context.applicationContext ?: context
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val policyStateLock = Any()

    private val _currentPolicyFlow: MutableStateFlow<DevicePolicyBundle>
    val currentPolicyFlow: StateFlow<DevicePolicyBundle>

    private var firestoreListener: ListenerRegistration? = null
    private val firestoreSyncMutex = Mutex()
    private val policyPersistMutex = Mutex()
    private val persistSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val roomSaveChannel = Channel<Pair<Long, DevicePolicyBundle>>(Channel.UNLIMITED)
    private val enforcementHandler = PolicyEnforcementHandler(appContext, repositoryScope)

    @Volatile
    private var lastSyncedCloudVersion: Long = 0L

    init {
        // Fast synchronous load from SharedPreferences cache only to avoid blocking caller/main thread with Room I/O
        val cachedPolicy = loadFastPolicyFromCache()
        val hasCachedPolicy = cachedPolicy != null
        val initialPolicy = cachedPolicy ?: DevicePolicyBundle.defaultPolicy()
        _currentPolicyFlow = MutableStateFlow(initialPolicy)
        currentPolicyFlow = _currentPolicyFlow.asStateFlow()
        com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = initialPolicy.isSelfControlAllowed

        repositoryScope.launch {
            for ((seq, policyToSave) in roomSaveChannel) {
                persistPolicyBundle(seq, policyToSave)
            }
        }

        repositoryScope.launch {
            val (reconciled, needsSave) = loadAndReconcilePolicy(initialPolicy)
            val adopted = synchronized(policyStateLock) {
                val current = _currentPolicyFlow.value
                val shouldAdoptInitialRoomLoad = !hasCachedPolicy && current == initialPolicy
                val isNewerOrEqualReconciled = reconciled != current &&
                    (shouldAdoptInitialRoomLoad ||
                        reconciled.version > current.version ||
                        (reconciled.version == current.version && reconciled.updatedAt >= current.updatedAt))
                if (isNewerOrEqualReconciled) {
                    _currentPolicyFlow.value = reconciled
                    com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = reconciled.isSelfControlAllowed
                    if (needsSave || !hasCachedPolicy) {
                        schedulePolicyPersistenceLocked(reconciled)
                    }
                    true
                } else if (!hasCachedPolicy && reconciled == current) {
                    schedulePolicyPersistenceLocked(current)
                    true
                } else {
                    false
                }
            }
            if (adopted) {
                checkAndApplyRemoteSelfControlReset(reconciled)
            }
        }
        // Observe local policy changes and trigger background sync
        startFirestoreSync()
    }

    private fun loadFastPolicyFromCache(): DevicePolicyBundle? {
        // SharedPreferences cache (sub-millisecond in-memory read on cold boot; Room SQLite is deferred to IO coroutine)
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedJson = prefs.getString(KEY_CACHED_POLICY_JSON, null)
            if (!cachedJson.isNullOrBlank()) {
                val parsed = DevicePolicyBundle.fromJsonOrNull(cachedJson)
                if (parsed != null) {
                    Log.d(TAG, "Fast synchronous policy loaded from SharedPreferences cache (version=${parsed.version})")
                    return parsed
                } else {
                    Log.w(TAG, "Corrupted fast policy cache JSON; awaiting asynchronous Room reconcile")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading fast policy cache from SharedPreferences", e)
        }
        return null
    }

    private fun saveFastPolicyCache(bundle: DevicePolicyBundle, syncCommit: Boolean = false) {
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().putString(KEY_CACHED_POLICY_JSON, bundle.toJson())
            if (syncCommit || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                editor.commit()
            } else {
                editor.apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving policy to fast SharedPreferences cache", e)
        }
    }

    private fun schedulePolicyPersistenceLocked(bundle: DevicePolicyBundle) {
        val seq = persistSequence.incrementAndGet()
        val sendResult = roomSaveChannel.trySend(seq to bundle)
        if (!sendResult.isSuccess) {
            Log.w(TAG, "Policy persistence channel unavailable; writing asynchronously on IO")
            repositoryScope.launch {
                persistPolicyBundle(seq, bundle)
            }
        }
    }

    private suspend fun persistPolicyBundle(seq: Long, bundle: DevicePolicyBundle) {
        policyPersistMutex.withLock {
            if (seq < persistSequence.get()) {
                Log.d(TAG, "Skipping superseded disk persistence for policy v${bundle.version} (seq=$seq < ${persistSequence.get()})")
                return@withLock
            }
            saveFastPolicyCache(bundle, syncCommit = true)
            try {
                policyDao.saveDevicePolicyBlocking(DevicePolicyEntity.fromBundle(bundle))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed persisting device policy v${bundle.version} to Room", e)
            }
        }
    }

    private suspend fun loadAndReconcilePolicy(initialPolicy: DevicePolicyBundle): Pair<DevicePolicyBundle, Boolean> {
        return try {
            val loaded = policyDao.getDevicePolicyBlocking()?.toBundle() ?: initialPolicy
            // Reconcile standalone devices (no guardians attached) on initial policy v1
            val hasGuardians = try {
                partnerDao.getPartnersByRoleBlocking(com.drmohammed.antiporn.data.local.db.entity.PartnerEntity.ROLE_GUARDIAN).isNotEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Failed checking guardians in SQLite during cold boot reconcile", e)
                false
            }
            if (loaded.isSupervised && !loaded.guardianUid.isNullOrBlank() && !hasGuardians) {
                try {
                    val guardian = com.drmohammed.antiporn.data.local.db.entity.PartnerEntity(
                        id = loaded.guardianUid,
                        pairingCode = AccountabilityRepository.CODE_LOCKED,
                        displayName = loaded.guardianName ?: appContext.getString(R.string.guardian_default_name),
                        role = com.drmohammed.antiporn.data.local.db.entity.PartnerEntity.ROLE_GUARDIAN,
                        status = com.drmohammed.antiporn.data.local.db.entity.PartnerEntity.STATUS_ACTIVE,
                        createdAt = loaded.updatedAt,
                        lastSyncAt = loaded.updatedAt
                    )
                    partnerDao.savePartnerBlocking(guardian)
                    Log.i(TAG, "Reconciled missing guardian in SQLite on cold boot: ${AiTelemetryReporter.maskIdentifier(loaded.guardianName)}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed reconciling missing guardian in SQLite", e)
                }
            }

            var workingPolicy = loaded
            var needsSave = false

            if (!hasGuardians && workingPolicy.version == 1L && workingPolicy.isVpnBlackholeEnabled != com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED) {
                workingPolicy = workingPolicy.copy(isVpnBlackholeEnabled = com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED)
                needsSave = true
            }

            if (needsSave) {
                val reconciled = workingPolicy.copy(updatedAt = TrustedTimeProvider.currentTimeMillis(appContext))
                Log.i(TAG, "Prepared standalone device policy reconciliation on cold boot (version=${reconciled.version})")
                reconciled to true
            } else {
                loaded to false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadAndReconcilePolicy", e)
            initialPolicy to false
        }
    }

    /**
     * Returns current cached policy bundle synchronously.
     */
    fun getCurrentPolicy(): DevicePolicyBundle = _currentPolicyFlow.value

    /**
     * Starts listening to Firestore policy updates for this device.
     */
    fun startFirestoreSync() {
        try {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser
            if (user == null) {
                auth.signInAnonymously()
                    .addOnSuccessListener { result ->
                        result.user?.uid?.let { uid ->
                            AccountabilityRepository.getInstance(appContext).persistUid(uid)
                            com.drmohammed.antiporn.cloud.fcm.AntiPornFirebaseMessagingService.syncPersistedToken(appContext, uid)
                            attachPolicyListener(uid)
                            ApprovalRepository.getInstance(appContext).startApprovalsSync(uid)
                            repositoryScope.launch {
                                AccountabilityRepository.getInstance(appContext).syncPartnersFromCloud()
                                AccountabilityRepository.getInstance(appContext).checkAndSelfHealSupervisionStatus()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed anonymous auth for policy sync, falling back to persistent device UID: ${e.message}")
                        val fallbackUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
                        attachPolicyListener(fallbackUid)
                        ApprovalRepository.getInstance(appContext).startApprovalsSync(fallbackUid)
                        repositoryScope.launch {
                            AccountabilityRepository.getInstance(appContext).checkAndSelfHealSupervisionStatus()
                        }
                    }
            } else {
                AccountabilityRepository.getInstance(appContext).persistUid(user.uid)
                com.drmohammed.antiporn.cloud.fcm.AntiPornFirebaseMessagingService.syncPersistedToken(appContext, user.uid)
                attachPolicyListener(user.uid)
                ApprovalRepository.getInstance(appContext).startApprovalsSync(user.uid)
                repositoryScope.launch {
                    AccountabilityRepository.getInstance(appContext).syncPartnersFromCloud()
                    AccountabilityRepository.getInstance(appContext).checkAndSelfHealSupervisionStatus()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating Firestore sync; continuing with offline local SQLite policy", e)
        }
    }

    private fun attachPolicyListener(uid: String) {
        firestoreListener?.remove()
        repositoryScope.launch {
            try {
                AccountabilityRepository.getInstance(appContext).updateHeartbeat()
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat error: ${e.message}")
            }
        }
        try {
            val firestore = FirebaseFirestore.getInstance()
            val maskedUid = AiTelemetryReporter.maskIdentifier(uid)
            Log.i(TAG, "[SelfControlDiag] attachPolicyListener: Attaching Firestore listener to /policies/$maskedUid")
            firestoreListener = firestore.collection("policies").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "[SelfControlDiag] Firestore Snapshot ERROR on /policies/$maskedUid: ${error.message} (code=${error.code})")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val data = snapshot.data
                        if (data != null) {
                            repositoryScope.launch {
                                try {
                                    val cloudPolicy = DevicePolicyBundle.fromMap(data)
                                    val current = _currentPolicyFlow.value
                                    val isFromGuardian = cloudPolicy.isSupervised && !cloudPolicy.guardianUid.isNullOrBlank()
                                    val supervisoryChanged = isFromGuardian && (
                                        cloudPolicy.isSelfControlAllowed != current.isSelfControlAllowed ||
                                        cloudPolicy.isCurfewEnabled != current.isCurfewEnabled ||
                                        cloudPolicy.curfewStartHour != current.curfewStartHour ||
                                        cloudPolicy.curfewStartMinute != current.curfewStartMinute ||
                                        cloudPolicy.curfewEndHour != current.curfewEndHour ||
                                        cloudPolicy.curfewEndMinute != current.curfewEndMinute ||
                                        cloudPolicy.isWeekendCurfewEnabled != current.isWeekendCurfewEnabled ||
                                        cloudPolicy.weekendCurfewStartHour != current.weekendCurfewStartHour ||
                                        cloudPolicy.weekendCurfewStartMinute != current.weekendCurfewStartMinute ||
                                        cloudPolicy.weekendCurfewEndHour != current.weekendCurfewEndHour ||
                                        cloudPolicy.weekendCurfewEndMinute != current.weekendCurfewEndMinute ||
                                        cloudPolicy.isVpnBlackholeEnabled != current.isVpnBlackholeEnabled ||
                                        cloudPolicy.isInstantInternetKillEnabled != current.isInstantInternetKillEnabled ||
                                        cloudPolicy.isIncognitoBlocked != current.isIncognitoBlocked ||
                                        cloudPolicy.lockNewInstallsUntilApproved != current.lockNewInstallsUntilApproved ||
                                        cloudPolicy.isDateTimeLocked != current.isDateTimeLocked ||
                                        cloudPolicy.dnsHost != current.dnsHost ||
                                        cloudPolicy.browserFilterMode != current.browserFilterMode ||
                                        cloudPolicy.customBlockedUrls != current.customBlockedUrls ||
                                        cloudPolicy.customAllowedUrls != current.customAllowedUrls ||
                                        cloudPolicy.guardianUid != current.guardianUid ||
                                        cloudPolicy.waqfaConfig != current.waqfaConfig
                                    )
                                    val statusChanged = cloudPolicy.breakoutStatus != current.breakoutStatus || cloudPolicy.deactivationStatus != current.deactivationStatus
                                    val isTimeNewer = cloudPolicy.updatedAt > current.updatedAt
                                    val isVersionNewer = cloudPolicy.version > current.version

                                    val maskedUid = AiTelemetryReporter.maskIdentifier(uid)
                                    val maskedGuardianUid = AiTelemetryReporter.maskIdentifier(cloudPolicy.guardianUid)
                                    Log.i(TAG, "[SelfControlDiag] Cloud policy received on ward /policies/$maskedUid: cloudPolicy(v${cloudPolicy.version}, supervised=${cloudPolicy.isSupervised}, allowed=${cloudPolicy.isSelfControlAllowed}, guardian='$maskedGuardianUid') vs local(v${current.version}, supervised=${current.isSupervised}, allowed=${current.isSelfControlAllowed}), isVersionNewer=$isVersionNewer, isTimeNewer=$isTimeNewer, supervisoryChanged=$supervisoryChanged, statusChanged=$statusChanged")

                                    com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordCloudSync(
                                        appContext,
                                        "POLICY_RECEIVED",
                                        isSuccess = true,
                                        details = "uid=$maskedUid, v${cloudPolicy.version}, isSupervised=${cloudPolicy.isSupervised}, curfew=${cloudPolicy.isCurfewEnabled}(${cloudPolicy.curfewStartHour}:${cloudPolicy.curfewStartMinute}), isVersionNewer=$isVersionNewer, isTimeNewer=$isTimeNewer"
                                    )

                                    if (isVersionNewer || isTimeNewer || supervisoryChanged || statusChanged || (current.isSupervised != cloudPolicy.isSupervised)) {
                                        val enforcedPolicy = cloudPolicy

                                        Log.i(TAG, "[SelfControlDiag] Sovereign cloud policy ACCEPTED! Enforcing update on device... (v${enforcedPolicy.version}, isSelfControlAllowed=${enforcedPolicy.isSelfControlAllowed})")
                                        updateLocalPolicyAndEnforce(enforcedPolicy)

                                        val adoptedPolicy = _currentPolicyFlow.value
                                        if (adoptedPolicy.version == enforcedPolicy.version &&
                                            adoptedPolicy.isSupervised &&
                                            !adoptedPolicy.guardianUid.isNullOrBlank()
                                        ) {
                                            Log.i(TAG, "Guardian detected from cloud snapshot: ${AiTelemetryReporter.maskIdentifier(adoptedPolicy.guardianName)} ($maskedGuardianUid)")
                                            val partner = com.drmohammed.antiporn.data.local.db.entity.PartnerEntity(
                                                id = adoptedPolicy.guardianUid,
                                                pairingCode = AccountabilityRepository.CODE_LOCKED,
                                                displayName = adoptedPolicy.guardianName ?: appContext.getString(R.string.guardian_default_name),
                                                role = com.drmohammed.antiporn.data.local.db.entity.PartnerEntity.ROLE_GUARDIAN,
                                                status = com.drmohammed.antiporn.data.local.db.entity.PartnerEntity.STATUS_ACTIVE,
                                                createdAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext),
                                                lastSyncAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
                                            )
                                            partnerDao.savePartnerBlocking(partner)
                                        }

                                        if (isFromGuardian) {
                                            repositoryScope.launch {
                                                try {
                                                    FirebaseFirestore.getInstance().collection("policies").document(uid).update(
                                                        mapOf(
                                                            "acknowledgedVersion" to cloudPolicy.version,
                                                            "lastAcknowledgedAt" to com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
                                                        )
                                                    ).await()
                                                    Log.i(TAG, "[SelfControlDiag] Acknowledged policy v${cloudPolicy.version} back to Firestore /policies/$maskedUid")
                                                    com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordCloudSync(
                                                        appContext,
                                                        "POLICY_ACKNOWLEDGED",
                                                        isSuccess = true,
                                                        details = "acknowledgedVersion=${cloudPolicy.version} on /policies/$maskedUid"
                                                    )
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "Failed writing policy acknowledgment: ${e.message}")
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error handling policy update from cloud", e)
                                }
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching Firestore policy listener", e)
        }
    }

    /**
     * Updates policy locally and saves to SQLite and memory flow without triggering
     * DPM shield re-enforcement. Used during teardown or deactivation completion.
     */
    fun updateLocalPolicyWithoutEnforcing(bundle: DevicePolicyBundle) {
        synchronized(policyStateLock) {
            _currentPolicyFlow.value = bundle
            com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = bundle.isSelfControlAllowed
            schedulePolicyPersistenceLocked(bundle)
        }
    }

    /**
     * Updates policy locally, saves to SQLite, and enforces on the device.
     */
    fun updateLocalPolicyAndEnforce(bundle: DevicePolicyBundle) {
        val currentBeforeUpdate = synchronized(policyStateLock) {
            val current = _currentPolicyFlow.value
            Log.i(TAG, "[SelfControlDiag] updateLocalPolicyAndEnforce: v${bundle.version}, isSupervised=${bundle.isSupervised}, isSelfControlAllowed=${bundle.isSelfControlAllowed}")
            val isBreakoutStatusChange = bundle.breakoutStatus != current.breakoutStatus || bundle.deactivationStatus != current.deactivationStatus
            if (bundle.version < current.version || (!isBreakoutStatusChange && bundle.version == current.version && bundle.updatedAt < current.updatedAt)) {
                Log.w(TAG, "Monotonic guard: rejected stale/out-of-order policy v${bundle.version}@${bundle.updatedAt} (current is v${current.version}@${current.updatedAt})")
                return@synchronized null to current
            }
            _currentPolicyFlow.value = bundle
            com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = bundle.isSelfControlAllowed
            schedulePolicyPersistenceLocked(bundle)
            enforcementHandler.enqueueApply(bundle, current)
            current to current
        }

        val previous = currentBeforeUpdate.first
        if (previous == null) {
            val current = currentBeforeUpdate.second
            BlackBoxRecorder.recordCloudSync(
                appContext,
                "POLICY_REJECTED_STALE",
                isSuccess = false,
                error = "Monotonic guard reject: incoming v${bundle.version}@${bundle.updatedAt} < current v${current.version}@${current.updatedAt}"
            )
            return
        }
        recordAndHandleSupervisionChange(bundle, previous)
        checkAndApplyRemoteSelfControlReset(bundle)
    }

    private fun checkAndApplyRemoteSelfControlReset(policy: DevicePolicyBundle) {
        val remoteResetAt = policy.remoteSelfControlResetRequestedAt ?: return
        if (remoteResetAt <= 0L) return
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastProcessed = prefs.getLong(KEY_LAST_PROCESSED_REMOTE_RESET_AT, 0L)
        if (remoteResetAt > lastProcessed) {
            prefs.edit().putLong(KEY_LAST_PROCESSED_REMOTE_RESET_AT, remoteResetAt).apply()
            Log.i(TAG, "[SelfControlDiag] Executing remote Self-Control QR reset requested at $remoteResetAt")
            val qrManager = com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager.getInstance(appContext)
            qrManager.completeEmergencyUnlock()
            try {
                com.drmohammed.antiporn.notification.manager.AppNotificationManager.getInstance(appContext).postEventNotification(
                    category = com.drmohammed.antiporn.notification.model.NotificationCategory.SYSTEM,
                    title = appContext.getString(R.string.notif_self_control_qr_reset_by_guardian_title),
                    message = appContext.getString(R.string.notif_self_control_qr_reset_by_guardian_msg),
                    dedupKey = "qr_reset_$remoteResetAt",
                    cooldownMs = 0L,
                    actionType = com.drmohammed.antiporn.notification.model.NotificationActionType.NONE,
                    actionPayload = null,
                    systemNotificationId = com.drmohammed.antiporn.notification.registry.NotificationRegistry.EVENT_WARD_REQUEST_ID,
                    channelId = com.drmohammed.antiporn.notification.registry.NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                    postSystemNotification = true,
                    importance = 2
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed posting QR reset notification", e)
            }
        }
    }

    private fun recordAndHandleSupervisionChange(bundle: DevicePolicyBundle, previous: DevicePolicyBundle) {
        BlackBoxRecorder.recordCloudSync(
            appContext,
            "POLICY_ENFORCED_LOCALLY",
            isSuccess = true,
            details = "v${bundle.version}, curfew=${bundle.isCurfewEnabled}(${bundle.curfewStartHour}:${bundle.curfewStartMinute}-${bundle.curfewEndHour}:${bundle.curfewEndMinute}), weekend=${bundle.isWeekendCurfewEnabled}(${bundle.weekendCurfewStartHour}:${bundle.weekendCurfewStartMinute}), vpn=${bundle.isVpnBlackholeEnabled}, dns=${bundle.dnsHost}, supervised=${bundle.isSupervised}"
        )

        val wasSupervised = previous.isSupervised
        val isNowSupervised = bundle.isSupervised
        if (wasSupervised && !isNowSupervised) {
            Log.i(TAG, "Supervision revocation detected in updateLocalPolicyAndEnforce! Restoring standalone mode...")
            repositoryScope.launch {
                try {
                    AccountabilityRepository.getInstance(appContext).onSupervisionRevokedLocally()
                    ApprovalRepository.getInstance(appContext).stopApprovalsSync()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Error cleaning up supervision state on revocation", e)
                }
            }
        }
    }

    /**
     * Convenience API to toggle self-control permission directly in local policy and enforce it.
     */
    @Deprecated("Legacy convenience method")
    fun updateSelfControlAllowed(allowed: Boolean) {
        val current = _currentPolicyFlow.value
        if (current.isSelfControlAllowed == allowed) return
        val updated = current.copy(
            isSelfControlAllowed = allowed,
            updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
        )
        updateLocalPolicyAndEnforce(updated)
    }

    /**
     * Applies policy bundle parameters across system engines (Curfew, Browser, VPN),
     * reconciling differences with previous policy to unsuspend removed blacklist packages
     * and clear uninstall blocking for removed protected packages.
     */
    fun applyPolicyToSystem(bundle: DevicePolicyBundle, previousPolicy: DevicePolicyBundle = _currentPolicyFlow.value) {
        enforcementHandler.enqueueApply(bundle, previousPolicy)
    }

    /**
     * Saves policy to Firestore for a specific target ward UID (invoked from Guardian interface).
     */
    suspend fun pushPolicyToWard(wardUid: String, bundle: DevicePolicyBundle): Boolean {
        val maskedWardUid = AiTelemetryReporter.maskIdentifier(wardUid)
        val firestore = FirebaseFirestore.getInstance()
        val snap = try {
            firestore.collection("policies").document(wardUid).get().await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Failed reading current cloud policy version for wardUid=$maskedWardUid", e)
            null
        }
        val cloudVersion = snap?.getLong("version") ?: 0L
        val targetVersion = if (bundle.version > cloudVersion) bundle.version else cloudVersion + 1L
        val policyToPush = bundle.copy(
            version = targetVersion,
            updatedAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
        )
        Log.i(TAG, "[SelfControlDiag] pushPolicyToWard: writing to Firestore /policies/$maskedWardUid: isSelfControlAllowed=${policyToPush.isSelfControlAllowed}, version=$targetVersion")
        com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordCloudSync(
            appContext,
            "PUSH_TO_WARD_INITIATED",
            isSuccess = true,
            details = "wardUid=$maskedWardUid, version=$targetVersion, cloudVersion=$cloudVersion, curfew=${policyToPush.isCurfewEnabled}(${policyToPush.curfewStartHour}:${policyToPush.curfewStartMinute})"
        )
        return try {
            firestore.collection("policies").document(wardUid)
                .set(policyToPush.toMap())
                .await()
            Log.i(TAG, "[SelfControlDiag] pushPolicyToWard SUCCESS for wardUid=$maskedWardUid (isSelfControlAllowed=${policyToPush.isSelfControlAllowed}, v$targetVersion)")
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordCloudSync(
                appContext,
                "PUSH_TO_WARD_SUCCESS",
                isSuccess = true,
                details = "wardUid=$maskedWardUid, version=$targetVersion"
            )
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "[SelfControlDiag] pushPolicyToWard FAILED for wardUid=$maskedWardUid: ${e.message}", e)
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordCloudSync(
                appContext,
                "PUSH_TO_WARD_FAILED",
                isSuccess = false,
                error = e.message,
                details = "wardUid=$maskedWardUid, version=$targetVersion"
            )
            false
        }
    }

    private fun syncPolicyToCloudAsync(uid: String, bundle: DevicePolicyBundle) {
        if (uid.isBlank()) return
        val maskedUid = AiTelemetryReporter.maskIdentifier(uid)
        repositoryScope.launch {
            firestoreSyncMutex.withLock {
                try {
                    if (bundle.version >= lastSyncedCloudVersion) {
                        FirebaseFirestore.getInstance().collection("policies").document(uid)
                            .set(bundle.toMap())
                            .await()
                        lastSyncedCloudVersion = bundle.version
                        Log.i(TAG, "Successfully synced policy v${bundle.version} to cloud ($maskedUid)")
                    } else {
                        Log.d(TAG, "Skipping cloud sync for stale policy v${bundle.version} (already at v$lastSyncedCloudVersion)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Policy cloud sync deferred (offline or error): ${e.message}")
                }
            }
        }
    }

    /**
     * Saves updated policy bundle directly for the local device, enforcing immediately
     * in SQLite and system engines (DPM), with asynchronous background cloud sync to Firestore.
     */
    suspend fun saveAndEnforceSelfPolicy(bundle: DevicePolicyBundle): Boolean = withContext(Dispatchers.IO) {
        try {
            val repoSupervised = try {
                AccountabilityRepository.getInstance(appContext).isSupervised()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Failed to query accountability supervision in saveAndEnforceSelfPolicy", e)
                false
            }
            val acceptedPair = synchronized(policyStateLock) {
                val current = _currentPolicyFlow.value
                val isSupervised = current.isSupervised || bundle.isSupervised || repoSupervised
                Log.i(TAG, "[SelfControlDiag] saveAndEnforceSelfPolicy called: isSupervised=$isSupervised, current.isSelfControlAllowed=${current.isSelfControlAllowed}, bundle.isSelfControlAllowed=${bundle.isSelfControlAllowed}")

                if (isSupervised && (!current.isSelfControlAllowed || !bundle.isSelfControlAllowed)) {
                    null
                } else {
                    val newPolicy = bundle.copy(
                        guardianUid = current.guardianUid,
                        guardianName = current.guardianName,
                        isSupervised = isSupervised,
                        isSelfControlAllowed = current.isSelfControlAllowed,
                        guardianPolicySnapshot = if (isSupervised) (current.guardianPolicySnapshot ?: current.toJson()) else null,
                        lastSelfModifiedAt = if (isSupervised) TrustedTimeProvider.currentTimeMillis(appContext) else null,
                        version = current.version + 1,
                        updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
                    )
                    _currentPolicyFlow.value = newPolicy
                    com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = newPolicy.isSelfControlAllowed
                    schedulePolicyPersistenceLocked(newPolicy)
                    enforcementHandler.enqueueApply(newPolicy, current)
                    current to newPolicy
                }
            }

            if (acceptedPair == null) {
                Log.w(TAG, "[SelfControlDiag] saveAndEnforceSelfPolicy REJECTED: self-control is disabled by supervisor!")
                BlackBoxRecorder.recordGate(
                    appContext,
                    "SELF_CONTROL_SAVE_REJECTED",
                    isSuccess = false,
                    error = "Access denied: self-control is disabled by supervisor"
                )
                return@withContext false
            }

            val (previous, updated) = acceptedPair
            recordAndHandleSupervisionChange(updated, previous)
            BlackBoxRecorder.recordGate(
                appContext,
                "SELF_CONTROL_POLICY_SAVED",
                isSuccess = true,
                details = "v${updated.version}, curfew=${updated.isCurfewEnabled}(${updated.curfewStartHour}:${updated.curfewStartMinute})"
            )

            // Asynchronously sync to cloud document if a synchronized UID exists
            val myUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
            syncPolicyToCloudAsync(myUid, updated)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error in saveAndEnforceSelfPolicy", e)
            false
        }
    }

    /**
     * Dynamically adds a verified productive, Quran, or Islamic application to the curfew whitelist.
     * Synchronized to prevent lost updates when multiple applications are updated simultaneously.
     */
    suspend fun addAppToCurfewWhitelist(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext false
        try {
            var alreadyWhitelisted = false
            val acceptedPair = synchronized(policyStateLock) {
                val current = _currentPolicyFlow.value
                if (current.customCurfewWhitelistPackages.contains(packageName)) {
                    alreadyWhitelisted = true
                    null
                } else if (AppPolicyRegistry.isRestrictedBrowser(packageName) ||
                    CurfewConfig.KNOWN_APP_STORES.contains(packageName) ||
                    CurfewConfig.CURFEW_SEARCH_APPS.contains(packageName) ||
                    AppPolicyRegistry.isHighRisk(packageName) ||
                    current.customBlacklistPackages.contains(packageName)
                ) {
                    null
                } else {
                    val newPolicy = current.copy(
                        customCurfewWhitelistPackages = current.customCurfewWhitelistPackages + packageName,
                        version = current.version + 1,
                        updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
                    )
                    _currentPolicyFlow.value = newPolicy
                    com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = newPolicy.isSelfControlAllowed
                    schedulePolicyPersistenceLocked(newPolicy)
                    enforcementHandler.enqueueApply(newPolicy, current)
                    current to newPolicy
                }
            }

            if (alreadyWhitelisted) return@withContext true
            if (acceptedPair == null) {
                Log.w(TAG, "Refusing to add restricted or blacklisted package to curfew whitelist: $packageName")
                return@withContext false
            }

            val (previous, updated) = acceptedPair
            recordAndHandleSupervisionChange(updated, previous)
            val myUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
            syncPolicyToCloudAsync(myUid, updated)
            Log.i(TAG, "Auto-whitelisted new Islamic/productive app in curfew: $packageName")
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed auto-whitelisting app $packageName in curfew", e)
            false
        }
    }

    suspend fun requestDeviceOwnerDeactivation(hasQr: Boolean): Result<Long> {
        return AccountabilityRepository.getInstance(appContext).requestDeviceOwnerDeactivation(hasQr)
    }

    suspend fun cancelDeviceOwnerDeactivation(): Result<Unit> {
        return AccountabilityRepository.getInstance(appContext).cancelDeviceOwnerDeactivation()
    }

    suspend fun executeDeviceOwnerTeardown(): Result<Unit> {
        return com.drmohammed.antiporn.core.engine.DeviceOwnerTeardownManager.executeTeardown(appContext)
    }

    companion object {
        private const val TAG = "AntiPorn_PolicyRepo"
        const val PREFS_NAME = "hisn_policy_fast_cache"
        const val KEY_CACHED_POLICY_JSON = "cached_policy_json"
        const val KEY_LAST_PROCESSED_REMOTE_RESET_AT = "last_processed_remote_reset_at"

        @Volatile
        private var instance: PolicyRepository? = null

        fun getInstance(context: Context): PolicyRepository {
            return instance ?: synchronized(this) {
                instance ?: try {
                    org.koin.core.context.GlobalContext.getOrNull()?.get<PolicyRepository>()
                } catch (t: Throwable) {
                    null
                } ?: PolicyRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun setInstance(repo: PolicyRepository?) {
            instance = repo
        }
    }
}
