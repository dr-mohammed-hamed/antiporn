package com.drmohammed.antiporn.data.repository

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val TAG = "AntiPorn_AccDeact"

/**
 * Requests Device Owner deactivation with 48h default delay, or 24h if QR code was verified.
 */
suspend fun AccountabilityRepository.requestDeviceOwnerDeactivation(hasQr: Boolean): Result<Long> = withContext(Dispatchers.IO) {
    try {
        if (!TrustedTimeProvider.isTrusted()) {
            TrustedTimeProvider.syncNow(appContext, force = true)
        }
        val now = TrustedTimeProvider.currentTimeMillis(appContext)
        val delayMs = if (hasQr) DevicePolicyBundle.DEACTIVATION_QR_DELAY_MS else DevicePolicyBundle.DEACTIVATION_DEFAULT_DELAY_MS
        val unlockAt = now + delayMs

        val currentPolicy = policyDao.getDevicePolicyBlocking()?.toBundle() ?: DevicePolicyBundle.defaultPolicy()
        val updatedLocal = currentPolicy.copy(
            deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
            deactivationRequestedAt = now,
            deactivationUnlockAt = unlockAt,
            deactivationHasQr = hasQr,
            updatedAt = now
        )
        policyDao.saveDevicePolicyBlocking(DevicePolicyEntity.fromBundle(updatedLocal))

        if (currentPolicy.isSupervised && !currentPolicy.guardianUid.isNullOrBlank()) {
            val myUid = getOrCreateCurrentUid()
            val docRef = firestore.collection("policies").document(myUid)
            val updates = mapOf(
                "deactivationStatus" to DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                "deactivationRequestedAt" to now,
                "deactivationUnlockAt" to unlockAt,
                "deactivationHasQr" to hasQr,
                "updatedAt" to now
            )
            docRef.update(updates).await()
            Timber.tag(TAG).i("Device Owner deactivation requested on cloud. Unlock at $unlockAt (hasQr=$hasQr)")
        }

        Result.success(unlockAt)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Error requesting device owner deactivation")
        Result.failure(e)
    }
}

/**
 * Cancels an active Device Owner deactivation request by the user.
 */
suspend fun AccountabilityRepository.cancelDeviceOwnerDeactivation(): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val now = TrustedTimeProvider.currentTimeMillis(appContext)
        val currentPolicy = policyDao.getDevicePolicyBlocking()?.toBundle() ?: DevicePolicyBundle.defaultPolicy()
        val updatedLocal = currentPolicy.copy(
            deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_CANCELLED,
            deactivationRequestedAt = null,
            deactivationUnlockAt = null,
            deactivationHasQr = false,
            updatedAt = now
        )
        policyDao.saveDevicePolicyBlocking(DevicePolicyEntity.fromBundle(updatedLocal))

        if (currentPolicy.isSupervised && !currentPolicy.guardianUid.isNullOrBlank()) {
            val myUid = getOrCreateCurrentUid()
            val docRef = firestore.collection("policies").document(myUid)
            val updates = mapOf(
                "deactivationStatus" to DevicePolicyBundle.DEACTIVATION_STATUS_CANCELLED,
                "deactivationRequestedAt" to null,
                "deactivationUnlockAt" to null,
                "deactivationHasQr" to false,
                "updatedAt" to now
            )
            docRef.update(updates).await()
            Timber.tag(TAG).i("Device Owner deactivation cancelled on cloud by ward")
        }

        Result.success(Unit)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Error cancelling device owner deactivation")
        Result.failure(e)
    }
}

/**
 * Rejects an active Device Owner deactivation request by the Guardian.
 */
suspend fun AccountabilityRepository.rejectDeviceOwnerDeactivation(wardUid: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val docRef = firestore.collection("policies").document(wardUid)
        val updates = mapOf(
            "deactivationStatus" to DevicePolicyBundle.DEACTIVATION_STATUS_REJECTED,
            "deactivationRequestedAt" to null,
            "deactivationUnlockAt" to null,
            "deactivationHasQr" to false,
            "updatedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
        )
        docRef.update(updates).await()
        Timber.tag(TAG).i("Guardian rejected device owner deactivation for ward $wardUid")
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Error rejecting device owner deactivation for ward $wardUid")
        Result.failure(e)
    }
}
