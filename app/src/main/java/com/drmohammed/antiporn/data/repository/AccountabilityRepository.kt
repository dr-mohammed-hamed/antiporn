package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.Locale

/**
 * Repository responsible for Anonymous Authentication, Pairing Code generation,
 * and managing Guardian / Ward relationships both locally in SQLite and in Cloud Firestore.
 * Implements strict single-guardian enforcement and automated revocation recovery.
 */
class AccountabilityRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val database = AntiPornDatabase.getInstance(appContext)
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        if (uid.isNotBlank() && prefs.getString(KEY_PERSISTED_UID, null) != uid) {
            prefs.edit().putString(KEY_PERSISTED_UID, uid).apply()
            Log.i(TAG, "Persisted synchronized UID: $uid")
        }
    }

    /**
     * Gets or generates the device's persistent 6-character pairing code.
     * If the device is currently supervised, returns LOCKED and hides code from cloud discovery.
     */
    suspend fun getOrCreateMyPairingCode(): String = withContext(Dispatchers.IO) {
        val currentPolicy = database.getDevicePolicy()?.toBundle() ?: DevicePolicyBundle.defaultPolicy()
        if (currentPolicy.isSupervised) {
            return@withContext CODE_LOCKED
        }

        var code = prefs.getString(KEY_MY_PAIRING_CODE, null)
        if (code.isNullOrBlank()) {
            code = generateRandomCode()
            prefs.edit().putString(KEY_MY_PAIRING_CODE, code).apply()
        }

        // Publish/refresh code in Firestore /pairings/{code}
        try {
            val uid = getOrCreateCurrentUid()
            val deviceName = getMyDisplayName()
            val pairingDoc = mapOf(
                "pairingCode" to code,
                "ownerUid" to uid,
                "ownerName" to deviceName,
                "status" to "ACTIVE",
                "createdAt" to System.currentTimeMillis()
            )
            firestore.collection("pairings").document(code).set(pairingDoc).await()
            Log.i(TAG, "Successfully registered pairing code $code for UID $uid (name: $deviceName)")
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing pairing code to Firestore", e)
        }

        code
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
                val policyDoc = firestore.collection("policies").document(targetUid).get().await()
                val existingGuardianUid = policyDoc.getString("guardianUid")
                val isAlreadySupervised = policyDoc.getBoolean("isSupervised") ?: (!existingGuardianUid.isNullOrBlank())

                if (isAlreadySupervised && !existingGuardianUid.isNullOrBlank() && existingGuardianUid != myUid) {
                    return@withContext Result.failure(IllegalStateException(appContext.getString(R.string.error_ward_already_has_guardian)))
                }

                val currentVersion = policyDoc.getLong("version") ?: 1L
                val basePolicy = if (policyDoc.exists() && policyDoc.data != null) {
                    DevicePolicyBundle.fromMap(policyDoc.data!!)
                } else {
                    DevicePolicyBundle.defaultPolicy()
                }

                val updatedPolicy = basePolicy.copy(
                    guardianUid = myUid,
                    guardianName = myDeviceName,
                    isSupervised = true,
                    version = currentVersion + 1,
                    updatedAt = System.currentTimeMillis()
                )
                firestore.collection("policies").document(targetUid).set(updatedPolicy.toMap()).await()

                try {
                    firestore.collection("pairings").document(cleanCode).update(
                        mapOf("status" to "LOCKED", "pairedGuardianUid" to myUid)
                    ).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Pairing code lock status warning: ${e.message}")
                }
            } else if (role == PartnerEntity.ROLE_GUARDIAN) {
                // Target is protecting ME (Target is my GUARDIAN, I am their WARD)
                val myPolicyDoc = firestore.collection("policies").document(myUid).get().await()
                val existingGuardian = myPolicyDoc.getString("guardianUid")
                val isAlreadySupervised = myPolicyDoc.getBoolean("isSupervised") ?: (!existingGuardian.isNullOrBlank())

                if (isAlreadySupervised && !existingGuardian.isNullOrBlank() && existingGuardian != targetUid) {
                    return@withContext Result.failure(IllegalStateException(appContext.getString(R.string.error_already_supervised)))
                }

                val currentVersion = myPolicyDoc.getLong("version") ?: 1L
                val basePolicy = if (myPolicyDoc.exists() && myPolicyDoc.data != null) {
                    DevicePolicyBundle.fromMap(myPolicyDoc.data!!)
                } else {
                    DevicePolicyBundle.defaultPolicy()
                }

                val updatedMyPolicy = basePolicy.copy(
                    guardianUid = targetUid,
                    guardianName = targetName,
                    isSupervised = true,
                    version = currentVersion + 1,
                    updatedAt = System.currentTimeMillis()
                )
                firestore.collection("policies").document(myUid).set(updatedMyPolicy.toMap()).await()
                database.saveDevicePolicy(DevicePolicyEntity.fromBundle(updatedMyPolicy))
                PolicyRepository.getInstance(appContext).updateLocalPolicyAndEnforce(updatedMyPolicy)
            }

            val partner = PartnerEntity(
                id = targetUid,
                pairingCode = cleanCode,
                displayName = targetName,
                role = role,
                status = PartnerEntity.STATUS_ACTIVE,
                createdAt = System.currentTimeMillis(),
                lastSyncAt = System.currentTimeMillis()
            )

            // Save relation in Firestore
            val relationData = mapOf(
                "partnerUid" to targetUid,
                "partnerName" to targetName,
                "role" to role,
                "pairingCode" to cleanCode,
                "pairedAt" to System.currentTimeMillis()
            )
            firestore.collection("users").document(myUid)
                .collection("partners").document(targetUid)
                .set(relationData)
                .await()

            // Save locally in SQLite
            database.savePartner(partner)

            Log.i(TAG, "Successfully paired with $targetName ($targetUid) as $role under strict single guardian lock")
            Result.success(partner)
        } catch (e: Exception) {
            Log.e(TAG, "Error during pairing with code $code", e)
            Result.failure(e)
        }
    }

    /**
     * Revokes supervision over a ward device.
     * Invoked when a Guardian deletes a ward from their Accountability list.
     * Resets the ward's policy to safe default settings, sets guardianUid = null,
     * and deletes the cloud/local partner relationship.
     */
    suspend fun revokeSupervision(wardUid: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val myUid = getOrCreateCurrentUid()

            val policyDoc = firestore.collection("policies").document(wardUid).get().await()
            val currentVersion = policyDoc.getLong("version") ?: 1L

            // Reset ward policy to safe default bundle with guardianUid = null
            val safeDefaultPolicy = DevicePolicyBundle.defaultPolicy().copy(
                guardianUid = null,
                guardianName = null,
                isSupervised = false,
                version = currentVersion + 1,
                updatedAt = System.currentTimeMillis()
            )
            firestore.collection("policies").document(wardUid).set(safeDefaultPolicy.toMap()).await()

            // Delete relation in cloud
            firestore.collection("users").document(myUid)
                .collection("partners").document(wardUid)
                .delete()
                .await()

            // Delete locally from SQLite
            database.deletePartner(wardUid)

            Log.i(TAG, "Successfully revoked supervision for ward $wardUid")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error revoking supervision for ward $wardUid", e)
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
            val guardians = database.getPartnersByRole(PartnerEntity.ROLE_GUARDIAN)
            for (guardian in guardians) {
                database.deletePartner(guardian.id)
            }

            // 2. Delete old pairing code from cloud and prefs
            val oldCode = prefs.getString(KEY_MY_PAIRING_CODE, null)
            if (!oldCode.isNullOrBlank()) {
                try {
                    firestore.collection("pairings").document(oldCode).delete().await()
                } catch (e: Exception) {
                    Log.w(TAG, "Old pairing code delete non-critical: ${e.message}")
                }
            }
            prefs.edit().remove(KEY_MY_PAIRING_CODE).apply()

            // 3. Generate a brand new cryptographically random 6-character code
            val newCode = generateRandomCode()
            prefs.edit().putString(KEY_MY_PAIRING_CODE, newCode).apply()

            // 4. Publish new code to Firestore
            val uid = getOrCreateCurrentUid()
            val deviceName = getMyDisplayName()
            val pairingDoc = mapOf(
                "pairingCode" to newCode,
                "ownerUid" to uid,
                "ownerName" to deviceName,
                "status" to "ACTIVE",
                "createdAt" to System.currentTimeMillis()
            )
            firestore.collection("pairings").document(newCode).set(pairingDoc).await()
            Log.i(TAG, "Successfully processed local revocation: generated and registered new pairing code $newCode")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing local supervision revocation cleanup", e)
        }
    }

    /**
     * Retrieves all saved partners from local SQLite database.
     */
    suspend fun getAllPartners(): List<PartnerEntity> = withContext(Dispatchers.IO) {
        database.getAllPartners()
    }

    suspend fun getGuardians(): List<PartnerEntity> = withContext(Dispatchers.IO) {
        val list = database.getPartnersByRole(PartnerEntity.ROLE_GUARDIAN)
        if (list.isNotEmpty()) return@withContext list

        // Self-Healing Fallback: Check if device policy is supervised and has guardianUid
        val policy = database.getDevicePolicy()?.toBundle()
        if (policy != null && policy.isSupervised && !policy.guardianUid.isNullOrBlank()) {
            val synthesizedGuardian = PartnerEntity(
                id = policy.guardianUid,
                pairingCode = CODE_LOCKED,
                displayName = policy.guardianName ?: appContext.getString(R.string.guardian_default_name),
                role = PartnerEntity.ROLE_GUARDIAN,
                status = PartnerEntity.STATUS_ACTIVE,
                createdAt = policy.updatedAt,
                lastSyncAt = System.currentTimeMillis()
            )
            try {
                database.savePartner(synthesizedGuardian)
                Log.i(TAG, "Self-healed guardian record in SQLite: ${synthesizedGuardian.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to self-heal guardian in SQLite", e)
            }
            return@withContext listOf(synthesizedGuardian)
        }
        emptyList()
    }

    suspend fun getWards(): List<PartnerEntity> = withContext(Dispatchers.IO) {
        database.getPartnersByRole(PartnerEntity.ROLE_WARD)
    }

    suspend fun deletePartner(partnerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val myUid = getOrCreateCurrentUid()
            firestore.collection("users").document(myUid)
                .collection("partners").document(partnerId)
                .delete()
                .await()
            database.deletePartner(partnerId)
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
                "updatedAt" to System.currentTimeMillis()
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
            val wards = database.getPartnersByRole(PartnerEntity.ROLE_WARD)
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
            database.updatePartnerDisplayName(partnerId, cleanName)

            // 2. Sync to Firestore in background
            val myUid = getOrCreateCurrentUid()
            try {
                firestore.collection("users").document(myUid)
                    .collection("partners").document(partnerId)
                    .set(
                        mapOf(
                            "partnerName" to cleanName,
                            "updatedAt" to System.currentTimeMillis()
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
            return instance ?: synchronized(this) {
                instance ?: AccountabilityRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
