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

/**
 * Silent kernel-level Blackhole VPN service running during night curfew.
 * Establishes a non-forwarding TUN interface taking all IPv4 and IPv6 routes (0.0.0.0/0 and ::/0).
 * Combined with DevicePolicyManager lockdown mode, it guarantees 100% data blackout across
 * all network interfaces (Wi-Fi, Mobile Data), without impacting cellular voice calls or SMS.
 *
 * Prepared for dynamic package exemptions via [CurfewConfig.getVpnBypassedPackages].
 */
class CurfewBlackholeVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Guaranteed FGS compliance: Always bring foreground notification up first on Android 8+
        startForegroundNotification()

        val action = intent?.action
        if (action == ACTION_STOP) {
            if (CurfewEnforcer.isEffectiveCurfewActive(this)) {
                Log.w(TAG, "Security Alert: Rejected ACTION_STOP because Curfew is still actively enforced!")
                establishTunnel()
                return START_STICKY
            }
            Log.i(TAG, "Received ACTION_STOP and curfew is inactive. Tearing down Blackhole VPN.")
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        // Verify that curfew should effectively be running
        if (!CurfewEnforcer.isEffectiveCurfewActive(this)) {
            Log.w(TAG, "Blackhole VPN started but curfew is inactive; stopping service")
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        establishTunnel()
        return START_STICKY
    }

    private fun establishTunnel() {
        try {
            vpnInterface?.close()
            vpnInterface = null

            val sessionName = getString(R.string.curfew_vpn_session_name)
            val builder = Builder()
                .setSession(sessionName)
                .addAddress("10.255.255.1", 30)
                .addAddress("fd00::1", 126)
                .addDnsServer("10.255.255.1")
                .addDnsServer("fd00::1")
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .setMtu(1500)
                .setBlocking(true)

            // Extensible Bypass Architecture:
            // Query configured bypassed packages and allow them to route outside the blackhole TUN
            val bypassedPackages = CurfewConfig.getVpnBypassedPackages(this)
            var bypassedCount = 0
            for (pkg in bypassedPackages) {
                try {
                    builder.addDisallowedApplication(pkg)
                    bypassedCount++
                    Log.i(TAG, "Exempted package from Curfew VPN: $pkg")
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Bypassed package not installed on device: $pkg")
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding disallowed application for $pkg", e)
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                isRunning = true
                Log.i(TAG, "Blackhole TUN interface established successfully. 100% internet blackout enforced ($bypassedCount bypassed packages).")
            } else {
                isRunning = false
                Log.e(TAG, "Failed to establish Blackhole TUN interface (builder.establish() returned null)")
            }
        } catch (e: Exception) {
            isRunning = false
            Log.e(TAG, "Exception while establishing Blackhole VPN tunnel", e)
        }
    }

    private fun startForegroundNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelTitle = getString(R.string.curfew_vpn_channel_name)
        val channelDesc = getString(R.string.curfew_vpn_channel_desc)
        val notifTitle = getString(R.string.curfew_vpn_notification_title)
        val notifDesc = getString(R.string.curfew_vpn_notification_desc)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelTitle,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = channelDesc
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notifTitle)
            .setContentText(notifDesc)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopVpn() {
        isRunning = false
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
        Log.w(TAG, "Blackhole VPN revoked by system or user")
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "CurfewBlackholeVpnService destroyed")
        stopVpn()
    }

    companion object {
        private const val TAG = "AntiPorn_BlackholeVpn"
        const val CHANNEL_ID = "antiporn_curfew_vpn"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.drmohammed.antiporn.curfew.ACTION_START_VPN"
        const val ACTION_STOP = "com.drmohammed.antiporn.curfew.ACTION_STOP_VPN"

        @Volatile
        var isRunning: Boolean = false
            private set

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
                Log.i(TAG, "Requested stop of CurfewBlackholeVpnService")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop CurfewBlackholeVpnService", e)
            }
        }
    }
}
