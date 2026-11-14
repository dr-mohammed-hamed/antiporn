package com.drmohammed.antiporn.ai.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.media.Image
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.ai.core.inference.HalalifyGenderDetector
import com.drmohammed.antiporn.ai.core.inference.NudeNetDetector
import com.drmohammed.antiporn.ai.core.pipeline.ContextualGatingWatcher
import com.drmohammed.antiporn.ai.core.tracking.KltMathHelper
import com.drmohammed.antiporn.ai.core.tracking.KltOpticalFlowTracker
import com.drmohammed.antiporn.ai.core.tracking.TrackedModestyBox
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
    private var lastA11yScreenshotAttemptMs: Long = 0L
    private var consecutiveA11yFailures: Int = 0

    private var inferenceBitmap: Bitmap? = null
    private var scratchPixels: IntArray? = null
    private var scratchRowBytes: ByteArray? = null

    @Volatile
    private var sceneGenerationId: Long = 0L

    @Volatile
    private var accumulatedScrollDx: Float = 0f

    @Volatile
    private var accumulatedScrollDy: Float = 0f

    private var tileStepIndex: Int = 0
    private var tileBitmap: Bitmap? = null
    private var scratchTilePixels: IntArray? = null

    private var lastDiagnosticLogMs: Long = 0L
    private var lastHalalifyIdleLogMs: Long = 0L

    private val drawnBoxesLock = Any()
    private val lastDrawnPixelBoxes = ArrayList<RectF>(32)

    @Volatile
    var initError: String = ""
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    fun setGeneralChangeRatio(ratio: Float) {
        if (::kltTracker.isInitialized) {
            kltTracker.lastGeneralChangeRatio = ratio
        }
    }

    fun initialize(onInitialized: (Boolean) -> Unit = {}) {
        val infThread = HandlerThread("AiInferenceWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        infThread.start()
        inferenceThread = infThread
        val handler = Handler(infThread.looper)
        inferenceHandler = handler

        kltTracker = KltOpticalFlowTracker()
        halalifyDetector = HalalifyGenderDetector(context)
        nudeNetDetector = NudeNetDetector()
        blurOverlayManager = ScreenBlurOverlayManager(context).apply {
            isKltTrackingActive = true
            onRegionsCleared = {
                synchronized(drawnBoxesLock) {
                    lastDrawnPixelBoxes.clear()
                }
            }
        }

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
            val halalifyOk = halalifyDetector.isInitialized
            val nudeOk = if (ENABLE_NUDENET) nudeNetDetector.initialize(context) else true
            if (!halalifyOk || !nudeOk) {
                initError = if (!halalifyOk) "Halalify initialization failed" else "NudeNet initialization failed"
                Log.e(TAG, "AI initialization failed: $initError")
                mainHandler.post { onInitialized(false) }
            } else {
                initError = ""
                Log.i(TAG, "Halalify ready (NudeNet enabled=$ENABLE_NUDENET)")
                mainHandler.post { onInitialized(true) }
            }
        }
    }

    fun start() {
        isRunning = true
        isHalalifyInferring.set(false)
        lastAnchorCheckTimeMs = 0L
        consecutiveA11yFailures = 0
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
            accumulatedScrollDx += kltTracker.lastGlobalDeltaX
            accumulatedScrollDy += kltTracker.lastGlobalDeltaY

            if (trackedBoxes.isNotEmpty()) {
                val (screenWidth, screenHeight) = getPhysicalScreenSize()
                val paddedBoxes = kltTracker.getPaddedBoxes()
                val overlayNeedsRedraw = !blurOverlayManager.hasActiveRegions || hasBoxesChangedSignificantly(paddedBoxes, screenWidth, screenHeight)
                if (overlayNeedsRedraw) {
                    blurOverlayManager.updateRegionsMapped(
                        rawRegions = paddedBoxes,
                        targetWidth = screenWidth,
                        targetHeight = screenHeight,
                        sourceWidth = 1,
                        sourceHeight = 1
                    )
                }
            } else {
                synchronized(drawnBoxesLock) {
                    lastDrawnPixelBoxes.clear()
                }
                blurOverlayManager.updateRegions(emptyList())
            }
        }

        // 2. Async Anchor Path: Clean A11y Screenshot OR 4-Stage Rotation (FullFrame + 3 Slices) (4-5 FPS)
        val a11y = HisnAccessibilityService.instance
        val isA11ySupported = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) && (a11y != null)
        val isA11yActive = isA11ySupported && (consecutiveA11yFailures < 3)

        val anchorInterval = if (isA11yActive) 350L else ANCHOR_INTERVAL_MS
        val shouldTriggerAnchor = !isHalalifyInferring.get() &&
                ((now - lastAnchorCheckTimeMs >= anchorInterval) || (::kltTracker.isInitialized && !kltTracker.hasActiveTracks() && (now - lastAnchorCheckTimeMs >= (if (isA11yActive) 350L else 50L))))

        if (shouldTriggerAnchor && isHalalifyInferring.compareAndSet(false, true)) {
            val canTryA11y = isA11yActive && (now - lastA11yScreenshotAttemptMs >= 350L)

            if (canTryA11y) {
                lastA11yScreenshotAttemptMs = now
                val captureTimestamp = now
                val captureGen = sceneGenerationId
                val startScrollX = accumulatedScrollDx
                val startScrollY = accumulatedScrollDy

                a11y?.captureCleanWindowBitmap { cleanBitmap, windowBounds ->
                    if (cleanBitmap != null) {
                        consecutiveA11yFailures = 0
                        if (isRunning && captureGen == sceneGenerationId) {
                            val posted = inferenceHandler?.post {
                                try {
                                    runHalalifyOnCleanBitmap(
                                        cleanBitmap = cleanBitmap,
                                        windowBounds = windowBounds,
                                        captureGen = captureGen,
                                        captureTimestamp = captureTimestamp,
                                        startScrollX = startScrollX,
                                        startScrollY = startScrollY,
                                        frameWidth = width,
                                        frameHeight = height
                                    )
                                } finally {
                                    try { cleanBitmap.recycle() } catch (_: Exception) {}
                                    lastAnchorCheckTimeMs = SystemClock.elapsedRealtime()
                                    isHalalifyInferring.set(false)
                                }
                            } ?: false
                            if (!posted) {
                                try { cleanBitmap.recycle() } catch (_: Exception) {}
                                lastAnchorCheckTimeMs = SystemClock.elapsedRealtime()
                                isHalalifyInferring.set(false)
                            }
                        } else {
                            try { cleanBitmap.recycle() } catch (_: Exception) {}
                            lastAnchorCheckTimeMs = SystemClock.elapsedRealtime()
                            isHalalifyInferring.set(false)
                        }
                    } else {
                        consecutiveA11yFailures++
                        Log.d(TAG, "A11y clean capture returned null (consecutive failures: $consecutiveA11yFailures)")
                        lastAnchorCheckTimeMs = SystemClock.elapsedRealtime()
                        isHalalifyInferring.set(false)
                    }
                }
            } else if (!isA11yActive) {
                dispatchMediaProjectionAnchor(image, width, height, now)
            } else {
                isHalalifyInferring.set(false)
            }
        }
    }

    private fun notifyDetectionUpdated(detections: List<com.drmohammed.antiporn.ai.core.model.HalalifyDetection>) {
        val topFemale = detections.filter { it.isFemale }.maxByOrNull { it.confidence }
            ?: detections.maxByOrNull { it.confidence }
        val label = if (topFemale != null) "female" else "clean"
        val maxConfidence = topFemale?.confidence ?: 0f
        mainHandler.post {
            try {
                onDetectionUpdated(label, maxConfidence)
            } catch (e: Exception) {
                Log.d(TAG, "Non-critical error in onDetectionUpdated: ${e.message}")
            }
        }
    }

    private fun runHalalifyOnCleanBitmap(
        cleanBitmap: Bitmap,
        windowBounds: Rect,
        captureGen: Long,
        captureTimestamp: Long,
        startScrollX: Float,
        startScrollY: Float,
        frameWidth: Int,
        frameHeight: Int
    ) {
        if (!isRunning || captureGen != sceneGenerationId) return
        val startHalalify = SystemClock.elapsedRealtime()
        val detections = halalifyDetector.detect(cleanBitmap)
        notifyDetectionUpdated(detections)
        val halalifyLatency = SystemClock.elapsedRealtime() - startHalalify
        val femaleDetections = detections.filter { it.isFemale }

        if (captureGen != sceneGenerationId) return

        val dtX = accumulatedScrollDx - startScrollX
        val dtY = accumulatedScrollDy - startScrollY

        val (physicalW, physicalH) = getPhysicalScreenSize()
        val scaleX = frameWidth.toFloat() / physicalW.toFloat().coerceAtLeast(1f)
        val scaleY = frameHeight.toFloat() / physicalH.toFloat().coerceAtLeast(1f)

        val scaledWinLeft = windowBounds.left * scaleX
        val scaledWinTop = windowBounds.top * scaleY
        val scaledWinWidth = (windowBounds.right - windowBounds.left) * scaleX
        val scaledWinHeight = (windowBounds.bottom - windowBounds.top) * scaleY

        val mappedFullBoxes = femaleDetections.map { det ->
            val pxL = scaledWinLeft + (det.boundingBox.left * scaledWinWidth) + dtX
            val pxT = scaledWinTop + (det.boundingBox.top * scaledWinHeight) + dtY
            val pxR = scaledWinLeft + (det.boundingBox.right * scaledWinWidth) + dtX
            val pxB = scaledWinTop + (det.boundingBox.bottom * scaledWinHeight) + dtY

            val normL = (pxL / frameWidth).coerceIn(0f, 1f)
            val normT = (pxT / frameHeight).coerceIn(0f, 1f)
            val normR = (pxR / frameWidth).coerceIn(normL + 0.001f, 1f)
            val normB = (pxB / frameHeight).coerceIn(normT + 0.001f, 1f)
            TrackedModestyBox.createRectF(normL, normT, normR, normB)
        }

        val mergedBoxes = KltMathHelper.mergeBoxesSpatially(mappedFullBoxes, 40f / frameHeight)

        val winNormL = (scaledWinLeft / frameWidth).coerceIn(0f, 1f)
        val winNormT = (scaledWinTop / frameHeight).coerceIn(0f, 1f)
        val winNormR = ((scaledWinLeft + scaledWinWidth) / frameWidth).coerceIn(winNormL + 0.001f, 1f)
        val winNormB = ((scaledWinTop + scaledWinHeight) / frameHeight).coerceIn(winNormT + 0.001f, 1f)
        val tileNormRect = TrackedModestyBox.createRectF(winNormL, winNormT, winNormR, winNormB)

        if (::kltTracker.isInitialized) {
            kltTracker.updateAnchorsCrossTile(
                detections = mergedBoxes,
                tileNormRect = tileNormRect,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMs = captureTimestamp
            )
        }

        renderOverlayAfterAnchor(halalifyLatency)
    }

    private fun dispatchMediaProjectionAnchor(
        image: Image,
        width: Int,
        height: Int,
        now: Long
    ) {
        val isPortrait = height >= width
        val stageIdx = (tileStepIndex++) % 4

        if (stageIdx == 0) {
            // Stage 0: FullFrame Letterboxed
            val fullBitmapInstance = copyFrameToInferenceBitmap(image)
            if (fullBitmapInstance != null) {
                val captureTimestamp = now
                val captureGen = sceneGenerationId
                val startScrollX = accumulatedScrollDx
                val startScrollY = accumulatedScrollDy

                inferenceHandler?.post {
                    try {
                        if (!isRunning || captureGen != sceneGenerationId) return@post
                        val startHalalify = SystemClock.elapsedRealtime()
                        val detections = halalifyDetector.detect(fullBitmapInstance)
                        notifyDetectionUpdated(detections)
                        val halalifyLatency = SystemClock.elapsedRealtime() - startHalalify
                        val femaleDetections = detections.filter { it.isFemale }

                        if (captureGen != sceneGenerationId) return@post

                        val dtX = accumulatedScrollDx - startScrollX
                        val dtY = accumulatedScrollDy - startScrollY

                        val mappedFullBoxes = femaleDetections.map { det ->
                            val pxL = det.boundingBox.left * width + dtX
                            val pxT = det.boundingBox.top * height + dtY
                            val pxR = det.boundingBox.right * width + dtX
                            val pxB = det.boundingBox.bottom * height + dtY

                            val normL = (pxL / width).coerceIn(0f, 1f)
                            val normT = (pxT / height).coerceIn(0f, 1f)
                            val normR = (pxR / width).coerceIn(normL + 0.001f, 1f)
                            val normB = (pxB / height).coerceIn(normT + 0.001f, 1f)
                            TrackedModestyBox.createRectF(normL, normT, normR, normB)
                        }

                        val mergedBoxes = KltMathHelper.mergeBoxesSpatially(mappedFullBoxes, 40f / height)
                        val tileNormRect = TrackedModestyBox.createRectF(0f, 0f, 1f, 1f)

                        if (::kltTracker.isInitialized) {
                            kltTracker.updateAnchorsCrossTile(
                                detections = mergedBoxes,
                                tileNormRect = tileNormRect,
                                frameWidth = width,
                                frameHeight = height,
                                timestampMs = captureTimestamp
                            )
                        }

                        renderOverlayAfterAnchor(halalifyLatency)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in FullFrame AI inference", e)
                    } finally {
                        lastAnchorCheckTimeMs = SystemClock.elapsedRealtime()
                        isHalalifyInferring.set(false)
                    }
                }
            } else {
                isHalalifyInferring.set(false)
            }
        } else {
            // Stages 1, 2, 3: Slices (Top, Mid, Bottom / Left, Center, Right)
            val sliceIdx = stageIdx - 1
            val tileW = 416.coerceAtMost(width)
            val tileH = 416.coerceAtMost(height)

            val tileOffsetX: Int
            val tileOffsetY: Int
            if (isPortrait) {
                tileOffsetX = 0
                val maxOffsetY = (height - tileH).coerceAtLeast(0)
                tileOffsetY = when (sliceIdx) {
                    0 -> 0 // Top
                    1 -> maxOffsetY / 2 // Middle
                    else -> maxOffsetY // Bottom
                }
            } else {
                tileOffsetY = 0
                val maxOffsetX = (width - tileW).coerceAtLeast(0)
                tileOffsetX = when (sliceIdx) {
                    0 -> 0 // Left
                    1 -> maxOffsetX / 2 // Center
                    else -> maxOffsetX // Right
                }
            }

            val tileBitmapInstance = copyTileToBitmap(image, tileOffsetX, tileOffsetY, tileW, tileH)
            if (tileBitmapInstance != null) {
                val captureTimestamp = now
                val captureGen = sceneGenerationId
                val startScrollX = accumulatedScrollDx
                val startScrollY = accumulatedScrollDy

                inferenceHandler?.post {
                    try {
                        if (!isRunning || captureGen != sceneGenerationId) return@post
                        val startHalalify = SystemClock.elapsedRealtime()
                        val detections = halalifyDetector.detect(tileBitmapInstance)
                        notifyDetectionUpdated(detections)
                        val halalifyLatency = SystemClock.elapsedRealtime() - startHalalify
                        val femaleDetections = detections.filter { it.isFemale }

                        if (captureGen != sceneGenerationId) return@post

                        val dtX = accumulatedScrollDx - startScrollX
                        val dtY = accumulatedScrollDy - startScrollY

                        val mappedFullBoxes = femaleDetections.map { det ->
                            val pxL = det.boundingBox.left * tileW + tileOffsetX + dtX
                            val pxT = det.boundingBox.top * tileH + tileOffsetY + dtY
                            val pxR = det.boundingBox.right * tileW + tileOffsetX + dtX
                            val pxB = det.boundingBox.bottom * tileH + tileOffsetY + dtY

                            val normL = (pxL / width).coerceIn(0f, 1f)
                            val normT = (pxT / height).coerceIn(0f, 1f)
                            val normR = (pxR / width).coerceIn(normL + 0.001f, 1f)
                            val normB = (pxB / height).coerceIn(normT + 0.001f, 1f)
                            TrackedModestyBox.createRectF(normL, normT, normR, normB)
                        }

                        val mergedBoxes = KltMathHelper.mergeBoxesSpatially(mappedFullBoxes, 40f / height)

                        val tileNormRect = TrackedModestyBox.createRectF(
                            tileOffsetX.toFloat() / width,
                            tileOffsetY.toFloat() / height,
                            (tileOffsetX + tileW).toFloat() / width,
                            (tileOffsetY + tileH).toFloat() / height
                        )

                        if (::kltTracker.isInitialized) {
                            kltTracker.updateAnchorsCrossTile(
                                detections = mergedBoxes,
                                tileNormRect = tileNormRect,
                                frameWidth = width,
                                frameHeight = height,
                                timestampMs = captureTimestamp
                            )
                        }

                        renderOverlayAfterAnchor(halalifyLatency)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in Tile AI inference", e)
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

    private fun renderOverlayAfterAnchor(halalifyLatency: Long) {
        if (::kltTracker.isInitialized && kltTracker.hasActiveTracks()) {
            val paddedBoxes = kltTracker.getPaddedBoxes()
            val (screenWidth, screenHeight) = getPhysicalScreenSize()
            val overlayNeedsRedraw = !blurOverlayManager.hasActiveRegions || hasBoxesChangedSignificantly(paddedBoxes, screenWidth, screenHeight)
            if (overlayNeedsRedraw) {
                blurOverlayManager.updateRegionsMapped(
                    rawRegions = paddedBoxes,
                    targetWidth = screenWidth,
                    targetHeight = screenHeight,
                    sourceWidth = 1,
                    sourceHeight = 1
                )
            }
        } else if (::kltTracker.isInitialized && !kltTracker.hasActiveTracks()) {
            synchronized(drawnBoxesLock) {
                lastDrawnPixelBoxes.clear()
            }
            blurOverlayManager.updateRegions(emptyList())
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastHalalifyIdleLogMs >= 2000L) {
                lastHalalifyIdleLogMs = nowMs
                val rawConfPercent = (halalifyDetector.lastMaxRawFemaleConfidence * 100).toInt()
                Log.i(TAG, "[Halalify Shield] Idle | Max Raw Conf: $rawConfPercent% | Latency: ${halalifyLatency}ms")
            }
        }
    }

    private fun hasBoxesChangedSignificantly(
        newBoxes: List<RectF>,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        synchronized(drawnBoxesLock) {
            if (newBoxes.size != lastDrawnPixelBoxes.size) {
                updateLastDrawnPixelBoxes(newBoxes, screenWidth, screenHeight)
                return true
            }
            val sw = screenWidth.toFloat()
            val sh = screenHeight.toFloat()
            for (i in newBoxes.indices) {
                val nb = newBoxes[i]
                val ob = lastDrawnPixelBoxes[i]
                val nl = nb.left * sw
                val nt = nb.top * sh
                val nr = nb.right * sw
                val nb_ = nb.bottom * sh

                if (kotlin.math.abs(nl - ob.left) >= 1.0f ||
                    kotlin.math.abs(nt - ob.top) >= 1.0f ||
                    kotlin.math.abs(nr - ob.right) >= 1.0f ||
                    kotlin.math.abs(nb_ - ob.bottom) >= 1.0f) {
                    updateLastDrawnPixelBoxes(newBoxes, screenWidth, screenHeight)
                    return true
                }
            }
            return false
        }
    }

    private fun updateLastDrawnPixelBoxes(
        boxes: List<RectF>,
        screenWidth: Int,
        screenHeight: Int
    ) {
        synchronized(drawnBoxesLock) {
            val sw = screenWidth.toFloat()
            val sh = screenHeight.toFloat()
            while (lastDrawnPixelBoxes.size < boxes.size) {
                lastDrawnPixelBoxes.add(RectF())
            }
            while (lastDrawnPixelBoxes.size > boxes.size) {
                lastDrawnPixelBoxes.removeAt(lastDrawnPixelBoxes.size - 1)
            }
            for (i in boxes.indices) {
                val src = boxes[i]
                val dst = lastDrawnPixelBoxes[i]
                dst.left = src.left * sw
                dst.top = src.top * sh
                dst.right = src.right * sw
                dst.bottom = src.bottom * sh
            }
        }
    }

    fun onStaticScreen() {
        if (::kltTracker.isInitialized && !kltTracker.hasActiveTracks()) {
            accumulatedScrollDx = 0f
            accumulatedScrollDy = 0f
        }
        if (::blurOverlayManager.isInitialized && blurOverlayManager.hasActiveRegions) {
            blurOverlayManager.retainActiveRegions()
        }
    }

    fun onHardSceneCut() {
        sceneGenerationId++
        accumulatedScrollDx = 0f
        accumulatedScrollDy = 0f
        consecutiveA11yFailures = 0
        synchronized(drawnBoxesLock) {
            lastDrawnPixelBoxes.clear()
        }
        if (::kltTracker.isInitialized) {
            kltTracker.flush()
        }
        if (::blurOverlayManager.isInitialized) {
            blurOverlayManager.dismissFast()
        }
    }

    private fun copyTileToBitmap(
        image: Image,
        offsetX: Int,
        offsetY: Int,
        tileWidth: Int,
        tileHeight: Int
    ): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        var bitmap = tileBitmap
        if (bitmap == null || bitmap.isRecycled || bitmap.width != tileWidth || bitmap.height != tileHeight) {
            try { bitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
            bitmap = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
            tileBitmap = bitmap
        }

        val requiredPixelCount = tileWidth * tileHeight
        var pixels = scratchTilePixels
        if (pixels == null || pixels.size != requiredPixelCount) {
            pixels = IntArray(requiredPixelCount)
            scratchTilePixels = pixels
        }

        val requiredRowBytes = (offsetX + tileWidth) * pixelStride
        var rowBytes = scratchRowBytes
        if (rowBytes == null || rowBytes.size < requiredRowBytes) {
            rowBytes = ByteArray(requiredRowBytes.coerceAtLeast(rowStride))
            scratchRowBytes = rowBytes
        }

        val savedPos = buffer.position()
        try {
            for (ty in 0 until tileHeight) {
                val fullY = offsetY + ty
                buffer.position(fullY * rowStride)
                buffer.get(rowBytes, 0, requiredRowBytes)
                val outRowOffset = ty * tileWidth
                for (tx in 0 until tileWidth) {
                    val fullX = offsetX + tx
                    val px = fullX * pixelStride
                    val r = rowBytes[px].toInt() and 0xFF
                    val g = rowBytes[px + 1].toInt() and 0xFF
                    val b = rowBytes[px + 2].toInt() and 0xFF
                    val a = if (pixelStride >= 4) (rowBytes[px + 3].toInt() and 0xFF) else 0xFF
                    pixels[outRowOffset + tx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, tileWidth, 0, 0, tileWidth, tileHeight)
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy tile to inference bitmap", e)
            return null
        } finally {
            try { buffer.position(savedPos) } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
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
                try { tileBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
                inferenceBitmap = null
                tileBitmap = null
                scratchPixels = null
                scratchTilePixels = null
                scratchRowBytes = null
                try { worker.quitSafely() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
            }
        } else {
            try { if (::halalifyDetector.isInitialized) halalifyDetector.close() } catch (e: Exception) { Log.e(TAG, "Error closing halalify", e) }
            try { if (::nudeNetDetector.isInitialized) nudeNetDetector.close() } catch (e: Exception) { Log.e(TAG, "Error closing nudeNet", e) }
            try { inferenceBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
            try { tileBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
            inferenceBitmap = null
            tileBitmap = null
            scratchPixels = null
            scratchTilePixels = null
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
        private const val ENABLE_NUDENET = false
        const val ANCHOR_INTERVAL_MS = 200L
    }
}
