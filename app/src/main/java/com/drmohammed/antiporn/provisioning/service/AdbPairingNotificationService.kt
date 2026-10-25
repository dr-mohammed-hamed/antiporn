package com.drmohammed.antiporn.provisioning.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.provisioning.engine.AdbMdnsDiscovery
import com.drmohammed.antiporn.provisioning.receiver.PairingCodeReceiver

/**
 * Foreground Service that manages mDNS auto-discovery and surfaces
 * the interactive notification with RemoteInput for effortless pairing.
 */
class AdbPairingNotificationService : Service() {

    private var mdnsDiscovery: AdbMdnsDiscovery? = null
    private var capturedPairingPort = 0
    private var capturedConnectPort = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground immediately
        val initialNotification = buildInitialNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        startMdnsRadar()

        return START_STICKY
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
        Log.i(TAG, "Port state updated: pairing=$capturedPairingPort, connect=$capturedConnectPort")

        // Broadcast to Activity if open
        val broadcastIntent = Intent(ACTION_PORTS_DISCOVERED).apply {
            setPackage(packageName)
            putExtra(EXTRA_PAIRING_PORT, capturedPairingPort)
            putExtra(EXTRA_CONNECT_PORT, capturedConnectPort)
        }
        sendBroadcast(broadcastIntent)

        // If both pairing port and connect port are ready, update notification with RemoteInput
        if (capturedPairingPort > 0 && capturedConnectPort > 0) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildPairingPromptNotification())
        }
    }

    private fun buildInitialNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(getString(R.string.provisioning_radar_active_title))
        .setContentText(getString(R.string.provisioning_radar_active_desc))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.provisioning_prompt_title))
            .setContentText(getString(R.string.provisioning_prompt_desc, capturedPairingPort))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(action)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.provisioning_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.provisioning_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        mdnsDiscovery?.stopDiscovery()
        mdnsDiscovery = null
        capturedPairingPort = 0
        capturedConnectPort = 0
        Companion.capturedPairingPort = 0
        Companion.capturedConnectPort = 0
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AntiPorn_PairingSvc"
        const val CHANNEL_ID = "anti_porn_pairing_channel"
        const val NOTIFICATION_ID = 4004

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
            val intent = Intent(context, AdbPairingNotificationService::class.java)
            context.stopService(intent)
        }
    }
}
