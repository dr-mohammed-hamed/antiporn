package com.drmohammed.antiporn.curfew

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.network.dns.DnsPacketParser
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import com.drmohammed.antiporn.data.repository.PolicyRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified Protection VPN Service coordinating daytime Local DNS Sinkhole filtering
 * and nighttime Curfew blackout.
 *
 * Architecture Highlights:
 * 1. Mode Seamless Handover: Seamlessly transitions between DNS-Only route (/32 & /128)
 *    and Curfew Blackhole (0.0.0.0/0 & ::/0) using atomic ParcelFileDescriptor exchange.
 * 2. IPv6 Leak Prevention: Enforces dummy IPv6 route (fd00::1/128) in DNS-Only mode.
 * 3. Zero-Delay Local DNS: Intercepts port 53 UDP; blocks prohibited domains (e.g. YouTube)
 *    instantly via synthetic NXDOMAIN, and passes allowed queries to safe upstream DNS (1.1.1.3).
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
    private val outStreamLock = Any()

    @Volatile
    private var currentMode: VpnMode = VpnMode.IDLE

    @Volatile
    private var lastBypassedPackages: Set<String>? = null

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
            Log.i(TAG, "Received ACTION_STOP and curfew blackout is not active. Stopping VPN.")
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        val targetMode = evaluateRequiredMode(this)
        Log.i(TAG, "CurfewBlackholeVpnService evaluating targetMode: $targetMode (currentMode=$currentMode)")
        if (targetMode == VpnMode.IDLE) {
            Log.i(TAG, "CurfewBlackholeVpnService evaluated targetMode as IDLE; stopping service")
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        establishTunnel(targetMode)
        return START_STICKY
    }

    @Synchronized
    private fun establishTunnel(targetMode: VpnMode) {
        if (targetMode == VpnMode.IDLE) {
            stopVpn()
            stopSelf()
            return
        }

        if (vpnInterface != null && currentMode == targetMode) {
            if (targetMode == VpnMode.CURFEW_BLACKHOLE) {
                val currentBypassed = CurfewConfig.getVpnBypassedPackages(this)
                if (currentBypassed == lastBypassedPackages) {
                    refreshBlockedUrlsCache()
                    return
                }
                Log.i(TAG, "Curfew bypassed packages changed ($lastBypassedPackages -> $currentBypassed), refreshing blackhole tunnel")
            } else {
                Log.d(TAG, "VPN already in targetMode: $targetMode, updating in-memory filter cache only")
                refreshBlockedUrlsCache()
                return
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
            .addDnsServer("10.255.255.1")
            .setMtu(1500)
            .setBlocking(true)

        // Architectural Requirement: DNS Filtering only intercepts DNS IPv4, Curfew takes all routes
        if (targetMode == VpnMode.DNS_FILTERING_ONLY) {
            builder.addRoute("10.255.255.1", 32)
            builder.addAddress("fd00::1", 128)
            builder.addDnsServer("fd00::1")
            builder.addRoute("fd00::1", 128)
        } else {
            builder.addAddress("fd00::1", 126)
            builder.addDnsServer("fd00::1")
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }

        // Absolute self-exemption: AntiPorn must NEVER be trapped inside its own TUN interface
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Cannot exempt self package from VPN. Aborting tunnel.", e)
            isRunning = false
            return
        }

        // Extensible Curfew Bypass Packages
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
        }

        val newVpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish TUN interface for mode $targetMode", e)
            null
        }

        if (newVpnInterface != null) {
            // Architectural Requirement 3: Seamless Handover of the ParcelFileDescriptor
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

            if (targetMode == VpnMode.CURFEW_BLACKHOLE) {
                lastBypassedPackages = CurfewConfig.getVpnBypassedPackages(this)
            } else {
                lastBypassedPackages = null
            }

            refreshBlockedUrlsCache()

            if (targetMode == VpnMode.DNS_FILTERING_ONLY) {
                startDnsFilteringLoop(newVpnInterface)
            }

            Log.i(TAG, "Seamless Handover completed successfully to mode: $targetMode")
        } else {
            Log.e(TAG, "builder.establish() returned null for mode $targetMode")
            if (oldVpnInterface == null) {
                isRunning = false
            }
        }
    }

    private val queryPermits = Semaphore(32)

    @Volatile
    private var cachedUpstreamAddress: InetAddress? = null
    @Volatile
    private var lastUpstreamResolveTime: Long = 0L

    private fun getUpstreamResolver(): InetAddress {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = cachedUpstreamAddress
        if (cached != null && (now - lastUpstreamResolveTime < 60_000L)) {
            return cached
        }

        val configuredHost = try {
            com.drmohammed.antiporn.shields.DnsShield.getEffectiveDnsHost(this)
        } catch (e: Exception) {
            DEFAULT_SAFE_UPSTREAM_DNS_IPV4
        }

        return try {
            InetAddress.getByName(configuredHost).also {
                cachedUpstreamAddress = it
                lastUpstreamResolveTime = now
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving DNS host $configuredHost, falling back to safe IPv4", e)
            lastUpstreamResolveTime = now
            cached ?: try {
                InetAddress.getByName(DEFAULT_SAFE_UPSTREAM_DNS_IPV4)
            } catch (ex: Exception) {
                InetAddress.getByName("1.1.1.3")
            }
        }
    }

    private fun refreshBlockedUrlsCache() {
        try {
            val policy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
            cachedBlockedUrls = policy.customBlockedUrls
            compiledBlockedDomainsCache = DnsPacketParser.compileEffectiveBlockedDomains(policy.customBlockedUrls)
            Log.d(TAG, "Refreshed in-memory blocked URLs cache: ${compiledBlockedDomainsCache.size} compiled rules")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh blocked URLs from PolicyRepository", e)
        }
    }

    private fun stopActiveIoLoop() {
        dnsWorkerJob?.cancel()
        dnsWorkerJob = null
    }

    /**
     * Simplified, non-blocking local DNS sinkhole loop.
     * Extracts QNAME, generates immediate NXDOMAIN for blocked domains,
     * and forwards raw query payload to safe upstream DNS using protected bounded sockets.
     */
    private fun startDnsFilteringLoop(pfd: ParcelFileDescriptor) {
        dnsWorkerJob = serviceScope.launch(Dispatchers.IO) {
            coroutineScope {
                val inStream = FileInputStream(pfd.fileDescriptor)
                val outStream = FileOutputStream(pfd.fileDescriptor)
                val packetBuffer = ByteArray(32767)

                Log.i(TAG, "DNS Filtering Loop started on TUN interface")

                try {
                    while (isActive && currentMode == VpnMode.DNS_FILTERING_ONLY) {
                        val bytesRead = try {
                            inStream.read(packetBuffer)
                        } catch (e: IOException) {
                            break // Interface closed or handover initiated
                        }
                        if (bytesRead <= 0) continue

                        val parsedQuery = DnsPacketParser.parseDnsQuery(packetBuffer, bytesRead) ?: continue
                        val blockedDomains = compiledBlockedDomainsCache

                        if (DnsPacketParser.isDomainBlockedCompiled(parsedQuery.qName, blockedDomains)) {
                            // Instant local block: Generate synthetic NXDOMAIN and reply
                            val nxResponsePacket = DnsPacketParser.buildNxDomainResponse(parsedQuery)
                            synchronized(outStreamLock) {
                                try {
                                    outStream.write(nxResponsePacket)
                                } catch (e: IOException) {
                                    Log.w(TAG, "Failed writing NXDOMAIN to TUN", e)
                                }
                            }
                            Log.d(TAG, "Blocked domain intercepted: ${parsedQuery.qName} -> NXDOMAIN delivered")
                        } else {
                            // Allowed domain: Forward raw DNS payload directly to upstream resolver via bounded protected socket
                            launch(Dispatchers.IO) {
                                queryPermits.withPermit {
                                    var querySocket: DatagramSocket? = null
                                    try {
                                        val socket = DatagramSocket().apply {
                                            soTimeout = 3500
                                        }
                                        querySocket = socket
                                        if (!protect(socket)) {
                                            Log.w(TAG, "VpnService.protect() failed for socket, dropping query")
                                            socket.close()
                                            return@withPermit
                                        }

                                        val upstream = getUpstreamResolver()
                                        val sendPacket = DatagramPacket(
                                            parsedQuery.rawDnsPayload,
                                            parsedQuery.rawDnsPayload.size,
                                            upstream,
                                            DnsPacketParser.DNS_PORT
                                        )
                                        socket.send(sendPacket)

                                        val receiveBuffer = ByteArray(4096)
                                        val recvPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                                        socket.receive(recvPacket)

                                        val dnsReplyPayload = ByteArray(recvPacket.length)
                                        System.arraycopy(receiveBuffer, 0, dnsReplyPayload, 0, recvPacket.length)

                                        val clientPacket = DnsPacketParser.wrapUpstreamDnsResponse(
                                            clientIp = parsedQuery.srcIp,
                                            serverIp = parsedQuery.dstIp,
                                            clientPort = parsedQuery.srcPort,
                                            serverPort = parsedQuery.dstPort,
                                            dnsResponsePayload = dnsReplyPayload
                                        )

                                        if (isActive && currentMode == VpnMode.DNS_FILTERING_ONLY) {
                                            synchronized(outStreamLock) {
                                                try {
                                                    outStream.write(clientPacket)
                                                } catch (e: IOException) {
                                                    Log.w(TAG, "Failed writing upstream DNS reply to TUN", e)
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Upstream DNS query failed for ${parsedQuery.qName}: ${e.message}")
                                    } finally {
                                        try {
                                            querySocket?.close()
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed closing query socket", e)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "DNS Filtering Loop terminated: ${e.message}")
                }
            }
        }
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
        const val CHANNEL_ID = "antiporn_curfew_vpn"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.drmohammed.antiporn.curfew.ACTION_START_VPN"
        const val ACTION_STOP = "com.drmohammed.antiporn.curfew.ACTION_STOP_VPN"

        // Cloudflare Family IPv4 (Malware & Adult Content Blocking)
        const val DEFAULT_SAFE_UPSTREAM_DNS_IPV4 = "1.1.1.3"

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

        @Volatile
        private var compiledBlockedDomainsCache: Set<String> = emptySet()

        fun evaluateRequiredMode(context: Context): VpnMode {
            val isCurfewOrKill = CurfewConfig.isInstantInternetKillEnabled(context) ||
                    (CurfewEnforcer.isEffectiveBlockActive(context) && CurfewConfig.isVpnBlackholeEnabled(context))
            if (isCurfewOrKill) {
                return VpnMode.CURFEW_BLACKHOLE
            }

            val policy = try {
                PolicyRepository.getInstance(context.applicationContext).getCurrentPolicy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading policy in evaluateRequiredMode", e)
                null
            }

            if (policy != null && policy.customBlockedUrls.isNotEmpty()) {
                return VpnMode.DNS_FILTERING_ONLY
            }

            return VpnMode.IDLE
        }

        fun syncPolicy(context: Context) {
            val appContext = context.applicationContext
            try {
                val policy = PolicyRepository.getInstance(appContext).getCurrentPolicy()
                cachedBlockedUrls = policy.customBlockedUrls
                compiledBlockedDomainsCache = DnsPacketParser.compileEffectiveBlockedDomains(policy.customBlockedUrls)
                Log.d(TAG, "Synchronized policy in Unified VPN: ${compiledBlockedDomainsCache.size} compiled rules")

                val targetMode = evaluateRequiredMode(appContext)
                activeService?.get()?.let { service ->
                    service.establishTunnel(targetMode)
                } ?: run {
                    if (targetMode != VpnMode.IDLE) {
                        start(appContext)
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
                Log.i(TAG, "Requested start of CurfewBlackholeVpnService")
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
                Log.i(TAG, "Requested stop of CurfewBlackholeVpnService via ACTION_STOP")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Cannot startService with ACTION_STOP in background; stopping service directly", e)
                try {
                    appContext.stopService(Intent(appContext, CurfewBlackholeVpnService::class.java))
                } catch (stopEx: Exception) {
                    Log.e(TAG, "Failed fallback stopService for CurfewBlackholeVpnService", stopEx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop CurfewBlackholeVpnService", e)
                try {
                    appContext.stopService(Intent(appContext, CurfewBlackholeVpnService::class.java))
                } catch (stopEx: Exception) {
                    Log.e(TAG, "Failed emergency stop of CurfewBlackholeVpnService", stopEx)
                }
            }
        }
    }
}
