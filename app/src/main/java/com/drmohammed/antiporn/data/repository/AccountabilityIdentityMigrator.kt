package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
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
    private val database: AntiPornDatabase = AntiPornDatabase.getInstance(appContext)
) {

    /**
     * Migrates user identity in Firestore from [oldUid] (e.g. anonymous or device fallback)
     * to [newUid] (e.g. authenticated Google account).
     *
     * 1. Writes an active forwarding alias at /uid_mappings/{oldUid}
     * 2. Copies existing /policies/{oldUid} to /policies/{newUid} if missing or lower version
     * 3. Copies /users/{oldUid}/partners collection to /users/{newUid}/partners
     */
    suspend fun migrateUserIdentity(oldUid: String, newUid: String) = withContext(Dispatchers.IO) {
        if (oldUid.isBlank() || newUid.isBlank() || oldUid == newUid) return@withContext
        try {
            Log.i(TAG, "[UidMigration] Starting identity migration from $oldUid to $newUid")

            // 1. Write forwarding record to /uid_mappings/$oldUid
            val forwardingRecord = mapOf(
                "activeUid" to newUid,
                "migratedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
            )
            try {
                firestore.collection("uid_mappings").document(oldUid)
                    .set(forwardingRecord, SetOptions.merge())
                    .await()
                Log.d(TAG, "[UidMigration] Forwarding alias recorded: $oldUid -> $newUid")
            } catch (e: Exception) {
                Log.w(TAG, "[UidMigration] Failed writing /uid_mappings/$oldUid: ${e.message}")
            }

            // 2. Policy Migration: check /policies/$oldUid and copy to /policies/$newUid
            try {
                val oldPolicyDoc = firestore.collection("policies").document(oldUid).get().await()
                if (oldPolicyDoc.exists() && oldPolicyDoc.data != null) {
                    val oldPolicyData = oldPolicyDoc.data ?: run { Log.w(TAG, "oldPolicyDoc data is null"); return@withContext }
                    val oldVersion = oldPolicyDoc.getLong("version") ?: 1L

                    val newPolicyDoc = try {
                        firestore.collection("policies").document(newUid).get().await()
                    } catch (e: Exception) {
                        null
                    }

                    val shouldCopy = if (newPolicyDoc == null || !newPolicyDoc.exists()) {
                        true
                    } else {
                        val newVersion = newPolicyDoc.getLong("version") ?: 0L
                        oldVersion > newVersion
                    }

                    if (shouldCopy) {
                        firestore.collection("policies").document(newUid)
                            .set(oldPolicyData, SetOptions.merge())
                            .await()
                        Log.i(TAG, "[UidMigration] Policy map copied from $oldUid to $newUid (v$oldVersion)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[UidMigration] Failed copying policy from $oldUid to $newUid: ${e.message}")
            }

            // 3. Partners Migration: fetch /users/$oldUid/partners and copy to /users/$newUid/partners
            try {
                val oldPartnersSnapshot = firestore.collection("users").document(oldUid)
                    .collection("partners").get().await()
                for (doc in oldPartnersSnapshot.documents) {
                    val partnerData = doc.data
                    if (partnerData != null) {
                        firestore.collection("users").document(newUid)
                            .collection("partners").document(doc.id)
                            .set(partnerData, SetOptions.merge())
                            .await()
                    }
                }
                Log.d(TAG, "[UidMigration] Copied ${oldPartnersSnapshot.size()} partner documents from $oldUid to $newUid")
            } catch (e: Exception) {
                Log.w(TAG, "[UidMigration] Failed copying partners from $oldUid to $newUid: ${e.message}")
            }

            Log.i(TAG, "[UidMigration] Migrated identity from $oldUid to $newUid")
        } catch (e: Exception) {
            Log.e(TAG, "[UidMigration] Error migrating user identity from $oldUid to $newUid", e)
        }
    }

    /**
     * Resolves the active UID for a partner, healing any stale references caused by
     * account upgrades or identity migrations.
     *
     * 1. Checks /uid_mappings/{partnerUid} for an activeUid alias.
     * 2. If not found and pairingCode is valid, checks /pairings/{pairingCode} for ownerUid.
     * 3. If resolved to a new UID, updates SQLite partners table and returns the resolved UID.
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

            // 2. If not found and pairingCode != null && pairingCode.isNotBlank() && pairingCode != AccountabilityRepository.CODE_LOCKED
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
                // Safeguard: Never downgrade a real authenticated UID to a temporary dev_ UUID
                if (resolvedUid.startsWith("dev_") && !partnerUid.startsWith("dev_")) {
                    Log.w(TAG, "[UidMigration] Refusing to downgrade authenticated UID $partnerUid to legacy $resolvedUid")
                    return@withContext partnerUid
                }
                Log.i(TAG, "[UidMigration] Partner UID resolved from $partnerUid to $resolvedUid")
                val localPartner = database.getPartnerById(partnerUid)
                if (localPartner != null) {
                    val updatedPartner = localPartner.copy(id = resolvedUid)
                    database.savePartner(updatedPartner)
                    database.deletePartner(partnerUid)

                    // If this partner is the active guardian, keep local device policy in sync
                    if (localPartner.role == PartnerEntity.ROLE_GUARDIAN) {
                        val policy = database.getDevicePolicy()?.toBundle()
                        if (policy != null && policy.guardianUid == partnerUid) {
                            val updatedPolicy = policy.copy(guardianUid = resolvedUid)
                            database.saveDevicePolicy(DevicePolicyEntity.fromBundle(updatedPolicy))
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
