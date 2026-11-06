package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.Locale

/**
 * Repository responsible for Anonymous Authentication, Pairing Code generation,
 * and managing Guardian / Ward relationships both locally in SQLite and in Cloud Firestore.
 * Implements strict single-guardian enforcement and automated revocation recovery.
 */
class AccountabilityRepository(
    context: Context,
    private val partnerDao: PartnerDao = HisnRoomDatabase.getInstance(context).partnerDao(),
    private val policyDao: DevicePolicyDao = HisnRoomDatabase.getInstance(context).devicePolicyDao()
) {

    private val appContext = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    val partnersFlow: Flow<List<PartnerEntity>> = partnerDao.getAllPartnersFlow()
    val guardiansFlow: Flow<List<PartnerEntity>> = partnerDao.getPartnersByRoleFlow(PartnerEntity.ROLE_GUARDIAN)
    val wardsFlow: Flow<List<PartnerEntity>> = partnerDao.getPartnersByRoleFlow(PartnerEntity.ROLE_WARD)

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val syncPartnersMutex = Mutex()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identityMigrator by lazy { AccountabilityIdentityMigrator(appContext, firestore, partnerDao, policyDao) }

    suspend fun getOrCreateCurrentUid(): String = withContext(Dispatchers.IO) {
        val user = auth.currentUser
        if (user != null) {
            persistUid(user.uid)
            com.drmohammed.antiporn.cloud.fcm.AntiPornFirebaseMessagingService.syncPersistedToken(appContext, user.uid)
            return@withContext user.uid
        }
        try {
            val result = auth.signInAnonymously().await()
            val uid = result.user?.uid
            if (!uid.isNullOrBlank()) {
                persistUid(uid)
                com.drmohammed.antiporn.cloud.fcm.AntiPornFirebaseMessagingService.syncPersistedToken(appContext, uid)
                return@withContext uid
            }
        } catch (e: Exception) {
            Log.w(TAG, "Anonymous auth unavailable, using persistent device UUID fallback: ${e.message}")
        }
        getCachedUid()
    }

    fun getCachedUid(): String {
        val authUid = auth.currentUser?.uid
        if (!authUid.isNullOrBlank()) {
            persistUid(authUid)
            return authUid
        }
        var persistedUid = prefs.getString(KEY_PERSISTED_UID, null)
        if (persistedUid.isNullOrBlank()) {
            persistedUid = "dev_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20)
            persistUid(persistedUid)
        }
        return persistedUid
    }

    fun persistUid(uid: String) {
        val oldUid = prefs.getString(KEY_PERSISTED_UID, null)
        if (uid.isNotBlank() && oldUid != uid) {
            prefs.edit().putString(KEY_PERSISTED_UID, uid).apply()
            Log.i(TAG, "Persisted synchronized UID: $uid")
            if (!oldUid.isNullOrBlank()) {
                Log.i(TAG, "UID transitioned from $oldUid to $uid. Invalidating old cached pairing code.")
                prefs.edit().remove(KEY_MY_PAIRING_CODE).apply()
                repositoryScope.launch(Dispatchers.IO) {
                    migrateUserIdentity(oldUid, uid)
                }
            }
        }
    }

    /**
     * Migrates user identity in Firestore from [oldUid] to [newUid].
     */
    suspend fun migrateUserIdentity(oldUid: String, newUid: String) =
        identityMigrator.migrateUserIdentity(oldUid, newUid)

    /**
     * Resolves the active UID for a partner, healing stale references caused by identity migrations.
     */
    suspend fun resolveActivePartnerUid(partnerUid: String, pairingCode: String? = null): String =
        identityMigrator.resolveActivePartnerUid(partnerUid, pairingCode)

    /**
     * Synchronously checks whether this device is currently under active guardian supervision.
     */
    fun isSupervised(): Boolean {
        val policy = policyDao.getDevicePolicyBlocking()?.toBundle()
        return policy?.isSupervised == true
    }

    /**
     * Gets or generates the device's persistent 6-character pairing code.
     * If the device is currently supervised, returns LOCKED and hides code from cloud discovery.
     * Automatically resolves collisions and self-heals permissions if the code belongs to another UID.
     */
    suspend fun getOrCreateMyPairingCode(): String = withContext(Dispatchers.IO) {
        val currentPolicy = policyDao.getDevicePolicyBlocking()?.toBundle() ?: DevicePolicyBundle.defaultPolicy()
        if (currentPolicy.isSupervised) {
            return@withContext CODE_LOCKED
        }

        val uid = getOrCreateCurrentUid()
        if (uid.startsWith("dev_") && auth.currentUser == null) {
            Log.w(TAG, "Device currently unauthenticated; skipping pairing code cloud publication")
            var offlineCode = prefs.getString(KEY_MY_PAIRING_CODE, null)
            if (offlineCode.isNullOrBlank()) {
                offlineCode = generateRandomCode()
                prefs.edit().putString(KEY_MY_PAIRING_CODE, offlineCode).apply()
            }
            return@withContext offlineCode
        }

        var code = prefs.getString(KEY_MY_PAIRING_CODE, null)
        var registered = false
        var attempts = 0
        val deviceName = getMyDisplayName()

        while (!registered && attempts < 3) {
            attempts++
            if (code.isNullOrBlank()) {
                code = generateRandomCode()
            }

            try {
                val existingDoc = firestore.collection("pairings").document(code).get().await()
                if (existingDoc.exists()) {
                    val existingOwner = existingDoc.getString("ownerUid")
                    if (!existingOwner.isNullOrBlank() && existingOwner != uid) {
                        Log.w(TAG, "Pairing code $code already owned by $existingOwner (not current $uid). Rotating...")
                        prefs.edit().remove(KEY_MY_PAIRING_CODE).apply()
                        code = null
                        continue
                    }
                }

                val currentLocalPolicy = policyDao.getDevicePolicyBlocking()?.toBundle() ?: DevicePolicyBundle.defaultPolicy()
                val pairingDoc = mapOf(
                    "pairingCode" to code,
                    "ownerUid" to uid,
                    "ownerName" to deviceName,
                    "status" to "ACTIVE",
                    "policyVersion" to currentLocalPolicy.version,
                    "createdAt" to (existingDoc.getLong("createdAt") ?: TrustedTimeProvider.currentTimeMillis(appContext))
                )
                firestore.collection("pairings").document(code).set(pairingDoc).await()
                prefs.edit().putString(KEY_MY_PAIRING_CODE, code).apply()
                registered = true
                Log.i(TAG, "Successfully registered pairing code $code for UID $uid (name: $deviceName, policyVersion: ${currentLocalPolicy.version})")
                com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                    appContext,
                    "CODE_REGISTERED",
                    isSuccess = true,
                    details = "code=$code, uid=$uid, version=${currentLocalPolicy.version}"
                )

                // Proactively ensure base policy exists in Firestore under /policies/$uid
                try {
                    firestore.collection("policies").document(uid).set(currentLocalPolicy.toMap(), com.google.firebase.firestore.SetOptions.merge()).await()
                    Log.d(TAG, "Ensured base policy document in Firestore for UID $uid")
                } catch (policyEx: Exception) {
                    Log.w(TAG, "Non-critical: base policy document seed skipped: ${policyEx.message}")
                }

                AppInventoryManager.getInstance(appContext).syncInventoryToCloudAsync()
            } catch (e: Exception) {
                if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    Log.w(TAG, "Permission denied for pairing code $code (stale or conflicting ownership). Rotating...")
                    prefs.edit().remove(KEY_MY_PAIRING_CODE).apply()
                    code = null
                } else {
                    Log.e(TAG, "Error publishing pairing code to Firestore", e)
                    break
                }
            }
        }

        code ?: (prefs.getString(KEY_MY_PAIRING_CODE, null) ?: generateRandomCode())
    }

    /**
     * Pairs with a partner by looking up their 6-digit pairing code in Firestore.
     * Enforces strict single-guardian lock: prevents linking if target or local device is already supervised.
     * @param code The 6-digit pairing code entered by the user.
     * @param role The role to assign: "GUARDIAN" (they protect me) or "WARD" (I protect them).
     */
    suspend fun pairWithCode(code: String, role: String): Result<PartnerEntity> = withContext(Dispatchers.IO) {
        try {
            val cleanCode = code.trim().uppercase()
            val myUid = getOrCreateCurrentUid()

            val snapshot = firestore.collection("pairings").document(cleanCode).get().await()
            if (!snapshot.exists()) {
                return@withContext Result.failure(IllegalArgumentException(appContext.getString(R.string.error_pairing_code_not_found)))
            }

            val targetUid = snapshot.getString("ownerUid")?.takeIf { it.isNotBlank() } ?: return@withContext Result.failure(
                IllegalStateException(appContext.getString(R.string.error_pairing_code_incomplete))
            )
            val targetName = snapshot.getString("ownerName") ?: appContext.getString(R.string.partner_default_name)

            if (targetUid == myUid) {
                return@withContext Result.failure(IllegalArgumentException(appContext.getString(R.string.error_cannot_pair_self)))
            }

            val myDeviceName = getMyDisplayName()

            if (role == PartnerEntity.ROLE_WARD) {
                // I am protecting targetUid (targetUid is my WARD, I am their GUARDIAN)
                val policyDoc = try {
                    firestore.collection("policies").document(targetUid).get().await()
                } catch (e: Exception) {
                    Log.w(TAG, "Ward policy doc not readable or doesn't exist yet before pairing: ${e.message}")
                    null
                }
                val existingGuardianUid = policyDoc?.getString("guardianUid")
                val isAlreadySupervised = policyDoc?.getBoolean("isSupervised") ?: (!existingGuardianUid.isNullOrBlank())

                if (isAlreadySupervised && !existingGuardianUid.isNullOrBlank() && existingGuardianUid != myUid) {
                    return@withContext Result.failure(IllegalStateException(appContext.getString(R.string.error_ward_already_has_guardian)))
                }

                val currentVersion = policyDoc?.getLong("version") ?: 1L
                val pairingPolicyVersion = snapshot.getLong("policyVersion") ?: 1L
                val baseVersion = maxOf(currentVersion, pairingPolicyVersion)
                val basePolicy = policyDoc?.data?.let { DevicePolicyBundle.fromMap(it) }
                    ?: DevicePolicyBundle.defaultPolicy()

                val updatedPolicy = basePolicy.copy(
                    guardianUid = myUid,
                    guardianName = myDeviceName,
                    isSupervised = true,
                    version = baseVersion + 1,
                    updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
                )
                try {
                    firestore.collection("pairings").document(cleanCode).update(
                        mapOf("status" to "LOCKED", "pairedGuardianUid" to myUid)
                    ).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Pairing code lock status warning: ${e.message}")
                }

                val policyMap = updatedPolicy.toMap().toMutableMap().apply {
                    put("pairingCode", cleanCode)
                }
                firestore.collection("policies").document(targetUid).set(policyMap).await()
            } else if (role == PartnerEntity.ROLE_GUARDIAN) {
                // Target is protecting ME (Target is my GUARDIAN, I am their WARD)
                val myPolicyDoc = try {
                    firestore.collection("policies").document(myUid).get().await()
                } catch (e: Exception) {
                    Log.w(TAG, "My policy doc not readable or doesn't exist yet: ${e.message}")
                    null
                }
                val existingGuardian = myPolicyDoc?.getString("guardianUid")
                val isAlreadySupervised = myPolicyDoc?.getBoolean("isSupervised") ?: (!existingGuardian.isNullOrBlank())

                if (isAlreadySupervised && !existingGuardian.isNullOrBlank() && existingGuardian != targetUid) {
                    return@withContext Result.failure(IllegalStateException(appContext.getString(R.string.error_already_supervised)))
                }

                val currentVersion = myPolicyDoc?.getLong("version") ?: 1L
                val basePolicy = myPolicyDoc?.data?.let { DevicePolicyBundle.fromMap(it) }
                    ?: policyDao.getDevicePolicyBlocking()?.toBundle()
                    ?: DevicePolicyBundle.defaultPolicy()

                val updatedMyPolicy = basePolicy.copy(
                    guardianUid = targetUid,
                    guardianName = targetName,
                    isSupervised = true,
                    version = currentVersion + 1,
                    updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
                )
                firestore.collection("policies").document(myUid).set(updatedMyPolicy.toMap()).await()
                policyDao.saveDevicePolicyBlocking(DevicePolicyEntity.fromBundle(updatedMyPolicy))
                PolicyRepository.getInstance(appContext).updateLocalPolicyAndEnforce(updatedMyPolicy)
            }

            val partner = PartnerEntity(
                id = targetUid,
                pairingCode = cleanCode,
                displayName = targetName,
                role = role,
                status = PartnerEntity.STATUS_ACTIVE,
                createdAt = TrustedTimeProvider.currentTimeMillis(appContext),
                lastSyncAt = TrustedTimeProvider.currentTimeMillis(appContext)
            )

            // Save relation in Firestore
            val relationData = mapOf(
                "partnerUid" to targetUid,
                "partnerName" to targetName,
                "role" to role,
                "pairingCode" to cleanCode,
                "pairedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
            )
            firestore.collection("users").document(myUid)
                .collection("partners").document(targetUid)
                .set(relationData)
                .await()

            // Save locally in SQLite
            partnerDao.savePartnerBlocking(partner)

            Log.i(TAG, "Successfully paired with $targetName ($targetUid) as $role under strict single guardian lock")
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                appContext,
                "PAIR_SUCCESS",
                isSuccess = true,
                details = "targetUid=$targetUid, targetName=$targetName, role=$role, code=$cleanCode"
            )
            Result.success(partner)
        } catch (e: Exception) {
            Log.e(TAG, "Error during pairing with code $code", e)
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                appContext,
                "PAIR_FAILED",
                isSuccess = false,
                details = "code=$code, role=$role",
                error = e.message
            )
            Result.failure(e)
        }
    }

    /**
     * Revokes supervision over a ward device.
     * Invoked when a Guardian deletes a ward from their Accountability list.
     * Resets the ward's policy to safe default settings, sets guardianUid = null,
     * and deletes the cloud/local partner relationship.
     *
     * Fully heals UID migrations: resolves any active Google account alias and updates
     * both the original pairing UID and active UID to prevent the ward from getting stranded.
     */
    suspend fun revokeSupervision(wardUid: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val myUid = getOrCreateCurrentUid()

            // Resolve active UID in case ward migrated (e.g. from initial anonymous setup to Google)
            val partner = partnerDao.getPartnerByIdBlocking(wardUid)
            val resolvedActiveUid = resolveActivePartnerUid(wardUid, partner?.pairingCode)
            val targetUids = mutableSetOf(wardUid)
            if (resolvedActiveUid.isNotBlank()) {
                targetUids.add(resolvedActiveUid)
            }

            for (targetUid in targetUids) {
                try {
                    val policyDoc = firestore.collection("policies").document(targetUid).get().await()
                    val currentVersion = policyDoc.getLong("version") ?: 1L

                    // Reset ward policy to safe default bundle with guardianUid = null
                    val safeDefaultPolicy = DevicePolicyBundle.defaultPolicy().copy(
                        guardianUid = null,
                        guardianName = null,
                        isSupervised = false,
                        version = currentVersion + 1,
                        updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
                    )
                    firestore.collection("policies").document(targetUid).set(safeDefaultPolicy.toMap()).await()
                    Log.i(TAG, "Revoked cloud policy for ward UID: $targetUid (v${safeDefaultPolicy.version})")
                } catch (e: Exception) {
                    Log.w(TAG, "Policy doc revocation warning for UID $targetUid: ${e.message}")
                }

                // Delete relation in cloud
                try {
                    firestore.collection("users").document(myUid)
                        .collection("partners").document(targetUid)
                        .delete()
                        .await()
                } catch (e: Exception) {
                    Log.w(TAG, "Cloud partner relation delete warning for UID $targetUid: ${e.message}")
                }

                // Delete locally from SQLite
                partnerDao.deletePartnerBlocking(targetUid)
            }

            Log.i(TAG, "Successfully revoked supervision for ward targets: $targetUids")
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                appContext,
                "SUPERVISION_REVOKED_BY_GUARDIAN",
                isSuccess = true,
                details = "targets=$targetUids"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error revoking supervision for ward $wardUid", e)
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                appContext,
                "SUPERVISION_REVOKE_FAILED",
                isSuccess = false,
                details = "wardUid=$wardUid",
                error = e.message
            )
            Result.failure(e)
        }
    }

    /**
     * Handles local restoration when a ward device receives a policy update
     * indicating that supervision has been revoked by the Guardian.
     * Deletes local guardian records, burns the old pairing code,
     * generates a fresh random pairing code, and registers it in Firestore.
     */
    suspend fun onSupervisionRevokedLocally() = withContext(Dispatchers.IO) {
        try {
            // 1. Delete all guardian records locally
            val guardians = partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_GUARDIAN)
            val currentCode = prefs.getString(KEY_MY_PAIRING_CODE, null)
            if (guardians.isEmpty() && !currentCode.isNullOrBlank() && currentCode != CODE_LOCKED) {
                Log.d(TAG, "Local supervision already revoked and fresh pairing code exists, skipping redundant cycle")
                return@withContext
            }

            val uid = getOrCreateCurrentUid()
            for (guardian in guardians) {
                partnerDao.deletePartnerBlocking(guardian.id)
                // Delete from cloud Firestore /users/{uid}/partners/{guardian.id} to eliminate ghost guardians
                if (!uid.startsWith("dev_") || auth.currentUser != null) {
                    repositoryScope.launch(Dispatchers.IO) {
                        try {
                            firestore.collection("users").document(uid)
                                .collection("partners").document(guardian.id)
                                .delete().await()
                            Log.d(TAG, "Pruned revoked guardian ${guardian.id} from cloud /users/$uid/partners")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed pruning revoked guardian from cloud: ${e.message}")
                        }
                    }
                }
            }

            val oldCode = prefs.getString(KEY_MY_PAIRING_CODE, null)

            // 2. Generate a brand new cryptographically random 6-character code immediately
            // to eliminate any race condition with UI collecting policy and seeing an empty or locked code
            val newCode = generateRandomCode()
            prefs.edit().putString(KEY_MY_PAIRING_CODE, newCode).apply()

            // 3. Delete old pairing code from cloud in background non-blocking coroutine
            if (!oldCode.isNullOrBlank() && oldCode != CODE_LOCKED && oldCode != newCode) {
                repositoryScope.launch(Dispatchers.IO) {
                    try {
                        firestore.collection("pairings").document(oldCode).delete().await()
                        Log.d(TAG, "Deleted old pairing code $oldCode from Firestore")
                    } catch (e: Exception) {
                        Log.w(TAG, "Old pairing code delete non-critical: ${e.message}")
                    }
                }
            }

            // 4. Publish new code to Firestore
            val deviceName = getMyDisplayName()
            val currentLocalPolicy = policyDao.getDevicePolicyBlocking()?.toBundle() ?: DevicePolicyBundle.defaultPolicy()
            val pairingDoc = mapOf(
                "pairingCode" to newCode,
                "ownerUid" to uid,
                "ownerName" to deviceName,
                "status" to "ACTIVE",
                "policyVersion" to currentLocalPolicy.version,
                "createdAt" to TrustedTimeProvider.currentTimeMillis(appContext)
            )
            firestore.collection("pairings").document(newCode).set(pairingDoc).await()
            Log.i(TAG, "Successfully processed local revocation: generated and registered new pairing code $newCode (v${currentLocalPolicy.version})")
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                appContext,
                "CODE_REGENERATED_REVOCATION",
                isSuccess = true,
                details = "code=$newCode, uid=$uid, version=${currentLocalPolicy.version}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing local supervision revocation cleanup", e)
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                appContext,
                "CODE_REGENERATION_FAILED",
                isSuccess = false,
                details = "uid=${getCachedUid()}",
                error = e.message
            )
        }
    }

    /**
     * Checks cloud policy against local database state on app startup or resume.
     * If the local device is marked supervised, but cloud policy indicates that supervision
     * has been revoked (isSupervised = false or guardianUid is null), automatically self-heals
     * by triggering local revocation cleanup, releasing system restrictions, and generating a new pairing code.
     */
    suspend fun checkAndSelfHealSupervisionStatus(): Boolean = withContext(Dispatchers.IO) {
        try {
            val localPolicy = policyDao.getDevicePolicyBlocking()?.toBundle()
            val isLocallySupervised = localPolicy?.isSupervised == true ||
                partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_GUARDIAN).isNotEmpty()

            if (!isLocallySupervised) {
                return@withContext false
            }

            val uid = getOrCreateCurrentUid()
            if (uid.startsWith("dev_") && auth.currentUser == null) {
                return@withContext false
            }

            val cloudSnapshot = firestore.collection("policies").document(uid).get().await()
            val cloudData = cloudSnapshot.data
            if (cloudSnapshot.exists() && cloudData != null) {
                val cloudPolicy = DevicePolicyBundle.fromMap(cloudData)
                val isCloudSupervised = cloudPolicy.isSupervised && !cloudPolicy.guardianUid.isNullOrBlank()

                if (!isCloudSupervised) {
                    Log.i(TAG, "[SelfHealing] Desync detected! Device locally marked supervised, but cloud policy is unsupervised. Self-healing now...")
                    com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                        appContext,
                        "SUPERVISION_HEALED",
                        isSuccess = true,
                        details = "Desync resolved: local was marked supervised, cloud policy is unsupervised"
                    )
                    PolicyRepository.getInstance(appContext).updateLocalPolicyAndEnforce(cloudPolicy)
                    onSupervisionRevokedLocally()
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[SelfHealing] Non-critical check skipped: ${e.message}")
        }
        false
    }

    /**
     * Retrieves all saved partners from local SQLite database.
     */
    suspend fun getAllPartners(): List<PartnerEntity> = withContext(Dispatchers.IO) {
        partnerDao.getAllPartnersBlocking()
    }

    /**
     * Synchronizes all partner relationships from Firestore into the local SQLite database.
     * Restores supervised wards and guardians automatically when switching devices or reinstalling.
     * Also migrates any local partners to cloud under current authenticated UID if needed.
     * Guarded with syncPartnersMutex to eliminate duplicate simultaneous cloud restore loops.
     */
    suspend fun syncPartnersFromCloud(): Result<List<PartnerEntity>> = withContext(Dispatchers.IO) {
        syncPartnersMutex.withLock {
            try {
                val myUid = getOrCreateCurrentUid()
                if (myUid.startsWith("dev_") && auth.currentUser == null) {
                    Log.d(TAG, "Unauthenticated device UID ($myUid), skipping cloud partner restore")
                    return@withLock Result.success(partnerDao.getAllPartnersBlocking())
                }

                Log.i(TAG, "Initiating cloud partners restore for UID: $myUid...")

                // 1. Fetch cloud policy first to verify if device is truly supervised in cloud
                val myPolicyDoc = try {
                    firestore.collection("policies").document(myUid).get().await()
                } catch (e: Exception) {
                    null
                }
                val isActuallyCloudSupervised = myPolicyDoc != null && myPolicyDoc.exists() &&
                    myPolicyDoc.getBoolean("isSupervised") == true &&
                    !myPolicyDoc.getString("guardianUid").isNullOrBlank()

                // 2. Fetch all partners registered under /users/{myUid}/partners
                val cloudPartnersSnapshot = firestore.collection("users").document(myUid)
                    .collection("partners").get().await()

                for (doc in cloudPartnersSnapshot.documents) {
                    val rawPartnerUid = doc.getString("partnerUid") ?: doc.id
                    val partnerName = doc.getString("partnerName") ?: "Partner"
                    val role = doc.getString("role") ?: PartnerEntity.ROLE_WARD
                    val pairingCode = doc.getString("pairingCode") ?: CODE_LOCKED
                    val pairedAt = doc.getLong("pairedAt") ?: TrustedTimeProvider.currentTimeMillis(appContext)

                    // GHOST GUARDIAN GUARD: If a partner doc has role == GUARDIAN, but cloud policy is NOT supervised:
                    if (role == PartnerEntity.ROLE_GUARDIAN && !isActuallyCloudSupervised) {
                        Log.w(TAG, "[GhostGuardian] Detected orphaned guardian $rawPartnerUid in cloud while policy is unsupervised. Pruning from cloud...")
                        try {
                            firestore.collection("users").document(myUid)
                                .collection("partners").document(doc.id)
                                .delete().await()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed pruning orphaned cloud guardian ${doc.id}: ${e.message}")
                        }
                        continue // NEVER save an orphaned guardian to SQLite!
                    }

                    val partnerUid = resolveActivePartnerUid(rawPartnerUid, pairingCode)
                    if (partnerUid != rawPartnerUid) {
                        try {
                            firestore.collection("users").document(myUid)
                                .collection("partners").document(rawPartnerUid)
                                .delete().await()
                            Log.d(TAG, "Pruned obsolete migrated partner doc $rawPartnerUid from cloud")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to prune stale partner doc $rawPartnerUid: ${e.message}")
                        }
                    }

                    val partnerEntity = PartnerEntity(
                        id = partnerUid,
                        pairingCode = pairingCode,
                        displayName = partnerName,
                        role = role,
                        status = PartnerEntity.STATUS_ACTIVE,
                        createdAt = pairedAt,
                        lastSyncAt = TrustedTimeProvider.currentTimeMillis(appContext)
                    )
                    partnerDao.savePartnerBlocking(partnerEntity)
                    Log.d(TAG, "Restored cloud partner to SQLite: $partnerName ($partnerUid, role=$role)")
                }

                // 3. Self-heal Guardian from /policies/{myUid} if supervised
                try {
                    if (isActuallyCloudSupervised) {
                        val guardianUid = myPolicyDoc?.getString("guardianUid")
                        val guardianName = myPolicyDoc.getString("guardianName")
                        if (!guardianUid.isNullOrBlank()) {
                            val activeGuardianUid = resolveActivePartnerUid(guardianUid, CODE_LOCKED)
                            val guardian = PartnerEntity(
                                id = activeGuardianUid,
                                pairingCode = CODE_LOCKED,
                                displayName = guardianName ?: appContext.getString(R.string.guardian_default_name),
                                role = PartnerEntity.ROLE_GUARDIAN,
                                status = PartnerEntity.STATUS_ACTIVE,
                                createdAt = myPolicyDoc.getLong("updatedAt") ?: TrustedTimeProvider.currentTimeMillis(appContext),
                                lastSyncAt = TrustedTimeProvider.currentTimeMillis(appContext)
                            )
                            partnerDao.savePartnerBlocking(guardian)
                            Log.i(TAG, "Restored Guardian from cloud policy: ${guardian.displayName} ($activeGuardianUid)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Non-critical: Failed to verify guardian from /policies/$myUid: ${e.message}")
                }

                // 3. Migrate any local ward partners to cloud /users/{myUid}/partners if missing from cloud
                val localPartners = partnerDao.getAllPartnersBlocking().filter { it.role != PartnerEntity.ROLE_GUARDIAN }
                for (local in localPartners) {
                    if (cloudPartnersSnapshot.documents.none { it.id == local.id }) {
                        try {
                            val relationData = mapOf(
                                "partnerUid" to local.id,
                                "partnerName" to local.displayName,
                                "role" to local.role,
                                "pairingCode" to local.pairingCode,
                                "pairedAt" to local.createdAt
                            )
                            firestore.collection("users").document(myUid)
                                .collection("partners").document(local.id)
                                .set(relationData, com.google.firebase.firestore.SetOptions.merge())
                                .await()
                            Log.i(TAG, "Migrated local partner ${local.displayName} (${local.id}) to cloud /users/$myUid/partners")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed migrating local partner ${local.id} to cloud: ${e.message}")
                        }
                    }
                }

                // 4. Perform self-healing check on supervision desync
                checkAndSelfHealSupervisionStatus()

                val finalPartners = partnerDao.getAllPartnersBlocking()
                Log.i(TAG, "Cloud partners sync completed successfully: ${finalPartners.size} total partners in SQLite")
                Result.success(finalPartners)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing partners from cloud", e)
                Result.failure(e)
            }
        }
    }

    suspend fun getGuardians(): List<PartnerEntity> = withContext(Dispatchers.IO) {
        val list = partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_GUARDIAN)
        if (list.isNotEmpty()) return@withContext list

        // Self-Healing Fallback: Check if device policy is supervised and has guardianUid
        val policy = policyDao.getDevicePolicyBlocking()?.toBundle()
        if (policy != null && policy.isSupervised && !policy.guardianUid.isNullOrBlank()) {
            val activeGuardianUid = resolveActivePartnerUid(policy.guardianUid, CODE_LOCKED)
            val synthesizedGuardian = PartnerEntity(
                id = activeGuardianUid,
                pairingCode = CODE_LOCKED,
                displayName = policy.guardianName ?: appContext.getString(R.string.guardian_default_name),
                role = PartnerEntity.ROLE_GUARDIAN,
                status = PartnerEntity.STATUS_ACTIVE,
                createdAt = policy.updatedAt,
                lastSyncAt = TrustedTimeProvider.currentTimeMillis(appContext)
            )
            try {
                partnerDao.savePartnerBlocking(synthesizedGuardian)
                Log.i(TAG, "Self-healed guardian record in SQLite: ${synthesizedGuardian.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to self-heal guardian in SQLite", e)
            }
            return@withContext listOf(synthesizedGuardian)
        }
        emptyList()
    }

    suspend fun getWards(): List<PartnerEntity> = withContext(Dispatchers.IO) {
        partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_WARD)
    }

    suspend fun deletePartner(partnerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val myUid = getOrCreateCurrentUid()
            firestore.collection("users").document(myUid)
                .collection("partners").document(partnerId)
                .delete()
                .await()
            partnerDao.deletePartnerBlocking(partnerId)
            Log.i(TAG, "Successfully deleted partner $partnerId from cloud and local database")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting partner $partnerId", e)
            Result.failure(e)
        }
    }

    /**
     * Gets the user's configured display name, falling back to hardware device model.
     */
    fun getMyDisplayName(): String {
        val customName = prefs.getString(KEY_MY_DISPLAY_NAME, null)?.trim()
        if (!customName.isNullOrBlank()) {
            return customName
        }
        return "${Build.MANUFACTURER} ${Build.MODEL}".replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
    }

    /**
     * Sets and persists the user's custom display name locally and syncs to Firestore.
     */
    suspend fun setMyDisplayName(newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanName = newName.trim().take(28)
            if (cleanName.isBlank()) {
                prefs.edit().remove(KEY_MY_DISPLAY_NAME).apply()
            } else {
                prefs.edit().putString(KEY_MY_DISPLAY_NAME, cleanName).apply()
            }

            val effectiveName = getMyDisplayName()
            val myUid = getOrCreateCurrentUid()

            // 1. Sync to /users/{myUid}
            val userData = mapOf(
                "uid" to myUid,
                "displayName" to effectiveName,
                "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "updatedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
            )
            try {
                firestore.collection("users").document(myUid)
                    .set(userData, com.google.firebase.firestore.SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Sync to /users/$myUid failed: ${e.message}")
            }

            // 2. Sync to active pairing doc if present
            val code = prefs.getString(KEY_MY_PAIRING_CODE, null)
            if (!code.isNullOrBlank()) {
                try {
                    firestore.collection("pairings").document(code)
                        .update("ownerName", effectiveName)
                        .await()
                } catch (e: Exception) {
                    Log.w(TAG, "Update ownerName in /pairings/$code failed: ${e.message}")
                }
            }

            // 3. Update guardianName in policies of all my wards
            val wards = partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_WARD)
            for (ward in wards) {
                try {
                    firestore.collection("policies").document(ward.id)
                        .update("guardianName", effectiveName)
                        .await()
                } catch (e: Exception) {
                    Log.w(TAG, "Update guardianName in /policies/${ward.id} failed: ${e.message}")
                }
            }

            Log.i(TAG, "Successfully updated my display name to '$effectiveName'")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting my display name", e)
            Result.failure(e)
        }
    }

    /**
     * Renames a partner (ward or guardian) locally in SQLite and in cloud Firestore.
     */
    suspend fun renamePartner(partnerId: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanName = newName.trim().take(28)
            if (cleanName.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Name cannot be blank"))
            }

            // 1. Update SQLite locally (immediate)
            partnerDao.updatePartnerDisplayNameBlocking(partnerId, cleanName, TrustedTimeProvider.currentTimeMillis(appContext))

            // 2. Sync to Firestore in background
            val myUid = getOrCreateCurrentUid()
            try {
                firestore.collection("users").document(myUid)
                    .collection("partners").document(partnerId)
                    .set(
                        mapOf(
                            "partnerName" to cleanName,
                            "updatedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Cloud sync for partnerName $partnerId failed: ${e.message}")
            }

            Log.i(TAG, "Successfully renamed partner $partnerId to '$cleanName'")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming partner $partnerId", e)
            Result.failure(e)
        }
    }

    /**
     * Updates the device's heartbeat timestamp in Firestore /policies/{myUid}.
     * Lightweight operation invoked periodically or on app foreground.
     */
    suspend fun updateHeartbeat(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val myUid = getOrCreateCurrentUid()
            val now = TrustedTimeProvider.currentTimeMillis(appContext)
            firestore.collection("policies").document(myUid)
                .set(mapOf("lastHeartbeat" to now), com.google.firebase.firestore.SetOptions.merge())
                .await()
            Log.d(TAG, "Heartbeat updated successfully at $now")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Non-critical heartbeat update failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Initiates a 24-hour Emergency Breakout request for a supervised ward device.
     * All protection policies remain 100% active during the 24-hour grace period.
     */
    suspend fun requestEmergencyBreakout(): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val myUid = getOrCreateCurrentUid()
            val docRef = firestore.collection("policies").document(myUid)
            val snapshot = docRef.get(com.google.firebase.firestore.Source.SERVER).await()
            if (!snapshot.exists()) {
                return@withContext Result.failure(IllegalStateException("Policy document does not exist"))
            }

            val currentStatus = snapshot.getString("breakoutStatus")
            if (currentStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING) {
                return@withContext Result.failure(IllegalStateException(appContext.getString(R.string.error_breakout_already_pending)))
            }

            val now = TrustedTimeProvider.currentTimeMillis(appContext)
            val unlockAt = now + DevicePolicyBundle.BREAKOUT_GRACE_PERIOD_MS
            val updates = mapOf(
                "breakoutStatus" to DevicePolicyBundle.BREAKOUT_STATUS_PENDING,
                "breakoutRequestedAt" to now,
                "breakoutUnlockAt" to unlockAt,
                "updatedAt" to now
            )
            docRef.update(updates).await()
            Log.i(TAG, "Emergency breakout requested. Unlock time set to $unlockAt")
            Result.success(unlockAt)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting emergency breakout", e)
            Result.failure(e)
        }
    }

    /**
     * Cancels an active emergency breakout request by the ward.
     */
    suspend fun cancelEmergencyBreakout(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val myUid = getOrCreateCurrentUid()
            val docRef = firestore.collection("policies").document(myUid)
            val updates = mapOf(
                "breakoutStatus" to DevicePolicyBundle.BREAKOUT_STATUS_CANCELLED,
                "breakoutRequestedAt" to null,
                "breakoutUnlockAt" to null,
                "updatedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
            )
            docRef.update(updates).await()
            Log.i(TAG, "Emergency breakout request cancelled by ward")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling emergency breakout", e)
            Result.failure(e)
        }
    }

    /**
     * Rejects an emergency breakout request by the Guardian.
     */
    suspend fun rejectEmergencyBreakout(wardUid: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val docRef = firestore.collection("policies").document(wardUid)
            val updates = mapOf(
                "breakoutStatus" to DevicePolicyBundle.BREAKOUT_STATUS_REJECTED,
                "breakoutRequestedAt" to null,
                "breakoutUnlockAt" to null,
                "updatedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
            )
            docRef.update(updates).await()
            Log.i(TAG, "Guardian rejected emergency breakout for ward $wardUid")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error rejecting emergency breakout for ward $wardUid", e)
            Result.failure(e)
        }
    }

    /**
     * Executes the final emergency breakout release after 24 hours have elapsed.
     */
    suspend fun executeEmergencyBreakout(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val myUid = getOrCreateCurrentUid()
            val docRef = firestore.collection("policies").document(myUid)
            val snapshot = docRef.get(com.google.firebase.firestore.Source.SERVER).await()
            if (!snapshot.exists()) {
                return@withContext Result.failure(IllegalStateException("Policy document does not exist"))
            }

            val status = snapshot.getString("breakoutStatus")
            val unlockAt = snapshot.getLong("breakoutUnlockAt") ?: 0L

            if (!TrustedTimeProvider.isTrusted()) {
                TrustedTimeProvider.syncNow(appContext, force = true)
            }
            if (!TrustedTimeProvider.isTrusted()) {
                return@withContext Result.failure(IllegalStateException(appContext.getString(R.string.error_trusted_time_required)))
            }

            val now = TrustedTimeProvider.currentTimeMillis(appContext)
            if (status != DevicePolicyBundle.BREAKOUT_STATUS_PENDING || now < unlockAt) {
                return@withContext Result.failure(IllegalStateException(appContext.getString(R.string.error_breakout_not_ready)))
            }

            val currentVersion = snapshot.getLong("version") ?: 1L
            val safeDefault = DevicePolicyBundle.defaultPolicy().copy(
                guardianUid = null,
                guardianName = null,
                isSupervised = false,
                breakoutStatus = DevicePolicyBundle.BREAKOUT_STATUS_COMPLETED,
                breakoutRequestedAt = null,
                breakoutUnlockAt = null,
                version = currentVersion + 1,
                updatedAt = now
            )
            docRef.set(safeDefault.toMap()).await()
            PolicyRepository.getInstance(appContext).updateLocalPolicyAndEnforce(safeDefault)
            onSupervisionRevokedLocally()
            Log.i(TAG, "Successfully executed emergency breakout after 24 hours")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing emergency breakout", e)
            Result.failure(e)
        }
    }

    /**
     * Reads the live policy of a ward to inspect heartbeat and breakout status.
     */
    suspend fun fetchWardPolicy(wardUid: String): DevicePolicyBundle? = withContext(Dispatchers.IO) {
        try {
            val snapshot = firestore.collection("policies").document(wardUid).get().await()
            val data = snapshot.data
            if (snapshot.exists() && data != null) {
                DevicePolicyBundle.fromMap(data)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching ward policy for $wardUid: ${e.message}")
            null
        }
    }

    private fun generateRandomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val sb = StringBuilder(6)
        for (i in 0 until 6) {
            sb.append(chars[random.nextInt(chars.length)])
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "AntiPorn_AccRepo"
        private const val PREFS_NAME = "antiporn_accountability_prefs"
        private const val KEY_MY_PAIRING_CODE = "key_my_pairing_code"
        private const val KEY_MY_DISPLAY_NAME = "key_my_display_name"
        private const val KEY_PERSISTED_UID = "key_persisted_uid"
        const val CODE_LOCKED = "LOCKED"

        @Volatile
        private var instance: AccountabilityRepository? = null

        fun getInstance(context: Context): AccountabilityRepository {
            return try {
                org.koin.core.context.GlobalContext.getOrNull()?.get<AccountabilityRepository>()
            } catch (e: Exception) {
                null
            } ?: (instance ?: synchronized(this) {
                instance ?: AccountabilityRepository(context.applicationContext).also {
                    instance = it
                }
            })
        }
    }
}
