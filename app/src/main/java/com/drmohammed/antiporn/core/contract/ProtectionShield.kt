package com.drmohammed.antiporn.core.contract

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus

/**
 * Standard contract for all pluggable security shields in AntiPorn Guard.
 * Every shield represents an isolated, modular security domain (< 150-200 lines).
 */
interface ProtectionShield {
    /** Unique identifier for this shield (e.g. "dns", "vpn", "browser") */
    val id: String

    /** Title string resource ID for UI presentation */
    val titleResId: Int

    /**
     * Applies this shield's enterprise security policy device-wide.
     * MUST be called on a background thread.
     */
    fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldResult

    /**
     * Evaluates the current active protection status of this shield.
     */
    fun checkStatus(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldStatus
}
