package com.drmohammed.antiporn.provisioning.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.provisioning.engine.AdbMdnsDiscovery
import com.drmohammed.antiporn.provisioning.receiver.PairingCodeReceiver
import timber.log.Timber

/**
 * Foreground Service that manages mDNS auto-discovery, live settings step-by-step
 * guidance via ContentObserver, and surfaces the high-priority Heads-Up pairing prompt.
 */
class AdbPairingNotificationService : Service() {

    private var mdnsDiscovery: AdbMdnsDiscovery? = null
    private var capturedPairingPort = 0
    private var capturedConnectPort = 0
    private var adbWifiObserver: ContentObserver? = null
    private var lastPromptedPairingPort = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        registerAdbWifiObserver()
        try {
            val initialNotification = buildGuideNotification(isWirelessAdbEnabled())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to promote service to foreground in onCreate")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        startMdnsRadar()
        return START_STICKY
    }

    private fun registerAdbWifiObserver() {
        try {
            val uri: Uri = Settings.Global.getUriFor("adb_wifi_enabled")
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val isEnabled = isWirelessAdbEnabled()
                    Timber.tag(TAG).i("Observed adb_wifi_enabled change: $isEnabled")
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    notificationManager?.notify(NOTIFICATION_ID, buildGuideNotification(isEnabled))
                }
            }
            adbWifiObserver = observer
            contentResolver.registerContentObserver(uri, false, observer)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed registering ContentObserver for adb_wifi_enabled: ${e.message}")
        }
    }

    private fun isWirelessAdbEnabled(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (_: Exception) {
            false
        }
    }

    private fun startMdnsRadar() {
        if (capturedPairingPort > 0 || capturedConnectPort > 0) {
            notifyPortState()
        }
        mdnsDiscovery?.stopDiscovery()
        mdnsDiscovery = AdbMdnsDiscovery(
            context = this,
            onPairingPortDiscovered = { port ->
                capturedPairingPort = port
                notifyPortState()
            },
            onConnectPortDiscovered = { port ->
                capturedConnectPort = port
                notifyPortState()
            }
        ).also { it.startDiscovery() }
    }

    private fun notifyPortState() {
        Companion.capturedPairingPort = capturedPairingPort
        Companion.capturedConnectPort = capturedConnectPort
        Timber.tag(TAG).i("Port state updated: pairing=$capturedPairingPort, connect=$capturedConnectPort")

        // Broadcast to Activity if open
        val broadcastIntent = Intent(ACTION_PORTS_DISCOVERED).apply {
            setPackage(packageName)
            putExtra(EXTRA_PAIRING_PORT, capturedPairingPort)
            putExtra(EXTRA_CONNECT_PORT, capturedConnectPort)
        }
        sendBroadcast(broadcastIntent)

        // Uncoupled Trigger: Surface Heads-Up Prompt only once when a new pairingPort is ready
        if (capturedPairingPort > 0 && capturedPairingPort != lastPromptedPairingPort) {
            lastPromptedPairingPort = capturedPairingPort
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(PAIRING_PROMPT_NOTIFICATION_ID, buildPairingPromptNotification())
        }
    }

    private fun buildGuideNotification(isWirelessAdbOn: Boolean): android.app.Notification {
        val message = if (isWirelessAdbOn) {
            getString(R.string.provisioning_guide_notif_toggle_done)
        } else {
            getString(R.string.provisioning_guide_notif_waiting_toggle)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID_GUIDE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.provisioning_guide_notif_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun buildPairingPromptNotification(): android.app.Notification {
        val remoteInput = RemoteInput.Builder(PairingCodeReceiver.EXTRA_PAIRING_CODE)
            .setLabel(getString(R.string.provisioning_input_label))
            .build()

        val replyIntent = Intent(this, PairingCodeReceiver::class.java).apply {
            putExtra(PairingCodeReceiver.EXTRA_PAIRING_PORT, capturedPairingPort)
            putExtra(PairingCodeReceiver.EXTRA_CONNECT_PORT, capturedConnectPort)
        }

        val replyPendingIntent = PendingIntent.getBroadcast(
            this,
            101,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_input_add,
            getString(R.string.provisioning_action_submit),
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val promptDesc = getString(R.string.provisioning_prompt_desc, capturedPairingPort)

        return NotificationCompat.Builder(this, CHANNEL_ID_HEADS_UP)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.provisioning_prompt_title))
            .setContentText(promptDesc)
            .setStyle(NotificationCompat.BigTextStyle().bigText(promptDesc))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVibrate(VIBRATION_PATTERN)
            .addAction(action)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return

            // 1. Ongoing Guide Channel (Low Priority, Quiet)
            val guideChannel = NotificationChannel(
                CHANNEL_ID_GUIDE,
                getString(R.string.provisioning_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.provisioning_channel_desc)
            }

            // 2. Heads-Up Prompt Channel (High Priority with Vibration)
            val headsUpChannel = NotificationChannel(
                CHANNEL_ID_HEADS_UP,
                getString(R.string.provisioning_prompt_title),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.provisioning_channel_desc)
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            }

            manager.createNotificationChannel(guideChannel)
            manager.createNotificationChannel(headsUpChannel)
        }
    }

    private fun stopForegroundAndSelf() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(PAIRING_PROMPT_NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error stopping foreground on ACTION_STOP_SERVICE")
        }
        stopSelf()
    }

    override fun onDestroy() {
        adbWifiObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
            adbWifiObserver = null
        }
        mdnsDiscovery?.stopDiscovery()
        mdnsDiscovery = null
        capturedPairingPort = 0
        capturedConnectPort = 0
        Companion.capturedPairingPort = 0
        Companion.capturedConnectPort = 0
        lastPromptedPairingPort = 0
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(PAIRING_PROMPT_NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AntiPorn_PairingSvc"
        const val CHANNEL_ID_GUIDE = NotificationRegistry.CHANNEL_ID_PAIRING
        const val CHANNEL_ID_HEADS_UP = "provisioning_pairing_heads_up_v2"

        const val NOTIFICATION_ID = NotificationRegistry.ADB_PAIRING_ID
        const val PAIRING_PROMPT_NOTIFICATION_ID = 9902
        private val VIBRATION_PATTERN = longArrayOf(0, 250, 100, 250)

        @Volatile var capturedPairingPort: Int = 0
        @Volatile var capturedConnectPort: Int = 0

        const val ACTION_START_SERVICE = "com.drmohammed.antiporn.START_PAIRING_SERVICE"
        const val ACTION_STOP_SERVICE = "com.drmohammed.antiporn.STOP_PAIRING_SERVICE"

        const val ACTION_PORTS_DISCOVERED = "com.drmohammed.antiporn.PORTS_DISCOVERED"
        const val EXTRA_PAIRING_PORT = "extra_pairing_port"
        const val EXTRA_CONNECT_PORT = "extra_connect_port"

        fun start(context: Context) {
            val intent = Intent(context, AdbPairingNotificationService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdbPairingNotificationService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
