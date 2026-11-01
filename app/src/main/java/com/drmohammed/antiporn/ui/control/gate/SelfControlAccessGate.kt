package com.drmohammed.antiporn.ui.control.gate

import android.app.Activity
import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.R

private const val TAG = "SelfControlGate"

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
        return SelfControlAccessGateRegistry.isSelfControlPermitted(context)
    }

    override fun requestAccess(activity: Activity, onGranted: () -> Unit) {
        if (canAccess(activity)) {
            onGranted.invoke()
        } else {
            val policy = try {
                com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(activity.applicationContext).getCurrentPolicy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to retrieve local policy in requestAccess", e)
                null
            }
            val msgRes = if (policy?.isSupervised == true && !policy.isSelfControlAllowed) {
                R.string.gate_access_denied_by_guardian
            } else {
                R.string.gate_access_denied
            }
            android.widget.Toast.makeText(activity, msgRes, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Central registry managing the active [SelfControlAccessGate].
 */
object SelfControlAccessGateRegistry {
    @Volatile
    private var activeGate: SelfControlAccessGate = OpenAccessGate

    /**
     * Architectural toggle: indicates whether the self-control panel is accessible
     * even when the device is actively supervised by an external guardian.
     * Defaults to true as requested, but can be switched to false when strict isolation is desired.
     */
    @Volatile
    var isSelfControlAllowedWhenSupervised: Boolean = true

    /**
     * Synchronously checks whether self-control is permitted by policy and supervision rules,
     * independent of the active pluggable gate challenge.
     */
    fun isSelfControlPermitted(context: Context): Boolean {
        val policy = try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context.applicationContext).getCurrentPolicy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed retrieving local policy in isSelfControlPermitted", e)
            null
        }

        val isSupervised = (policy?.isSupervised == true) || try {
            com.drmohammed.antiporn.data.repository.AccountabilityRepository.getInstance(context.applicationContext).isSupervised()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check accountability supervision in isSelfControlPermitted", e)
            true
        }

        if (isSupervised) {
            if (policy == null) {
                Log.w(TAG, "Precautionary lockout: policy cannot be retrieved on supervised device")
                return false
            }
            if (!policy.isSelfControlAllowed) {
                return false
            }
        }
        return true
    }

    fun getActiveGate(): SelfControlAccessGate {
        val delegate = activeGate
        return object : SelfControlAccessGate {
            override val gateId: String get() = delegate.gateId
            override val displayNameResId: Int get() = delegate.displayNameResId

            override fun canAccess(context: Context): Boolean {
                if (!isSelfControlPermitted(context)) return false
                return delegate.canAccess(context)
            }

            override fun requestAccess(activity: Activity, onGranted: () -> Unit) {
                if (!isSelfControlPermitted(activity)) {
                    val policy = try {
                        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(activity.applicationContext).getCurrentPolicy()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to retrieve local policy in requestAccess", e)
                        null
                    }
                    val msgRes = if (policy?.isSupervised == true && !policy.isSelfControlAllowed) {
                        R.string.gate_access_denied_by_guardian
                    } else {
                        R.string.gate_access_denied
                    }
                    android.widget.Toast.makeText(activity, msgRes, android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                delegate.requestAccess(activity, onGranted)
            }
        }
    }

    fun setActiveGate(gate: SelfControlAccessGate) {
        activeGate = gate
    }
}

