package com.drmohammed.antiporn.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Encapsulates network connectivity monitoring to close the "Boot-without-Network Loophole".
 * Cleanly registers a [ConnectivityManager.NetworkCallback] and safely unregisters to prevent leaks.
 */
class NetworkWatchdog {

    companion object {
        private const val TAG = "AntiPorn_NetWatchdog"
    }

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Registers a network callback that notifies when Internet connectivity is restored.
     */
    fun register(context: Context, onNetworkAvailable: () -> Unit) {
        if (networkCallback != null) return

        try {
            val appContext = context.applicationContext
            connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Internet connection detected as available.")
                    onNetworkAvailable.invoke()
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Network connection lost.")
                }
            }

            val callback = networkCallback ?: return
            connectivityManager?.registerNetworkCallback(request, callback)
            Log.i(TAG, "NetworkWatchdog registered successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NetworkWatchdog callback", e)
        }
    }

    /**
     * Unregisters the callback cleanly to prevent memory or context leaks.
     */
    fun unregister() {
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
