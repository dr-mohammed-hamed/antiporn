package com.drmohammed.antiporn.data.repository

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.shields.BrowserShield
import com.drmohammed.antiporn.shields.DnsShield
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
                    false
                }
                if (!hasGuardians && loaded.version == 1L && loaded.isVpnBlackholeEnabled != com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED) {
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating Firestore sync; continuing with offline local SQLite policy", e)
        }
    }

    private fun attachPolicyListener(uid: String) {
        firestoreListener?.remove()
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
                                    if (cloudPolicy.version > current.version || (cloudPolicy.version == current.version && cloudPolicy.updatedAt > current.updatedAt)) {
                                        Log.i(TAG, "Received newer policy from cloud (v${cloudPolicy.version})")
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
        if (bundle.version < current.version || (bundle.version == current.version && bundle.updatedAt < current.updatedAt)) {
            Log.w(TAG, "Monotonic guard: rejected stale/out-of-order policy v${bundle.version}@${bundle.updatedAt} (current is v${current.version}@${current.updatedAt})")
            return
        }
        _currentPolicyFlow.value = bundle
        database.saveDevicePolicy(DevicePolicyEntity.fromBundle(bundle))
        applyPolicyToSystem(bundle)
    }

    /**
     * Applies policy bundle parameters across system engines (Curfew, Browser, VPN).
     */
    fun applyPolicyToSystem(bundle: DevicePolicyBundle) {
        repositoryScope.launch {
            try {
                // 1. Reschedule curfew alarms
                CurfewScheduler.scheduleNextAlarms(appContext)

                // 2. Synchronize curfew enforcer with new timings and parameters
                CurfewEnforcer.syncBlockState(appContext)

                // 3. Apply browser & DNS restrictions
                val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                if (dpm != null && dpm.isDeviceOwnerApp(appContext.packageName)) {
                    val admin = ComponentName(appContext, AdminReceiver::class.java)
                    BrowserShield.applyBrowserRestrictions(dpm, admin, bundle.isIncognitoBlocked)
                    DnsShield.apply(appContext, dpm, admin)
                }
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
