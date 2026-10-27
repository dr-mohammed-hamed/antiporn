package com.drmohammed.antiporn.core.engine

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCallbacks = CopyOnWriteArrayList<(List<ShieldResult>) -> Unit>()
    private val isEnforcing = AtomicBoolean(false)

    fun getAdminComponent(context: Context): ComponentName {
        return ComponentName(context, AdminReceiver::class.java)
    }

    /**
     * Applies all registered security shields asynchronously on a background thread.
     * Prevents UI freezing and drops no callbacks by safely queuing concurrent requests.
     */
    fun applyAllAsync(context: Context, onComplete: ((List<ShieldResult>) -> Unit)? = null) {
        synchronized(pendingCallbacks) {
            if (onComplete != null) {
                pendingCallbacks.add(onComplete)
            }

            if (!isEnforcing.compareAndSet(false, true)) {
                Log.i(TAG, "Enforcement in progress, queued callback for completion notification")
                return
            }
        }

        val appContext = context.applicationContext
        bgExecutor.execute {
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

            mainHandler.post {
                val callbacks = mutableListOf<(List<ShieldResult>) -> Unit>()
                synchronized(pendingCallbacks) {
                    callbacks.addAll(pendingCallbacks)
                    pendingCallbacks.clear()
                    isEnforcing.set(false)
                }

                for (cb in callbacks) {
                    try {
                        cb.invoke(results)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error invoking completion callback", e)
                    }
                }
            }
        }
    }

    /**
     * Synchronously applies all shields in sequence.
     * MUST be called from a background thread.
     */
    fun applyAllSync(context: Context): List<ShieldResult> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return listOf(
                ShieldResult(
                    shieldId = "engine",
                    success = false,
                    message = context.getString(R.string.toast_dpm_missing)
                )
            )

        val admin = getAdminComponent(context)
        val isDeviceOwner = isDeviceOwner(context)

        if (!isDeviceOwner) {
            Log.w(TAG, "App is not Device/Profile Owner. Cannot apply shields.")
            return listOf(
                ShieldResult(
                    shieldId = "engine",
                    success = false,
                    message = context.getString(R.string.toast_not_device_owner)
                )
            )
        }

        val results = mutableListOf<ShieldResult>()

        for (shield in shields) {
            try {
                val res = shield.apply(context, dpm, admin)
                results.add(res)
                Log.d(TAG, "Applied shield [${shield.id}]: success=${res.success}")
            } catch (e: Exception) {
                Log.e(TAG, "Exception applying shield [${shield.id}]", e)
                results.add(
                    ShieldResult(
                        shieldId = shield.id,
                        success = false,
                        message = context.getString(R.string.toast_error_applying, e.localizedMessage ?: "Unknown")
                    )
                )
            }
        }

        if (!DnsWatchdogService.isRunning) {
            try {
                DnsWatchdogService.startService(context)
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
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return shields.associate { it.id to ShieldStatus.Disabled(context.getString(R.string.toast_dpm_missing)) }

        val admin = getAdminComponent(context)
        val isDeviceOwner = isDeviceOwner(context)

        if (!isDeviceOwner) {
            return shields.associate { it.id to ShieldStatus.Disabled(context.getString(R.string.card_device_owner_inactive)) }
        }

        val statusMap = mutableMapOf<String, ShieldStatus>()
        for (shield in shields) {
            val status = try {
                shield.checkStatus(context, dpm, admin)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking status for shield [${shield.id}]", e)
                ShieldStatus.NeedsEnforcement(context.getString(R.string.card_device_owner_inactive))
            }
            statusMap[shield.id] = status
        }
        return statusMap
    }

    /**
     * Checks if the app is currently the active Device Owner or Profile Owner.
     */
    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val pkg = context.packageName
        return dpm.isDeviceOwnerApp(pkg) || dpm.isProfileOwnerApp(pkg)
    }

    /**
     * Determines whether all shields are actively protecting the device.
     */
    fun isSystemFullyProtected(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        val statuses = evaluateSystemStatus(context)
        return statuses.values.all { it is ShieldStatus.Active }
    }
}
