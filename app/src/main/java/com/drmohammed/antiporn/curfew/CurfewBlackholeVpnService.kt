package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.network.dns.BinaryDomainFilter
import com.drmohammed.antiporn.core.network.dns.DnsPacketParser
import com.drmohammed.antiporn.core.network.dns.DnsTunnelWorker
import com.drmohammed.antiporn.core.network.dns.DnsUpstreamForwarder
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

/**
 * Unified 24/7 Protection VPN Service coordinating daytime Local DNS Sinkhole
 * inspection and nighttime Curfew blackout.
 */
class CurfewBlackholeVpnService : VpnService() {

    enum class VpnMode {
        IDLE,
        DNS_FILTERING_ONLY,
        CURFEW_BLACKHOLE
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dnsWorkerJob: Job? = null

    @Volatile
    private var currentMode: VpnMode = VpnMode.IDLE

    @Volatile
    private var lastBypassedPackages: Set<String>? = null

    private var dnsForwarder: DnsUpstreamForwarder? = null
    private var dnsTunnelWorker: DnsTunnelWorker? = null

    @Volatile
    private var activePolicy: DevicePolicyBundle = DevicePolicyBundle.defaultPolicy()

    @Volatile
    private var compiledBlockedDomainsCache: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        try {
            BinaryDomainFilter.init(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "BinaryDomainFilter pre-init failed", e)
        }
        val forwarder = DnsUpstreamForwarder(
            vpnService = this,
            policyProvider = { activePolicy }
        )
        dnsForwarder = forwarder
        dnsTunnelWorker = DnsTunnelWorker(
            forwarder = forwarder,
            policyProvider = { activePolicy },
            customBlockedProvider = { compiledBlockedDomainsCache },
            isRunningProvider = { currentMode == VpnMode.DNS_FILTERING_ONLY }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeService = WeakReference(this)
        startForegroundNotification()

        val isCurfewBlackoutActive = CurfewConfig.isInstantInternetKillEnabled(this) ||
                (CurfewEnforcer.isEffectiveBlockActive(this) && CurfewConfig.isVpnBlackholeEnabled(this))

        val action = intent?.action
        if (action == ACTION_STOP) {
            if (isCurfewBlackoutActive) {
                Log.w(TAG, "Rejected ACTION_STOP because curfew blackout is actively enforced!")
                establishTunnel(VpnMode.CURFEW_BLACKHOLE)
                return START_STICKY
            }
            Log.i(TAG, "Received ACTION_STOP. Tearing down VPN tunnel and stopping service.")
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val policy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
            activePolicy = policy
            compiledBlockedDomainsCache = DnsPacketParser.compileEffectiveBlockedDomains(policy.customBlockedUrls)
            dnsForwarder?.setCustomDnsHost(policy.dnsHost)
        } catch (e: Exception) {
            Log.w(TAG, "Error refreshing policy in onStartCommand", e)
        }

        val targetMode = evaluateRequiredMode(this)
        Log.i(TAG, "Evaluating targetMode: $targetMode (currentMode=$currentMode)")
        if (targetMode == VpnMode.IDLE) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        val forceRebuild = intent?.getBooleanExtra("force_rebuild", false) == true
        establishTunnel(targetMode, forceRebuild)
        return START_STICKY
    }

    @Synchronized
    private fun establishTunnel(targetMode: VpnMode, forceRebuild: Boolean = false) {
        if (targetMode == VpnMode.IDLE) {
            stopVpn()
            stopSelf()
            return
        }

        if (!forceRebuild && vpnInterface != null && currentMode == targetMode) {
            if (targetMode == VpnMode.CURFEW_BLACKHOLE) {
                val currentBypassed = CurfewConfig.getVpnBypassedPackages(this)
                if (currentBypassed == lastBypassedPackages) {
                    refreshBlockedUrlsCache()
                    return
                }
            } else {
                if (dnsWorkerJob?.isActive == true) {
                    refreshBlockedUrlsCache()
                    return
                }
            }
        }

        val oldVpnInterface = vpnInterface
        val sessionName = if (targetMode == VpnMode.CURFEW_BLACKHOLE) {
            getString(R.string.curfew_vpn_session_name)
        } else {
            getString(R.string.curfew_vpn_dns_filter_session)
        }

        val builder = Builder()
            .setSession(sessionName)
            .addAddress("10.255.255.1", 30)
            .setMtu(1500)
            .setBlocking(true)

        if (targetMode == VpnMode.DNS_FILTERING_ONLY) {
            // Decouple DNS server (10.255.255.2) from TUN interface IP (10.255.255.1)
            // so Linux kernel table local does not consume DNS queries via local loopback
            builder.addDnsServer("10.255.255.2")
            builder.addRoute("10.255.255.2", 32)

            // IPv6 Leak Prevention without 5-second DNS Timeout:
            // 1. Add dummy IPv6 address and route (sinkhole) WITHOUT registering fd00::1 as a DNS server
            builder.addAddress("fd00::1", 128)
            builder.addRoute("fd00::1", 128)

            // 2. Blackhole routes to well-known public IPv6 DNS servers to prevent direct IPv6 resolution bypass
            builder.addRoute("2001:4860:4860::8888", 128)
            builder.addRoute("2001:4860:4860::8844", 128)
            builder.addRoute("2606:4700:4700::1111", 128)
            builder.addRoute("2606:4700:4700::1001", 128)
            builder.addRoute("2606:4700:4700::1113", 128)
            builder.addRoute("2606:4700:4700::1003", 128)
        } else {
            builder.addDnsServer("10.255.255.1")
            builder.addAddress("fd00::1", 126)
            builder.addDnsServer("fd00::1")
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }

        try {
            builder.addDisallowedApplication(packageName)

            if (targetMode == VpnMode.CURFEW_BLACKHOLE) {
                val bypassedPackages = CurfewConfig.getVpnBypassedPackages(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        if (com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(this)) {
                            com.drmohammed.antiporn.telemetry.SafeDpmGateway.setAlwaysOnVpnPackageBlocking(
                                context = this,
                                vpnPackage = packageName,
                                lockdown = true,
                                bypassedPackages = bypassedPackages
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not update Always-On VPN lockdown bypassed packages", e)
                    }
                }
                for (pkg in bypassedPackages) {
                    if (pkg == packageName) continue
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to bypass package $pkg from VPN", e)
                    }
                }
            } else {
                // Daytime DNS-Only mode: Ensure lockdown is false so normal non-DNS traffic flows freely
                try {
                    if (com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(this)) {
                        val isLockdown = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(this).getOrNull() == true
                        if (isLockdown) {
                            com.drmohammed.antiporn.telemetry.SafeDpmGateway.setAlwaysOnVpnPackageBlocking(
                                context = this,
                                vpnPackage = packageName,
                                lockdown = false
                            )
                            Log.i(TAG, "Always-On VPN lockdown successfully reset to false for daytime DNS mode")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not reset Always-On VPN lockdown in daytime", e)
                }
            }

            val newVpnInterface = try {
                builder.establish()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to establish TUN interface for mode $targetMode", e)
                null
            }

            if (newVpnInterface != null) {
                stopActiveIoLoop()
                try {
                    oldVpnInterface?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing previous TUN interface during handover", e)
                }

                vpnInterface = newVpnInterface
                currentMode = targetMode
                Companion.currentMode = targetMode
                isRunning = true

                lastBypassedPackages = if (targetMode == VpnMode.CURFEW_BLACKHOLE) {
                    CurfewConfig.getVpnBypassedPackages(this)
                } else null

                refreshBlockedUrlsCache()

                if (targetMode == VpnMode.DNS_FILTERING_ONLY) {
                    startDnsFilteringLoop(newVpnInterface)
                }
                Log.i(TAG, "Seamless Handover completed successfully to mode: $targetMode")
            } else if (oldVpnInterface == null) {
                isRunning = false
                stopVpn()
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during tunnel establishment for mode $targetMode", e)
            if (oldVpnInterface == null) {
                isRunning = false
                stopVpn()
                stopSelf()
            }
        }
    }

    private fun refreshBlockedUrlsCache() {
        try {
            val policy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
            activePolicy = policy
            cachedBlockedUrls = policy.customBlockedUrls
            compiledBlockedDomainsCache = DnsPacketParser.compileEffectiveBlockedDomains(policy.customBlockedUrls)
            dnsForwarder?.setCustomDnsHost(policy.dnsHost)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh policy cache", e)
        }
    }

    private fun stopActiveIoLoop() {
        dnsWorkerJob?.cancel()
        dnsWorkerJob = null
    }

    private fun startDnsFilteringLoop(pfd: ParcelFileDescriptor) {
        val worker = dnsTunnelWorker ?: return
        dnsWorkerJob = worker.start(serviceScope, pfd)
    }

    private fun startForegroundNotification() {
        val notification = ProtectionNotificationCoordinator.buildProtectionNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Synchronized
    private fun stopVpn() {
        isRunning = false
        currentMode = VpnMode.IDLE
        Companion.currentMode = VpnMode.IDLE
        lastBypassedPackages = null
        stopActiveIoLoop()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface descriptor", e)
        } finally {
            vpnInterface = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onRevoke() {
        Log.w(TAG, "Protection VPN revoked by system or user")
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "CurfewBlackholeVpnService destroyed")
        if (activeService?.get() === this) {
            activeService = null
        }
        serviceScope.cancel()
        stopVpn()
    }

    companion object {
        private const val TAG = "AntiPorn_UnifiedVpn"
        const val CHANNEL_ID = NotificationRegistry.CHANNEL_ID_PROTECTION_VPN
        const val NOTIFICATION_ID = NotificationRegistry.FGS_CURFEW_VPN_ID

        const val ACTION_START = "com.drmohammed.antiporn.curfew.ACTION_START_VPN"
        const val ACTION_STOP = "com.drmohammed.antiporn.curfew.ACTION_STOP_VPN"

        @Volatile
        private var activeService: WeakReference<CurfewBlackholeVpnService>? = null

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentMode: VpnMode = VpnMode.IDLE
            private set

        @Volatile
        private var cachedBlockedUrls: Set<String> = emptySet()

        fun evaluateRequiredMode(context: Context): VpnMode {
            val isCurfewOrKill = CurfewConfig.isInstantInternetKillEnabled(context) ||
                    (CurfewEnforcer.isEffectiveBlockActive(context) && CurfewConfig.isVpnBlackholeEnabled(context))
            if (isCurfewOrKill) {
                return VpnMode.CURFEW_BLACKHOLE
            }
            val isFullSystemEnabled = try {
                val policy = PolicyRepository.getInstance(context).getCurrentPolicy()
                policy.isFullSystemFilteringEnabled && policy.browserFilterMode != DevicePolicyBundle.BROWSER_FILTER_OFF
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading policy for VPN mode evaluation; defaulting to IDLE", e)
                false
            }
            return if (isFullSystemEnabled) {
                VpnMode.DNS_FILTERING_ONLY
            } else {
                VpnMode.IDLE
            }
        }

        fun syncPolicy(context: Context) {
            val appContext = context.applicationContext
            try {
                val policy = PolicyRepository.getInstance(appContext).getCurrentPolicy()
                cachedBlockedUrls = policy.customBlockedUrls
                val compiled = DnsPacketParser.compileEffectiveBlockedDomains(policy.customBlockedUrls)

                val targetMode = evaluateRequiredMode(appContext)
                activeService?.get()?.let { service ->
                    service.activePolicy = policy
                    service.compiledBlockedDomainsCache = compiled
                    service.dnsForwarder?.setCustomDnsHost(policy.dnsHost)
                    service.establishTunnel(targetMode, forceRebuild = true)
                } ?: run {
                    if (targetMode != VpnMode.IDLE) {
                        start(appContext)
                    } else if (isRunning) {
                        stop(appContext)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing policy in CurfewBlackholeVpnService", e)
            }
        }

        fun forceReleaseVpn(context: Context) {
            Log.i(TAG, "Force releasing VPN interface and service")
            activeService?.get()?.let { service ->
                service.stopVpn()
                service.stopSelf()
            }
            activeService = null
            isRunning = false
            stop(context)
        }

        fun start(context: Context) {
            val appContext = context.applicationContext
            try {
                val intent = Intent(appContext, CurfewBlackholeVpnService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(appContext, intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CurfewBlackholeVpnService", e)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            try {
                val intent = Intent(appContext, CurfewBlackholeVpnService::class.java).apply {
                    action = ACTION_STOP
                }
                appContext.startService(intent)
            } catch (e: IllegalStateException) {
                try {
                    appContext.stopService(Intent(appContext, CurfewBlackholeVpnService::class.java))
                } catch (stopEx: Exception) {
                    Log.e(TAG, "Failed fallback stopService for CurfewBlackholeVpnService", stopEx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop CurfewBlackholeVpnService", e)
            }
        }
    }
}
