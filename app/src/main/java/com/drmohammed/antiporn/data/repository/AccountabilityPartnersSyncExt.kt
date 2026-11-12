package com.drmohammed.antiporn.data.repository

import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.google.firebase.firestore.SetOptions
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "AntiPorn_AccPartners"

/**
 * Synchronizes all partner relationships from Firestore into the local SQLite database.
 * Restores supervised wards and guardians automatically when switching devices or reinstalling.
 * Also migrates any local partners to cloud under current authenticated UID if needed.
 * Guarded with syncPartnersMutex to eliminate duplicate simultaneous cloud restore loops.
 */
internal suspend fun AccountabilityRepository.syncPartnersFromCloudInternal(): Result<List<PartnerEntity>> = withContext(Dispatchers.IO) {
    syncPartnersMutex.withLock {
        try {
            val myUid = getOrCreateCurrentUid()
            val maskedMyUid = AiTelemetryReporter.maskIdentifier(myUid)
            if (myUid.startsWith("dev_") && auth.currentUser == null) {
                Log.d(TAG, "Unauthenticated device UID ($maskedMyUid), skipping cloud partner restore")
                return@withLock Result.success(partnerDao.getAllPartnersBlocking())
            }

            Log.i(TAG, "Initiating cloud partners restore for UID: $maskedMyUid...")

            val myPolicyDoc = try {
                firestore.collection("policies").document(myUid).get().await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            val isActuallyCloudSupervised = myPolicyDoc != null && myPolicyDoc.exists() &&
                myPolicyDoc.getBoolean("isSupervised") == true &&
                !myPolicyDoc.getString("guardianUid").isNullOrBlank()

            val cloudPartnersSnapshot = firestore.collection("users").document(myUid)
                .collection("partners").get().await()

            for (doc in cloudPartnersSnapshot.documents) {
                val rawPartnerUid = doc.getString("partnerUid") ?: doc.id
                val maskedRawPartner = AiTelemetryReporter.maskIdentifier(rawPartnerUid)
                val partnerName = doc.getString("partnerName") ?: "Partner"
                val role = doc.getString("role") ?: PartnerEntity.ROLE_WARD
                val pairingCode = doc.getString("pairingCode") ?: AccountabilityRepository.CODE_LOCKED
                val pairedAt = doc.getLong("pairedAt") ?: TrustedTimeProvider.currentTimeMillis(appContext)

                if (role == PartnerEntity.ROLE_GUARDIAN && !isActuallyCloudSupervised) {
                    Log.w(TAG, "[GhostGuardian] Detected orphaned guardian $maskedRawPartner in cloud while policy is unsupervised. Pruning from cloud...")
                    try {
                        firestore.collection("users").document(myUid)
                            .collection("partners").document(doc.id)
                            .delete().await()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Failed pruning orphaned cloud guardian ${AiTelemetryReporter.maskIdentifier(doc.id)}: ${e.message}")
                    }
                    continue
                }

                val partnerUid = resolveActivePartnerUid(rawPartnerUid, pairingCode)
                val maskedPartner = AiTelemetryReporter.maskIdentifier(partnerUid)
                if (partnerUid != rawPartnerUid) {
                    try {
                        val partnersColl = firestore.collection("users").document(myUid).collection("partners")
                        partnersColl.document(doc.id).delete().await()
                        if (rawPartnerUid != doc.id) {
                            partnersColl.document(rawPartnerUid).delete().await()
                        }
                        Log.d(TAG, "Pruned obsolete migrated partner doc ${AiTelemetryReporter.maskIdentifier(doc.id)} / $maskedRawPartner from cloud")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Failed to prune stale partner doc ${AiTelemetryReporter.maskIdentifier(doc.id)} / $maskedRawPartner: ${e.message}")
                    }
                    try {
                        val updatedDocData = mapOf(
                            "partnerUid" to partnerUid,
                            "partnerName" to partnerName,
                            "role" to role,
                            "pairingCode" to pairingCode,
                            "pairedAt" to pairedAt
                        )
                        firestore.collection("users").document(myUid)
                            .collection("partners").document(partnerUid)
                            .set(updatedDocData, SetOptions.merge()).await()
                        Log.d(TAG, "Uploaded synchronized partner doc $maskedPartner to cloud")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Failed writing updated partner doc $maskedPartner to cloud: ${e.message}")
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
                Log.d(TAG, "Restored cloud partner to SQLite: $partnerName ($maskedPartner, role=$role)")
            }

            try {
                if (isActuallyCloudSupervised) {
                    val guardianUid = myPolicyDoc?.getString("guardianUid")
                    val guardianName = myPolicyDoc?.getString("guardianName")
                    if (!guardianUid.isNullOrBlank()) {
                        val activeGuardianUid = resolveActivePartnerUid(guardianUid, AccountabilityRepository.CODE_LOCKED)
                        val guardian = PartnerEntity(
                            id = activeGuardianUid,
                            pairingCode = AccountabilityRepository.CODE_LOCKED,
                            displayName = guardianName ?: appContext.getString(R.string.guardian_default_name),
                            role = PartnerEntity.ROLE_GUARDIAN,
                            status = PartnerEntity.STATUS_ACTIVE,
                            createdAt = myPolicyDoc.getLong("updatedAt") ?: TrustedTimeProvider.currentTimeMillis(appContext),
                            lastSyncAt = TrustedTimeProvider.currentTimeMillis(appContext)
                        )
                        partnerDao.savePartnerBlocking(guardian)
                        Log.i(TAG, "Restored Guardian from cloud policy: ${guardian.displayName} (${AiTelemetryReporter.maskIdentifier(activeGuardianUid)})")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Non-critical: Failed to verify guardian from /policies/$maskedMyUid: ${e.message}")
            }

            val localPartners = partnerDao.getAllPartnersBlocking().filter { it.role != PartnerEntity.ROLE_GUARDIAN }
            for (local in localPartners) {
                if (cloudPartnersSnapshot.documents.none { it.id == local.id }) {
                    val maskedLocalId = AiTelemetryReporter.maskIdentifier(local.id)
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
                            .set(relationData, SetOptions.merge())
                            .await()
                        Log.i(TAG, "Migrated local partner ${local.displayName} ($maskedLocalId) to cloud /users/$maskedMyUid/partners")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Failed migrating local partner $maskedLocalId to cloud: ${e.message}")
                    }
                }
            }

            checkAndSelfHealSupervisionStatus()

            val finalPartners = partnerDao.getAllPartnersBlocking()
            Log.i(TAG, "Cloud partners sync completed successfully: ${finalPartners.size} total partners in SQLite")
            Result.success(finalPartners)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error syncing partners from cloud", e)
            Result.failure(e)
        }
    }
}

internal suspend fun AccountabilityRepository.getGuardiansInternal(): List<PartnerEntity> = withContext(Dispatchers.IO) {
    val list = partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_GUARDIAN)
    if (list.isNotEmpty()) return@withContext list

    val policy = policyDao.getDevicePolicyBlocking()?.toBundle()
    if (policy != null && policy.isSupervised && !policy.guardianUid.isNullOrBlank()) {
        val activeGuardianUid = resolveActivePartnerUid(policy.guardianUid, AccountabilityRepository.CODE_LOCKED)
        val synthesizedGuardian = PartnerEntity(
            id = activeGuardianUid,
            pairingCode = AccountabilityRepository.CODE_LOCKED,
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

internal suspend fun AccountabilityRepository.getWardsInternal(): List<PartnerEntity> = withContext(Dispatchers.IO) {
    partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_WARD)
}

internal suspend fun AccountabilityRepository.deletePartnerInternal(partnerId: String): Result<Unit> = withContext(Dispatchers.IO) {
    val maskedPartner = AiTelemetryReporter.maskIdentifier(partnerId)
    try {
        val myUid = getOrCreateCurrentUid()
        firestore.collection("users").document(myUid)
            .collection("partners").document(partnerId)
            .delete()
            .await()
        partnerDao.deletePartnerBlocking(partnerId)
        Log.i(TAG, "Successfully deleted partner $maskedPartner from cloud and local database")
        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Error deleting partner $maskedPartner", e)
        Result.failure(e)
    }
}

/**
 * Gets the user's configured display name, falling back to hardware device model.
 */
internal fun AccountabilityRepository.getMyDisplayNameInternal(): String {
    val customName = prefs.getString(AccountabilityRepository.KEY_MY_DISPLAY_NAME, null)?.trim()
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
internal suspend fun AccountabilityRepository.setMyDisplayNameInternal(newName: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val cleanName = newName.trim().take(28)
        if (cleanName.isBlank()) {
            prefs.edit().remove(AccountabilityRepository.KEY_MY_DISPLAY_NAME).apply()
        } else {
            prefs.edit().putString(AccountabilityRepository.KEY_MY_DISPLAY_NAME, cleanName).apply()
        }

        val effectiveName = getMyDisplayName()
        val myUid = getOrCreateCurrentUid()
        val maskedMyUid = AiTelemetryReporter.maskIdentifier(myUid)

        val userData = mapOf(
            "uid" to myUid,
            "displayName" to effectiveName,
            "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "updatedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
        )
        try {
            firestore.collection("users").document(myUid)
                .set(userData, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Sync to /users/$maskedMyUid failed: ${e.message}")
        }

        val code = prefs.getString(AccountabilityRepository.KEY_MY_PAIRING_CODE, null)
        if (!code.isNullOrBlank()) {
            val maskedCode = AiTelemetryReporter.maskIdentifier(code)
            try {
                firestore.collection("pairings").document(code)
                    .update("ownerName", effectiveName)
                    .await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Update ownerName in /pairings/$maskedCode failed: ${e.message}")
            }
        }

        val wards = partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_WARD)
        for (ward in wards) {
            try {
                firestore.collection("policies").document(ward.id)
                    .update("guardianName", effectiveName)
                    .await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Update guardianName in /policies/${AiTelemetryReporter.maskIdentifier(ward.id)} failed: ${e.message}")
            }
        }

        Log.i(TAG, "Successfully updated my display name to '$effectiveName'")
        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Error setting my display name", e)
        Result.failure(e)
    }
}

/**
 * Renames a partner (ward or guardian) locally in SQLite and in cloud Firestore.
 */
internal suspend fun AccountabilityRepository.renamePartnerInternal(partnerId: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
    val maskedPartner = AiTelemetryReporter.maskIdentifier(partnerId)
    try {
        val cleanName = newName.trim().take(28)
        if (cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Name cannot be blank"))
        }

        partnerDao.updatePartnerDisplayNameBlocking(partnerId, cleanName, TrustedTimeProvider.currentTimeMillis(appContext))

        val myUid = getOrCreateCurrentUid()
        try {
            firestore.collection("users").document(myUid)
                .collection("partners").document(partnerId)
                .set(
                    mapOf(
                        "partnerName" to cleanName,
                        "updatedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
                    ),
                    SetOptions.merge()
                )
                .await()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Cloud sync for partnerName $maskedPartner failed: ${e.message}")
        }

        Log.i(TAG, "Successfully renamed partner $maskedPartner to '$cleanName'")
        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Error renaming partner $maskedPartner", e)
        Result.failure(e)
    }
}
