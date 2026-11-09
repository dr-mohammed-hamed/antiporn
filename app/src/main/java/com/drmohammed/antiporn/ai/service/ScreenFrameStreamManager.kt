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
    private val onHardSceneCutDetected: () -> Unit = {}
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

    fun start(mediaProjection: MediaProjection) {
        val thread = HandlerThread("AiProjectionWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        backgroundThread = thread
        val handler = Handler(thread.looper)
        backgroundHandler = handler

        isRunning = true
        isStopping = false
        isProcessingFrame.set(false)
        frameCounter = 0
        fpsTimerStart = 0L

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val (realWidth, realHeight) = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && wm != null) {
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
        val width = 416.coerceAtMost(realWidth)
        val height = ((realHeight.toFloat() * width) / realWidth).roundToInt().coerceAtLeast(1)
        val density = context.resources.displayMetrics.densityDpi

        Log.i(TAG, "Creating VirtualDisplay: physical=${realWidth}x${realHeight}, virtual=${width}x${height}, density=$density")

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
