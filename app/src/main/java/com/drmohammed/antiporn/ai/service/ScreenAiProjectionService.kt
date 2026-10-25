package com.drmohammed.antiporn.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
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
import com.drmohammed.antiporn.ai.core.inference.HalalifyGenderDetector
import com.drmohammed.antiporn.ai.core.inference.NudeNetDetector
import com.drmohammed.antiporn.ai.core.model.NudeNetDetection
import com.drmohammed.antiporn.ai.core.model.NudeNetResult
import com.drmohammed.antiporn.ai.core.pipeline.ContextualGatingWatcher
import com.drmohammed.antiporn.ai.core.pipeline.FrameDifferencer
import com.drmohammed.antiporn.ai.core.tracking.KltOpticalFlowTracker
import com.drmohammed.antiporn.ai.diagnostic.TelegramAlertDispatcher
import com.drmohammed.antiporn.ai.overlay.ScreenBlurOverlayManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

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

    private var inferenceThread: HandlerThread? = null
    private var inferenceHandler: Handler? = null

    private lateinit var kltTracker: KltOpticalFlowTracker
    private lateinit var halalifyDetector: HalalifyGenderDetector
    private lateinit var nudeNetDetector: NudeNetDetector
    private lateinit var blurOverlayManager: ScreenBlurOverlayManager
    private lateinit var frameDifferencer: FrameDifferencer
    private lateinit var contextualWatcher: ContextualGatingWatcher

    // صمام إسقاط الإطارات لمنع تراكم الذاكرة وتأخر استجابة الهاتف (Drop Latest)
    private val isProcessingFrame = AtomicBoolean(false)
    private val isHalalifyInferring = AtomicBoolean(false)
    private var lastAnchorCheckTimeMs: Long = 0L

    private var inferenceBitmap: Bitmap? = null
    private var scratchPixels: IntArray? = null
    private var scratchRowBytes: ByteArray? = null

    private var lastAlertTimeMs: Long = 0L
    private var lastDiagnosticLogMs: Long = 0L
    private var lastHalalifyIdleLogMs: Long = 0L
    private var lastOverlayRenderTimeMs: Long = 0L

    companion object {
        private const val TAG = "ScreenAiProjectionSvc"
        private const val CHANNEL_ID = "antiporn_ai_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ALERT_DEBOUNCE_MS = 5000L // خافق التكرار لمنع الإغراق
        const val ANCHOR_INTERVAL_MS = 200L

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
        var modelName: String = "Halalify v2 + NudeNet v3"
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

        kltTracker = KltOpticalFlowTracker()

        val infThread = HandlerThread("AiInferenceWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        infThread.start()
        inferenceThread = infThread
        inferenceHandler = Handler(infThread.looper)

        halalifyDetector = HalalifyGenderDetector(applicationContext)
        nudeNetDetector = NudeNetDetector()
        inferenceHandler?.post {
            val nudeOk = nudeNetDetector.initialize(applicationContext)
            if (!nudeOk) {
                initError = "NudeNet initialization failed"
                Log.e(TAG, "NudeNetDetector initialization failed")
            } else {
                initError = ""
                Log.i(TAG, "AI detectors ready: $modelName")
            }
        }

        blurOverlayManager = ScreenBlurOverlayManager(applicationContext)
        frameDifferencer = FrameDifferencer()

        contextualWatcher = ContextualGatingWatcher(
            applicationContext,
            onStateChanged = { isTarget, _ ->
                frameDifferencer.reset()
                if (!isTarget) {
                    if (::kltTracker.isInitialized) {
                        kltTracker.flush()
                    }
                    if (::blurOverlayManager.isInitialized) {
                        blurOverlayManager.dismissFast()
                    }
                }
            }
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
            isProcessingFrame.set(false)
            isHalalifyInferring.set(false)
            if (::kltTracker.isInitialized) {
                kltTracker.flush()
            }
            try { imageReader?.setOnImageAvailableListener(null, null) } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
            try { virtualDisplay?.release() } catch (e: Exception) { Log.e(TAG, "Error releasing display on STOP", e) }
            if (::blurOverlayManager.isInitialized) {
                try {
                    blurOverlayManager.detachOverlay()
                    blurOverlayManager.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing blurOverlayManager on STOP", e)
                }
            }
            val infWorker = inferenceThread
            val infHandler = inferenceHandler
            inferenceThread = null
            inferenceHandler = null
            if (infWorker != null && infWorker.isAlive && infHandler != null) {
                infHandler.removeCallbacksAndMessages(null)
                infHandler.post {
                    try { inferenceBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
                    inferenceBitmap = null
                    try { infWorker.quitSafely() } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
                }
            } else {
                try { inferenceBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
                inferenceBitmap = null
            }

            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            // حماية إعادة الدخول: تجاهل START مكرر أثناء التشغيل أو أثناء STOP جارٍ.
            if (isRunning || isStopping) {
                Log.w(TAG, "Duplicate/reentrant ACTION_START ignored; running=$isRunning stopping=$isStopping")
                return START_NOT_STICKY
            }
            // CRITICAL(C4): fail-closed — never hold MediaProjection while detectors are dead.
            if (initError.isNotEmpty() || !::halalifyDetector.isInitialized || !::nudeNetDetector.isInitialized) {
                Log.e(TAG, "Refusing START: detectors not ready: $initError")
                val msg = initError.ifEmpty { getString(R.string.ai_init_failed_desc, NudeNetDetector.MODEL_INPUT_SIZE) }
                mainHandler.post {
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                }
                stopSelf()
                return START_NOT_STICKY
            }

            // تأكد من جاهزية خيوط المعالجة في حال تم إيقاف الخدمة وإعادة تشغيلها
            if (inferenceThread == null || inferenceThread?.isAlive != true) {
                val infThread = HandlerThread("AiInferenceWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND)
                infThread.start()
                inferenceThread = infThread
                inferenceHandler = Handler(infThread.looper)
            }
            if (backgroundThread == null || backgroundThread?.isAlive != true) {
                val bgThread = HandlerThread("AiProjectionWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND)
                bgThread.start()
                backgroundThread = bgThread
                backgroundHandler = Handler(bgThread.looper)
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
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start foreground notification on error fallback", e)
                }
                isRunning = false
                isStopping = false
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
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
        isProcessingFrame.set(false)
        isHalalifyInferring.set(false)
        lastAnchorCheckTimeMs = 0L
        if (::kltTracker.isInitialized) {
            kltTracker.flush()
        }
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
        val displayMetrics = resources.displayMetrics
        val realWidth = displayMetrics.widthPixels.coerceAtLeast(1)
        val realHeight = displayMetrics.heightPixels.coerceAtLeast(1)
        // أبعاد العرض الافتراضي مطابقة تماماً لنسبة أبعاد الشاشة الحقيقية لتفادي أشرطة السواد والتشويه الهندسي
        val width = 416.coerceAtMost(realWidth)
        val height = ((realHeight.toFloat() * width) / realWidth).roundToInt().coerceAtLeast(1)
        val density = displayMetrics.densityDpi

        Log.i(TAG, "Creating VirtualDisplay: physical=${realWidth}x${realHeight}, virtual=${width}x${height}, density=$density")

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ imageReaderInstance ->
            // CRITICAL(C3): drop frames synchronously after STOP — onDestroy is async.
            if (!isRunning || isStopping) {
                try { imageReaderInstance.acquireLatestImage()?.close() } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
                return@setOnImageAvailableListener
            }

            // الشرط الثاني الإلزامي: استخدام آلية إسقاط الإطارات (Drop Latest)
            // إذا كان المعالج لا يزال يعالج إطاراً سابقاً، يتم إسقاط الإطار الجديد فوراً دون وضعه في طابور انتظار
            if (!isProcessingFrame.compareAndSet(false, true)) {
                try { imageReaderInstance.acquireLatestImage()?.close() } catch (e: Exception) { Log.d(TAG, "Dropped frame cleanup: ${e.message}") }
                return@setOnImageAvailableListener
            }

            val image = try {
                imageReaderInstance.acquireLatestImage()
            } catch (e: Exception) {
                Log.d(TAG, "acquireLatestImage failed (reader closing?)", e)
                null
            }

            if (image == null) {
                isProcessingFrame.set(false)
                return@setOnImageAvailableListener
            }

            try {
                // حساب الـ FPS
                trackFps()

                val now = SystemClock.elapsedRealtime()
                if (now - lastDiagnosticLogMs >= 2000L) {
                    lastDiagnosticLogMs = now
                    val fgPkg = contextualWatcher.currentForegroundPackage
                    val isTarget = contextualWatcher.isTargetAppForeground
                    Log.i(TAG, "[ScreenAi Scan Active] Pkg: $fgPkg (Target: $isTarget) | FPS: ${"%.1f".format(currentFps)}")
                }

                // صمام الأمان: إهمال فوري للإطار وإزالة البلور فوراً إذا انتقل المستخدم لتطبيق آمن
                if (!contextualWatcher.isTargetAppForeground) {
                    if (::kltTracker.isInitialized) {
                        kltTracker.flush()
                    }
                    if (::blurOverlayManager.isInitialized && blurOverlayManager.hasActiveRegions) {
                        blurOverlayManager.dismissFast()
                    }
                    return@setOnImageAvailableListener
                }

                // المستوى 0: التحقق مما إذا كانت الشاشة ثابتة عبر البايتات الخام RGBA مباشرة
                val planes = image.planes
                if (planes.isEmpty()) {
                    Log.d(TAG, "Frame has no planes; dropping")
                    return@setOnImageAvailableListener
                }
                val plane = planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride

                if (!frameDifferencer.hasScreenChanged(
                        rawBuffer = plane.buffer,
                        width = width,
                        height = height,
                        rowStride = rowStride,
                        pixelStride = pixelStride
                    )) {
                    // الشاشة ساكنة تماماً: تثبيت البلور الحالي إن وجد ومنع أي وميض أو سقوط
                    if (::blurOverlayManager.isInitialized && blurOverlayManager.hasActiveRegions && !blurOverlayManager.isPendingClear) {
                        blurOverlayManager.retainActiveRegions()
                    }
                    return@setOnImageAvailableListener
                }

                // فحص الانتقال المفاجئ الكاسح للمشهد (Hard Scene Cut: تغير أكثر من 70% من الشاشة في إطار واحد)
                if (frameDifferencer.lastChangeRatio >= 0.70f) {
                    if (::kltTracker.isInitialized) {
                        kltTracker.flush()
                    }
                }

                // -----------------------------------------------------------------
                // المسار السريع: تتبع التدفق البصري بنبضات الشاشة (60 FPS @ ~1.2ms)
                // -----------------------------------------------------------------
                if (::kltTracker.isInitialized && kltTracker.hasActiveTracks()) {
                    val trackedBoxes = kltTracker.update(
                        rawBuffer = plane.buffer,
                        width = width,
                        height = height,
                        rowStride = rowStride,
                        pixelStride = pixelStride,
                        timestampMs = now
                    )
                    if (trackedBoxes.isNotEmpty()) {
                        val screenMetrics = resources.displayMetrics
                        val paddedBoxes = kltTracker.getPaddedBoxes()
                        blurOverlayManager.updateRegionsMapped(
                            rawRegions = paddedBoxes,
                            targetWidth = screenMetrics.widthPixels,
                            targetHeight = screenMetrics.heightPixels,
                            sourceWidth = 1,
                            sourceHeight = 1
                        )
                        lastOverlayRenderTimeMs = now
                    }
                }

                // -----------------------------------------------------------------
                // المسار غير المتزامن: إطلاق استدلال Halalify (Anchor Trigger @ 4-5 FPS)
                // -----------------------------------------------------------------
                val shouldTriggerAnchor = !isHalalifyInferring.get() &&
                        ((now - lastAnchorCheckTimeMs >= ANCHOR_INTERVAL_MS) || (::kltTracker.isInitialized && !kltTracker.hasActiveTracks()))

                if (shouldTriggerAnchor && isHalalifyInferring.compareAndSet(false, true)) {
                    val bitmap = copyFrameToInferenceBitmap(image)
                    if (bitmap != null) {
                        val captureTimestamp = now
                        inferenceHandler?.post {
                            try {
                                if (!isRunning || isStopping) return@post
                                val startHalalify = SystemClock.elapsedRealtime()
                                val detections = halalifyDetector.detect(bitmap)
                                val halalifyLatency = SystemClock.elapsedRealtime() - startHalalify
                                val femaleDetections = detections.filter { it.isFemale }

                                if (::kltTracker.isInitialized) {
                                    kltTracker.initOrUpdateAnchorsFromHalalify(
                                        detections = femaleDetections,
                                        frameWidth = width,
                                        frameHeight = height,
                                        timestampMs = captureTimestamp
                                    )
                                }

                                if (femaleDetections.isNotEmpty()) {
                                    val paddedBoxes = if (::kltTracker.isInitialized) {
                                        val tracks = kltTracker.getPaddedBoxes()
                                        if (tracks.isNotEmpty()) tracks else femaleDetections.map { it.boundingBox }
                                    } else {
                                        femaleDetections.map { it.boundingBox }
                                    }
                                    val screenMetrics = resources.displayMetrics
                                    blurOverlayManager.updateRegionsMapped(
                                        rawRegions = paddedBoxes,
                                        targetWidth = screenMetrics.widthPixels,
                                        targetHeight = screenMetrics.heightPixels,
                                        sourceWidth = 1,
                                        sourceHeight = 1
                                    )
                                    lastOverlayRenderTimeMs = SystemClock.elapsedRealtime()
                                    Log.i(TAG, "[Halalify Shield] Active | Female Detections: ${femaleDetections.size} | Top Conf: ${"%.1f".format((femaleDetections.maxOfOrNull { it.confidence } ?: 0f) * 100)}% | Latency: ${halalifyLatency}ms | Boxes: ${paddedBoxes.joinToString { "[${"%.2f".format(it.left)}, ${"%.2f".format(it.top)}, ${"%.2f".format(it.right)}, ${"%.2f".format(it.bottom)}]" }}")

                                    // الشرط الأول الإلزامي: ربط الاستدعاء المشروط (Gated Execution)
                                    // لا يتم تمرير الإطار إلى NudeNetDetector إلا إذا اكتشف Halalify وجود شبهة جسد أو أنثى على الشاشة لتوفير طاقة المعالج ومنع السخونة
                                    if (::nudeNetDetector.isInitialized) {
                                        val nudeResult = nudeNetDetector.detect(bitmap)
                                        handleNudeNetResult(nudeResult)
                                    }
                                } else if (::kltTracker.isInitialized && !kltTracker.hasActiveTracks()) {
                                    blurOverlayManager.updateRegions(emptyList())
                                    val nowMs = SystemClock.elapsedRealtime()
                                    if (nowMs - lastHalalifyIdleLogMs >= 2000L) {
                                        lastHalalifyIdleLogMs = nowMs
                                        val rawConfPercent = (halalifyDetector.lastMaxRawFemaleConfidence * 100).toInt()
                                        Log.i(TAG, "[Halalify Shield] Idle | Female Detections: 0 | Max Raw Conf: $rawConfPercent% | Latency: ${halalifyLatency}ms")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error during background Halalify/NudeNet inference", e)
                            } finally {
                                lastAnchorCheckTimeMs = SystemClock.elapsedRealtime()
                                isHalalifyInferring.set(false)
                            }
                        }
                    } else {
                        isHalalifyInferring.set(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in frame processing loop", e)
            } finally {
                // الإغلاق الإلزامي لتفادي تجويع الذاكرة وإعادة ضبط صمام إسقاط الإطارات فوراً
                image.close()
                isProcessingFrame.set(false)
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

    private fun copyFrameToInferenceBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val requiredPixelCount = width * height
        val requiredRowBytes = width * pixelStride

        var pixels = scratchPixels
        if (pixels == null || pixels.size != requiredPixelCount) {
            pixels = IntArray(requiredPixelCount)
            scratchPixels = pixels
        }

        var rowBytes = scratchRowBytes
        if (rowBytes == null || rowBytes.size < requiredRowBytes) {
            rowBytes = ByteArray(requiredRowBytes)
            scratchRowBytes = rowBytes
        }

        var bitmap = inferenceBitmap
        if (bitmap == null || bitmap.isRecycled || bitmap.width != width || bitmap.height != height) {
            try { bitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            inferenceBitmap = bitmap
        }

        val savedPos = buffer.position()
        try {
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(rowBytes, 0, requiredRowBytes)
                val rowOffset = y * width
                for (x in 0 until width) {
                    val px = x * pixelStride
                    val r = rowBytes[px].toInt() and 0xFF
                    val g = rowBytes[px + 1].toInt() and 0xFF
                    val b = rowBytes[px + 2].toInt() and 0xFF
                    val a = if (pixelStride >= 4) (rowBytes[px + 3].toInt() and 0xFF) else 0xFF
                    pixels[rowOffset + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy frame to inference bitmap", e)
            return null
        } finally {
            try { buffer.position(savedPos) } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
        }
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

    private fun handleNudeNetResult(result: NudeNetResult) {
        if (result.hasHardPorn) {
            val topHard = result.detections
                .filter { it.isHardPorn }
                .maxByOrNull { it.confidence }
            if (topHard != null) {
                val snapshot = LastDetection(topHard.classType.labelAr, topHard.confidence)
                lastDetection = snapshot
                lastDetectedLabel = snapshot.label
                lastConfidence = snapshot.confidence
                triggerHardPornAlertThrottled(topHard.confidence)
            }
        } else if (result.hasSoftPorn) {
            val topSoft = result.detections
                .filter { it.isSoftPorn }
                .maxByOrNull { it.confidence }
            if (topSoft != null) {
                val snapshot = LastDetection(topSoft.classType.labelAr, topSoft.confidence)
                lastDetection = snapshot
                lastDetectedLabel = snapshot.label
                lastConfidence = snapshot.confidence
                triggerSoftPornAlertThrottled(topSoft.classType.labelAr, topSoft.confidence)
            }
        }
    }

    private fun triggerHardPornAlertThrottled(confidence: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlertTimeMs < ALERT_DEBOUNCE_MS) {
            return
        }
        lastAlertTimeMs = now

        val confidencePercent = (confidence * 100).toInt()
        val message = getString(R.string.ai_alert_hard_porn, confidencePercent)

        // 1. نبضة اهتزاز مزدوجة قوية
        triggerDoubleHapticPulse()

        // 2. إظهار رسالة Toast على الخيط الرئيسي
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }

        Log.w(TAG, "[ALERT] HARD PORN ALERT TRIGGERED: $message")
    }

    private fun triggerSoftPornAlertThrottled(label: String, confidence: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlertTimeMs < ALERT_DEBOUNCE_MS) {
            return
        }
        lastAlertTimeMs = now

        val confidencePercent = (confidence * 100).toInt()
        val message = getString(R.string.ai_alert_soft_porn, label, confidencePercent)

        // 1. نبضة اهتزاز مفردة (150ms)
        triggerHapticPulse()

        // 2. إظهار رسالة Toast على الخيط الرئيسي
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }

        Log.w(TAG, "[ALERT] SOFT PORN ALERT TRIGGERED: $message")
    }

    private fun triggerDoubleHapticPulse() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator == null || !vibrator.hasVibrator()) {
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // نبضتان قويتان: اهتزاز 200ms، توقف 100ms، اهتزاز 250ms
                val timings = longArrayOf(0, 200, 100, 250)
                if (vibrator.hasAmplitudeControl()) {
                    val amplitudes = intArrayOf(0, 255, 0, 255)
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 200, 100, 250), -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger double haptic pulse", e)
        }
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

            if (vibrator == null || !vibrator.hasVibrator()) {
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(150L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(150L)
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
        isProcessingFrame.set(false)
        isHalalifyInferring.set(false)

        if (::contextualWatcher.isInitialized) {
            contextualWatcher.stop()
        }

        if (::kltTracker.isInitialized) {
            kltTracker.flush()
        }

        if (::blurOverlayManager.isInitialized) {
            try {
                blurOverlayManager.detachOverlay()
                blurOverlayManager.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing blurOverlayManager in onDestroy", e)
            }
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

        // 2. إيقاف خيط الاستدلال الخلفي (AiInferenceWorker) وإغلاق كواشف الذكاء الاصطناعي على خيطها الأصلي
        val infWorker = inferenceThread
        val infHandler = inferenceHandler
        inferenceThread = null
        inferenceHandler = null
        if (infWorker != null && infWorker.isAlive && infHandler != null) {
            infHandler.removeCallbacksAndMessages(null)
            infHandler.post {
                try {
                    if (::halalifyDetector.isInitialized) halalifyDetector.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing halalifyDetector on inference worker", e)
                }
                try {
                    if (::nudeNetDetector.isInitialized) nudeNetDetector.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing nudeNetDetector on inference worker", e)
                }
                try { inferenceBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
                inferenceBitmap = null
                try { infWorker.quitSafely() } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
            }
        } else {
            if (::halalifyDetector.isInitialized) {
                try { halalifyDetector.close() } catch (e: Exception) {
                    Log.e(TAG, "Error closing halalifyDetector", e)
                }
            }
            if (::nudeNetDetector.isInitialized) {
                try { nudeNetDetector.close() } catch (e: Exception) {
                    Log.e(TAG, "Error closing nudeNetDetector", e)
                }
            }
            try { inferenceBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
            inferenceBitmap = null
        }

        // 3. إيقاف خيط التقاط الإطارات (Capture Worker Thread) وإغلاق ImageReader
        val worker = backgroundThread
        val workerHandler = backgroundHandler
        backgroundThread = null
        backgroundHandler = null
        if (worker != null && worker.isAlive && workerHandler != null) {
            workerHandler.postAtFrontOfQueue {
                try {
                    imageReader?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing image reader on worker", e)
                } finally {
                    imageReader = null
                }
                scratchPixels = null
                scratchRowBytes = null
                try { worker.quitSafely() } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
            }
            // Best-effort fallback: force quit after 3s WITHOUT blocking main.
            mainHandler.postDelayed({
                try { if (worker.isAlive) worker.quit() } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
            }, 3000L)
        } else {
            try { imageReader?.close() } catch (e: Exception) {
                Log.e(TAG, "Error closing image reader", e)
            } finally { imageReader = null }
            scratchPixels = null
            scratchRowBytes = null
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
