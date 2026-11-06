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
import com.drmohammed.antiporn.ota.repository.OtaRepository
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Scheduled background worker for periodic Over-The-Air (OTA) update checking and silent installation.
 * Runs strictly on deferred intervals (24 hours) with network constraints, keeping foreground real-time
 * watchdog loops free of long-running network operations.
 */
class OtaUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val otaRepository: OtaRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (isStopped) return@withContext Result.retry()
        val startTime = TrustedTimeProvider.currentTimeMillis(applicationContext)
        Timber.i("OtaUpdateWorker started at $startTime (Run attempt: $runAttemptCount)")

        try {
            if (isStopped) return@withContext Result.retry()
            val otaResult = otaRepository.checkForUpdatesAndAutoInstall(applicationContext)
            val duration = TrustedTimeProvider.currentTimeMillis(applicationContext) - startTime
            if (otaResult.isFailure) {
                val error = otaResult.exceptionOrNull()
                Timber.w(error, "OTA auto-install failed after ${duration}ms (attempt $runAttemptCount)")
                BlackBoxRecorder.record(
                    context = applicationContext,
                    tag = "OTA_WORKER",
                    actionName = "PERIODIC_CHECK_FAILURE",
                    isSuccess = false,
                    executionTimeMs = duration,
                    errorDetails = error?.message,
                    metadata = "runAttempt=$runAttemptCount"
                )
                return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
            BlackBoxRecorder.record(
                context = applicationContext,
                tag = "OTA_WORKER",
                actionName = "PERIODIC_CHECK_SUCCESS",
                isSuccess = true,
                executionTimeMs = duration,
                metadata = "runAttempt=$runAttemptCount"
            )
            Result.success()
        } catch (ce: CancellationException) {
            Timber.i("Worker cancelled cleanly")
            throw ce
        } catch (e: Exception) {
            val duration = TrustedTimeProvider.currentTimeMillis(applicationContext) - startTime
            Timber.w(e, "OtaUpdateWorker failed after ${duration}ms")
            BlackBoxRecorder.record(
                context = applicationContext,
                tag = "OTA_WORKER",
                actionName = "PERIODIC_CHECK_FAILURE",
                isSuccess = false,
                executionTimeMs = duration,
                errorDetails = e.message,
                metadata = "runAttempt=$runAttemptCount"
            )
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val WORK_NAME = "hisn_periodic_ota_update_work"
        private const val REPEAT_INTERVAL_HOURS = 24L

        /**
         * Schedules the periodic OTA update worker with network constraints and KEEP policy.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<OtaUpdateWorker>(
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
            Timber.i("Scheduled periodic OTA worker ($WORK_NAME) every $REPEAT_INTERVAL_HOURS hours with KEEP policy")
        }
    }
}
