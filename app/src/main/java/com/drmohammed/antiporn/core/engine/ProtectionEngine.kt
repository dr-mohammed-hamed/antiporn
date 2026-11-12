package com.drmohammed.antiporn.core.engine

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.DnsWatchdogService
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.BrowserShield
import com.drmohammed.antiporn.shields.CurfewShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.shields.SystemTamperShield
import com.drmohammed.antiporn.shields.VpnShield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central orchestrator engine coordinating all pluggable protection shields.
 * Manages background execution, thread-safe callback dispatch, and system status evaluation.
 */
object ProtectionEngine {

    private const val TAG = "AntiPorn_Engine"

    val shields: List<ProtectionShield> = listOf(
        DnsShield,
        VpnShield,
        BrowserShield,
        SystemTamperShield,
        AppSuspensionShield,
        CurfewShield
    )

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingCallbacks = CopyOnWriteArrayList<(List<ShieldResult>) -> Unit>()
    private val isEnforcing = AtomicBoolean(false)
    @Volatile
    private var needsRerun: Boolean = false
    private val lastRecordedShieldFailures = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getAdminComponent(context: Context): ComponentName {
        return ComponentName(context, AdminReceiver::class.java)
    }

    /**
     * Suspending version: Applies all registered security shields asynchronously on [Dispatchers.IO].
     */
    suspend fun applyAll(context: Context): List<ShieldResult> = withContext(Dispatchers.IO) {
        applyAllSync(context.applicationContext ?: context)
    }

    /**
     * Applies all registered security shields asynchronously on a background coroutine.
     * Prevents UI freezing and drops no callbacks by safely queuing concurrent requests.
     */
    fun applyAllAsync(context: Context, onComplete: ((List<ShieldResult>) -> Unit)? = null) {
        synchronized(pendingCallbacks) {
            if (onComplete != null) {
                pendingCallbacks.add(onComplete)
            }

            if (!isEnforcing.compareAndSet(false, true)) {
                needsRerun = true
                Log.i(TAG, "Enforcement in progress, queued callback and scheduled rerun for completion notification")
                return
            }
            needsRerun = false
        }

        val appContext = context.applicationContext ?: context
        engineScope.launch {
            while (true) {
                val results = try {
                    applyAllSync(appContext)
                } catch (t: Throwable) {
                    Log.e(TAG, "Fatal error executing applyAllSync", t)
                    listOf(
                        ShieldResult(
                            shieldId = "engine",
                            success = false,
                            message = appContext.getString(R.string.toast_error_applying, t.localizedMessage ?: "Unknown")
                        )
                    )
                }

                val callbacksToNotify: List<(List<ShieldResult>) -> Unit>? = synchronized(pendingCallbacks) {
                    if (needsRerun) {
                        needsRerun = false
                        null
                    } else {
                        val copy = pendingCallbacks.toList()
                        pendingCallbacks.clear()
                        isEnforcing.set(false)
                        copy
                    }
                }

                if (callbacksToNotify != null) {
                    withContext(Dispatchers.Main) {
                        for (cb in callbacksToNotify) {
                            try {
                                cb.invoke(results)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error invoking completion callback", e)
                            }
                        }
                    }
                    break
                }
            }
        }
    }

    /**
     * Synchronously applies all shields in sequence.
     * MUST be called from a background thread.
     */
    fun applyAllSync(context: Context): List<ShieldResult> {
        val appContext = context.applicationContext ?: context
        if (!isDeviceOwner(appContext)) {
            Log.w(TAG, "App is not Device/Profile Owner. Cannot apply shields.")
            return listOf(
                ShieldResult(
                    shieldId = "engine",
                    success = false,
                    message = appContext.getString(R.string.toast_not_device_owner)
                )
            )
        }

        val dpmResult = com.drmohammed.antiporn.telemetry.SafeDpmGateway.executeRawBlocking(
            context = appContext,
            tag = com.drmohammed.antiporn.telemetry.BlackBoxRecorder.TAG_SHIELD,
            actionName = "applyAllShieldsSync",
            requireDeviceOwner = true
        ) { dpm, admin ->
            val results = mutableListOf<ShieldResult>()
            for (shield in shields) {
                try {
                    val res = shield.apply(appContext, dpm, admin)
                    results.add(res)
                    Log.d(TAG, "Applied shield [${shield.id}]: success=${res.success}")
                } catch (e: Exception) {
                    Log.e(TAG, "Exception applying shield [${shield.id}]", e)
                    results.add(
                        ShieldResult(
                            shieldId = shield.id,
                            success = false,
                            message = appContext.getString(R.string.toast_error_applying, e.localizedMessage ?: "Unknown")
                        )
                    )
                }
            }
            results
        }

        val results = dpmResult.getOrNull() ?: listOf(
            ShieldResult(
                shieldId = "engine",
                success = false,
                message = appContext.getString(R.string.toast_dpm_missing)
            )
        )

        if (!DnsWatchdogService.isRunning) {
            try {
                DnsWatchdogService.startService(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start watchdog service", e)
            }
        }

        return results
    }

    /**
     * Evaluates the active status of every registered shield.
     */
    fun evaluateSystemStatus(context: Context): Map<String, ShieldStatus> {
        val appContext = context.applicationContext ?: context
        if (!isDeviceOwner(appContext)) {
            return shields.associate { it.id to ShieldStatus.Disabled(appContext.getString(R.string.card_device_owner_inactive)) }
        }

        val dpmResult = com.drmohammed.antiporn.telemetry.SafeDpmGateway.executeRawBlocking(
            context = appContext,
            tag = com.drmohammed.antiporn.telemetry.BlackBoxRecorder.TAG_SHIELD,
            actionName = "evaluateSystemStatus",
            requireDeviceOwner = true,
            recordSuccess = false
        ) { dpm, admin ->
            val statusMap = mutableMapOf<String, ShieldStatus>()
            for (shield in shields) {
                val status = try {
                    shield.checkStatus(appContext, dpm, admin)
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking status for shield [${shield.id}]", e)
                    ShieldStatus.NeedsEnforcement(appContext.getString(R.string.card_device_owner_inactive))
                }
                statusMap[shield.id] = status

                if (status !is ShieldStatus.Active && status !is ShieldStatus.Unsupported) {
                    val failureKey = when (status) {
                        is ShieldStatus.NeedsEnforcement -> "NEEDS_ENFORCEMENT: ${status.reason}"
                        is ShieldStatus.Disabled -> "DISABLED: ${status.warning}"
                        else -> "INACTIVE"
                    }
                    if (lastRecordedShieldFailures.put(shield.id, failureKey) != failureKey) {
                        com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordShield(
                            context = appContext,
                            shieldId = shield.id,
                            isSuccess = false,
                            details = failureKey
                        )
                    }
                } else if (status is ShieldStatus.Active) {
                    val previousFailure = lastRecordedShieldFailures.remove(shield.id)
                    if (previousFailure != null) {
                        com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordShield(
                            context = appContext,
                            shieldId = shield.id,
                            isSuccess = true,
                            details = "RECOVERED"
                        )
                    }
                }
            }
            statusMap
        }

        val statusMap = dpmResult.getOrNull()
        if (statusMap != null) {
            val allHealthy = statusMap.isNotEmpty() && statusMap.values.all {
                it is ShieldStatus.Active || it is ShieldStatus.Unsupported
            }
            if (allHealthy) {
                com.drmohammed.antiporn.telemetry.BlackBoxRecorder.markHealthyShieldCheck(appContext)
            }
            return statusMap
        }
        return shields.associate { it.id to ShieldStatus.Disabled(appContext.getString(R.string.toast_dpm_missing)) }
    }

    /**
     * Checks if the app is currently the active Device Owner or Profile Owner.
     */
    fun isDeviceOwner(context: Context): Boolean {
        return com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(context)
    }

    /**
     * Determines whether all shields are actively protecting the device.
     */
    fun isSystemFullyProtected(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        val statuses = evaluateSystemStatus(context)
        return statuses.values.all { it is ShieldStatus.Active || it is ShieldStatus.Unsupported }
    }
}
