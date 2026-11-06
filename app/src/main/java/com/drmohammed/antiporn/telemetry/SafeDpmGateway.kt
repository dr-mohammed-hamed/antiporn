package com.drmohammed.antiporn.telemetry

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Centralized, type-safe execution gateway for all Android Enterprise (DevicePolicyManager) commands.
 * 
 * Strict Guarantees:
 * 1. Pre-flight permission verification (`isAdminActive`, `isDeviceOwnerApp`).
 * 2. Mandatory background execution off the Main Thread (`Dispatchers.IO`).
 * 3. Accurate execution latency tracking in milliseconds.
 * 4. Surgical exception catching (SecurityException, IllegalArgumentException) preventing swallowed failures.
 * 5. Automatic atomic recording in SQLite Room [HisnRoomDatabase] diagnostic table.
 */
object SafeDpmGateway {

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
    suspend fun <T> execute(
        context: Context,
        tag: String,
        actionName: String,
        metadata: String? = null,
        requireDeviceOwner: Boolean = true,
        action: (dpm: DevicePolicyManager, admin: ComponentName) -> T
    ): DpmExecutionResult<T> = withContext(Dispatchers.IO) {
        executeInternal(context, tag, actionName, metadata, requireDeviceOwner, action)
    }

    /**
     * Synchronously executes a DPM command with the same pre-flight checks and telemetry logging.
     * Designed for legacy background workers (e.g. Executors or BroadcastReceivers).
     * Issues a warning if invoked directly from the Main (UI) thread.
     */
    fun <T> executeBlocking(
        context: Context,
        tag: String,
        actionName: String,
        metadata: String? = null,
        requireDeviceOwner: Boolean = true,
        action: (dpm: DevicePolicyManager, admin: ComponentName) -> T
    ): DpmExecutionResult<T> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "[WARN] SafeDpmGateway.executeBlocking called on Main Thread for action [$actionName]. Consider migrating to suspend execute()!")
        }
        return executeInternal(context, tag, actionName, metadata, requireDeviceOwner, action)
    }

    // ==========================================
    // High-Level Domain Execution Methods
    // ==========================================

    suspend fun setPackagesSuspended(
        context: Context,
        packages: Array<String>,
        suspended: Boolean,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Array<String>> = execute(
        context = context,
        tag = tag,
        actionName = "setPackagesSuspended",
        metadata = "suspended=$suspended, count=${packages.size}"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpm.setPackagesSuspended(admin, packages, suspended) ?: emptyArray()
        } else {
            emptyArray()
        }
    }

    fun setPackagesSuspendedBlocking(
        context: Context,
        packages: Array<String>,
        suspended: Boolean,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Array<String>> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setPackagesSuspendedBlocking",
        metadata = "suspended=$suspended, count=${packages.size}"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpm.setPackagesSuspended(admin, packages, suspended) ?: emptyArray()
        } else {
            emptyArray()
        }
    }

    suspend fun isPackageSuspended(
        context: Context,
        packageName: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = execute(
        context = context,
        tag = tag,
        actionName = "isPackageSuspended",
        metadata = "pkg=$packageName"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpm.isPackageSuspended(admin, packageName)
        } else {
            false
        }
    }

    fun isPackageSuspendedBlocking(
        context: Context,
        packageName: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "isPackageSuspendedBlocking",
        metadata = "pkg=$packageName"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpm.isPackageSuspended(admin, packageName)
        } else {
            false
        }
    }

    suspend fun setApplicationHidden(
        context: Context,
        packageName: String,
        hidden: Boolean,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = execute(
        context = context,
        tag = tag,
        actionName = "setApplicationHidden",
        metadata = "pkg=$packageName, hidden=$hidden"
    ) { dpm, admin ->
        dpm.setApplicationHidden(admin, packageName, hidden)
    }

    fun setApplicationHiddenBlocking(
        context: Context,
        packageName: String,
        hidden: Boolean,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setApplicationHiddenBlocking",
        metadata = "pkg=$packageName, hidden=$hidden"
    ) { dpm, admin ->
        dpm.setApplicationHidden(admin, packageName, hidden)
    }

    suspend fun isApplicationHidden(
        context: Context,
        packageName: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = execute(
        context = context,
        tag = tag,
        actionName = "isApplicationHidden",
        metadata = "pkg=$packageName"
    ) { dpm, admin ->
        dpm.isApplicationHidden(admin, packageName)
    }

    fun isApplicationHiddenBlocking(
        context: Context,
        packageName: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "isApplicationHiddenBlocking",
        metadata = "pkg=$packageName"
    ) { dpm, admin ->
        dpm.isApplicationHidden(admin, packageName)
    }

    suspend fun addUserRestriction(
        context: Context,
        userRestriction: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = execute(
        context = context,
        tag = tag,
        actionName = "addUserRestriction",
        metadata = "restriction=$userRestriction"
    ) { dpm, admin ->
        dpm.addUserRestriction(admin, userRestriction)
    }

    fun addUserRestrictionBlocking(
        context: Context,
        userRestriction: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "addUserRestrictionBlocking",
        metadata = "restriction=$userRestriction"
    ) { dpm, admin ->
        dpm.addUserRestriction(admin, userRestriction)
    }

    suspend fun clearUserRestriction(
        context: Context,
        userRestriction: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = execute(
        context = context,
        tag = tag,
        actionName = "clearUserRestriction",
        metadata = "restriction=$userRestriction"
    ) { dpm, admin ->
        dpm.clearUserRestriction(admin, userRestriction)
    }

    fun clearUserRestrictionBlocking(
        context: Context,
        userRestriction: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "clearUserRestrictionBlocking",
        metadata = "restriction=$userRestriction"
    ) { dpm, admin ->
        dpm.clearUserRestriction(admin, userRestriction)
    }

    suspend fun setUninstallBlocked(
        context: Context,
        packageName: String,
        blocked: Boolean,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = execute(
        context = context,
        tag = tag,
        actionName = "setUninstallBlocked",
        metadata = "pkg=$packageName, blocked=$blocked"
    ) { dpm, admin ->
        dpm.setUninstallBlocked(admin, packageName, blocked)
    }

    fun setUninstallBlockedBlocking(
        context: Context,
        packageName: String,
        blocked: Boolean,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setUninstallBlockedBlocking",
        metadata = "pkg=$packageName, blocked=$blocked"
    ) { dpm, admin ->
        dpm.setUninstallBlocked(admin, packageName, blocked)
    }

    suspend fun isUninstallBlocked(
        context: Context,
        packageName: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = execute(
        context = context,
        tag = tag,
        actionName = "isUninstallBlocked",
        metadata = "pkg=$packageName"
    ) { dpm, admin ->
        dpm.isUninstallBlocked(admin, packageName)
    }

    fun isUninstallBlockedBlocking(
        context: Context,
        packageName: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "isUninstallBlockedBlocking",
        metadata = "pkg=$packageName"
    ) { dpm, admin ->
        dpm.isUninstallBlocked(admin, packageName)
    }

    suspend fun setApplicationRestrictions(
        context: Context,
        packageName: String,
        restrictions: Bundle,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = execute(
        context = context,
        tag = tag,
        actionName = "setApplicationRestrictions",
        metadata = "pkg=$packageName"
    ) { dpm, admin ->
        dpm.setApplicationRestrictions(admin, packageName, restrictions)
    }

    fun setApplicationRestrictionsBlocking(
        context: Context,
        packageName: String,
        restrictions: Bundle,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setApplicationRestrictionsBlocking",
        metadata = "pkg=$packageName"
    ) { dpm, admin ->
        dpm.setApplicationRestrictions(admin, packageName, restrictions)
    }

    suspend fun setGlobalPrivateDns(
        context: Context,
        host: String?,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Int> = execute(
        context = context,
        tag = tag,
        actionName = "setGlobalPrivateDns",
        metadata = "host=$host"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (host.isNullOrBlank()) {
                dpm.setGlobalPrivateDnsModeOpportunistic(admin)
            } else {
                dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host)
            }
        } else {
            0
        }
    }

    fun setGlobalPrivateDnsBlocking(
        context: Context,
        host: String?,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Int> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setGlobalPrivateDnsBlocking",
        metadata = "host=$host"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (host.isNullOrBlank()) {
                dpm.setGlobalPrivateDnsModeOpportunistic(admin)
            } else {
                dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host)
            }
        } else {
            0
        }
    }

    suspend fun getGlobalPrivateDnsHost(
        context: Context,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<String?> = execute(
        context = context,
        tag = tag,
        actionName = "getGlobalPrivateDnsHost"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.getGlobalPrivateDnsHost(admin)
        } else {
            null
        }
    }

    fun getGlobalPrivateDnsHostBlocking(
        context: Context,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<String?> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "getGlobalPrivateDnsHostBlocking"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.getGlobalPrivateDnsHost(admin)
        } else {
            null
        }
    }

    suspend fun setAlwaysOnVpnPackage(
        context: Context,
        vpnPackage: String?,
        lockdown: Boolean,
        bypassedPackages: Set<String>? = null,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = execute(
        context = context,
        tag = tag,
        actionName = "setAlwaysOnVpnPackage",
        metadata = "vpn=$vpnPackage, lockdown=$lockdown"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && bypassedPackages != null) {
            dpm.setAlwaysOnVpnPackage(admin, vpnPackage, lockdown, bypassedPackages)
        } else {
            dpm.setAlwaysOnVpnPackage(admin, vpnPackage, lockdown)
        }
    }

    fun setAlwaysOnVpnPackageBlocking(
        context: Context,
        vpnPackage: String?,
        lockdown: Boolean,
        bypassedPackages: Set<String>? = null,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setAlwaysOnVpnPackageBlocking",
        metadata = "vpn=$vpnPackage, lockdown=$lockdown"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && bypassedPackages != null) {
            dpm.setAlwaysOnVpnPackage(admin, vpnPackage, lockdown, bypassedPackages)
        } else {
            dpm.setAlwaysOnVpnPackage(admin, vpnPackage, lockdown)
        }
    }

    suspend fun getAlwaysOnVpnPackage(
        context: Context,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<String?> = execute(
        context = context,
        tag = tag,
        actionName = "getAlwaysOnVpnPackage"
    ) { dpm, admin ->
        dpm.getAlwaysOnVpnPackage(admin)
    }

    fun getAlwaysOnVpnPackageBlocking(
        context: Context,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<String?> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "getAlwaysOnVpnPackageBlocking"
    ) { dpm, admin ->
        dpm.getAlwaysOnVpnPackage(admin)
    }

    suspend fun isAlwaysOnVpnLockdownEnabled(
        context: Context,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = execute(
        context = context,
        tag = tag,
        actionName = "isAlwaysOnVpnLockdownEnabled"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.isAlwaysOnVpnLockdownEnabled(admin)
        } else {
            false
        }
    }

    fun isAlwaysOnVpnLockdownEnabledBlocking(
        context: Context,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "isAlwaysOnVpnLockdownEnabledBlocking"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.isAlwaysOnVpnLockdownEnabled(admin)
        } else {
            false
        }
    }

    suspend fun setPackageSuspendedWithMessage(
        context: Context,
        packageName: String,
        message: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = execute(
        context = context,
        tag = tag,
        actionName = "setPackageSuspendedWithMessage",
        metadata = "pkg=$packageName"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
                val builder = builderClass.getDeclaredConstructor().newInstance()
                val setMessageMethod = builderClass.getMethod("setMessage", String::class.java)
                setMessageMethod.invoke(builder, message)
                val buildMethod = builderClass.getMethod("build")
                val dialogInfo = buildMethod.invoke(builder)

                val suspendMethod = DevicePolicyManager::class.java.getMethod(
                    "setPackagesSuspended",
                    ComponentName::class.java,
                    Array<String>::class.java,
                    Boolean::class.javaPrimitiveType,
                    android.os.PersistableBundle::class.java,
                    android.os.PersistableBundle::class.java,
                    Class.forName("android.content.pm.SuspendDialogInfo")
                )
                val rawResult = suspendMethod.invoke(dpm, admin, arrayOf(packageName), true, null, null, dialogInfo)
                val failedPkgs = (rawResult as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
                if (rawResult != null && !failedPkgs.contains(packageName)) {
                    return@execute true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reflection for SuspendDialogInfo failed, falling back to standard suspension: ${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val failed = dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            failed.isEmpty() || !failed.contains(packageName)
        } else {
            false
        }
    }

    fun setPackageSuspendedWithMessageBlocking(
        context: Context,
        packageName: String,
        message: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setPackageSuspendedWithMessageBlocking",
        metadata = "pkg=$packageName"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
                val builder = builderClass.getDeclaredConstructor().newInstance()
                val setMessageMethod = builderClass.getMethod("setMessage", String::class.java)
                setMessageMethod.invoke(builder, message)
                val buildMethod = builderClass.getMethod("build")
                val dialogInfo = buildMethod.invoke(builder)

                val suspendMethod = DevicePolicyManager::class.java.getMethod(
                    "setPackagesSuspended",
                    ComponentName::class.java,
                    Array<String>::class.java,
                    Boolean::class.javaPrimitiveType,
                    android.os.PersistableBundle::class.java,
                    android.os.PersistableBundle::class.java,
                    Class.forName("android.content.pm.SuspendDialogInfo")
                )
                val rawResult = suspendMethod.invoke(dpm, admin, arrayOf(packageName), true, null, null, dialogInfo)
                val failedPkgs = (rawResult as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
                if (rawResult != null && !failedPkgs.contains(packageName)) {
                    return@executeBlocking true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reflection for SuspendDialogInfo failed, falling back to standard suspension: ${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val failed = dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            failed.isEmpty() || !failed.contains(packageName)
        } else {
            false
        }
    }

    suspend fun setTimeZone(
        context: Context,
        timeZoneId: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = execute(
        context = context,
        tag = tag,
        actionName = "setTimeZone",
        metadata = "tz=$timeZoneId"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setTimeZone(admin, timeZoneId)
        }
    }

    fun setTimeZoneBlocking(
        context: Context,
        timeZoneId: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setTimeZoneBlocking",
        metadata = "tz=$timeZoneId"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setTimeZone(admin, timeZoneId)
        }
    }

    suspend fun setAutoTimeRequired(
        context: Context,
        required: Boolean,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = execute(
        context = context,
        tag = tag,
        actionName = "setAutoTimeRequired",
        metadata = "required=$required"
    ) { dpm, admin ->
        @Suppress("DEPRECATION")
        dpm.setAutoTimeRequired(admin, required)
    }

    fun setAutoTimeRequiredBlocking(
        context: Context,
        required: Boolean,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setAutoTimeRequiredBlocking",
        metadata = "required=$required"
    ) { dpm, admin ->
        @Suppress("DEPRECATION")
        dpm.setAutoTimeRequired(admin, required)
    }

    suspend fun setAutoTimeEnabled(
        context: Context,
        enabled: Boolean,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = execute(
        context = context,
        tag = tag,
        actionName = "setAutoTimeEnabled",
        metadata = "enabled=$enabled"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm.setAutoTimeEnabled(admin, enabled)
        }
    }

    fun setAutoTimeEnabledBlocking(
        context: Context,
        enabled: Boolean,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setAutoTimeEnabledBlocking",
        metadata = "enabled=$enabled"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm.setAutoTimeEnabled(admin, enabled)
        }
    }

    suspend fun setAutoTimeZoneEnabled(
        context: Context,
        enabled: Boolean,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = execute(
        context = context,
        tag = tag,
        actionName = "setAutoTimeZoneEnabled",
        metadata = "enabled=$enabled"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm.setAutoTimeZoneEnabled(admin, enabled)
        }
    }

    fun setAutoTimeZoneEnabledBlocking(
        context: Context,
        enabled: Boolean,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setAutoTimeZoneEnabledBlocking",
        metadata = "enabled=$enabled"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm.setAutoTimeZoneEnabled(admin, enabled)
        }
    }

    suspend fun enableSystemApp(
        context: Context,
        intent: Intent,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Int> = execute(
        context = context,
        tag = tag,
        actionName = "enableSystemAppByIntent",
        metadata = "intent=$intent"
    ) { dpm, admin ->
        dpm.enableSystemApp(admin, intent)
    }

    fun enableSystemAppBlocking(
        context: Context,
        intent: Intent,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Int> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "enableSystemAppByIntentBlocking",
        metadata = "intent=$intent"
    ) { dpm, admin ->
        dpm.enableSystemApp(admin, intent)
    }

    suspend fun enableSystemApp(
        context: Context,
        packageName: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = execute(
        context = context,
        tag = tag,
        actionName = "enableSystemAppByPackage",
        metadata = "pkg=$packageName"
    ) { dpm, admin ->
        dpm.enableSystemApp(admin, packageName)
    }

    fun enableSystemAppBlocking(
        context: Context,
        packageName: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Unit> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "enableSystemAppByPackageBlocking",
        metadata = "pkg=$packageName"
    ) { dpm, admin ->
        dpm.enableSystemApp(admin, packageName)
    }

    suspend fun getUserRestrictions(
        context: Context,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Bundle?> = execute(
        context = context,
        tag = tag,
        actionName = "getUserRestrictions"
    ) { dpm, admin ->
        dpm.getUserRestrictions(admin)
    }

    fun getUserRestrictionsBlocking(
        context: Context,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Bundle?> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "getUserRestrictionsBlocking"
    ) { dpm, admin ->
        dpm.getUserRestrictions(admin)
    }

    fun hasUserRestriction(
        context: Context,
        userRestriction: String,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): Boolean {
        return try {
            val bundle = getUserRestrictionsBlocking(context, tag).getOrNull()
            bundle?.getBoolean(userRestriction, false) == true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun setPermissionGrantState(
        context: Context,
        packageName: String,
        permission: String,
        grantState: Int,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = execute(
        context = context,
        tag = tag,
        actionName = "setPermissionGrantState",
        metadata = "pkg=$packageName, perm=$permission, state=$grantState"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dpm.setPermissionGrantState(admin, packageName, permission, grantState)
        } else {
            false
        }
    }

    fun setPermissionGrantStateBlocking(
        context: Context,
        packageName: String,
        permission: String,
        grantState: Int,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Boolean> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setPermissionGrantStateBlocking",
        metadata = "pkg=$packageName, perm=$permission, state=$grantState"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dpm.setPermissionGrantState(admin, packageName, permission, grantState)
        } else {
            false
        }
    }

    suspend fun <T> executeRaw(
        context: Context,
        tag: String,
        actionName: String,
        metadata: String? = null,
        requireDeviceOwner: Boolean = true,
        action: (dpm: DevicePolicyManager, admin: ComponentName) -> T
    ): DpmExecutionResult<T> = execute(
        context = context,
        tag = tag,
        actionName = actionName,
        metadata = metadata,
        requireDeviceOwner = requireDeviceOwner,
        action = action
    )

    fun <T> executeRawBlocking(
        context: Context,
        tag: String,
        actionName: String,
        metadata: String? = null,
        requireDeviceOwner: Boolean = true,
        action: (dpm: DevicePolicyManager, admin: ComponentName) -> T
    ): DpmExecutionResult<T> = executeBlocking(
        context = context,
        tag = tag,
        actionName = actionName,
        metadata = metadata,
        requireDeviceOwner = requireDeviceOwner,
        action = action
    )

    private fun <T> executeInternal(
        context: Context,
        tag: String,
        actionName: String,
        metadata: String?,
        requireDeviceOwner: Boolean,
        action: (dpm: DevicePolicyManager, admin: ComponentName) -> T
    ): DpmExecutionResult<T> {
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = getAdminComponent(appContext)

        // 1. Pre-Flight: Check DPM service availability
        if (dpm == null) {
            val msg = "DevicePolicyManager system service is unavailable"
            Log.e(TAG, "Pre-flight failed for [$actionName]: $msg")
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
                dpm.isDeviceOwnerApp(appContext.packageName)
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

            recordTelemetry(appContext, tag, actionName, duration, true, null, metadata)
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
            Log.w(TAG, "Failed recording telemetry in SafeDpmGateway", e)
        }
    }
}

/**
 * Result wrapper for DPM operations executed via [SafeDpmGateway].
 */
sealed class DpmExecutionResult<out T> {

    abstract val executionTimeMs: Long
    abstract val actionName: String

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw cause ?: RuntimeException("DPM Action [$actionName] failed ($errorType): $message")
    }

    data class Success<T>(
        val data: T,
        override val executionTimeMs: Long,
        override val actionName: String
    ) : DpmExecutionResult<T>()

    data class Failure(
        val errorType: DpmErrorType,
        val message: String,
        override val executionTimeMs: Long,
        override val actionName: String,
        val cause: Throwable? = null
    ) : DpmExecutionResult<Nothing>()
}

/**
 * Detailed error classification for DPM execution failures.
 */
enum class DpmErrorType {
    DPM_SERVICE_UNAVAILABLE,
    ADMIN_INACTIVE,
    NOT_DEVICE_OWNER,
    SECURITY_EXCEPTION,
    INVALID_ARGUMENT,
    ILLEGAL_STATE,
    EXECUTION_FAILED
}
