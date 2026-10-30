package com.drmohammed.antiporn.ui.control.gate

import android.app.Activity
import android.content.Context
import com.drmohammed.antiporn.R

/**
 * Pluggable barrier/gate interface controlling access to the Self-Control panel.
 *
 * Current implementation uses [OpenAccessGate] (direct immediate access),
 * but can be seamlessly substituted by future gates (e.g. QrCodeScanAccessGate,
 * DelayTimerAccessGate, PinAccessGate) without any refactoring of the activity or policies.
 */
interface SelfControlAccessGate {
    val gateId: String
    val displayNameResId: Int

    /**
     * Synchronously checks whether access is currently granted.
     */
    fun canAccess(context: Context): Boolean

    /**
     * Initiates the gate challenge/verification flow (e.g. prompting user or launching scanner).
     * Invokes [onGranted] when the challenge is successfully completed.
     */
    fun requestAccess(activity: Activity, onGranted: () -> Unit)
}

/**
 * Default gate granting direct, immediate access to the Self-Control panel without barriers.
 */
object OpenAccessGate : SelfControlAccessGate {
    override val gateId: String = "open"
    override val displayNameResId: Int = R.string.gate_open_title

    override fun canAccess(context: Context): Boolean {
        if (!SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised) {
            val isSupervised = try {
                com.drmohammed.antiporn.data.repository.AccountabilityRepository.getInstance(context.applicationContext).isSupervised()
            } catch (e: Exception) {
                false
            }
            if (isSupervised) return false
        }
        return true
    }

    override fun requestAccess(activity: Activity, onGranted: () -> Unit) {
        onGranted.invoke()
    }
}

/**
 * Central registry managing the active [SelfControlAccessGate].
 */
object SelfControlAccessGateRegistry {
    @Volatile
    private var activeGate: SelfControlAccessGate = OpenAccessGate

    fun getActiveGate(): SelfControlAccessGate = activeGate

    fun setActiveGate(gate: SelfControlAccessGate) {
        activeGate = gate
    }

    /**
     * Architectural toggle: indicates whether the self-control panel is accessible
     * even when the device is actively supervised by an external guardian.
     * Defaults to true as requested, but can be switched to false when strict isolation is desired.
     */
    @Volatile
    var isSelfControlAllowedWhenSupervised: Boolean = true
}

