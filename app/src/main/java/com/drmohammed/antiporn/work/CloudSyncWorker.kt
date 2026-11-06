package com.drmohammed.antiporn.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Scheduled background worker for periodic Cloud Heartbeat, Partner Sync, and Supervision Self-Healing.
 * Runs on a 12-hour periodic interval under network connectivity constraints.
 * Real-time watchdog observation remains in Foreground Service; this worker guarantees eventual consistency.
 */
class CloudSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val accountabilityRepository: AccountabilityRepository,
    private val policyRepository: PolicyRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (isStopped) return@withContext Result.retry()
        val startTime = TrustedTimeProvider.currentTimeMillis(applicationContext)
        Timber.i("CloudSyncWorker started at $startTime (Run attempt: $runAttemptCount)")

        try {
            if (isStopped) return@withContext Result.retry()

            // 1. Update cloud heartbeat
            val heartbeatRes = accountabilityRepository.updateHeartbeat()

            if (isStopped) return@withContext Result.retry()

            // 2. Sync partner relationships from Firestore to local Room database
            val partnerRes = accountabilityRepository.syncPartnersFromCloud()

            if (isStopped) return@withContext Result.retry()

            // 3. Reconcile supervision status if desync occurred
            accountabilityRepository.checkAndSelfHealSupervisionStatus()

            val duration = TrustedTimeProvider.currentTimeMillis(applicationContext) - startTime
            val isSuccess = heartbeatRes.isSuccess && partnerRes.isSuccess

            BlackBoxRecorder.recordCloudSync(
                context = applicationContext,
                action = "PERIODIC_CLOUD_SYNC",
                isSuccess = isSuccess,
                details = "heartbeat=${heartbeatRes.isSuccess}, partnersSynced=${partnerRes.getOrNull()?.size ?: 0}",
                error = if (!isSuccess) "heartbeat: ${heartbeatRes.exceptionOrNull()?.message}, sync: ${partnerRes.exceptionOrNull()?.message}" else null,
                executionTimeMs = duration
            )

            if (isSuccess) {
                Result.success()
            } else {
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (ce: CancellationException) {
            Timber.i("Worker cancelled cleanly")
            throw ce
        } catch (e: Exception) {
            val duration = TrustedTimeProvider.currentTimeMillis(applicationContext) - startTime
            Timber.w(e, "CloudSyncWorker failed after ${duration}ms")
            BlackBoxRecorder.recordCloudSync(
                context = applicationContext,
                action = "PERIODIC_CLOUD_SYNC_ERROR",
                isSuccess = false,
                error = e.message,
                executionTimeMs = duration
            )
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val WORK_NAME = "hisn_periodic_cloud_sync_work"
        private const val REPEAT_INTERVAL_HOURS = 12L

        /**
         * Schedules the periodic cloud sync worker with network constraints and KEEP policy.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<CloudSyncWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Timber.i("Scheduled periodic Cloud Sync worker ($WORK_NAME) every $REPEAT_INTERVAL_HOURS hours with KEEP policy")
        }
    }
}
