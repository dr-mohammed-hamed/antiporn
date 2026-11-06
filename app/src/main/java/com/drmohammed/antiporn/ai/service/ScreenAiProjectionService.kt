package com.drmohammed.antiporn.ai.service

import android.app.Notification
import android.app.NotificationChannel
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R

/**
 * Clean Foreground Service coordinating screen capture and AI modesty/nudity detection.
 * Delegates capture mechanics to [ScreenFrameStreamManager] and AI inference to [AiInferenceCoordinator].
 */
class ScreenAiProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private lateinit var streamManager: ScreenFrameStreamManager
    private lateinit var inferenceCoordinator: AiInferenceCoordinator
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "ScreenAiProjectionSvc"
        private const val CHANNEL_ID = NotificationRegistry.CHANNEL_ID_AI
        private const val NOTIFICATION_ID = NotificationRegistry.FGS_AI_PROJECTION_ID

        const val ACTION_START = "com.drmohammed.antiporn.ai.action.START"
        const val ACTION_STOP = "com.drmohammed.antiporn.ai.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isStopping: Boolean = false
            private set

        data class LastDetection(val label: String = "", val confidence: Float = 0f)

        @Volatile
        var lastDetection: LastDetection = LastDetection()
            private set

        @Volatile
        var lastDetectedLabel: String = ""
            private set

        @Volatile
        var lastConfidence: Float = 0f
            private set

        val currentFps: Float
            get() = activeStreamManager?.currentFps ?: 0f

        @Volatile
        var modelName: String = "Halalify v2 + NudeNet v3"
            private set

        val initError: String
            get() = activeInferenceCoordinator?.initError.orEmpty()

        @Volatile
        private var activeStreamManager: ScreenFrameStreamManager? = null

        @Volatile
        private var activeInferenceCoordinator: AiInferenceCoordinator? = null
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ScreenAiProjectionService onCreate")

        inferenceCoordinator = AiInferenceCoordinator(
            context = applicationContext,
            mainHandler = mainHandler,
            onDetectionUpdated = { label, confidence ->
                lastDetection = LastDetection(label, confidence)
                lastDetectedLabel = label
                lastConfidence = confidence
            }
        )
        activeInferenceCoordinator = inferenceCoordinator

        streamManager = ScreenFrameStreamManager(
            context = applicationContext,
            onFrameAcquired = { image, width, height ->
                inferenceCoordinator.processFrame(image, width, height)
            },
            onStaticFrameDetected = {
                inferenceCoordinator.onStaticScreen()
            },
            onHardSceneCutDetected = {
                inferenceCoordinator.onHardSceneCut()
            }
        )
        activeStreamManager = streamManager

        inferenceCoordinator.initialize()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        if (action == ACTION_STOP) {
            handleStopAction()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            handleStartAction(intent)
        }

        return START_NOT_STICKY
    }

    private fun handleStartAction(intent: Intent) {
        if (isRunning || isStopping) {
            Log.w(TAG, "Duplicate/reentrant ACTION_START ignored; running=$isRunning stopping=$isStopping")
            return
        }

        if (initError.isNotEmpty()) {
            Log.e(TAG, "Refusing START: detectors not ready: $initError")
            mainHandler.post {
                Toast.makeText(applicationContext, initError, Toast.LENGTH_LONG).show()
            }
            stopSelf()
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
            startForegroundWithNotification()
            initMediaProjection(resultCode, resultData)
        } else {
            Log.e(TAG, "Invalid projection token parameters")
            try {
                startForegroundWithNotification()
            } catch (e: Exception) {
                Log.w(TAG, "Failed startForeground fallback", e)
            }
            isRunning = false
            isStopping = false
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleStopAction() {
        isRunning = false
        isStopping = true

        streamManager.stop()
        inferenceCoordinator.stop()

        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification() {
        lastDetection = LastDetection()
        lastDetectedLabel = ""
        lastConfidence = 0f

        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpManager == null) {
            isRunning = false
            isStopping = false
            stopSelf()
            return
        }

        try {
            val mp = mpManager.getMediaProjection(resultCode, resultData) ?: run {
                Log.e(TAG, "getMediaProjection returned null")
                isRunning = false
                isStopping = false
                stopSelf()
                return
            }
            mediaProjection = mp

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system")
                    stopSelf()
                }
            }
            projectionCallback = callback
            mp.registerCallback(callback, mainHandler)

            inferenceCoordinator.start()
            streamManager.start(mp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
            isRunning = false
            isStopping = false
            stopSelf()
        }
    }

    private fun buildServiceNotification(): Notification {
        val stopIntent = Intent(this, ScreenAiProjectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ai_service_notification_title))
            .setContentText(getString(R.string.ai_service_notification_desc))
            .setSmallIcon(R.drawable.ic_ai_shield)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_ai_shield, getString(R.string.ai_service_action_stop), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ai_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.ai_service_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ScreenAiProjectionService onDestroy")
        isRunning = false
        isStopping = false

        streamManager.stop()
        inferenceCoordinator.stop()

        activeStreamManager = null
        activeInferenceCoordinator = null

        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        } catch (e: Exception) {
            Log.d(TAG, "unregisterCallback non-critical", e)
        }
        projectionCallback = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection", e)
        }
        mediaProjection = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
