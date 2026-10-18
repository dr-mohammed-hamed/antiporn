package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
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
     */
    suspend fun getOrCreateMyPairingCode(): String = withContext(Dispatchers.IO) {
        var code = prefs.getString(KEY_MY_PAIRING_CODE, null)
        if (code.isNullOrBlank()) {
            code = generateRandomCode()
            prefs.edit().putString(KEY_MY_PAIRING_CODE, code).apply()
        }

        // Publish/refresh code in Firestore /pairings/{code}
        try {
            val uid = getOrCreateCurrentUid()
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
            val pairingDoc = mapOf(
                "pairingCode" to code,
                "ownerUid" to uid,
                "ownerName" to deviceName,
                "status" to "ACTIVE",
                "createdAt" to System.currentTimeMillis()
            )
            firestore.collection("pairings").document(code).set(pairingDoc).await()
            Log.i(TAG, "Successfully registered pairing code $code for UID $uid")
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing pairing code to Firestore", e)
        }

        code
    }

    /**
     * Pairs with a partner by looking up their 6-digit pairing code in Firestore.
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

            val partner = PartnerEntity(
                id = targetUid,
                pairingCode = cleanCode,
                displayName = targetName,
                role = role,
                status = PartnerEntity.STATUS_ACTIVE,
                createdAt = System.currentTimeMillis(),
                lastSyncAt = System.currentTimeMillis()
            )

            // Save relation in Firestore first
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

            // Save locally in SQLite only after cloud commit succeeds
            database.savePartner(partner)

            Log.i(TAG, "Successfully paired with $targetName ($targetUid) as $role")
            Result.success(partner)
        } catch (e: Exception) {
            Log.e(TAG, "Error during pairing with code $code", e)
            Result.failure(e)
        }
    }

    /**
     * Retrieves all saved partners from local SQLite database.
     */
    suspend fun getAllPartners(): List<PartnerEntity> = withContext(Dispatchers.IO) {
        database.getAllPartners()
    }

    suspend fun getGuardians(): List<PartnerEntity> = withContext(Dispatchers.IO) {
        database.getPartnersByRole(PartnerEntity.ROLE_GUARDIAN)
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
        private const val KEY_PERSISTED_UID = "key_persisted_uid"

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
