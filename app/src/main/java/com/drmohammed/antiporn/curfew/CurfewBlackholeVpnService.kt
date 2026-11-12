package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.Looper
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
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * Unified 24/7 Protection VPN Service coordinating daytime Local DNS Sinkhole
 * inspection and nighttime Curfew blackout via serialized off-main-thread execution.
 */
class CurfewBlackholeVpnService : VpnService() {

    enum class VpnMode {
        IDLE,
        DNS_FILTERING_ONLY,
        CURFEW_BLACKHOLE
    }

    private sealed class VpnCommand {
        data class StartOrUpdate(val forceRebuild: Boolean = false) : VpnCommand()
        data class StopIfAllowed(val startId: Int = -1) : VpnCommand()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandChannel = Channel<VpnCommand>(Channel.UNLIMITED)
    private val tunnelMutex = Mutex()
    private var dnsWorkerJob: Job? = null

    @Volatile
    private var isServiceDestroyed: Boolean = false

    @Volatile
    private var isServiceRevoked: Boolean = false

    @Volatile
    private var lastStartId: Int = -1

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
        synchronized(companionStateLock) {
            activeService = WeakReference(this)
            isStartPending = false
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

        serviceScope.launch {
            try {
                BinaryDomainFilter.init(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "BinaryDomainFilter pre-init failed", e)
            }
            for (cmd in commandChannel) {
                handleCommand(cmd)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeService = WeakReference(this)
        isStartPending = false
        lastStartId = startId
        safeStartForegroundNotification()

        val action = intent?.action
        if (action == ACTION_STOP) {
            commandChannel.trySend(VpnCommand.StopIfAllowed(startId = startId))
            return START_NOT_STICKY
        }

        isServiceRevoked = false
        val forceRebuild = intent?.getBooleanExtra("force_rebuild", false) == true
        commandChannel.trySend(VpnCommand.StartOrUpdate(forceRebuild = forceRebuild))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == SERVICE_INTERFACE) {
            activeService = WeakReference(this)
            isStartPending = false
            Log.i(TAG, "Always-On OS direct bind received; promoting foreground and ensuring tunnel")
            safeStartForegroundNotification()
            commandChannel.trySend(VpnCommand.StartOrUpdate(forceRebuild = false))
        }
        return super.onBind(intent)
    }

    private suspend fun handleCommand(cmd: VpnCommand) {
        val appContext = applicationContext ?: this
        val isCurfewBlackoutActive = isBlackoutActive(appContext)

        when (cmd) {
            is VpnCommand.StopIfAllowed -> {
                if (isCurfewBlackoutActive) {
                    Log.w(TAG, "Rejected ACTION_STOP because curfew blackout is actively enforced!")
                    establishTunnel(VpnMode.CURFEW_BLACKHOLE, forceRebuild = false)
                    syncNotificationSafely(appContext)
                    return
                }
                if (cmd.startId > 0 && lastStartId > cmd.startId) {
                    Log.i(TAG, "Ignoring superseded ACTION_STOP(startId=${cmd.startId}, latest=$lastStartId)")
                    return
                }
                Log.i(TAG, "Received ACTION_STOP. Tearing down VPN tunnel and stopping service.")
                stopVpnAndSelf(appContext, cmd.startId)
            }

            is VpnCommand.StartOrUpdate -> {
                refreshBlockedUrlsCache()
                val targetMode = evaluateRequiredMode(appContext, fallbackOnFailure = currentMode)
                Log.i(TAG, "Evaluating targetMode: $targetMode (currentMode=$currentMode)")
                if (targetMode == VpnMode.IDLE) {
                    stopVpnAndSelf(appContext, lastStartId)
                    return
                }
                establishTunnel(targetMode, cmd.forceRebuild)
                syncNotificationSafely(appContext)
            }
        }
    }

    private suspend fun establishTunnel(targetMode: VpnMode, forceRebuild: Boolean = false) {
        tunnelMutex.withLock {
            val appContext = applicationContext ?: this
            if (isServiceDestroyed || isServiceRevoked) {
                Log.i(TAG, "Aborting establishTunnel because service is destroyed or revoked")
                completeTunnelWaiters(false)
                return@withLock
            }
            if (targetMode == VpnMode.IDLE) {
                stopVpnInternal(removeActiveRef = true)
                stopServiceForStartId(lastStartId)
                return@withLock
            }

            if (!forceRebuild && vpnInterface != null && currentMode == targetMode) {
                if (targetMode == VpnMode.CURFEW_BLACKHOLE) {
                    val currentBypassed = CurfewConfig.getVpnBypassedPackages(appContext)
                    if (currentBypassed == lastBypassedPackages) {
                        refreshBlockedUrlsCache()
                        completeTunnelWaiters(true)
                        return@withLock
                    }
                } else {
                    if (dnsWorkerJob?.isActive == true) {
                        refreshBlockedUrlsCache()
                        completeTunnelWaiters(true)
                        return@withLock
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
            builder.addDnsServer("10.255.255.2")
            builder.addRoute("10.255.255.2", 32)
            builder.addAddress("fd00::1", 128)
            builder.addRoute("fd00::1", 128)
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

        var appliedBypassedPackages: Set<String>? = null
        try {
            builder.addDisallowedApplication(packageName)

            if (targetMode == VpnMode.CURFEW_BLACKHOLE) {
                val bypassedPackages = CurfewConfig.getVpnBypassedPackages(appContext)
                appliedBypassedPackages = bypassedPackages
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && SafeDpmGateway.isDeviceOwner(appContext)) {
                    try {
                        val alwaysOnPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(appContext, recordSuccess = false).getOrNull()
                        val isLockdown = SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(appContext, recordSuccess = false).getOrNull() == true
                        if (alwaysOnPkg != packageName || !isLockdown || lastBypassedPackages != bypassedPackages) {
                            SafeDpmGateway.setAlwaysOnVpnPackageBlocking(
                                context = appContext,
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
                try {
                    if (SafeDpmGateway.isDeviceOwner(appContext)) {
                        val isLockdown = SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(appContext, recordSuccess = false).getOrNull() == true
                        if (isLockdown) {
                            SafeDpmGateway.setAlwaysOnVpnPackageBlocking(
                                context = appContext,
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

            if (isServiceDestroyed || isServiceRevoked) {
                try {
                    newVpnInterface?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing TUN interface after service destroy/revoke", e)
                }
                withContext(NonCancellable) {
                    stopVpnInternal(removeActiveRef = true)
                }
                completeTunnelWaiters(false)
                return@withLock
            }

            if (newVpnInterface != null) {
                try {
                    oldVpnInterface?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing previous TUN interface during handover", e)
                }
                stopActiveIoLoop()

                if (isServiceDestroyed || isServiceRevoked) {
                    try {
                        newVpnInterface.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing TUN interface after IoLoop stop on destroy/revoke", e)
                    }
                    withContext(NonCancellable) {
                        stopVpnInternal(removeActiveRef = true)
                    }
                    completeTunnelWaiters(false)
                    return@withLock
                }

                vpnInterface = newVpnInterface
                currentMode = targetMode
                var tunnelActivated = false
                synchronized(companionStateLock) {
                    if (!isServiceDestroyed && !isServiceRevoked && (activeService?.get() === this || activeService == null)) {
                        activeService = WeakReference(this)
                        Companion.currentMode = targetMode
                        isRunning = true
                        isStartPending = false
                        tunnelActivated = true
                    }
                }
                lastBypassedPackages = appliedBypassedPackages

                refreshBlockedUrlsCache()

                if (targetMode == VpnMode.DNS_FILTERING_ONLY) {
                    startDnsFilteringLoop(newVpnInterface)
                }
                if (tunnelActivated) {
                    completeTunnelWaiters(true)
                } else if (synchronized(companionStateLock) { activeService == null || activeService?.get() === this }) {
                    completeTunnelWaiters(false)
                }
                Log.i(TAG, "Seamless Handover completed successfully to mode: $targetMode")
            } else {
                completeTunnelWaiters(oldVpnInterface != null && isRunning)
                if (oldVpnInterface == null) {
                    stopVpnInternal(removeActiveRef = true)
                    stopServiceForStartId(lastStartId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during tunnel establishment for mode $targetMode", e)
            completeTunnelWaiters(oldVpnInterface != null && isRunning && !isServiceDestroyed && !isServiceRevoked)
            if (isServiceDestroyed || isServiceRevoked || oldVpnInterface == null) {
                withContext(NonCancellable) {
                    stopVpnInternal(removeActiveRef = true)
                }
                if (!isServiceDestroyed) {
                    stopServiceForStartId(lastStartId)
                }
            }
        } finally {
            if ((isServiceDestroyed || isServiceRevoked) && vpnInterface != null) {
                withContext(NonCancellable) {
                    stopVpnInternal(removeActiveRef = true)
                }
            }
        }
        }
        if (isServiceDestroyed || isServiceRevoked) {
            withContext(NonCancellable) {
                tunnelMutex.withLock {
                    if (vpnInterface != null || activeService?.get() === this@CurfewBlackholeVpnService) {
                        stopVpnInternal(removeActiveRef = true)
                    }
                }
            }
        }
    }

    private fun refreshBlockedUrlsCache() {
        try {
            val policy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
            activePolicy = policy
            compiledBlockedDomainsCache = DnsPacketParser.compileEffectiveBlockedDomains(policy.customBlockedUrls)
            dnsForwarder?.setCustomDnsHost(policy.dnsHost)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh policy cache", e)
        }
    }

    private suspend fun stopActiveIoLoop() {
        val job = dnsWorkerJob
        dnsWorkerJob = null
        if (job != null) {
            try {
                job.cancelAndJoin()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping DNS worker loop", e)
            }
        }
    }

    private fun startDnsFilteringLoop(pfd: ParcelFileDescriptor) {
        val worker = dnsTunnelWorker ?: return
        dnsWorkerJob = worker.start(serviceScope, pfd)
    }

    private fun safeStartForegroundNotification() {
        try {
            val notification = ProtectionNotificationCoordinator.buildProtectionNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed promoting CurfewBlackholeVpnService to foreground", e)
        }
    }

    private fun syncNotificationSafely(appContext: Context) {
        try {
            ProtectionNotificationCoordinator.syncProtectionNotification(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Error syncing protection notification from VPN service", e)
        }
    }

    private suspend fun stopVpnAndSelf(appContext: Context, expectedStartId: Int = -1) {
        if (expectedStartId > 0 && lastStartId > expectedStartId) {
            Log.i(TAG, "Skipping superseded stopVpnAndSelf(expected=$expectedStartId, latest=$lastStartId)")
            return
        }
        val shouldStop: Boolean = tunnelMutex.withLock {
            if (expectedStartId > 0 && lastStartId > expectedStartId) {
                Log.i(TAG, "Skipping stopVpnInternal inside lock due to newer startId=$lastStartId")
                false
            } else {
                stopVpnInternal(removeActiveRef = true)
                true
            }
        }
        if (!shouldStop) return
        syncNotificationSafely(appContext)
        val stopTargetId = if (expectedStartId > 0) expectedStartId else lastStartId
        stopServiceForStartId(stopTargetId)
    }

    private fun stopServiceForStartId(startId: Int) {
        if (startId > 0) {
            stopSelfResult(startId)
        } else {
            stopSelf()
        }
    }

    @Synchronized
    private fun stopVpnInternal(removeActiveRef: Boolean) {
        currentMode = VpnMode.IDLE
        lastBypassedPackages = null
        val ownedActive = synchronized(companionStateLock) {
            val isOwner = activeService?.get() === this
            if (isOwner) {
                isRunning = false
                isStartPending = false
                Companion.currentMode = VpnMode.IDLE
                if (removeActiveRef) {
                    activeService = null
                }
            }
            isOwner || activeService == null
        }
        if (ownedActive) {
            completeTunnelWaiters(false)
        }

        dnsWorkerJob?.cancel()
        dnsWorkerJob = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface descriptor", e)
        } finally {
            vpnInterface = null
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping foreground notification", e)
        }
    }

    override fun onRevoke() {
        Log.w(TAG, "Protection VPN revoked by system or user")
        isServiceRevoked = true
        completeTunnelWaiters(false)
        if (tunnelMutex.tryLock()) {
            try {
                stopVpnInternal(removeActiveRef = true)
            } finally {
                tunnelMutex.unlock()
            }
        }
        stopServiceForStartId(lastStartId)
        super.onRevoke()
    }

    override fun onDestroy() {
        isServiceDestroyed = true
        if (synchronized(companionStateLock) { activeService == null || activeService?.get() === this }) {
            completeTunnelWaiters(false)
        }
        super.onDestroy()
        Log.i(TAG, "CurfewBlackholeVpnService destroyed")
        commandChannel.close()
        try {
            if (tunnelMutex.tryLock()) {
                try {
                    stopVpnInternal(removeActiveRef = true)
                } finally {
                    tunnelMutex.unlock()
                }
            }
        } finally {
            serviceScope.cancel()
            synchronized(companionStateLock) {
                if (activeService?.get() === this) {
                    isRunning = false
                    isStartPending = false
                    Companion.currentMode = VpnMode.IDLE
                    activeService = null
                }
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_UnifiedVpn"
        const val CHANNEL_ID = NotificationRegistry.CHANNEL_ID_PROTECTION_VPN
        const val NOTIFICATION_ID = NotificationRegistry.FGS_CURFEW_VPN_ID

        const val ACTION_START = "com.drmohammed.antiporn.curfew.ACTION_START_VPN"
        const val ACTION_STOP = "com.drmohammed.antiporn.curfew.ACTION_STOP_VPN"

        private val companionStateLock = Any()

        @Volatile
        private var activeService: WeakReference<CurfewBlackholeVpnService>? = null

        @Volatile
        private var isStartPending: Boolean = false

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentMode: VpnMode = VpnMode.IDLE
            private set

        @Volatile
        private var tunnelReadyDeferred: CompletableDeferred<Boolean>? = null

        private fun prepareTunnelWaiter(): CompletableDeferred<Boolean> = synchronized(companionStateLock) {
            tunnelReadyDeferred?.takeIf { !it.isCompleted }
                ?: CompletableDeferred<Boolean>().also { tunnelReadyDeferred = it }
        }

        private fun completeTunnelWaiters(ready: Boolean) {
            val deferred = synchronized(companionStateLock) {
                tunnelReadyDeferred.also { tunnelReadyDeferred = null }
            }
            deferred?.complete(ready)
        }

        fun awaitTunnelReadyBlocking(timeoutMs: Long = 1_500L): Boolean {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return synchronized(companionStateLock) { isRunning }
            }
            val deferred = synchronized(companionStateLock) {
                if (isRunning) return true
                if (!isStartPending && activeService?.get() == null) return false
                tunnelReadyDeferred?.takeIf { !it.isCompleted }
                    ?: CompletableDeferred<Boolean>().also { tunnelReadyDeferred = it }
            }
            return try {
                runBlocking {
                    withTimeoutOrNull(timeoutMs) { deferred.await() } ?: synchronized(companionStateLock) { isRunning }
                }
            } catch (e: Exception) {
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                Log.w(TAG, "Interrupted/failed awaiting VPN tunnel readiness", e)
                synchronized(companionStateLock) { isRunning }
            }
        }

        fun isServiceOrTunnelActive(): Boolean = synchronized(companionStateLock) {
            isRunning || isStartPending || activeService?.get() != null
        }

        fun getLiveStateSummary(): String = synchronized(companionStateLock) {
            if (!isRunning && !isStartPending && activeService?.get() == null) "INACTIVE" else "ACTIVE (${currentMode.name})"
        }

        private fun isBlackoutActive(context: Context): Boolean =
            CurfewConfig.isInstantInternetKillEnabled(context) ||
                (CurfewEnforcer.isEffectiveBlockActive(context) && CurfewConfig.isVpnBlackholeEnabled(context))

        fun evaluateRequiredMode(context: Context, fallbackOnFailure: VpnMode = currentMode): VpnMode {
            if (isBlackoutActive(context)) {
                return VpnMode.CURFEW_BLACKHOLE
            }
            val isFullSystemEnabled = try {
                val policy = PolicyRepository.getInstance(context).getCurrentPolicy()
                policy.isFullSystemFilteringEnabled && policy.browserFilterMode != DevicePolicyBundle.BROWSER_FILTER_OFF
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading policy for VPN mode evaluation; preserving safe fallback=$fallbackOnFailure", e)
                return if (fallbackOnFailure != VpnMode.IDLE) fallbackOnFailure else VpnMode.IDLE
            }
            return if (isFullSystemEnabled) {
                VpnMode.DNS_FILTERING_ONLY
            } else {
                VpnMode.IDLE
            }
        }

        fun syncPolicy(context: Context) {
            val appContext = context.applicationContext ?: context
            try {
                val targetMode = evaluateRequiredMode(appContext)
                val runningService = synchronized(companionStateLock) {
                    val service = activeService?.get()
                    if (service != null && !service.isServiceDestroyed && targetMode != VpnMode.IDLE && !isRunning) {
                        prepareTunnelWaiter()
                    }
                    service
                }
                if (runningService != null && !runningService.isServiceDestroyed) {
                    val sent = runningService.commandChannel.trySend(VpnCommand.StartOrUpdate(forceRebuild = true))
                    if (!sent.isSuccess && targetMode != VpnMode.IDLE) {
                        start(appContext)
                    }
                } else if (targetMode != VpnMode.IDLE) {
                    start(appContext)
                } else if (isServiceOrTunnelActive()) {
                    stop(appContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing policy in CurfewBlackholeVpnService", e)
            }
        }

        fun forceReleaseVpn(context: Context) {
            if (!isServiceOrTunnelActive()) {
                Log.d(TAG, "VPN already inactive; skipping forceReleaseVpn")
                return
            }
            Log.i(TAG, "Force releasing VPN interface and service")
            stop(context)
        }

        fun start(context: Context) {
            val appContext = context.applicationContext ?: context
            try {
                synchronized(companionStateLock) {
                    isStartPending = true
                    prepareTunnelWaiter()
                }
                val intent = Intent(appContext, CurfewBlackholeVpnService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(appContext, intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (e: Exception) {
                synchronized(companionStateLock) {
                    isStartPending = false
                }
                completeTunnelWaiters(false)
                Log.e(TAG, "Failed to start CurfewBlackholeVpnService", e)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext ?: context
            if (isBlackoutActive(appContext)) {
                Log.w(TAG, "Rejected CurfewBlackholeVpnService.stop() because curfew blackout is actively enforced")
                return
            }
            val runningService = synchronized(companionStateLock) {
                isStartPending = false
                val service = activeService?.get()
                if (service == null || service.isServiceDestroyed) {
                    activeService = null
                    isRunning = false
                    currentMode = VpnMode.IDLE
                }
                service
            }
            completeTunnelWaiters(false)
            if (runningService == null || runningService.isServiceDestroyed) {
                try {
                    appContext.stopService(Intent(appContext, CurfewBlackholeVpnService::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop CurfewBlackholeVpnService", e)
                }
                return
            }
            val targetStartId = runningService.lastStartId
            val sent = try {
                runningService.commandChannel.trySend(VpnCommand.StopIfAllowed(startId = targetStartId)).isSuccess
            } catch (e: Exception) {
                Log.w(TAG, "Error signaling active VPN service stop", e)
                false
            }
            if (!sent) {
                Log.w(TAG, "Failed enqueuing StopIfAllowed; falling back to stopService")
                synchronized(companionStateLock) {
                    if (activeService?.get() === runningService) {
                        activeService = null
                        isRunning = false
                        currentMode = VpnMode.IDLE
                    }
                }
                try {
                    appContext.stopService(Intent(appContext, CurfewBlackholeVpnService::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop CurfewBlackholeVpnService", e)
                }
            }
        }
    }
}
