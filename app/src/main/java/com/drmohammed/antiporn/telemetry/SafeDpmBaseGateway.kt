package com.drmohammed.antiporn.telemetry

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Base execution contract defining the core suspend and blocking DPM execution primitives.
 */
interface SafeDpmBaseGateway {

    suspend fun <T> execute(
        context: Context,
        tag: String,
        actionName: String,
        metadata: String? = null,
        requireDeviceOwner: Boolean = true,
        recordSuccess: Boolean = true,
        action: (dpm: DevicePolicyManager, admin: ComponentName) -> T
    ): DpmExecutionResult<T>

    fun <T> executeBlocking(
        context: Context,
        tag: String,
        actionName: String,
        metadata: String? = null,
        requireDeviceOwner: Boolean = true,
        recordSuccess: Boolean = true,
        action: (dpm: DevicePolicyManager, admin: ComponentName) -> T
    ): DpmExecutionResult<T>
}
