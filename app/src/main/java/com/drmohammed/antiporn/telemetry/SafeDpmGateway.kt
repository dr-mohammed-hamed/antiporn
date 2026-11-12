package com.drmohammed.antiporn.telemetry

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Centralized, type-safe execution gateway for all Android Enterprise (DevicePolicyManager) commands.
 * Implements domain bridges [SafeDpmPackageBridge] and [SafeDpmPolicyBridge] to maintain modular cohesion
 * and strict adherence to the project constitution's file size ceiling (< 500 lines).
 * 
 * Strict Guarantees:
 * 1. Pre-flight permission verification (`isAdminActive`, `isDeviceOwnerApp || isProfileOwnerApp`).
 * 2. Mandatory background execution off the Main Thread (`Dispatchers.IO`).
 * 3. Accurate execution latency tracking in milliseconds.
 * 4. Surgical exception catching (SecurityException, IllegalArgumentException, IllegalStateException) preventing swallowed failures.
 * 5. Automatic atomic recording in SQLite Room [HisnRoomDatabase] diagnostic table.
 */
object SafeDpmGateway : SafeDpmPackageBridge, SafeDpmPolicyBridge {

    private const val TAG = "AntiPorn_DpmGateway"

    /**
     * Resolves the primary DeviceAdminReceiver component for AntiPorn.
     */
    fun getAdminComponent(context: Context): ComponentName {
        return ComponentName(context, AdminReceiver::class.java)
    }

    /**
     * Checks if the app is actively registered as an Active Device Admin.
     */
    fun isAdminActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
            dpm.isAdminActive(getAdminComponent(context))
        } catch (e: Exception) {
            Log.w(TAG, "Error checking isAdminActive", e)
            false
        }
    }

    /**
     * Checks if the app is actively running as the device-wide Device Owner or Profile Owner.
     */
    fun isDeviceOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
            dpm.isDeviceOwnerApp(context.packageName) || dpm.isProfileOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking isDeviceOwnerApp", e)
            false
        }
    }

    /**
     * Asynchronously executes a DPM command on [Dispatchers.IO] with strict pre-flight checks,
     * latency measurement, and automated SQLite telemetry logging.
     */
    override suspend fun <T> execute(
        context: Context,
        tag: String,
        actionName: String,
        metadata: String?,
        requireDeviceOwner: Boolean,
        recordSuccess: Boolean,
        action: (dpm: DevicePolicyManager, admin: ComponentName) -> T
    ): DpmExecutionResult<T> = withContext(Dispatchers.IO) {
        executeInternal(context, tag, actionName, metadata, requireDeviceOwner, recordSuccess, action)
    }

    /**
     * Synchronously executes a DPM command with the same pre-flight checks and telemetry logging.
     * Designed for legacy background workers (e.g. Executors or BroadcastReceivers).
     * Issues a warning if invoked directly from the Main (UI) thread.
     */
    override fun <T> executeBlocking(
        context: Context,
        tag: String,
        actionName: String,
        metadata: String?,
        requireDeviceOwner: Boolean,
        recordSuccess: Boolean,
        action: (dpm: DevicePolicyManager, admin: ComponentName) -> T
    ): DpmExecutionResult<T> {
        val isMainThread = try {
            Looper.myLooper() == Looper.getMainLooper()
        } catch (e: RuntimeException) {
            if (e.message?.contains("not mocked") != true) throw e
            false
        }
        if (isMainThread) {
            safeLog(Log.WARN, "[WARN] SafeDpmGateway.executeBlocking called on Main Thread for action [$actionName]. Consider migrating to suspend execute()!")
        }
        return executeInternal(context, tag, actionName, metadata, requireDeviceOwner, recordSuccess, action)
    }

    suspend fun <T> executeRaw(
        context: Context,
        tag: String,
        actionName: String,
        metadata: String? = null,
        requireDeviceOwner: Boolean = true,
        recordSuccess: Boolean = true,
        action: (dpm: DevicePolicyManager, admin: ComponentName) -> T
    ): DpmExecutionResult<T> = execute(
        context = context,
        tag = tag,
        actionName = actionName,
        metadata = metadata,
        requireDeviceOwner = requireDeviceOwner,
        recordSuccess = recordSuccess,
        action = action
    )

    fun <T> executeRawBlocking(
        context: Context,
        tag: String,
        actionName: String,
        metadata: String? = null,
        requireDeviceOwner: Boolean = true,
        recordSuccess: Boolean = true,
        action: (dpm: DevicePolicyManager, admin: ComponentName) -> T
    ): DpmExecutionResult<T> = executeBlocking(
        context = context,
        tag = tag,
        actionName = actionName,
        metadata = metadata,
        requireDeviceOwner = requireDeviceOwner,
        recordSuccess = recordSuccess,
        action = action
    )

    private fun <T> executeInternal(
        context: Context,
        tag: String,
        actionName: String,
        metadata: String?,
        requireDeviceOwner: Boolean,
        recordSuccess: Boolean,
        action: (dpm: DevicePolicyManager, admin: ComponentName) -> T
    ): DpmExecutionResult<T> {
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = getAdminComponent(appContext)

        // 1. Pre-Flight: Check DPM service availability
        if (dpm == null) {
            val msg = "DevicePolicyManager system service is unavailable"
            safeLog(Log.ERROR, "Pre-flight failed for [$actionName]: $msg")
            recordTelemetry(appContext, tag, actionName, 0L, false, msg, metadata)
            return DpmExecutionResult.Failure(
                errorType = DpmErrorType.DPM_SERVICE_UNAVAILABLE,
                message = msg,
                executionTimeMs = 0L,
                actionName = actionName
            )
        }

        // 2. Pre-Flight: Check Active Admin registration
        val isAdminActive = try {
            dpm.isAdminActive(admin)
        } catch (e: Exception) {
            val msg = "Exception checking isAdminActive: ${e.message}"
            Log.e(TAG, "Pre-flight exception for [$actionName]: $msg", e)
            recordTelemetry(appContext, tag, actionName, 0L, false, msg, metadata)
            return DpmExecutionResult.Failure(
                errorType = if (e is SecurityException) DpmErrorType.SECURITY_EXCEPTION else DpmErrorType.EXECUTION_FAILED,
                message = msg,
                executionTimeMs = 0L,
                actionName = actionName,
                cause = e
            )
        }
        if (!isAdminActive) {
            val msg = "DeviceAdminReceiver is not active for package ${appContext.packageName}"
            Log.e(TAG, "Pre-flight failed for [$actionName]: $msg")
            recordTelemetry(appContext, tag, actionName, 0L, false, msg, metadata)
            return DpmExecutionResult.Failure(
                errorType = DpmErrorType.ADMIN_INACTIVE,
                message = msg,
                executionTimeMs = 0L,
                actionName = actionName
            )
        }

        // 3. Pre-Flight: Check Device Owner status if required
        if (requireDeviceOwner) {
            val isDeviceOwner = try {
                dpm.isDeviceOwnerApp(appContext.packageName) || dpm.isProfileOwnerApp(appContext.packageName)
            } catch (e: Exception) {
                val msg = "Exception checking isDeviceOwnerApp: ${e.message}"
                Log.e(TAG, "Pre-flight exception for [$actionName]: $msg", e)
                recordTelemetry(appContext, tag, actionName, 0L, false, msg, metadata)
                return DpmExecutionResult.Failure(
                    errorType = if (e is SecurityException) DpmErrorType.SECURITY_EXCEPTION else DpmErrorType.EXECUTION_FAILED,
                    message = msg,
                    executionTimeMs = 0L,
                    actionName = actionName,
                    cause = e
                )
            }
            if (!isDeviceOwner) {
                val msg = "App is not the active Device Owner (required for action [$actionName])"
                Log.e(TAG, "Pre-flight failed for [$actionName]: $msg")
                recordTelemetry(appContext, tag, actionName, 0L, false, msg, metadata)
                return DpmExecutionResult.Failure(
                    errorType = DpmErrorType.NOT_DEVICE_OWNER,
                    message = msg,
                    executionTimeMs = 0L,
                    actionName = actionName
                )
            }
        }

        // 4. Execution & Latency Measurement
        val startTime = SystemClock.elapsedRealtime()
        return try {
            val result = action(dpm, admin)
            val duration = SystemClock.elapsedRealtime() - startTime

            if (recordSuccess) {
                recordTelemetry(appContext, tag, actionName, duration, true, null, metadata)
            }
            Log.d(TAG, "[SUCCESS] DPM Action [$actionName] executed successfully in ${duration}ms")

            DpmExecutionResult.Success(
                data = result,
                executionTimeMs = duration,
                actionName = actionName
            )
        } catch (e: SecurityException) {
            val duration = SystemClock.elapsedRealtime() - startTime
            val errMsg = "SecurityException: ${e.message}"
            Log.e(TAG, "[FAILURE] DPM Action [$actionName] failed with SecurityException (${duration}ms)", e)
            recordTelemetry(appContext, tag, actionName, duration, false, errMsg, metadata)

            DpmExecutionResult.Failure(
                errorType = DpmErrorType.SECURITY_EXCEPTION,
                message = errMsg,
                executionTimeMs = duration,
                actionName = actionName,
                cause = e
            )
        } catch (e: IllegalArgumentException) {
            val duration = SystemClock.elapsedRealtime() - startTime
            val errMsg = "IllegalArgumentException: ${e.message}"
            Log.e(TAG, "[FAILURE] DPM Action [$actionName] failed with IllegalArgumentException (${duration}ms)", e)
            recordTelemetry(appContext, tag, actionName, duration, false, errMsg, metadata)

            DpmExecutionResult.Failure(
                errorType = DpmErrorType.INVALID_ARGUMENT,
                message = errMsg,
                executionTimeMs = duration,
                actionName = actionName,
                cause = e
            )
        } catch (e: IllegalStateException) {
            val duration = SystemClock.elapsedRealtime() - startTime
            val errMsg = "IllegalStateException: ${e.message}"
            Log.e(TAG, "[FAILURE] DPM Action [$actionName] failed with IllegalStateException (${duration}ms)", e)
            recordTelemetry(appContext, tag, actionName, duration, false, errMsg, metadata)

            DpmExecutionResult.Failure(
                errorType = DpmErrorType.ILLEGAL_STATE,
                message = errMsg,
                executionTimeMs = duration,
                actionName = actionName,
                cause = e
            )
        } catch (t: Throwable) {
            val duration = SystemClock.elapsedRealtime() - startTime
            val errMsg = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "[FAILURE] DPM Action [$actionName] failed with unexpected exception (${duration}ms)", t)
            recordTelemetry(appContext, tag, actionName, duration, false, errMsg, metadata)

            DpmExecutionResult.Failure(
                errorType = DpmErrorType.EXECUTION_FAILED,
                message = errMsg,
                executionTimeMs = duration,
                actionName = actionName,
                cause = t
            )
        }
    }

    /**
     * Safely logs the execution event to SQLite WAL diagnostic table without throwing.
     */
    private fun recordTelemetry(
        context: Context,
        tag: String,
        actionName: String,
        executionTimeMs: Long,
        isSuccess: Boolean,
        errorDetails: String?,
        contextMetadata: String?
    ) {
        try {
            BlackBoxRecorder.record(
                context = context,
                tag = tag,
                actionName = actionName,
                isSuccess = isSuccess,
                executionTimeMs = executionTimeMs,
                errorDetails = errorDetails,
                metadata = contextMetadata
            )
        } catch (e: Exception) {
            safeLog(Log.WARN, "Failed recording telemetry in SafeDpmGateway", e)
        }
    }

    private fun safeLog(priority: Int, message: String, throwable: Throwable? = null) {
        try {
            when (priority) {
                Log.WARN -> Log.w(TAG, message, throwable)
                else -> Log.e(TAG, message, throwable)
            }
        } catch (e: RuntimeException) {
            if (e.message?.contains("not mocked") != true) throw e
        }
    }
}
