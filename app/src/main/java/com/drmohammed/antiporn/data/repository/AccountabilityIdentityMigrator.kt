package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Handles identity transitions and alias resolutions when a device UID transitions
 * from anonymous/hardware fallback to an authenticated Google account.
 */
class AccountabilityIdentityMigrator(
    private val appContext: Context,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val partnerDao: PartnerDao = HisnRoomDatabase.getInstance(appContext).partnerDao(),
    private val policyDao: DevicePolicyDao = HisnRoomDatabase.getInstance(appContext).devicePolicyDao(),
    private val database: HisnRoomDatabase = HisnRoomDatabase.getInstance(appContext)
) {

    /**
     * Migrates user identity in Firestore from [oldUid] (e.g. anonymous or device fallback)
     * to [newUid] (e.g. authenticated Google account).
     */
    suspend fun migrateUserIdentity(oldUid: String, newUid: String) = withContext(Dispatchers.IO) {
        if (oldUid.isBlank() || newUid.isBlank() || oldUid == newUid) return@withContext
        try {
            Log.i(TAG, "[UidMigration] Starting identity migration from $oldUid to $newUid")
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordIdentity(
                appContext,
                "IDENTITY_MIGRATION_STARTED",
                isSuccess = true,
                details = "from=$oldUid, to=$newUid"
            )

            // 1. Write forwarding record to /uid_mappings/$oldUid
            val forwardingRecord = mapOf(
                "activeUid" to newUid,
                "migratedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
            )
            try {
                firestore.collection("uid_mappings").document(oldUid)
                    .set(forwardingRecord, SetOptions.merge()).await()
                Log.i(TAG, "[UidMigration] Forwarding mapping created: $oldUid -> $newUid")
            } catch (e: Exception) {
                Log.w(TAG, "[UidMigration] Failed writing /uid_mappings/$oldUid: ${e.message}")
            }

            // 2. Migrate sovereign policy document
            try {
                val oldPolicyDoc = firestore.collection("policies").document(oldUid).get().await()
                if (oldPolicyDoc.exists()) {
                    val oldPolicyData = oldPolicyDoc.data
                    if (oldPolicyData != null) {
                        val newPolicyDoc = firestore.collection("policies").document(newUid).get().await()
                        val shouldCopy = !newPolicyDoc.exists() ||
                                ((newPolicyDoc.getLong("version") ?: 0L) < (oldPolicyDoc.getLong("version") ?: 0L))

                        if (shouldCopy) {
                            firestore.collection("policies").document(newUid)
                                .set(oldPolicyData, SetOptions.merge()).await()
                            Log.i(TAG, "[UidMigration] Policy migrated successfully from $oldUid to $newUid")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[UidMigration] Policy copy error: ${e.message}")
            }

            // 3. Migrate partners subcollection
            try {
                val partnersSnapshot = firestore.collection("users").document(oldUid)
                    .collection("partners").get().await()

                for (doc in partnersSnapshot.documents) {
                    val data = doc.data ?: continue
                    firestore.collection("users").document(newUid)
                        .collection("partners").document(doc.id)
                        .set(data, SetOptions.merge()).await()
                }
                Log.i(TAG, "[UidMigration] Migrated ${partnersSnapshot.size()} partner documents to $newUid")
            } catch (e: Exception) {
                Log.w(TAG, "[UidMigration] Partners copy error: ${e.message}")
            }

            // 4. Update foreign partner references where oldUid was a guardian or ward
            try {
                // If oldUid was protecting someone, update the ward's /policies doc
                val wardsSnapshot = firestore.collection("policies")
                    .whereEqualTo("guardianUid", oldUid).get().await()
                for (doc in wardsSnapshot.documents) {
                    doc.reference.update(
                        mapOf(
                            "guardianUid" to newUid,
                            "updatedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
                        )
                    ).await()
                    Log.i(TAG, "[UidMigration] Updated ward ${doc.id} guardianUid from $oldUid to $newUid")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[UidMigration] Foreign reference update error: ${e.message}")
            }

            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordIdentity(
                appContext,
                "IDENTITY_MIGRATION_COMPLETED",
                isSuccess = true,
                details = "successfully migrated $oldUid to $newUid"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[UidMigration] Error executing user identity migration", e)
            com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordIdentity(
                appContext,
                "IDENTITY_MIGRATION_FAILED",
                isSuccess = false,
                error = e.message
            )
        }
    }

    /**
     * Resolves the current canonical UID for a partner by checking Firestore alias mappings.
     */
    suspend fun resolveActivePartnerUid(partnerUid: String, pairingCode: String? = null): String = withContext(Dispatchers.IO) {
        if (partnerUid.isBlank()) return@withContext partnerUid
        try {
            var resolvedUid: String? = null

            // 1. Check Firestore /uid_mappings/$partnerUid
            try {
                val mappingDoc = firestore.collection("uid_mappings").document(partnerUid).get().await()
                if (mappingDoc.exists()) {
                    val activeUid = mappingDoc.getString("activeUid")
                    if (!activeUid.isNullOrBlank()) {
                        resolvedUid = activeUid
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[UidMigration] Failed checking /uid_mappings/$partnerUid: ${e.message}")
            }

            // 2. Check pairings if available
            if (resolvedUid.isNullOrBlank() && !pairingCode.isNullOrBlank() && pairingCode != AccountabilityRepository.CODE_LOCKED) {
                try {
                    val pairingDoc = firestore.collection("pairings").document(pairingCode).get().await()
                    if (pairingDoc.exists()) {
                        val ownerUid = pairingDoc.getString("ownerUid")
                        if (!ownerUid.isNullOrBlank()) {
                            resolvedUid = ownerUid
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[UidMigration] Failed checking /pairings/$pairingCode: ${e.message}")
                }
            }

            // 3. If resolvedUid != null && resolvedUid != partnerUid
            if (!resolvedUid.isNullOrBlank() && resolvedUid != partnerUid) {
                if (resolvedUid.startsWith("dev_") && !partnerUid.startsWith("dev_")) {
                    Log.w(TAG, "[UidMigration] Refusing to downgrade authenticated UID $partnerUid to legacy $resolvedUid")
                    return@withContext partnerUid
                }
                Log.i(TAG, "[UidMigration] Partner UID resolved from $partnerUid to $resolvedUid")
                com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordIdentity(
                    appContext,
                    "PARTNER_UID_RESOLVED",
                    isSuccess = true,
                    details = "partnerUid switched from $partnerUid to $resolvedUid"
                )
                database.withTransaction {
                    val localPartner = partnerDao.getPartnerById(partnerUid)
                    if (localPartner != null) {
                        val updatedPartner = localPartner.copy(id = resolvedUid)
                        partnerDao.savePartner(updatedPartner)
                        partnerDao.deletePartner(partnerUid)

                        // If this partner is the active guardian, keep local device policy in sync
                        if (localPartner.role == PartnerEntity.ROLE_GUARDIAN) {
                            val policy = policyDao.getDevicePolicy()?.toBundle()
                            if (policy != null && policy.guardianUid == partnerUid) {
                                val updatedPolicy = policy.copy(guardianUid = resolvedUid)
                                policyDao.saveDevicePolicy(DevicePolicyEntity.fromBundle(updatedPolicy))
                            }
                        }
                    }
                }
                return@withContext resolvedUid
            }
        } catch (e: Exception) {
            Log.w(TAG, "[UidMigration] Error resolving active partner UID for $partnerUid: ${e.message}")
        }
        partnerUid
    }

    companion object {
        private const val TAG = "IdentityMigrator"
    }
}
