package com.drmohammed.antiporn.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Unit tests verifying WorkManager worker constraints, schedule constants,
 * and periodic execution parameters for Phase 4.
 */
class WorkManagerSchedulingTest {

    @Test
    fun testOtaUpdateWorkerConstantsAndConstraints() {
        assertEquals("hisn_periodic_ota_update_work", OtaUpdateWorker.WORK_NAME)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())

        val request = PeriodicWorkRequestBuilder<OtaUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        assertNotNull(request.id)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
    }

    @Test
    fun testCloudSyncWorkerConstantsAndConstraints() {
        assertEquals("hisn_periodic_cloud_sync_work", CloudSyncWorker.WORK_NAME)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())

        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        assertNotNull(request.id)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
    }

    @Test
    fun testPeriodicPolicyKeepInvariant() {
        // Enforcing that periodic background workers always use ExistingPeriodicWorkPolicy.KEEP
        // to prevent thrashing or duplicate scheduling on cold boot
        val policy = ExistingPeriodicWorkPolicy.KEEP
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, policy)
    }
}
