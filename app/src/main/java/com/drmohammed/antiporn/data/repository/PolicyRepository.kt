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
import kotlinx.coroutines.tasks.await

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

    init {
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
                    loaded
                } else if (!hasGuardians && loaded.version == 1L && loaded.isVpnBlackholeEnabled != com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED) {
                    val reconciled = loaded.copy(
                        isVpnBlackholeEnabled = com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED,
                        updatedAt = System.currentTimeMillis()
                    )
                    repositoryScope.launch {
                        try {
                            val current = _currentPolicyFlow.value
                            if (current.version == 1L && current.isVpnBlackholeEnabled == reconciled.isVpnBlackholeEnabled) {
                                database.saveDevicePolicy(com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity.fromBundle(reconciled))
                                Log.i(TAG, "Reconciled standalone device policy asynchronously with DEFAULT_VPN_BLACKHOLE_ENABLED=${com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED}")
                            } else {
                                Log.i(TAG, "Skipping async reconcile save: policy already superseded by v${current.version}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed saving reconciled standalone policy", e)
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
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed anonymous auth for policy sync, falling back to persistent device UID: ${e.message}")
                        val fallbackUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
                        attachPolicyListener(fallbackUid)
                        ApprovalRepository.getInstance(appContext).startApprovalsSync(fallbackUid)
                    }
            } else {
                AccountabilityRepository.getInstance(appContext).persistUid(user.uid)
                com.drmohammed.antiporn.cloud.fcm.AntiPornFirebaseMessagingService.syncPersistedToken(appContext, user.uid)
                attachPolicyListener(user.uid)
                ApprovalRepository.getInstance(appContext).startApprovalsSync(user.uid)
                repositoryScope.launch {
                    AccountabilityRepository.getInstance(appContext).syncPartnersFromCloud()
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
            firestoreListener = firestore.collection("policies").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Error listening to cloud policy updates: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val data = snapshot.data
                        if (data != null) {
                            repositoryScope.launch {
                                try {
                                    val cloudPolicy = DevicePolicyBundle.fromMap(data)
                                    val current = _currentPolicyFlow.value
                                    val statusChanged = cloudPolicy.breakoutStatus != current.breakoutStatus
                                    if (cloudPolicy.version > current.version || (cloudPolicy.version == current.version && cloudPolicy.updatedAt > current.updatedAt) || statusChanged) {
                                        Log.i(TAG, "Received policy update from cloud (v${cloudPolicy.version}, breakout=${cloudPolicy.breakoutStatus})")
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
                                                createdAt = System.currentTimeMillis(),
                                                lastSyncAt = System.currentTimeMillis()
                                            )
                                            database.savePartner(partner)
                                        }

                                        updateLocalPolicyAndEnforce(cloudPolicy)
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
    fun updateLocalPolicyAndEnforce(bundle: DevicePolicyBundle) {
        val current = _currentPolicyFlow.value
        val isBreakoutStatusChange = bundle.breakoutStatus != current.breakoutStatus
        if (!isBreakoutStatusChange && (bundle.version < current.version || (bundle.version == current.version && bundle.updatedAt < current.updatedAt))) {
            Log.w(TAG, "Monotonic guard: rejected stale/out-of-order policy v${bundle.version}@${bundle.updatedAt} (current is v${current.version}@${current.updatedAt})")
            return
        }
        _currentPolicyFlow.value = bundle
        database.saveDevicePolicy(DevicePolicyEntity.fromBundle(bundle))
        applyPolicyToSystem(bundle, current)
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
                    if (bundle.isDateTimeLocked) {
                        try {
                            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
                            Log.i(TAG, "Enforced DISALLOW_CONFIG_DATE_TIME restriction from policy bundle")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed applying DISALLOW_CONFIG_DATE_TIME: ${e.message}")
                        }

                        if (bundle.timeZoneId.isNotBlank()) {
                            val validTz = if (com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry.isValidIanaId(bundle.timeZoneId)) {
                                bundle.timeZoneId
                            } else {
                                com.drmohammed.antiporn.curfew.CurfewConfig.MAKKAH_TIMEZONE_ID
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
                            Log.i(TAG, "Cleared DISALLOW_CONFIG_DATE_TIME restriction from policy bundle")
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

                // 3. Synchronize curfew enforcer with new timings and parameters
                CurfewEnforcer.syncBlockState(appContext)

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
                        val packagesToUnsuspend = removedFromBlacklist.filter { pkg ->
                            !isBlockActive || immunePackages.contains(pkg)
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
        return try {
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("policies").document(wardUid)
                .set(bundle.toMap())
                .await()
            Log.i(TAG, "Successfully pushed policy update to ward $wardUid")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed pushing policy update to ward $wardUid", e)
            false
        }
    }

    /**
     * Saves updated policy bundle directly for the local device, enforcing immediately
     * in SQLite and system engines (DPM), with asynchronous background cloud sync to Firestore.
     */
    suspend fun saveAndEnforceSelfPolicy(bundle: DevicePolicyBundle): Boolean {
        return try {
            val current = _currentPolicyFlow.value
            val updated = bundle.copy(
                version = current.version + 1,
                updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
            )
            updateLocalPolicyAndEnforce(updated)

            // Asynchronously sync to cloud document if a synchronized UID exists
            val myUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
            if (myUid.isNotBlank()) {
                repositoryScope.launch {
                    try {
                        FirebaseFirestore.getInstance().collection("policies").document(myUid)
                            .set(updated.toMap())
                            .await()
                        Log.i(TAG, "Successfully synced self-control policy update to cloud ($myUid)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Self-control policy cloud sync deferred (offline or error): ${e.message}")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in saveAndEnforceSelfPolicy", e)
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
