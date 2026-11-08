package com.drmohammed.antiporn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.services.NetworkWatchdog
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.ui.shields.ActiveShieldsEvaluator
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DnsWatchdogService : Service() {

    companion object {
        private const val TAG = "AntiPorn_Watchdog"
        const val CHANNEL_ID = NotificationRegistry.CHANNEL_ID_WATCHDOG
        const val NOTIFICATION_ID = NotificationRegistry.FGS_WATCHDOG_ID
        private const val DEBOUNCE_DELAY_MS = 500L
        private const val SCREEN_DEBOUNCE_DELAY_MS = 500L
        // صمام أمان دوري مريح للبطارية (15 دقيقة):
        // التعدي على الـ DNS يُرصد لحظياً عبر dnsObserver (ContentObserver)،
        // وتثبيت التطبيقات يرصد فورياً عبر packageReceiver (ACTION_PACKAGE_ADDED)،
        // وفتح/قفل الشاشة يرصد لحظياً عبر screenReceiver،
        // وبالتالي فإن النبضة الدورية لا تحتاج لإيقاظ المعالج كل دقيقة.
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L // 15 دقيقة كصمام أمان دوري مريح للبطارية
        private const val CLOUD_HEARTBEAT_INTERVAL_MS = 12 * 3600 * 1000L
        private const val OTA_UPDATE_CHECK_INTERVAL_MS = 12 * 3600 * 1000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startService(context: Context) {
            if (isRunning) {
                Log.d(TAG, "DnsWatchdogService is already running, skipping startService")
                return
            }
            try {
                val intent = Intent(context, DnsWatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DnsWatchdogService", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isSyncingApprovals = AtomicBoolean(false)

    private val debounceHandler = Handler(Looper.getMainLooper())
    private val checkRunnable = Runnable {
        checkAndEnforceProtection()
    }

    private val screenDebounceRunnable = Runnable {
        Log.i(TAG, "Layer 2 - Screen event debounced check: enforcing blacklist and verifying shields")
        AppSuspensionShield.enforceBlacklistFastAsync(applicationContext)
        com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(applicationContext).syncZeroTrustBaselineAndReconcileAsync()
        checkAndEnforceProtection()
    }

    private var lastCloudHeartbeatElapsed = 0L
    private var lastOtaCheckElapsed = 0L
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "Layer 3 - Periodic heartbeat (15m): cleaning stray apps & verifying shields")
            AppSuspensionShield.enforceBlacklistFastAsync(applicationContext)
            checkAndEnforceProtection()
            TrustedTimeProvider.syncNowAsync(applicationContext)

            val nowRealtime = android.os.SystemClock.elapsedRealtime()
            if (nowRealtime - lastCloudHeartbeatElapsed >= CLOUD_HEARTBEAT_INTERVAL_MS || lastCloudHeartbeatElapsed == 0L) {
                serviceScope.launch {
                    try {
                        val res = com.drmohammed.antiporn.data.repository.AccountabilityRepository.getInstance(applicationContext).updateHeartbeat()
                        if (res.isSuccess) {
                            lastCloudHeartbeatElapsed = nowRealtime
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Watchdog cloud heartbeat update error: ${e.message}")
                    }
                }
            }

            if (nowRealtime - lastOtaCheckElapsed >= OTA_UPDATE_CHECK_INTERVAL_MS || lastOtaCheckElapsed == 0L) {
                serviceScope.launch {
                    try {
                        com.drmohammed.antiporn.ota.repository.OtaRepository.getInstance(applicationContext)
                            .checkForUpdatesAndAutoInstall(applicationContext)
                        lastOtaCheckElapsed = android.os.SystemClock.elapsedRealtime()
                    } catch (e: Exception) {
                        Log.w(TAG, "Watchdog periodic OTA update error: ${e.message}")
                    }
                }
            }

            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private var dnsObserver: ContentObserver? = null
    private val networkWatchdog = NetworkWatchdog()

    private var isPackageReceiverRegistered = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action ?: return
            val packageName = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
            Log.w(TAG, "[EVENT] [DYNAMIC_WATCHDOG_RECEIVER] onReceive: action=$action | package=$packageName")
            when (action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    PackageChangeHandler.handlePackageAdded(
                        context = applicationContext,
                        packageName = packageName,
                        isReplacing = isReplacing,
                        source = "Dynamic_Watchdog"
                    )
                }
                Intent.ACTION_PACKAGE_REMOVED -> {
                    val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    if (isReplacing) {
                        Log.i(TAG, "⏩ [DYNAMIC_WATCHDOG_RECEIVER] Package $packageName is being replaced/updated (EXTRA_REPLACING=true). Ignoring temporary removal.")
                        return
                    }
                    PackageChangeHandler.handlePackageRemoved(
                        context = applicationContext,
                        packageName = packageName,
                        isReplacing = isReplacing,
                        source = "Dynamic_Watchdog"
                    )
                }
            }
        }
    }

    private var isScreenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action
            if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
                Log.d(TAG, "Layer 2 - Screen event observed ($action). Debouncing scan...")
                debounceHandler.removeCallbacks(screenDebounceRunnable)
                debounceHandler.postDelayed(screenDebounceRunnable, SCREEN_DEBOUNCE_DELAY_MS)
                com.drmohammed.antiporn.waqfa.engine.WaqfaForegroundWatcher.getInstance(applicationContext).onScreenStateChanged(true)
            } else if (action == Intent.ACTION_SCREEN_OFF) {
                com.drmohammed.antiporn.waqfa.engine.WaqfaForegroundWatcher.getInstance(applicationContext).onScreenStateChanged(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "DnsWatchdogService created")
        startForegroundNotification()
        registerDnsObserver()
        registerNetworkWatchdog()
        registerPackageReceiver()
        registerScreenReceiver()
        startHeartbeat()
        checkAndEnforceProtection()
        AppSuspensionShield.enforceBlacklistFastAsync(applicationContext)
        com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(applicationContext).syncZeroTrustBaselineAndReconcileAsync()
        com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher.syncWatcherStateAsync(applicationContext)
        com.drmohammed.antiporn.waqfa.engine.WaqfaForegroundWatcher.getInstance(applicationContext).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        Log.i(TAG, "DnsWatchdogService started")
        startForegroundNotification()
        com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher.syncWatcherStateAsync(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.w(TAG, "DnsWatchdogService destroyed")
        stopHeartbeat()
        debounceHandler.removeCallbacks(checkRunnable)
        debounceHandler.removeCallbacks(screenDebounceRunnable)
        unregisterDnsObserver()
        unregisterPackageReceiver()
        unregisterScreenReceiver()
        com.drmohammed.antiporn.waqfa.engine.WaqfaForegroundWatcher.getInstance(applicationContext).stop()
        networkWatchdog.unregister()
        serviceScope.cancel()
        // Service cleanup complete
    }

    private fun startForegroundNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelTitle = getString(R.string.watchdog_notification_title)
        val channelDesc = getString(R.string.watchdog_notification_desc)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelTitle,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = channelDesc
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(channelTitle)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerDnsObserver() {
        if (dnsObserver != null) return

        val handler = Handler(Looper.getMainLooper())
        dnsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "DNS setting change observed: uri=$uri. Debouncing check...")
                debounceHandler.removeCallbacks(checkRunnable)
                debounceHandler.postDelayed(checkRunnable, DEBOUNCE_DELAY_MS)
            }
        }

        try {
            val modeUri = Settings.Global.getUriFor("private_dns_mode")
            val specifierUri = Settings.Global.getUriFor("private_dns_specifier")

            val observer = dnsObserver ?: return

            if (modeUri != null) {
                contentResolver.registerContentObserver(modeUri, false, observer)
            }
            if (specifierUri != null) {
                contentResolver.registerContentObserver(specifierUri, false, observer)
            }
            Log.i(TAG, "ContentObserver registered for DNS settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register ContentObserver for DNS settings", e)
        }
    }

    private fun unregisterDnsObserver() {
        dnsObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
                Log.i(TAG, "ContentObserver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister ContentObserver", e)
            }
            dnsObserver = null
        }
    }

    private fun registerNetworkWatchdog() {
        networkWatchdog.register(this) {
            Log.i(TAG, "Network became available! Debouncing protection check...")
            debounceHandler.removeCallbacks(checkRunnable)
            debounceHandler.postDelayed(checkRunnable, 1000L)
            TrustedTimeProvider.syncNowAsync(applicationContext)

            if (isSyncingApprovals.compareAndSet(false, true)) {
                serviceScope.launch {
                    try {
                        com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(applicationContext).syncPendingApprovalsToCloud()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed syncing pending approvals on network recovery: ${e.message}")
                    } finally {
                        isSyncingApprovals.set(false)
                    }
                }
            } else {
                Log.d(TAG, "Approval cloud sync already in progress, skipping duplicate trigger (network flapping guard)")
            }
        }
    }

    private fun registerPackageReceiver() {
        if (isPackageReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            // CRITICAL: System broadcasts like ACTION_PACKAGE_ADDED require RECEIVER_EXPORTED.
            // ContextCompat requires RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED on all API levels.
            ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            isPackageReceiverRegistered = true
            Log.i(TAG, "Layer 1 - Dynamic package BroadcastReceiver registered successfully (RECEIVER_EXPORTED)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register dynamic package receiver", e)
        }
    }

    private fun unregisterPackageReceiver() {
        if (!isPackageReceiverRegistered) return
        try {
            unregisterReceiver(packageReceiver)
            isPackageReceiverRegistered = false
            Log.i(TAG, "Layer 1 - Dynamic package BroadcastReceiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister dynamic package receiver: ${e.message}")
        }
    }

    private fun registerScreenReceiver() {
        if (isScreenReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            // Explicit NOT_EXPORTED required for targetSdk=36 / API 33+.
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isScreenReceiverRegistered = true
            Log.i(TAG, "Layer 2 - Screen / User Present BroadcastReceiver registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen receiver", e)
        }
    }

    private fun unregisterScreenReceiver() {
        if (!isScreenReceiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
            isScreenReceiverRegistered = false
            Log.i(TAG, "Layer 2 - Screen / User Present BroadcastReceiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister screen receiver: ${e.message}")
        }
    }

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        Log.i(TAG, "Layer 3 - Periodic heartbeat scheduled (every ${HEARTBEAT_INTERVAL_MS / 1000}s)")
    }

    private fun stopHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        Log.i(TAG, "Layer 3 - Periodic heartbeat cancelled")
    }

    private fun checkAndEnforceProtection() {
        if (!ProtectionEngine.isDeviceOwner(this)) {
            Log.d(TAG, "Not Device Owner, skipping watchdog enforcement")
            return
        }

        val desyncReasons = mutableListOf<String>()

        // 1. Surgical inspection of all registered security shields
        val shieldStatuses = ProtectionEngine.evaluateSystemStatus(this)
        for ((shieldId, status) in shieldStatuses) {
            when (status) {
                is ShieldStatus.Active -> { /* In fully active and enforced state */ }
                is ShieldStatus.Unsupported -> {
                    // System/OS hardware limitation - not a tamper event and cannot be fixed by re-enforcing
                    Log.d(TAG, "Shield [$shieldId] is unsupported on current OS API level (${status.minSdkRequired})")
                }
                is ShieldStatus.NeedsEnforcement -> {
                    desyncReasons.add("[$shieldId: ${status.reason}]")
                }
                is ShieldStatus.Disabled -> {
                    desyncReasons.add("[$shieldId: ${status.warning}]")
                }
            }
        }

        // 2. Direct Settings.Global verification for Private DNS (aware of 24/7 VPN & DoT fallback)
        try {
            val mode = Settings.Global.getString(contentResolver, "private_dns_mode")
            val specifier = Settings.Global.getString(contentResolver, "private_dns_specifier")
            val isVpnActive = com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.isRunning
            if (isVpnActive) {
                // When 24/7 VPN is active, Private DNS is set to opportunistic (or off) to use local TUN UDP 53
                val isAppropriate = mode == "opportunistic" || mode == "off" || mode == null
                if (!isAppropriate) {
                    val reason = "Settings.Global DNS mismatch: VPN is running, expected mode in (opportunistic, off) but found mode='$mode'"
                    Log.w(TAG, reason)
                    desyncReasons.add("[DNS_GLOBAL: $reason]")
                }
            } else if (mode != null) {
                val expectedHost = DnsShield.getEffectiveDnsHost(this)
                val isHostMatching = specifier?.equals(expectedHost, ignoreCase = true) == true
                val isAdultFiltering = specifier != null && ActiveShieldsEvaluator.isHostAdultFiltering(specifier)
                if (mode != "hostname" || (!isHostMatching && !isAdultFiltering)) {
                    val reason = "Settings.Global DNS mismatch (mode=$mode, specifier=$specifier, expected=$expectedHost)"
                    Log.w(TAG, reason)
                    desyncReasons.add("[DNS_GLOBAL: $reason]")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read Settings.Global directly", e)
        }

        // 3. Verify Target Timezone and Network Auto Time directly (only if time lock is active in policy)
        val isTimeLocked = try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(this)
                .getCurrentPolicy().isDateTimeLocked
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading dateTimeLocked policy", e)
            false
        }

        if (isTimeLocked) {
            try {
                val targetTzId = CurfewConfig.getTargetTimeZoneId(this)
                val targetTz = TimeZone.getTimeZone(targetTzId)
                TimeZone.setDefault(null)
                val currentTz = TimeZone.getDefault()
                val trustedTime = TrustedTimeProvider.currentTimeMillis(this)
                val isTargetTz = currentTz.id == targetTzId || currentTz.hasSameRules(targetTz) ||
                    currentTz.getOffset(trustedTime) == targetTz.getOffset(trustedTime)
                if (!isTargetTz) {
                    val tzReason = "Timezone mismatch (current=${currentTz.id}, expected=$targetTzId)"
                    Log.w(TAG, tzReason)
                    desyncReasons.add("[TIMEZONE: $tzReason]")
                }

                // Check auto-time (network clock synchronization)
                val autoTime = Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME, 1)
                if (autoTime != 1) {
                    val autoTimeReason = "Auto time disabled (autoTime=$autoTime)"
                    Log.w(TAG, autoTimeReason)
                    desyncReasons.add("[AUTO_TIME: $autoTimeReason]")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not verify Timezone or Auto Time directly in watchdog", e)
            }
        }

        // Live self-healing curfew/nap block synchronization during active periods
        if (CurfewEnforcer.isEffectiveBlockActive(this)) {
            try {
                CurfewEnforcer.syncBlockStateAsync(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error synchronizing block state in watchdog", e)
            }
        }

        val needsEnforce = desyncReasons.isNotEmpty()
        if (needsEnforce) {
            val detailedReasons = desyncReasons.joinToString(" | ")
            Log.i(TAG, "Tampering or desync detected in protection shields: $detailedReasons. Re-enforcing via ProtectionEngine...")

            // Forensic recording into Black Box Flight Recorder
            BlackBoxRecorder.record(
                context = applicationContext,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = "WATCHDOG_TAMPER_DETECTED",
                isSuccess = false,
                errorDetails = detailedReasons,
                metadata = "failedCount=${desyncReasons.size}"
            )

            ProtectionEngine.applyAllAsync(applicationContext)
            try {
                AppNotificationManager.getInstance(applicationContext).postEventNotification(
                    category = NotificationCategory.SHIELDS,
                    title = getString(R.string.notif_tamper_alert_title),
                    message = getString(R.string.notif_tamper_alert_msg),
                    dedupKey = "tamper_alert_enforce",
                    cooldownMs = 15 * 60 * 1000L,
                    actionType = NotificationActionType.NONE,
                    systemNotificationId = NotificationRegistry.EVENT_SYSTEM_TAMPER_ID,
                    channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                    postSystemNotification = true,
                    importance = 3
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error posting tamper alert notification", e)
            }
        }
        com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(applicationContext).syncZeroTrustBaselineAndReconcileAsync()
    }
}
