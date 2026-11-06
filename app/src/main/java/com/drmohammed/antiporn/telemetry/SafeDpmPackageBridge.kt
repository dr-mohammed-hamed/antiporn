package com.drmohammed.antiporn.telemetry

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Domain bridge providing type-safe Android Enterprise operations for application and package lifecycle management.
 */
interface SafeDpmPackageBridge : SafeDpmBaseGateway {

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
            dpm.setPackagesSuspended(admin, packages, suspended) ?: packages
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
            dpm.setPackagesSuspended(admin, packages, suspended) ?: packages
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
                Log.w("AntiPorn_DpmGateway", "Reflection for SuspendDialogInfo failed, falling back to standard suspension: ${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val failed = dpm.setPackagesSuspended(admin, arrayOf(packageName), true) as Array<String>?
            failed?.contains(packageName) == false
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
                Log.w("AntiPorn_DpmGateway", "Reflection for SuspendDialogInfo failed, falling back to standard suspension: ${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val failed = dpm.setPackagesSuspended(admin, arrayOf(packageName), true) as Array<String>?
            failed?.contains(packageName) == false
        } else {
            false
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
}
