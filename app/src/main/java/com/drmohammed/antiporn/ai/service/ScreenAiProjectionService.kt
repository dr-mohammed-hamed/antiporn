package com.drmohammed.antiporn.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.ai.core.inference.IModestyDetector
import com.drmohammed.antiporn.ai.core.inference.MobileNetV4ModestyDetector
import com.drmohammed.antiporn.ai.core.inference.TfLiteModestyDetector
import com.drmohammed.antiporn.ai.core.pipeline.ContextualGatingWatcher
import com.drmohammed.antiporn.ai.core.pipeline.FrameDifferencer
import com.drmohammed.antiporn.ai.core.pipeline.RowStrideExtractor

/**
 * خدمة التقاط الشاشة والاستدلال بالذكاء الاصطناعي في الخلفية
 * مصممة بدقة لتفادي ألغام أندرويد 14+ وواجهة ريلمي
 */
class ScreenAiProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var detector: IModestyDetector
    private lateinit var rowStrideExtractor: RowStrideExtractor
    private lateinit var frameDifferencer: FrameDifferencer
    private lateinit var contextualWatcher: ContextualGatingWatcher

    private var lastAlertTimeMs: Long = 0L

    companion object {
        private const val TAG = "ScreenAiProjectionSvc"
        private const val CHANNEL_ID = "antiporn_ai_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ALERT_DEBOUNCE_MS = 5000L // خافق التكرار لمنع الإغراق

        const val ACTION_START = "com.drmohammed.antiporn.ai.action.START"
        const val ACTION_STOP = "com.drmohammed.antiporn.ai.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * CRITICAL(C3): re-entrancy guard — ACTION_STOP sets this synchronously so a
         * rapid STOP→START cannot overwrite live VirtualDisplay/ImageReader before
         * async onDestroy tears them down. Cleared at end of onDestroy.
         */
        @Volatile
        var isStopping: Boolean = false
            private set

        /** Atomic snapshot — avoids torn label/confidence pair across bg/UI threads. */
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

        @Volatile
        var currentFps: Float = 0f
            private set

        /** اسم الموديل النشط للعرض في بطاقة التجربة. */
        @Volatile
        var modelName: String = MobileNetV4ModestyDetector.MODEL_DISPLAY_NAME
            private set

        /** رسالة فشل التهيئة (فارغة = نجاح) — تُعرض بالأحمر بدل الفشل الصامت. */
        @Volatile
        var initError: String = ""
            private set

        private var frameCounter: Int = 0
        private var fpsTimerStart: Long = 0L
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ScreenAiProjectionService onCreate")

        val thread = HandlerThread("AiProjectionWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)

        // النموذج الوحيد للتجربة: MobileNetV4 (للتبديل إلى YOLO بدّل السطر التالي فقط —
        // الأبعاد والتطبيع تُشتق من واجهة IModestyDetector، بلا downcast).
        detector = MobileNetV4ModestyDetector()
        val initOk = detector.initialize(applicationContext)
        if (!initOk) {
            initError = applicationContext.getString(R.string.ai_init_failed_desc, detector.inputWidth)
            Log.e(TAG, "AI detector init FAILED: $initError")
        } else {
            initError = ""
            Log.i(TAG, "AI detector ready: $modelName ${detector.inputWidth}x${detector.inputHeight}")
        }

        rowStrideExtractor = RowStrideExtractor(
            width = detector.inputWidth,
            height = detector.inputHeight,
            isChannelsLast = detector.isChannelsLast,
            normalization = if (detector is TfLiteModestyDetector)
                RowStrideExtractor.Normalization.UNIT_01
            else
                RowStrideExtractor.Normalization.IMAGENET_MEAN_STD
        )
        frameDifferencer = FrameDifferencer()

        contextualWatcher = ContextualGatingWatcher(
            applicationContext,
            onStateChanged = { _, _ -> frameDifferencer.reset() }
        )
        contextualWatcher.start()

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        if (action == ACTION_STOP) {
            isRunning = false
            // CRITICAL(C3): stop frame flow synchronously — onDestroy is async and a rapid
            // STOP→START would otherwise overwrite live handles (leak + double VirtualDisplay).
            isStopping = true
            try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) { }
            try { virtualDisplay?.release() } catch (e: Exception) { Log.e(TAG, "Error releasing display on STOP", e) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            // حماية إعادة الدخول: تجاهل START مكرر أثناء التشغيل أو أثناء STOP جارٍ.
            if (isRunning || isStopping) {
                Log.w(TAG, "Duplicate/reentrant ACTION_START ignored; running=$isRunning stopping=$isStopping")
                return START_NOT_STICKY
            }
            // CRITICAL(C4): fail-closed — never hold MediaProjection while detector is dead.
            if (initError.isNotEmpty() || !::detector.isInitialized) {
                Log.e(TAG, "Refusing START: detector not ready: $initError")
                val msg = initError.ifEmpty { getString(R.string.ai_init_failed_desc, detector.inputWidth) }
                mainHandler.post {
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                }
                stopSelf()
                return START_NOT_STICKY
            }
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }

            if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
                // 1. استدعاء startForeground أولاً لتفادي SecurityException في أندرويد 14+
                startForegroundWithNotification()

                // 2. تهيئة MediaProjection بعد اكتمال الخدمة الأمامية
                initMediaProjection(resultCode, resultData)
            } else {
                Log.e(TAG, "Invalid projection token parameters")
                // MAJOR: startForegroundService() requires startForeground before stopSelf
                // or the system throws ForegroundServiceDidNotStartInTimeException.
                try {
                    startForegroundWithNotification()
                } catch (_: Exception) { }
                isRunning = false
                isStopping = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        // MAJOR(S3): clear stale cross-session state on every START (detections/FPS/error).
        lastDetection = LastDetection()
        lastDetectedLabel = ""
        lastConfidence = 0f
        currentFps = 0f
        frameCounter = 0
        fpsTimerStart = 0L
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
            mp.registerCallback(callback, backgroundHandler)

            setupVirtualDisplay(mp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
            isRunning = false
            isStopping = false
            stopSelf()
        }
    }

    private fun setupVirtualDisplay(mp: MediaProjection) {
        // أبعاد الدخل تُحسم من واجهة IModestyDetector runtime — لا أرقام صلبة ولا downcast.
        val width = detector.inputWidth
        val height = detector.inputHeight
        val density = resources.displayMetrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ imageReaderInstance ->
            // CRITICAL(C3): drop frames synchronously after STOP — onDestroy is async.
            if (!isRunning || isStopping) {
                try { imageReaderInstance.acquireLatestImage()?.close() } catch (_: Exception) { }
                return@setOnImageAvailableListener
            }
            val image = try {
                imageReaderInstance.acquireLatestImage()
            } catch (e: Exception) {
                Log.d(TAG, "acquireLatestImage failed (reader closing?)", e)
                null
            } ?: return@setOnImageAvailableListener

            try {
                // صمام الأمان: إهمال فوري للإطار إذا كان المستخدم في تطبيق آمن
                if (!contextualWatcher.isTargetAppForeground) {
                    return@setOnImageAvailableListener
                }

                // حساب الـ FPS
                trackFps()

                // المستوى 0: التحقق مما إذا كانت الشاشة ثابتة عبر البايتات الخام RGBA مباشرة
                val planes = image.planes
                if (planes.isEmpty()) {
                    Log.d(TAG, "Frame has no planes; dropping")
                    return@setOnImageAvailableListener
                }
                val plane = planes[0]
                if (!frameDifferencer.hasScreenChanged(
                        rawBuffer = plane.buffer,
                        width = width,
                        height = height,
                        rowStride = plane.rowStride,
                        pixelStride = plane.pixelStride
                    )) {
                    return@setOnImageAvailableListener
                }

                // استخراج البكسلات سطراً بسطر متجاوزاً حشو الـ rowStride (تطبيع ImageNet داخل المستخرج)
                val buffer = rowStrideExtractor.extract(image)

                // المستوى الوحيد للتجربة: مصنف MobileNetV4 → درجة شبهة
                val result = detector.detect(buffer)

                if (result.hasViolation && result.topViolation != null) {
                    val violation = result.topViolation
                    // MAJOR(S3): atomic snapshot — UI reads label+confidence together, never torn.
                    val snapshot = LastDetection(violation.classType.labelAr, violation.confidence)
                    lastDetection = snapshot
                    lastDetectedLabel = snapshot.label
                    lastConfidence = snapshot.confidence

                    val dbgTop = (detector as? MobileNetV4ModestyDetector)?.lastTopClassId ?: -1
                    Log.w(TAG, "Suspicion=${violation.confidence} topClass=$dbgTop latency=${result.latencyMs}ms")
                    triggerAlertThrottled(violation.classType.labelAr, violation.confidence)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in frame processing loop", e)
            } finally {
                // الإغلاق الإلزامي لتفادي تجويع الذاكرة
                image.close()
            }
        }, backgroundHandler)

        virtualDisplay = mp.createVirtualDisplay(
            "ModestyAiVirtualDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            backgroundHandler
        )

        Log.i(TAG, "VirtualDisplay created successfully at ${width}x${height}")
    }

    private fun trackFps() {
        frameCounter++
        val now = SystemClock.elapsedRealtime()
        if (fpsTimerStart == 0L) {
            fpsTimerStart = now
        } else if (now - fpsTimerStart >= 1000L) {
            currentFps = (frameCounter * 1000f) / (now - fpsTimerStart)
            frameCounter = 0
            fpsTimerStart = now
        }
    }

    private fun triggerAlertThrottled(label: String, confidence: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlertTimeMs < ALERT_DEBOUNCE_MS) {
            return
        }
        lastAlertTimeMs = now

        val confidencePercent = (confidence * 100).toInt()
        val message = getString(R.string.ai_alert_toast_format, label, confidencePercent)

        // 1. نبضة اهتزاز لمسية فورية (150ms) لكسر كتم واجهة ريلمي
        triggerHapticPulse()

        // 2. إظهار رسالة Toast على الخيط الرئيسي
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }

        Log.w(TAG, "ALERT TRIGGERED: $message")
    }

    private fun triggerHapticPulse() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(150L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(150L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger haptic pulse", e)
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
        currentFps = 0f

        if (::contextualWatcher.isInitialized) {
            contextualWatcher.stop()
        }

        // 1. إيقاف تدفق الإطارات أولاً وهدم العرض الافتراضي (متزامن على main — سريع).
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtual display or listener", e)
        }
        // MINOR: cancel stale Toast/alert callbacks queued just before STOP.
        mainHandler.removeCallbacksAndMessages(null)

        // 2. CRITICAL(C6): never join() on main (ANR). Drain worker WITHOUT blocking:
        // close detector ON the worker at front of queue, then quit. Never null a live handler
        // while in-flight tasks run, and never imageReader.close() while worker is in extract().
        val worker = backgroundThread
        val workerHandler = backgroundHandler
        backgroundThread = null
        backgroundHandler = null
        if (worker != null && worker.isAlive && workerHandler != null) {
            workerHandler.postAtFrontOfQueue {
                try {
                    if (::detector.isInitialized) detector.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing detector on worker", e)
                } finally {
                    try { worker.quitSafely() } catch (_: Exception) { }
                }
                try {
                    imageReader?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing image reader on worker", e)
                } finally {
                    imageReader = null
                }
            }
            // Best-effort fallback: force quit after 3s WITHOUT blocking main.
            mainHandler.postDelayed({
                try { if (worker.isAlive) worker.quit() } catch (_: Exception) { }
            }, 3000L)
        } else {
            if (::detector.isInitialized) {
                try { detector.close() } catch (e: Exception) {
                    Log.e(TAG, "Error closing detector", e)
                }
            }
            try { imageReader?.close() } catch (e: Exception) {
                Log.e(TAG, "Error closing image reader", e)
            } finally { imageReader = null }
        }

        try {
            try {
                projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
            } catch (e: Exception) {
                Log.d(TAG, "unregisterCallback failed", e)
            }
            projectionCallback = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
