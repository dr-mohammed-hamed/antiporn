package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * mDNS Network Service Discovery for Android 11+ Wireless Debugging.
 *
 * Android OS advertises two local services:
 * 1. `_adb-tls-pairing._tcp`: Active when user opens "Pair device with pairing code".
 * 2. `_adb-tls-connect._tcp`: Active whenever "Wireless debugging" toggle is ON.
 *
 * This radar eliminates friction for non-technical users by capturing the dynamic ports
 * automatically in ~200ms without requiring split-screen or manual port entry.
 */
class AdbMdnsDiscovery(
    context: Context,
    private val onPairingPortDiscovered: (port: Int) -> Unit,
    private val onConnectPortDiscovered: (port: Int) -> Unit
) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var isPairingDiscoveryActive = false
    private var isConnectDiscoveryActive = false
    private var pendingStopPairing = false
    private var pendingStopConnect = false

    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, (Int) -> Unit>>()
    private var isResolving = false

    private val pairingDiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "mDNS Pairing discovery started: $regType")
            isPairingDiscoveryActive = true
            if (pendingStopPairing) {
                pendingStopPairing = false
                try {
                    nsdManager?.stopServiceDiscovery(this)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping pairing discovery on pending stop", e)
                }
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "mDNS Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
            if (serviceInfo.serviceType.contains(SERVICE_TYPE_PAIRING)) {
                resolveService(serviceInfo) { port ->
                    Log.i(TAG, "Captured dynamic Pairing Port: $port")
                    onPairingPortDiscovered(port)
                }
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "mDNS Pairing service lost: ${serviceInfo.serviceName}")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "mDNS Pairing discovery stopped")
            isPairingDiscoveryActive = false
            pendingStopPairing = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "mDNS Pairing discovery start failed with error: $errorCode")
            isPairingDiscoveryActive = false
            pendingStopPairing = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "mDNS Pairing discovery stop failed with error: $errorCode")
            isPairingDiscoveryActive = false
            pendingStopPairing = false
        }
    }

    private val connectDiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "mDNS Connect discovery started: $regType")
            isConnectDiscoveryActive = true
            if (pendingStopConnect) {
                pendingStopConnect = false
                try {
                    nsdManager?.stopServiceDiscovery(this)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping connect discovery on pending stop", e)
                }
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType.contains(SERVICE_TYPE_CONNECT)) {
                resolveService(serviceInfo) { port ->
                    Log.i(TAG, "Captured dynamic Connect Port: $port")
                    onConnectPortDiscovered(port)
                }
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

        override fun onDiscoveryStopped(serviceType: String) {
            isConnectDiscoveryActive = false
            pendingStopConnect = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "mDNS Connect discovery start failed with error: $errorCode")
            isConnectDiscoveryActive = false
            pendingStopConnect = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "mDNS Connect discovery stop failed with error: $errorCode")
            isConnectDiscoveryActive = false
            pendingStopConnect = false
        }
    }

    /**
     * Starts monitoring for both pairing and connect mDNS services.
     */
    fun startDiscovery() {
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager is not available on this device")
            return
        }
        pendingStopPairing = false
        pendingStopConnect = false
        try {
            if (!isPairingDiscoveryActive) {
                nsdManager.discoverServices(
                    "_adb-tls-pairing._tcp",
                    NsdManager.PROTOCOL_DNS_SD,
                    pairingDiscoveryListener
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error starting pairing discovery", e)
        }

        try {
            if (!isConnectDiscoveryActive) {
                nsdManager.discoverServices(
                    "_adb-tls-connect._tcp",
                    NsdManager.PROTOCOL_DNS_SD,
                    connectDiscoveryListener
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error starting connect discovery", e)
        }
    }

    /**
     * Stops all active mDNS discovery listeners.
     */
    fun stopDiscovery() {
        if (nsdManager == null) return
        if (isPairingDiscoveryActive) {
            try {
                nsdManager.stopServiceDiscovery(pairingDiscoveryListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping pairing discovery", e)
            }
            isPairingDiscoveryActive = false
        } else {
            pendingStopPairing = true
        }

        if (isConnectDiscoveryActive) {
            try {
                nsdManager.stopServiceDiscovery(connectDiscoveryListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping connect discovery", e)
            }
            isConnectDiscoveryActive = false
        } else {
            pendingStopConnect = true
        }

        synchronized(this) {
            resolveQueue.clear()
            isResolving = false
        }
    }

    @Synchronized
    private fun resolveService(serviceInfo: NsdServiceInfo, onResolved: (Int) -> Unit) {
        resolveQueue.add(serviceInfo to onResolved)
        processNextResolveLocked()
    }

    @Synchronized
    private fun processNextResolveLocked() {
        if (isResolving || resolveQueue.isEmpty()) return
        val item = resolveQueue.removeFirstOrNull() ?: return
        val serviceInfo = item.first
        val onResolved = item.second
        val manager = nsdManager ?: return

        isResolving = true
        try {
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Failed to resolve service ${serviceInfo.serviceName}: error $errorCode")
                    synchronized(this@AdbMdnsDiscovery) {
                        isResolving = false
                        processNextResolveLocked()
                    }
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    try {
                        val port = resolvedInfo.port
                        if (port > 0) {
                            onResolved(port)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception in onResolved callback for ${serviceInfo.serviceName}", e)
                    } finally {
                        synchronized(this@AdbMdnsDiscovery) {
                            isResolving = false
                            processNextResolveLocked()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Exception resolving service: ${serviceInfo.serviceName}", e)
            synchronized(this@AdbMdnsDiscovery) {
                isResolving = false
                processNextResolveLocked()
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_AdbMdns"
        private const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing"
        private const val SERVICE_TYPE_CONNECT = "_adb-tls-connect"
    }
}
