package com.drmohammed.antiporn.ai.service

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.ai.core.inference.HalalifyGenderDetector
import com.drmohammed.antiporn.ai.core.inference.NudeNetDetector
import com.drmohammed.antiporn.ai.core.model.NudeNetResult
import com.drmohammed.antiporn.ai.core.pipeline.ContextualGatingWatcher
import com.drmohammed.antiporn.ai.core.tracking.KltOpticalFlowTracker
import com.drmohammed.antiporn.ai.overlay.ScreenBlurOverlayManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates AI inference models (Halalify + NudeNet), optical flow tracking (KLT),
 * contextual app gating, and blur overlay rendering.
 */
class AiInferenceCoordinator(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val onDetectionUpdated: (label: String, confidence: Float) -> Unit = { _, _ -> }
) {
    private var inferenceThread: HandlerThread? = null
    private var inferenceHandler: Handler? = null

    lateinit var kltTracker: KltOpticalFlowTracker
        private set
    lateinit var halalifyDetector: HalalifyGenderDetector
        private set
    lateinit var nudeNetDetector: NudeNetDetector
        private set
    lateinit var blurOverlayManager: ScreenBlurOverlayManager
        private set
    lateinit var contextualWatcher: ContextualGatingWatcher
        private set

    private val isHalalifyInferring = AtomicBoolean(false)
    private var lastAnchorCheckTimeMs: Long = 0L

    private var inferenceBitmap: Bitmap? = null
    private var scratchPixels: IntArray? = null
    private var scratchRowBytes: ByteArray? = null

    private var lastAlertTimeMs: Long = 0L
    private var lastDiagnosticLogMs: Long = 0L
    private var lastHalalifyIdleLogMs: Long = 0L

    @Volatile
    var initError: String = ""
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    fun initialize(onInitialized: (Boolean) -> Unit = {}) {
        val infThread = HandlerThread("AiInferenceWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        infThread.start()
        inferenceThread = infThread
        val handler = Handler(infThread.looper)
        inferenceHandler = handler

        kltTracker = KltOpticalFlowTracker()
        halalifyDetector = HalalifyGenderDetector(context)
        nudeNetDetector = NudeNetDetector()
        blurOverlayManager = ScreenBlurOverlayManager(context)

        contextualWatcher = ContextualGatingWatcher(
            context,
            onStateChanged = { isTarget, _ ->
                if (!isTarget) {
                    if (::kltTracker.isInitialized) kltTracker.flush()
                    if (::blurOverlayManager.isInitialized) blurOverlayManager.dismissFast()
                }
            }
        )
        contextualWatcher.start()

        handler.post {
            val nudeOk = nudeNetDetector.initialize(context)
            if (!nudeOk) {
                initError = "NudeNet initialization failed"
                Log.e(TAG, "NudeNetDetector initialization failed")
                mainHandler.post { onInitialized(false) }
            } else {
                initError = ""
                Log.i(TAG, "AI detectors ready")
                mainHandler.post { onInitialized(true) }
            }
        }
    }

    fun start() {
        isRunning = true
        isHalalifyInferring.set(false)
        lastAnchorCheckTimeMs = 0L
        if (::kltTracker.isInitialized) kltTracker.flush()
    }

    fun processFrame(image: Image, width: Int, height: Int) {
        if (!isRunning) return

        val now = SystemClock.elapsedRealtime()

        if (now - lastDiagnosticLogMs >= 2000L) {
            lastDiagnosticLogMs = now
            val fgPkg = contextualWatcher.currentForegroundPackage
            val isTarget = contextualWatcher.isTargetAppForeground
            Log.i(TAG, "[ScreenAi Scan Active] Pkg: $fgPkg (Target: $isTarget)")
        }

        if (!contextualWatcher.isTargetAppForeground) {
            if (::kltTracker.isInitialized) kltTracker.flush()
            if (::blurOverlayManager.isInitialized && blurOverlayManager.hasActiveRegions) {
                blurOverlayManager.dismissFast()
            }
            return
        }

        val plane = image.planes.firstOrNull() ?: return
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        // 1. Fast Path: KLT Optical Flow Tracking (60 FPS @ ~1.2ms)
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
                val (screenWidth, screenHeight) = getPhysicalScreenSize()
                val paddedBoxes = kltTracker.getPaddedBoxes()
                blurOverlayManager.updateRegionsMapped(
                    rawRegions = paddedBoxes,
                    targetWidth = screenWidth,
                    targetHeight = screenHeight,
                    sourceWidth = 1,
                    sourceHeight = 1
                )
            }
        }

        // 2. Async Anchor Path: Halalify + NudeNet (4-5 FPS)
        val shouldTriggerAnchor = !isHalalifyInferring.get() &&
                ((now - lastAnchorCheckTimeMs >= ANCHOR_INTERVAL_MS) || (::kltTracker.isInitialized && !kltTracker.hasActiveTracks()))

        if (shouldTriggerAnchor && isHalalifyInferring.compareAndSet(false, true)) {
            val bitmap = copyFrameToInferenceBitmap(image)
            if (bitmap != null) {
                val captureTimestamp = now
                inferenceHandler?.post {
                    try {
                        if (!isRunning) return@post
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
                            val (screenWidth, screenHeight) = getPhysicalScreenSize()
                            blurOverlayManager.updateRegionsMapped(
                                rawRegions = paddedBoxes,
                                targetWidth = screenWidth,
                                targetHeight = screenHeight,
                                sourceWidth = 1,
                                sourceHeight = 1
                            )

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
                                Log.i(TAG, "[Halalify Shield] Idle | Max Raw Conf: $rawConfPercent% | Latency: ${halalifyLatency}ms")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in AI inference", e)
                    } finally {
                        lastAnchorCheckTimeMs = SystemClock.elapsedRealtime()
                        isHalalifyInferring.set(false)
                    }
                }
            } else {
                isHalalifyInferring.set(false)
            }
        }
    }

    fun onStaticScreen() {
        if (::blurOverlayManager.isInitialized && blurOverlayManager.hasActiveRegions && !blurOverlayManager.isPendingClear) {
            blurOverlayManager.retainActiveRegions()
        }
    }

    fun onHardSceneCut() {
        if (::kltTracker.isInitialized) {
            kltTracker.flush()
        }
    }

    private fun handleNudeNetResult(result: NudeNetResult) {
        if (result.hasHardPorn) {
            val topHard = result.detections
                .filter { it.isHardPorn }
                .maxByOrNull { it.confidence }
            if (topHard != null) {
                onDetectionUpdated(topHard.classType.labelAr, topHard.confidence)
                triggerHardPornAlertThrottled(topHard.confidence)
            }
        } else if (result.hasSoftPorn) {
            val topSoft = result.detections
                .filter { it.isSoftPorn }
                .maxByOrNull { it.confidence }
            if (topSoft != null) {
                onDetectionUpdated(topSoft.classType.labelAr, topSoft.confidence)
                triggerSoftPornAlertThrottled(topSoft.classType.labelAr, topSoft.confidence)
            }
        }
    }

    private fun triggerHardPornAlertThrottled(confidence: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlertTimeMs < ALERT_DEBOUNCE_MS) return
        lastAlertTimeMs = now

        val confidencePercent = (confidence * 100).toInt()
        val message = context.getString(R.string.ai_alert_hard_porn, confidencePercent)

        triggerDoubleHapticPulse()
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }

        try {
            com.drmohammed.antiporn.notification.manager.AppNotificationManager.getInstance(context).postEventNotification(
                category = com.drmohammed.antiporn.notification.model.NotificationCategory.AI,
                title = context.getString(R.string.ai_service_channel_name),
                message = message,
                dedupKey = "ai_porn_alert_hard",
                cooldownMs = 15 * 60 * 1000L,
                actionType = com.drmohammed.antiporn.notification.model.NotificationActionType.NONE,
                postSystemNotification = false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error recording hard porn alert in in-app notification center", e)
        }
    }

    private fun triggerSoftPornAlertThrottled(label: String, confidence: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlertTimeMs < ALERT_DEBOUNCE_MS) return
        lastAlertTimeMs = now

        val confidencePercent = (confidence * 100).toInt()
        val message = context.getString(R.string.ai_alert_soft_porn, label, confidencePercent)

        triggerHapticPulse()
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerDoubleHapticPulse() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (!vibrator.hasVibrator()) return

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
            try { bitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
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
            try { buffer.position(savedPos) } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
        }
    }

    fun stop() {
        isRunning = false
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
                Log.e(TAG, "Error closing blurOverlayManager", e)
            }
        }

        val worker = inferenceThread
        val handler = inferenceHandler
        inferenceThread = null
        inferenceHandler = null

        if (worker != null && worker.isAlive && handler != null) {
            handler.removeCallbacksAndMessages(null)
            handler.post {
                try { if (::halalifyDetector.isInitialized) halalifyDetector.close() } catch (e: Exception) { Log.e(TAG, "Error closing halalify", e) }
                try { if (::nudeNetDetector.isInitialized) nudeNetDetector.close() } catch (e: Exception) { Log.e(TAG, "Error closing nudeNet", e) }
                try { inferenceBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
                inferenceBitmap = null
                scratchPixels = null
                scratchRowBytes = null
                try { worker.quitSafely() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
            }
        } else {
            try { if (::halalifyDetector.isInitialized) halalifyDetector.close() } catch (e: Exception) { Log.e(TAG, "Error closing halalify", e) }
            try { if (::nudeNetDetector.isInitialized) nudeNetDetector.close() } catch (e: Exception) { Log.e(TAG, "Error closing nudeNet", e) }
            try { inferenceBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
            inferenceBitmap = null
            scratchPixels = null
            scratchRowBytes = null
        }
    }

    private fun getPhysicalScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && wm != null) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
        } else if (wm != null) {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(dm)
            Pair(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
        } else {
            val dm = context.resources.displayMetrics
            Pair(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
        }
    }

    companion object {
        private const val TAG = "AiInferenceCoord"
        private const val ALERT_DEBOUNCE_MS = 5000L
        const val ANCHOR_INTERVAL_MS = 200L
    }
}
