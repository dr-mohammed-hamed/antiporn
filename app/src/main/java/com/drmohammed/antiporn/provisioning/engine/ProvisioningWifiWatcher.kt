package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * Lifecycle-aware monitor for active Wi-Fi or local network connectivity.
 *
 * Android Wireless Debugging operates locally over Wi-Fi (wlan0) or Ethernet (eth0).
 * Having active Wi-Fi or Ethernet transport enables ADB pairing without strictly
 * requiring external Internet access validation.
 */
class ProvisioningWifiWatcher(
    context: Context,
    private val onWifiStateChanged: (isConnected: Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Synchronously checks if the device is currently connected to Wi-Fi or local network.
     */
    fun isWifiConnectedAndValidated(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Starts listening for Wi-Fi availability and capability changes.
     */
    fun startWatching() {
        stopWatching()

        if (connectivityManager == null) {
            onWifiStateChanged(false)
            return
        }

        // Notify initial state immediately on main thread
        mainHandler.post {
            onWifiStateChanged(isWifiConnectedAndValidated())
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { onWifiStateChanged(true) }
            }

            override fun onLost(network: Network) {
                mainHandler.post { onWifiStateChanged(isWifiConnectedAndValidated()) }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val isValid = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                mainHandler.post { onWifiStateChanged(isValid) }
            }
        }

        networkCallback = callback
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed registering network callback: ${e.message}")
            mainHandler.post { onWifiStateChanged(isWifiConnectedAndValidated()) }
        }
    }

    /**
     * Unregisters the callback to prevent leaks.
     */
    fun stopWatching() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WifiWatcher"
    }
}
