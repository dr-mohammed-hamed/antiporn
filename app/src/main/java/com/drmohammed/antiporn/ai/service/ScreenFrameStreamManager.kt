package com.drmohammed.antiporn.ai.service

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.drmohammed.antiporn.ai.core.pipeline.FrameDifferencer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Manages the screen capture pipeline:
 * - Initializes ImageReader with physical/virtual display parity
 * - Creates VirtualDisplay from MediaProjection
 * - Enforces Level-0 Frame Differencing to drop identical static frames
 * - Computes real-time screen capture FPS
 * - Provides strict synchronized teardown to prevent native ImageReader crashes.
 */
class ScreenFrameStreamManager(
    private val context: Context,
    private val frameDifferencer: FrameDifferencer = FrameDifferencer(),
    private val onFrameAcquired: (image: Image, width: Int, height: Int) -> Unit,
    private val onStaticFrameDetected: () -> Unit = {},
    private val onHardSceneCutDetected: () -> Unit = {},
    private val onSceneChangeRatio: (Float) -> Unit = {}
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val isProcessingFrame = AtomicBoolean(false)

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isStopping: Boolean = false
        private set

    @Volatile
    var currentFps: Float = 0f
        private set

    private var frameCounter: Int = 0
    private var fpsTimerStart: Long = 0L

    private var currentMediaProjection: MediaProjection? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var currentVirtualWidth: Int = 0
    private var currentVirtualHeight: Int = 0

    fun start(mediaProjection: MediaProjection) {
        val thread = HandlerThread("AiProjectionWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        backgroundThread = thread
        val handler = Handler(thread.looper)
        backgroundHandler = handler

        currentMediaProjection = mediaProjection
        isRunning = true
        isStopping = false
        isProcessingFrame.set(false)
        frameCounter = 0
        fpsTimerStart = 0L

        val (realWidth, realHeight) = getPhysicalScreenBounds()
        val (width, height) = computeVirtualDimensions(realWidth, realHeight)
        setupVirtualDisplay(mediaProjection, width, height, handler)

        // تسجيل مستمع لتغير اتجاه الشاشة (دوران الشاشة بين Portrait و Landscape)
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    backgroundHandler?.post {
                        recreateVirtualDisplayIfNeeded()
                    }
                }
            }
        }
        displayListener = listener
        try {
            dm?.registerDisplayListener(listener, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register DisplayListener: ${e.message}")
        }
    }

    private fun getPhysicalScreenBounds(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
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

    private fun computeVirtualDimensions(realWidth: Int, realHeight: Int): Pair<Int, Int> {
        val isPortrait = realHeight >= realWidth
        val shortEdge = 416.coerceAtMost(minOf(realWidth, realHeight))
        return if (isPortrait) {
            val w = shortEdge
            val h = ((realHeight.toFloat() * w) / realWidth).roundToInt().coerceAtLeast(1)
            Pair(w, h)
        } else {
            val h = shortEdge
            val w = ((realWidth.toFloat() * h) / realHeight).roundToInt().coerceAtLeast(1)
            Pair(w, h)
        }
    }

    private fun recreateVirtualDisplayIfNeeded() {
        if (!isRunning || isStopping) return
        val mp = currentMediaProjection ?: return
        val (realWidth, realHeight) = getPhysicalScreenBounds()
        val (newWidth, newHeight) = computeVirtualDimensions(realWidth, realHeight)
        if (newWidth == currentVirtualWidth && newHeight == currentVirtualHeight) return

        Log.i(TAG, "Recreating VirtualDisplay on orientation change: ${currentVirtualWidth}x${currentVirtualHeight} -> ${newWidth}x${newHeight}")
        val handler = backgroundHandler ?: return

        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}

        setupVirtualDisplay(mp, newWidth, newHeight, handler)
    }

    private fun setupVirtualDisplay(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        handler: Handler
    ) {
        currentVirtualWidth = width
        currentVirtualHeight = height
        val (realWidth, realHeight) = getPhysicalScreenBounds()
        val density = context.resources.displayMetrics.densityDpi

        Log.i(TAG, "Setting up VirtualDisplay: physical=${realWidth}x${realHeight}, virtual=${width}x${height}, density=$density")

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ imageReaderInstance ->
            if (!isRunning || isStopping) {
                try { imageReaderInstance.acquireLatestImage()?.close() } catch (e: Exception) { Log.d(TAG, "Non-critical cleanup: ${e.message}") }
                return@setOnImageAvailableListener
            }

            // Drop Latest Backpressure valve
            if (!isProcessingFrame.compareAndSet(false, true)) {
                try { imageReaderInstance.acquireLatestImage()?.close() } catch (e: Exception) { Log.d(TAG, "Dropped frame cleanup: ${e.message}") }
                return@setOnImageAvailableListener
            }

            val image = try {
                imageReaderInstance.acquireLatestImage()
            } catch (e: Exception) {
                Log.d(TAG, "acquireLatestImage failed", e)
                null
            }

            if (image == null) {
                isProcessingFrame.set(false)
                return@setOnImageAvailableListener
            }

            try {
                trackFps()

                val planes = image.planes
                if (planes.isEmpty()) {
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
                    onStaticFrameDetected()
                    return@setOnImageAvailableListener
                }

                onSceneChangeRatio(frameDifferencer.lastChangeRatio)

                if (frameDifferencer.lastChangeRatio >= 0.70f) {
                    onHardSceneCutDetected()
                }

                onFrameAcquired(image, width, height)
            } catch (e: Exception) {
                Log.e(TAG, "Error in frame stream processing", e)
            } finally {
                image.close()
                isProcessingFrame.set(false)
            }
        }, handler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ModestyAiVirtualDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
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

    fun stop() {
        isRunning = false
        isStopping = true
        isProcessingFrame.set(false)
        currentFps = 0f

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayListener?.let {
            try { dm?.unregisterDisplayListener(it) } catch (_: Exception) {}
        }
        displayListener = null
        currentMediaProjection = null

        try { imageReader?.setOnImageAvailableListener(null, null) } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
        try { virtualDisplay?.release() } catch (e: Exception) { Log.e(TAG, "Error releasing virtualDisplay", e) }
        virtualDisplay = null

        val worker = backgroundThread
        val handler = backgroundHandler
        backgroundThread = null
        backgroundHandler = null

        if (worker != null && worker.isAlive && handler != null) {
            handler.postAtFrontOfQueue {
                try {
                    imageReader?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing imageReader", e)
                } finally {
                    imageReader = null
                }
                try { worker.quitSafely() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
            }
        } else {
            try { imageReader?.close() } catch (e: Exception) { Log.e(TAG, "Error closing imageReader", e) }
            imageReader = null
        }
    }

    companion object {
        private const val TAG = "ScreenFrameStreamMgr"
    }
}
