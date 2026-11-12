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
    internal val partnerDao: PartnerDao = HisnRoomDatabase.getInstance(context).partnerDao(),
    internal val policyDao: DevicePolicyDao = HisnRoomDatabase.getInstance(context).devicePolicyDao()
) {

    internal val appContext = context.applicationContext
    internal val auth = FirebaseAuth.getInstance()
    internal val firestore = FirebaseFirestore.getInstance()

    val partnersFlow: Flow<List<PartnerEntity>> = partnerDao.getAllPartnersFlow()
    val guardiansFlow: Flow<List<PartnerEntity>> = partnerDao.getPartnersByRoleFlow(PartnerEntity.ROLE_GUARDIAN)
    val wardsFlow: Flow<List<PartnerEntity>> = partnerDao.getPartnersByRoleFlow(PartnerEntity.ROLE_WARD)

    internal val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    internal val syncPartnersMutex = Mutex()
    private val revocationMutex = Mutex()
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
            if (e is kotlinx.coroutines.CancellationException) throw e
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
            val maskedUid = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(uid)
            Log.i(TAG, "Persisted synchronized UID: $maskedUid")
            if (!oldUid.isNullOrBlank()) {
                val maskedOldUid = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(oldUid)
                Log.i(TAG, "UID transitioned from $maskedOldUid to $maskedUid. Invalidating old cached pairing code.")
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
                        val maskedCode = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(code)
                        val maskedExisting = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(existingOwner)
                        val maskedUid = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(uid)
                        Log.w(TAG, "Pairing code $maskedCode already owned by $maskedExisting (not current $maskedUid). Rotating...")
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
                val maskedCode = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(code)
                val maskedUid = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(uid)
                Log.i(TAG, "Successfully registered pairing code $maskedCode for UID $maskedUid (name: $deviceName, policyVersion: ${currentLocalPolicy.version})")
                com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                    appContext,
                    "CODE_REGISTERED",
                    isSuccess = true,
                    details = "code=$maskedCode, uid=$maskedUid, version=${currentLocalPolicy.version}"
                )

                // Proactively ensure base policy exists in Firestore under /policies/$uid
                try {
                    firestore.collection("policies").document(uid).set(currentLocalPolicy.toMap(), com.google.firebase.firestore.SetOptions.merge()).await()
                    Log.d(TAG, "Ensured base policy document in Firestore for UID $maskedUid")
                } catch (policyEx: Exception) {
                    Log.w(TAG, "Non-critical: base policy document seed skipped: ${policyEx.message}")
                }

                AppInventoryManager.getInstance(appContext).syncInventoryToCloudAsync()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    Log.w(TAG, "Permission denied for pairing code ${com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(code)} (stale or conflicting ownership). Rotating...")
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

            val maskedTargetUid = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(targetUid)
            Log.i(TAG, "Successfully paired with $targetName ($maskedTargetUid) as $role under strict single guardian lock")
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                appContext,
                "PAIR_SUCCESS",
                isSuccess = true,
                details = "targetUid=$maskedTargetUid, role=$role"
            )
            Result.success(partner)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error during pairing (masked=${com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(code)})", e)
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                appContext,
                "PAIR_FAILED",
                isSuccess = false,
                details = "role=$role",
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
                    val maskedTarget = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(targetUid)
                    Log.i(TAG, "Revoked cloud policy for ward UID: $maskedTarget (v${safeDefaultPolicy.version})")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Policy doc revocation warning for UID ${com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(targetUid)}: ${e.message}")
                }

                // Delete relation in cloud
                try {
                    firestore.collection("users").document(myUid)
                        .collection("partners").document(targetUid)
                        .delete()
                        .await()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Cloud partner relation delete warning for UID ${com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(targetUid)}: ${e.message}")
                }

                // Delete locally from SQLite
                partnerDao.deletePartnerBlocking(targetUid)
            }

            val maskedTargets = targetUids.joinToString(",") {
                com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(it)
            }
            Log.i(TAG, "Successfully revoked supervision for ward targets: $maskedTargets")
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                appContext,
                "SUPERVISION_REVOKED_BY_GUARDIAN",
                isSuccess = true,
                details = "targets=$maskedTargets"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val maskedWardUid = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(wardUid)
            Log.e(TAG, "Error revoking supervision for ward $maskedWardUid", e)
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                appContext,
                "SUPERVISION_REVOKE_FAILED",
                isSuccess = false,
                details = "wardUid=$maskedWardUid",
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
        revocationMutex.withLock {
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
                            Log.d(TAG, "Pruned revoked guardian ${com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(guardian.id)} from cloud /users/${com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(uid)}/partners")
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
                        Log.d(TAG, "Deleted old pairing code ${com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(oldCode)} from Firestore")
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
            val maskedNewCode = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(newCode)
            val maskedUid = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(uid)
            Log.i(TAG, "Successfully processed local revocation: generated and registered new pairing code $maskedNewCode (v${currentLocalPolicy.version})")
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                appContext,
                "CODE_REGENERATED_REVOCATION",
                isSuccess = true,
                details = "code=$maskedNewCode, uid=$maskedUid, version=${currentLocalPolicy.version}"
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error executing local supervision revocation cleanup", e)
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordPairing(
                appContext,
                "CODE_REGENERATION_FAILED",
                isSuccess = false,
                details = "uid=${com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(getCachedUid())}",
                error = e.message
            )
        }
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
            if (e is kotlinx.coroutines.CancellationException) throw e
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

    suspend fun updateHeartbeat(): Result<Unit> = updateHeartbeatInternal()

    suspend fun requestEmergencyBreakout(): Result<Long> = requestEmergencyBreakoutInternal()

    suspend fun cancelEmergencyBreakout(): Result<Unit> = cancelEmergencyBreakoutInternal()

    suspend fun rejectEmergencyBreakout(wardUid: String): Result<Unit> = rejectEmergencyBreakoutInternal(wardUid)

    suspend fun executeEmergencyBreakout(): Result<Unit> = executeEmergencyBreakoutInternal()

    suspend fun fetchWardPolicy(wardUid: String): DevicePolicyBundle? = fetchWardPolicyInternal(wardUid)

    suspend fun syncPartnersFromCloud(): Result<List<PartnerEntity>> = syncPartnersFromCloudInternal()

    suspend fun getGuardians(): List<PartnerEntity> = getGuardiansInternal()

    suspend fun getWards(): List<PartnerEntity> = getWardsInternal()

    suspend fun deletePartner(partnerId: String): Result<Unit> = deletePartnerInternal(partnerId)

    fun getMyDisplayName(): String = getMyDisplayNameInternal()

    suspend fun setMyDisplayName(newName: String): Result<Unit> = setMyDisplayNameInternal(newName)

    suspend fun renamePartner(partnerId: String, newName: String): Result<Unit> = renamePartnerInternal(partnerId, newName)

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
        internal const val KEY_MY_PAIRING_CODE = "key_my_pairing_code"
        internal const val KEY_MY_DISPLAY_NAME = "key_my_display_name"
        private const val KEY_PERSISTED_UID = "key_persisted_uid"
        const val CODE_LOCKED = "LOCKED"

        @Volatile
        private var instance: AccountabilityRepository? = null

        fun getInstance(context: Context): AccountabilityRepository {
            return instance ?: synchronized(this) {
                instance ?: try {
                    org.koin.core.context.GlobalContext.getOrNull()?.get<AccountabilityRepository>()
                } catch (t: Throwable) {
                    null
                } ?: AccountabilityRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun setInstance(repo: AccountabilityRepository?) {
            instance = repo
        }
    }
}
