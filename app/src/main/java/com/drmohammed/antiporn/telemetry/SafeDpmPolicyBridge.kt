package com.drmohammed.antiporn.telemetry

import android.content.Context
import android.os.Build
import android.os.Bundle

/**
 * Domain bridge providing type-safe Android Enterprise operations for system restrictions,
 * DNS security, VPN lockdown, and clock/timezone policy enforcement.
 */
interface SafeDpmPolicyBridge : SafeDpmBaseGateway {

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

    suspend fun setGlobalPrivateDnsOpportunistic(
        context: Context,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Int> = execute(
        context = context,
        tag = tag,
        actionName = "setGlobalPrivateDnsOpportunistic",
        metadata = "mode=opportunistic"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.setGlobalPrivateDnsModeOpportunistic(admin)
        } else {
            0
        }
    }

    fun setGlobalPrivateDnsOpportunisticBlocking(
        context: Context,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Int> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "setGlobalPrivateDnsOpportunisticBlocking",
        metadata = "mode=opportunistic"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.setGlobalPrivateDnsModeOpportunistic(admin)
        } else {
            0
        }
    }

    suspend fun getGlobalPrivateDnsMode(
        context: Context,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Int> = execute(
        context = context,
        tag = tag,
        actionName = "getGlobalPrivateDnsMode"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.getGlobalPrivateDnsMode(admin)
        } else {
            -1
        }
    }

    fun getGlobalPrivateDnsModeBlocking(
        context: Context,
        tag: String = BlackBoxRecorder.TAG_DPM
    ): DpmExecutionResult<Int> = executeBlocking(
        context = context,
        tag = tag,
        actionName = "getGlobalPrivateDnsModeBlocking"
    ) { dpm, admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.getGlobalPrivateDnsMode(admin)
        } else {
            -1
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
}
