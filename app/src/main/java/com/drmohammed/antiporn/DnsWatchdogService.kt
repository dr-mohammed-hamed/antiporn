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
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.services.NetworkWatchdog
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.DnsShield
import java.util.TimeZone
import java.util.concurrent.Executors

class DnsWatchdogService : Service() {

    companion object {
        private const val TAG = "AntiPorn_Watchdog"
        const val CHANNEL_ID = "antiporn_watchdog"
        const val NOTIFICATION_ID = 1001
        private const val DEBOUNCE_DELAY_MS = 500L
        private const val SCREEN_DEBOUNCE_DELAY_MS = 500L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L

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

    private val bgExecutor = Executors.newSingleThreadExecutor()

    private val debounceHandler = Handler(Looper.getMainLooper())
    private val checkRunnable = Runnable {
        checkAndEnforceProtection()
    }

    private val screenDebounceRunnable = Runnable {
        Log.i(TAG, "Layer 2 - Screen event debounced check: enforcing blacklist and verifying shields")
        AppSuspensionShield.enforceBlacklistFastAsync(applicationContext)
        checkAndEnforceProtection()
    }

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "Layer 3 - Periodic heartbeat (60s): cleaning stray apps & verifying shields")
            AppSuspensionShield.enforceBlacklistFastAsync(applicationContext)
            checkAndEnforceProtection()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private var dnsObserver: ContentObserver? = null
    private val networkWatchdog = NetworkWatchdog()

    private var isPackageReceiverRegistered = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action
            if (action == Intent.ACTION_PACKAGE_ADDED) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                if (AppSuspensionShield.isBlacklisted(packageName)) {
                    Log.i(TAG, "Layer 1 - Dynamic package receiver detected blacklisted package: $packageName")
                    try {
                        bgExecutor.execute {
                            try {
                                AppSuspensionShield.handlePackageAdded(applicationContext, packageName)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling added package $packageName in watchdog", e)
                            }
                        }
                    } catch (e: java.util.concurrent.RejectedExecutionException) {
                        Log.w(TAG, "Watchdog bgExecutor rejected task during shutdown for $packageName", e)
                    }
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        Log.i(TAG, "DnsWatchdogService started")
        startForegroundNotification()
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
        CurfewForegroundWatcher.stop()
        networkWatchdog.unregister()
        try {
            bgExecutor.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down bgExecutor", e)
        }
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
            .setContentText(channelDesc)
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
            // CRITICAL(C1): explicit NOT_EXPORTED required for targetSdk=36 / API 33+.
            ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isPackageReceiverRegistered = true
            Log.i(TAG, "Layer 1 - Dynamic package BroadcastReceiver registered successfully")
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
                addAction(Intent.ACTION_USER_PRESENT)
            }
            // CRITICAL(C1): explicit NOT_EXPORTED required for targetSdk=36 / API 33+.
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

        var needsEnforce = !ProtectionEngine.isSystemFullyProtected(this)

        // Verify Settings.Global directly for DNS settings
        try {
            val mode = Settings.Global.getString(contentResolver, "private_dns_mode")
            val specifier = Settings.Global.getString(contentResolver, "private_dns_specifier")
            if (mode != null && specifier != null) {
                val expectedHost = DnsShield.getEffectiveDnsHost(this)
                if (mode != "hostname" || specifier != expectedHost) {
                    Log.w(TAG, "Settings.Global reports DNS mismatch: mode=$mode, specifier=$specifier, expected=$expectedHost")
                    needsEnforce = true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read Settings.Global directly", e)
        }

        // Verify Makkah Timezone (Asia/Riyadh, GMT+3) and Network Auto Time directly
        try {
            val currentTz = TimeZone.getDefault()
            val isMakkahTz = currentTz.id == CurfewConfig.MAKKAH_TIMEZONE_ID || currentTz.rawOffset == 3 * 3600 * 1000
            if (!isMakkahTz) {
                Log.w(TAG, "Timezone mismatch detected in watchdog: current=${currentTz.id}, expected=${CurfewConfig.MAKKAH_TIMEZONE_ID}")
                needsEnforce = true
            }

            val autoTime = Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME, 1)
            val autoTimeZone = Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME_ZONE, 0)
            if (autoTime != 1 || autoTimeZone != 0) {
                Log.w(TAG, "Auto time settings mismatch in watchdog: autoTime=$autoTime, autoTimeZone=$autoTimeZone")
                needsEnforce = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not verify Timezone or Auto Time directly in watchdog", e)
        }

        // Live self-healing curfew/nap block synchronization during active periods
        if (CurfewEnforcer.isEffectiveBlockActive(this)) {
            try {
                CurfewEnforcer.syncBlockStateAsync(this)
                CurfewForegroundWatcher.start(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error synchronizing block state in watchdog", e)
            }
        } else {
            CurfewForegroundWatcher.stop()
        }

        if (needsEnforce) {
            Log.i(TAG, "Tampering or desync detected in protection shields! Re-enforcing via ProtectionEngine...")
            ProtectionEngine.applyAllAsync(applicationContext)
        }
    }
}
