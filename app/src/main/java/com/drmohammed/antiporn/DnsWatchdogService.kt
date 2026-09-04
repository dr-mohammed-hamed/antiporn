package com.drmohammed.antiporn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.drmohammed.antiporn.services.NetworkWatchdog
import com.drmohammed.antiporn.shields.DnsShield

class DnsWatchdogService : Service() {

    companion object {
        private const val TAG = "AntiPorn_Watchdog"
        const val CHANNEL_ID = "antiporn_watchdog"
        const val NOTIFICATION_ID = 1001
        private const val DEBOUNCE_DELAY_MS = 500L

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

    private val debounceHandler = Handler(Looper.getMainLooper())
    private val checkRunnable = Runnable {
        checkAndEnforceProtection()
    }
    private var dnsObserver: ContentObserver? = null
    private val networkWatchdog = NetworkWatchdog()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "DnsWatchdogService created")
        startForegroundNotification()
        registerDnsObserver()
        registerNetworkWatchdog()
        checkAndEnforceProtection()
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
        debounceHandler.removeCallbacks(checkRunnable)
        unregisterDnsObserver()
        networkWatchdog.unregister()
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
                if (mode != "hostname" || specifier != DnsShield.NEXTDNS_HOST) {
                    Log.w(TAG, "Settings.Global reports DNS mismatch: mode=$mode, specifier=$specifier")
                    needsEnforce = true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read Settings.Global directly", e)
        }

        if (needsEnforce) {
            Log.i(TAG, "Tampering or desync detected in protection shields! Re-enforcing via ProtectionEngine...")
            ProtectionEngine.applyAllAsync(applicationContext)
        }
    }
}
