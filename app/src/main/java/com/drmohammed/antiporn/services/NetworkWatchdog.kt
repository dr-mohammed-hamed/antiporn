package com.drmohammed.antiporn.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import java.util.concurrent.ConcurrentHashMap

/**
 * Encapsulates network connectivity monitoring to close the "Boot-without-Network Loophole"
 * and record deduplicated physical network transitions to [BlackBoxRecorder].
 * Cleanly registers a [ConnectivityManager.NetworkCallback] and safely unregisters to prevent leaks.
 */
class NetworkWatchdog {

    companion object {
        private const val TAG = "AntiPorn_NetWatchdog"

        internal data class NetworkStateSnapshot(
            val transport: String,
            val isValidated: Boolean,
            val isCaptivePortal: Boolean
        )

        internal data class NetworkTransitionEvent(
            val action: String,
            val isSuccess: Boolean,
            val details: String?,
            val error: String?
        )

        /**
         * Resolves a human-readable transport label from [NetworkCapabilities].
         */
        internal fun resolveTransportLabel(caps: NetworkCapabilities?): String {
            if (caps == null) return "OTHER"
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
        }

        /**
         * Builds an immutable [NetworkStateSnapshot] from [NetworkCapabilities].
         */
        internal fun buildSnapshot(caps: NetworkCapabilities?): NetworkStateSnapshot {
            return NetworkStateSnapshot(
                transport = resolveTransportLabel(caps),
                isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                isCaptivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
            )
        }

        /**
         * Classifies meaningful capability transitions and suppresses identical snapshots
         * to prevent RSSI/bandwidth callback flapping floods.
         */
        internal fun classifyCapabilityTransition(
            previous: NetworkStateSnapshot?,
            current: NetworkStateSnapshot,
            networkId: String
        ): NetworkTransitionEvent? {
            if (previous == null) {
                return if (current.isCaptivePortal) {
                    NetworkTransitionEvent(
                        action = "NET_CAPTIVE_PORTAL",
                        isSuccess = false,
                        details = "transport=${current.transport}, net=$networkId",
                        error = "Captive portal / ISP login redirect detected"
                    )
                } else {
                    null
                }
            }

            if (previous == current) return null

            return when {
                !previous.isCaptivePortal && current.isCaptivePortal -> NetworkTransitionEvent(
                    action = "NET_CAPTIVE_PORTAL",
                    isSuccess = false,
                    details = "transport=${current.transport}, net=$networkId",
                    error = "Captive portal / ISP login redirect detected"
                )
                !previous.isValidated && current.isValidated -> NetworkTransitionEvent(
                    action = "NET_VALIDATED",
                    isSuccess = true,
                    details = "transport=${current.transport}, net=$networkId",
                    error = null
                )
                previous.isValidated && !current.isValidated -> NetworkTransitionEvent(
                    action = "NET_UNVALIDATED",
                    isSuccess = false,
                    details = "transport=${current.transport}, net=$networkId",
                    error = "Connected without internet validation (!NET_CAPABILITY_VALIDATED)"
                )
                previous.transport != current.transport -> NetworkTransitionEvent(
                    action = "NET_TRANSPORT_CHANGED",
                    isSuccess = true,
                    details = "${previous.transport}->${current.transport}, validated=${current.isValidated}, net=$networkId",
                    error = null
                )
                else -> null
            }
        }
    }

    private val callbackLock = Any()
    @Volatile
    private var isRegistered = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val statesByNetwork = ConcurrentHashMap<String, NetworkStateSnapshot>()

    /**
     * Registers a network callback that notifies when Internet connectivity is restored
     * and records physical network lifecycle and capability transitions.
     */
    fun register(context: Context, onNetworkAvailable: () -> Unit) {
        synchronized(callbackLock) {
            if (networkCallback != null) return

            try {
                val appContext = context.applicationContext
                val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (manager == null) {
                    Log.w(TAG, "ConnectivityManager unavailable; skipping NetworkWatchdog registration.")
                    return
                }

                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (!isRegistered) return
                        try {
                            val caps = manager.getNetworkCapabilities(network)
                            if (caps == null) {
                                // Defer onNetworkAvailable until onCapabilitiesChanged confirms non-VPN transport.
                                return
                            }
                            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                            val netKey = network.toString()
                            val snapshot = buildSnapshot(caps)
                            if (statesByNetwork.putIfAbsent(netKey, snapshot) == null) {
                                recordNetworkAvailable(appContext, snapshot, netKey)
                            }
                            onNetworkAvailable.invoke()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling onAvailable for network=$network", e)
                        }
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        if (!isRegistered) return
                        try {
                            val netKey = network.toString()
                            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                statesByNetwork.remove(netKey)
                                return
                            }

                            val current = buildSnapshot(networkCapabilities)
                            val previous = statesByNetwork.put(netKey, current)
                            if (previous == null) {
                                recordNetworkAvailable(appContext, current, netKey)
                                if (current.isCaptivePortal) {
                                    classifyCapabilityTransition(null, current, netKey)?.let { event ->
                                        recordTransitionEvent(appContext, event)
                                    }
                                }
                                onNetworkAvailable.invoke()
                                return
                            }

                            val event = classifyCapabilityTransition(previous, current, netKey) ?: return
                            recordTransitionEvent(appContext, event)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling onCapabilitiesChanged for network=$network", e)
                        }
                    }

                    override fun onLost(network: Network) {
                        if (!isRegistered) return
                        try {
                            val netKey = network.toString()
                            val removed = statesByNetwork.remove(netKey) ?: return

                            Log.i(
                                TAG,
                                "Network connection lost: transport=${removed.transport}, wasValidated=${removed.isValidated}, net=$netKey"
                            )
                            BlackBoxRecorder.recordNetwork(
                                context = appContext,
                                action = "NET_LOST",
                                isSuccess = true,
                                details = "transport=${removed.transport}, wasValidated=${removed.isValidated}, net=$netKey"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling onLost for network=$network", e)
                        }
                    }
                }

                connectivityManager = manager
                networkCallback = callback
                isRegistered = true
                manager.registerNetworkCallback(request, callback)
                Log.i(TAG, "NetworkWatchdog registered successfully.")
            } catch (e: Exception) {
                isRegistered = false
                statesByNetwork.clear()
                networkCallback = null
                connectivityManager = null
                Log.e(TAG, "Failed to register NetworkWatchdog callback", e)
            }
        }
    }

    /**
     * Unregisters the callback cleanly to prevent memory or context leaks.
     */
    fun unregister() {
        synchronized(callbackLock) {
            isRegistered = false
            statesByNetwork.clear()
            networkCallback?.let {
                try {
                    connectivityManager?.unregisterNetworkCallback(it)
                    Log.i(TAG, "NetworkWatchdog unregistered.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister NetworkWatchdog callback", e)
                }
                networkCallback = null
                connectivityManager = null
            }
        }
    }

    private fun recordNetworkAvailable(appContext: Context, snapshot: NetworkStateSnapshot, netKey: String) {
        Log.i(
            TAG,
            "Internet connection detected as available: transport=${snapshot.transport}, validated=${snapshot.isValidated}, captive=${snapshot.isCaptivePortal}, net=$netKey"
        )
        BlackBoxRecorder.recordNetwork(
            context = appContext,
            action = "NET_AVAILABLE",
            isSuccess = true,
            details = "transport=${snapshot.transport}, validated=${snapshot.isValidated}, captive=${snapshot.isCaptivePortal}, net=$netKey"
        )
    }

    private fun recordTransitionEvent(appContext: Context, event: NetworkTransitionEvent) {
        if (event.isSuccess) {
            Log.i(TAG, "Network transition [${event.action}]: ${event.details}")
        } else {
            Log.w(TAG, "Network transition [${event.action}]: ${event.error} (${event.details})")
        }
        BlackBoxRecorder.recordNetwork(
            context = appContext,
            action = event.action,
            isSuccess = event.isSuccess,
            details = event.details,
            error = event.error
        )
    }
}
