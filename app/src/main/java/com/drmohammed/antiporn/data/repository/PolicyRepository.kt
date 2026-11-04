package com.drmohammed.antiporn.data.repository

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.BrowserShield
import com.drmohammed.antiporn.shields.DnsShield
import java.util.TimeZone
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
class PolicyRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val database = AntiPornDatabase.getInstance(appContext)
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _currentPolicyFlow = MutableStateFlow(loadInitialPolicy())
    val currentPolicyFlow: StateFlow<DevicePolicyBundle> = _currentPolicyFlow.asStateFlow()

    private var firestoreListener: ListenerRegistration? = null
    private val firestoreSyncMutex = Mutex()
    @Volatile
    private var lastSyncedCloudVersion: Long = 0L

    init {
        com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = _currentPolicyFlow.value.isSelfControlAllowed
        // Observe local policy changes and trigger background sync
        startFirestoreSync()
    }

    private fun loadInitialPolicy(): DevicePolicyBundle {
        return try {
            val loaded = database.getDevicePolicy()?.toBundle()
            if (loaded != null) {
                // Reconcile standalone devices (no guardians attached) on initial policy v1
                val hasGuardians = try {
                    database.getPartnersByRole(com.drmohammed.antiporn.data.local.db.entity.PartnerEntity.ROLE_GUARDIAN).isNotEmpty()
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
                        database.savePartner(guardian)
                        Log.i(TAG, "Reconciled missing guardian in SQLite on cold boot: ${loaded.guardianName}")
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
                    repositoryScope.launch {
                        try {
                            database.saveDevicePolicy(com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity.fromBundle(reconciled))
                            Log.i(TAG, "Reconciled standalone device policy on cold boot (version=${reconciled.version})")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed saving reconciled policy", e)
                        }
                    }
                    reconciled
                } else {
                    loaded
                }
            } else {
                DevicePolicyBundle.defaultPolicy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading initial policy from SQLite", e)
            DevicePolicyBundle.defaultPolicy()
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
            Log.i(TAG, "[SelfControlDiag] attachPolicyListener: Attaching Firestore listener to /policies/$uid")
            firestoreListener = firestore.collection("policies").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "[SelfControlDiag] Firestore Snapshot ERROR on /policies/$uid: ${error.message} (code=${error.code})")
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
                                        cloudPolicy.guardianUid != current.guardianUid
                                    )
                                    val statusChanged = cloudPolicy.breakoutStatus != current.breakoutStatus
                                    val isTimeNewer = cloudPolicy.updatedAt > current.updatedAt
                                    val isVersionNewer = cloudPolicy.version > current.version

                                    Log.i(TAG, "[SelfControlDiag] Cloud policy received on ward /policies/$uid: cloudPolicy(v${cloudPolicy.version}, supervised=${cloudPolicy.isSupervised}, allowed=${cloudPolicy.isSelfControlAllowed}, guardian='${cloudPolicy.guardianUid}') vs local(v${current.version}, supervised=${current.isSupervised}, allowed=${current.isSelfControlAllowed}), isVersionNewer=$isVersionNewer, isTimeNewer=$isTimeNewer, supervisoryChanged=$supervisoryChanged, statusChanged=$statusChanged")

                                    if (isVersionNewer || isTimeNewer || supervisoryChanged || statusChanged || (current.isSupervised != cloudPolicy.isSupervised)) {
                                        val wasSupervised = current.isSupervised
                                        val isNowSupervised = cloudPolicy.isSupervised

                                        if (wasSupervised && !isNowSupervised) {
                                            Log.i(TAG, "Supervision revocation detected from cloud snapshot! Restoring standalone mode...")
                                            AccountabilityRepository.getInstance(appContext).onSupervisionRevokedLocally()
                                            ApprovalRepository.getInstance(appContext).stopApprovalsSync()
                                        } else if (isNowSupervised && !cloudPolicy.guardianUid.isNullOrBlank()) {
                                            Log.i(TAG, "Guardian detected from cloud snapshot: ${cloudPolicy.guardianName} (${cloudPolicy.guardianUid})")
                                            val partner = com.drmohammed.antiporn.data.local.db.entity.PartnerEntity(
                                                id = cloudPolicy.guardianUid,
                                                pairingCode = AccountabilityRepository.CODE_LOCKED,
                                                displayName = cloudPolicy.guardianName ?: appContext.getString(R.string.guardian_default_name),
                                                role = com.drmohammed.antiporn.data.local.db.entity.PartnerEntity.ROLE_GUARDIAN,
                                                status = com.drmohammed.antiporn.data.local.db.entity.PartnerEntity.STATUS_ACTIVE,
                                                createdAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext),
                                                lastSyncAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
                                            )
                                            database.savePartner(partner)
                                        }

                                        val enforcedPolicy = cloudPolicy

                                        Log.i(TAG, "[SelfControlDiag] Sovereign cloud policy ACCEPTED! Enforcing update on device... (v${enforcedPolicy.version}, isSelfControlAllowed=${enforcedPolicy.isSelfControlAllowed})")
                                        updateLocalPolicyAndEnforce(enforcedPolicy)

                                        if (isFromGuardian) {
                                            repositoryScope.launch {
                                                try {
                                                    FirebaseFirestore.getInstance().collection("policies").document(uid).update(
                                                        mapOf(
                                                            "acknowledgedVersion" to cloudPolicy.version,
                                                            "lastAcknowledgedAt" to com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
                                                        )
                                                    ).await()
                                                    Log.i(TAG, "[SelfControlDiag] Acknowledged policy v${cloudPolicy.version} back to Firestore /policies/$uid")
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
     * Updates policy locally, saves to SQLite, and enforces on the device.
     */
    @Synchronized
    fun updateLocalPolicyAndEnforce(bundle: DevicePolicyBundle) {
        val current = _currentPolicyFlow.value
        Log.i(TAG, "[SelfControlDiag] updateLocalPolicyAndEnforce: v${bundle.version}, isSupervised=${bundle.isSupervised}, isSelfControlAllowed=${bundle.isSelfControlAllowed}")
        val isBreakoutStatusChange = bundle.breakoutStatus != current.breakoutStatus
        if (!isBreakoutStatusChange && (bundle.version < current.version || (bundle.version == current.version && bundle.updatedAt < current.updatedAt))) {
            Log.w(TAG, "Monotonic guard: rejected stale/out-of-order policy v${bundle.version}@${bundle.updatedAt} (current is v${current.version}@${current.updatedAt})")
            return
        }

        database.saveDevicePolicy(DevicePolicyEntity.fromBundle(bundle))
        _currentPolicyFlow.value = bundle
        com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = bundle.isSelfControlAllowed

        val wasSupervised = current.isSupervised
        val isNowSupervised = bundle.isSupervised
        if (wasSupervised && !isNowSupervised) {
            Log.i(TAG, "Supervision revocation detected in updateLocalPolicyAndEnforce! Restoring standalone mode...")
            repositoryScope.launch {
                try {
                    AccountabilityRepository.getInstance(appContext).onSupervisionRevokedLocally()
                    ApprovalRepository.getInstance(appContext).stopApprovalsSync()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up supervision state on revocation", e)
                }
            }
        }
        applyPolicyToSystem(bundle, current)
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
        repositoryScope.launch {
            try {
                val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return@launch
                val isDeviceOwner = com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(appContext)
                val isDo = dpm.isDeviceOwnerApp(appContext.packageName)
                val admin = if (isDeviceOwner) ComponentName(appContext, AdminReceiver::class.java) else null

                // 1. Dynamic Date & Time Protection / Timezone Configuration (Requires DO)
                if (isDo && admin != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Always synchronize device timezone with policy timezone (e.g. Cairo or Makkah)
                    if (bundle.timeZoneId.isNotBlank()) {
                        val validTz = if (com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry.isValidIanaId(bundle.timeZoneId)) {
                            bundle.timeZoneId
                        } else {
                            com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID
                        }
                        try {
                            TimeZone.setDefault(null)
                            val currentTz = TimeZone.getDefault()
                            if (currentTz.id != validTz) {
                                dpm.setTimeZone(admin, validTz)
                                TimeZone.setDefault(null)
                                Log.i(TAG, "Applied dynamic timezone update to: $validTz")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed setting timezone ${bundle.timeZoneId} via DPM", e)
                        }
                    }

                    if (bundle.isDateTimeLocked) {
                        try {
                            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
                            Log.i(TAG, "Enforced DISALLOW_CONFIG_DATE_TIME restriction from policy bundle")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed applying DISALLOW_CONFIG_DATE_TIME: ${e.message}")
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                dpm.setAutoTimeZoneEnabled(admin, false)
                                dpm.setAutoTimeEnabled(admin, true)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed setting auto time/timezone on Android 11+: ${e.message}")
                            }
                        } else {
                            try {
                                @Suppress("DEPRECATION")
                                dpm.setAutoTimeRequired(admin, true)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed setting auto time required: ${e.message}")
                            }
                        }
                    } else {
                        // Time lock is not enabled: Clear restrictions and restore default auto-time state
                        try {
                            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
                            Log.i(TAG, "Cleared DISALLOW_CONFIG_DATE_TIME restriction from policy bundle (phone settings open)")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed clearing DISALLOW_CONFIG_DATE_TIME: ${e.message}")
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                dpm.setAutoTimeZoneEnabled(admin, true)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed restoring auto timezone on Android 11+: ${e.message}")
                            }
                        } else {
                            try {
                                @Suppress("DEPRECATION")
                                dpm.setAutoTimeRequired(admin, false)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed clearing auto time required: ${e.message}")
                            }
                        }
                    }
                }

                // 2. Reschedule curfew alarms (using the newly updated timezone)
                CurfewScheduler.scheduleNextAlarms(appContext)

                // 3. Synchronize curfew enforcer and VPN bypassed packages with new timings and parameters
                com.drmohammed.antiporn.curfew.CurfewConfig.setVpnBypassedPackages(appContext, bundle.curfewVpnBypassPackages)
                CurfewEnforcer.syncBlockState(appContext)
                com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.syncPolicy(appContext)

                // 4. Apply browser & DNS restrictions, custom blacklist suspension, and protected apps
                if (isDeviceOwner && admin != null) {
                    BrowserShield.applyBrowserRestrictions(dpm, admin, bundle)
                    DnsShield.apply(appContext, dpm, admin)

                    // 24/7 Custom App Freezing (bundle.customBlacklistPackages)
                    val currentBlacklist = bundle.customBlacklistPackages
                    val criticalPackages = com.drmohammed.antiporn.curfew.CurfewAppResolver.resolveCoreSystemCriticalPackages(appContext)
                    val packagesToSuspend = currentBlacklist.filter {
                        !criticalPackages.contains(it) && !AppPolicyRegistry.isImmune(it)
                    }.toTypedArray()
                    if (packagesToSuspend.isNotEmpty()) {
                        try {
                            dpm.setPackagesSuspended(admin, packagesToSuspend, true)
                            Log.i(TAG, "Enforced 24/7 custom blacklist suspension for ${packagesToSuspend.size} packages")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed suspending customBlacklistPackages", e)
                        }
                    }

                    // Unsuspend packages that were removed from custom blacklist
                    val removedFromBlacklist = (previousPolicy.customBlacklistPackages - currentBlacklist).filter {
                        it != appContext.packageName && !AppPolicyRegistry.isImmune(it) && !AppSuspensionShield.isBlacklisted(it)
                    }
                    if (removedFromBlacklist.isNotEmpty()) {
                        val isBlockActive = CurfewEnforcer.isEffectiveBlockActive(appContext)
                        val immunePackages = CurfewAppResolver.resolveImmunePackages(appContext)
                        val pendingPackages = ApprovalRepository.getInstance(appContext)
                            .getLocalPendingApprovals()
                            .filter { it.isPending }
                            .map { it.packageName }
                            .toSet()
                        val packagesToUnsuspend = removedFromBlacklist.filter { pkg ->
                            (!isBlockActive || immunePackages.contains(pkg)) && !pendingPackages.contains(pkg)
                        }.toTypedArray()
                        if (packagesToUnsuspend.isNotEmpty()) {
                            try {
                                dpm.setPackagesSuspended(admin, packagesToUnsuspend, false)
                                Log.i(TAG, "Unsuspended ${packagesToUnsuspend.size} packages removed from custom blacklist")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed unsuspending removed blacklist packages", e)
                            }
                        }
                    }

                    // Immediately unsuspend packages added to curfew whitelist or immune if curfew is inactive
                    val newlyWhitelisted = bundle.customCurfewWhitelistPackages
                    val isBlockActive = CurfewEnforcer.isEffectiveBlockActive(appContext)
                    if (!isBlockActive && newlyWhitelisted.isNotEmpty()) {
                        val isZeroTrustActive = bundle.lockNewInstallsUntilApproved
                        val approvedPackages = if (isZeroTrustActive) {
                            try {
                                ApprovalRepository.getInstance(appContext).getLocalPendingApprovals()
                                    .filter { it.isApproved }
                                    .map { it.packageName }
                                    .toSet()
                            } catch (e: Exception) {
                                emptySet()
                            }
                        } else emptySet()
                        val immunePackages = if (isZeroTrustActive) {
                            CurfewAppResolver.resolveImmunePackages(appContext)
                        } else emptySet()

                        val whitelistedToUnsuspend = newlyWhitelisted.filter { pkg ->
                            !currentBlacklist.contains(pkg) &&
                            !AppSuspensionShield.isBlacklisted(pkg) &&
                            (!isZeroTrustActive || approvedPackages.contains(pkg) || immunePackages.contains(pkg) || pkg == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE || CurfewConfig.KNOWN_APP_STORES.contains(pkg)) &&
                            try { dpm.isPackageSuspended(admin, pkg) } catch (e: Exception) { false }
                        }.toTypedArray()
                        if (whitelistedToUnsuspend.isNotEmpty()) {
                            try {
                                dpm.setPackagesSuspended(admin, whitelistedToUnsuspend, false)
                                Log.i(TAG, "Unsuspended ${whitelistedToUnsuspend.size} newly whitelisted packages during daytime: ${whitelistedToUnsuspend.joinToString()}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed unsuspending newly whitelisted packages", e)
                            }
                        }
                    }

                    // App Protection (bundle.protectedAppPackages)
                    for (pkg in bundle.protectedAppPackages) {
                        try {
                            dpm.setUninstallBlocked(admin, pkg, true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed setting uninstall blocked for protected package: $pkg", e)
                        }
                    }

                    // Clear uninstall blocked for packages removed from protection
                    val unprotectPackages = previousPolicy.protectedAppPackages - bundle.protectedAppPackages
                    for (pkg in unprotectPackages) {
                        try {
                            dpm.setUninstallBlocked(admin, pkg, false)
                            Log.i(TAG, "Cleared uninstall blocked for unprotected package: $pkg")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed clearing uninstall blocked for package: $pkg", e)
                        }
                    }

                    if (bundle.protectedAppPackages.isNotEmpty()) {
                        dpm.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
                        Log.i(TAG, "Added DISALLOW_APPS_CONTROL restriction for ${bundle.protectedAppPackages.size} protected packages")
                    } else {
                        dpm.clearUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
                    }
                }

                // 4. Synchronize Zero-Trust active foreground watcher state
                ZeroTrustForegroundWatcher.syncWatcherState(appContext)

                // 5. Trigger inventory sync to cloud directly in coroutine
                AppInventoryManager.getInstance(appContext).syncInventoryToCloud()

                // 6. Synchronize Unified Local DNS Filter and Curfew VPN engine
                com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.syncPolicy(appContext)

                Log.i(TAG, "Successfully applied policy bundle v${bundle.version} to system engines")
            } catch (e: Exception) {
                Log.e(TAG, "Error applying policy bundle to system engines", e)
            }
        }
    }

    /**
     * Saves policy to Firestore for a specific target ward UID (invoked from Guardian interface).
     */
    suspend fun pushPolicyToWard(wardUid: String, bundle: DevicePolicyBundle): Boolean {
        val firestore = FirebaseFirestore.getInstance()
        val snap = try {
            firestore.collection("policies").document(wardUid).get().await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        val cloudVersion = snap?.getLong("version") ?: 0L
        val targetVersion = if (bundle.version > cloudVersion) bundle.version else cloudVersion + 1L
        val policyToPush = bundle.copy(
            version = targetVersion,
            updatedAt = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
        )
        Log.i(TAG, "[SelfControlDiag] pushPolicyToWard: writing to Firestore /policies/$wardUid: isSelfControlAllowed=${policyToPush.isSelfControlAllowed}, version=$targetVersion")
        return try {
            firestore.collection("policies").document(wardUid)
                .set(policyToPush.toMap())
                .await()
            Log.i(TAG, "[SelfControlDiag] pushPolicyToWard SUCCESS for wardUid=$wardUid (isSelfControlAllowed=${policyToPush.isSelfControlAllowed}, v$targetVersion)")
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "[SelfControlDiag] pushPolicyToWard FAILED for wardUid=$wardUid: ${e.message}", e)
            false
        }
    }

    private fun syncPolicyToCloudAsync(uid: String, bundle: DevicePolicyBundle) {
        if (uid.isBlank()) return
        repositoryScope.launch {
            firestoreSyncMutex.withLock {
                try {
                    if (bundle.version >= lastSyncedCloudVersion) {
                        FirebaseFirestore.getInstance().collection("policies").document(uid)
                            .set(bundle.toMap())
                            .await()
                        lastSyncedCloudVersion = bundle.version
                        Log.i(TAG, "Successfully synced policy v${bundle.version} to cloud ($uid)")
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
            val updated = synchronized(this@PolicyRepository) {
                val current = _currentPolicyFlow.value
                val isSupervised = current.isSupervised || bundle.isSupervised || try {
                    AccountabilityRepository.getInstance(appContext).isSupervised()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query accountability supervision in saveAndEnforceSelfPolicy", e)
                    false
                }
                Log.i(TAG, "[SelfControlDiag] saveAndEnforceSelfPolicy called: isSupervised=$isSupervised, current.isSelfControlAllowed=${current.isSelfControlAllowed}, bundle.isSelfControlAllowed=${bundle.isSelfControlAllowed}")

                if (isSupervised && (!current.isSelfControlAllowed || !bundle.isSelfControlAllowed)) {
                    Log.w(TAG, "[SelfControlDiag] saveAndEnforceSelfPolicy REJECTED: self-control is disabled by supervisor! (current.allowed=${current.isSelfControlAllowed}, bundle.allowed=${bundle.isSelfControlAllowed})")
                    return@withContext false
                }

                val newPolicy = bundle.copy(
                    guardianUid = current.guardianUid,
                    guardianName = current.guardianName,
                    isSupervised = current.isSupervised,
                    isSelfControlAllowed = current.isSelfControlAllowed,
                    guardianPolicySnapshot = if (isSupervised) (current.guardianPolicySnapshot ?: current.toJson()) else null,
                    lastSelfModifiedAt = if (isSupervised) TrustedTimeProvider.currentTimeMillis(appContext) else null,
                    version = current.version + 1,
                    updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
                )
                updateLocalPolicyAndEnforce(newPolicy)
                newPolicy
            }

            // Asynchronously sync to cloud document if a synchronized UID exists
            val myUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
            syncPolicyToCloudAsync(myUid, updated)
            true
        } catch (e: Exception) {
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
            val updated = synchronized(this@PolicyRepository) {
                val current = _currentPolicyFlow.value
                if (current.customCurfewWhitelistPackages.contains(packageName)) {
                    return@withContext true
                }
                if (AppPolicyRegistry.isRestrictedBrowser(packageName) ||
                    com.drmohammed.antiporn.curfew.CurfewConfig.KNOWN_APP_STORES.contains(packageName) ||
                    com.drmohammed.antiporn.curfew.CurfewConfig.CURFEW_SEARCH_APPS.contains(packageName) ||
                    AppSuspensionShield.isBlacklisted(packageName) ||
                    current.customBlacklistPackages.contains(packageName)) {
                    Log.w(TAG, "Refusing to add restricted or blacklisted package to curfew whitelist: $packageName")
                    return@withContext false
                }
                val newPolicy = current.copy(
                    customCurfewWhitelistPackages = current.customCurfewWhitelistPackages + packageName,
                    version = current.version + 1,
                    updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
                )
                updateLocalPolicyAndEnforce(newPolicy)
                newPolicy
            }

            val myUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
            syncPolicyToCloudAsync(myUid, updated)
            Log.i(TAG, "Auto-whitelisted new Islamic/productive app in curfew: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed auto-whitelisting app $packageName in curfew", e)
            false
        }
    }

    companion object {
        private const val TAG = "AntiPorn_PolicyRepo"

        @Volatile
        private var instance: PolicyRepository? = null

        fun getInstance(context: Context): PolicyRepository {
            return instance ?: synchronized(this) {
                instance ?: PolicyRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
