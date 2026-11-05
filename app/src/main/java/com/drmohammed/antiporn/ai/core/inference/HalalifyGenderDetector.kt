package com.drmohammed.antiporn.ai.core.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import timber.log.Timber
import com.drmohammed.antiporn.ai.core.model.HalalifyDetection
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * محرك استدلال نموذج Halalify v2 للكشف عن النساء وتحديد مواقعهن على الشاشة.
 *
 * - المدخلات: [1, 416, 416, 3] Float32 (NHWC)، تطبيع [0, 1] (value / 255.0f).
 * - المخرجات: مصفوفة مسبقة الحجز [1, 7, 14365]:
 *   - القنوات 0..3: cx, cy, w, h
 *   - القناة 4: female (الفئة 0)
 *   - القناة 5: male (الفئة 1)
 *   - القناة 6: ignored (الفئة 2)
 * - المرشحات: إذا كانت القناة 4 (female) أكبر من القنوات الأخرى وثقتها >= confidenceThreshold (افتراضياً 0.35f).
 * - معالجة لاحقة: فك الـ Letterbox بناءً على أبعاد الإطار، ثم تطبيق NMS (عتبة IoU 0.45).
 * - يدعم التزامن الآمن (inferenceLock) والتحرير السليم للموارد عبر close().
 */
class HalalifyGenderDetector(
    val modelAssetPath: String = DEFAULT_MODEL_ASSET,
    val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    val iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
    private val numThreads: Int = 2
) : AutoCloseable {

    constructor(context: Context) : this() {
        initialize(context)
    }

    internal constructor(
        interpreter: Interpreter?,
        confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD
    ) : this(DEFAULT_MODEL_ASSET, confidenceThreshold, iouThreshold) {
        this.interpreter = interpreter
    }

    private var interpreter: Interpreter? = null
    private val inferenceLock = Any()

    @Volatile
    var lastMaxRawFemaleConfidence: Float = 0f
        private set

    // مصفوفة الخرج مسبقة الحجز لتفادي ضغط مجمع النفايات [1, 7, 14365]
    private val outputArray: Array<Array<FloatArray>> =
        Array(1) { Array(OUTPUT_CHANNELS) { FloatArray(NUM_CANDIDATES) } }

    // حاوية بايتات الإدخال مسبقة الحجز لتحويل Bitmap إلى Float32 NHWC
    private val preallocatedInputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * MODEL_CHANNELS * 4).apply {
            order(ByteOrder.nativeOrder())
        }
    }

    private val pixelArray by lazy { IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE) }

    private var letterboxedBitmap: Bitmap? = null
    private var letterboxCanvas: Canvas? = null
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun getOrCreateLetterbox(): Pair<Bitmap, Canvas> {
        var b = letterboxedBitmap
        var c = letterboxCanvas
        if (b == null || b.isRecycled || c == null) {
            b = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
            c = Canvas(b)
            letterboxedBitmap = b
            letterboxCanvas = c
        }
        return Pair(b, c)
    }

    companion object {
        private const val TAG = "HalalifyGenderDetector"
        const val DEFAULT_MODEL_ASSET = "halalify_v2.tflite"
        const val MODEL_INPUT_SIZE = 416
        const val MODEL_CHANNELS = 3
        const val NUM_CANDIDATES = 14365
        const val OUTPUT_CHANNELS = 7
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.35f
        const val DEFAULT_IOU_THRESHOLD = 0.45f
        const val MAX_DETECTIONS = 100

        private fun safeLogI(msg: String) {
            try {
                Timber.tag(TAG).i(msg)
            } catch (_: Throwable) {
                // Ignore fallback in unmocked environments
            }
        }

        private fun safeLogW(msg: String) {
            try {
                Timber.tag(TAG).w(msg)
            } catch (_: Throwable) {
                // Ignore fallback in unmocked environments
            }
        }

        private fun safeLogE(msg: String, tr: Throwable? = null) {
            try {
                if (tr != null) Timber.tag(TAG).e(tr, msg) else Timber.tag(TAG).e(msg)
            } catch (_: Throwable) {
                // Ignore fallback in unmocked environments
            }
        }
    }

    /**
     * تهيئة المفسر وتحميل أوزان النموذج من مجلد Assets بأمان
     */
    fun initialize(context: Context): Boolean {
        synchronized(inferenceLock) {
            close()
            return try {
                val assetFd = context.assets.openFd(modelAssetPath)
                val modelBuffer = try {
                    FileInputStream(assetFd.fileDescriptor).use { inputStream ->
                        inputStream.channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            assetFd.startOffset,
                            assetFd.declaredLength
                        )
                    }
                } finally {
                    try { assetFd.close() } catch (e: Exception) { safeLogW("Error closing assetFd: ${e.message}") }
                }

                val options = Interpreter.Options().apply {
                    setNumThreads(numThreads)
                    setUseNNAPI(false)
                }
                val interp = Interpreter(modelBuffer, options)
                this.interpreter = interp
                safeLogI("Halalify v2 model initialized successfully from $modelAssetPath")
                true
            } catch (e: Exception) {
                safeLogE("Failed to initialize Halalify v2 model from $modelAssetPath", e)
                close()
                false
            }
        }
    }

    /**
     * تشغيل الاستدلال على صورة Bitmap مع تطبيق معالجة Letterbox التلقائية
     */
    fun detect(inputBitmap: Bitmap): List<HalalifyDetection> {
        synchronized(inferenceLock) {
            val interp = interpreter ?: run {
                safeLogW("Interpreter is not initialized")
                return emptyList()
            }

            val origWidth = inputBitmap.width
            val origHeight = inputBitmap.height
            if (origWidth <= 0 || origHeight <= 0) return emptyList()

            val scale = minOf(
                MODEL_INPUT_SIZE.toFloat() / origWidth,
                MODEL_INPUT_SIZE.toFloat() / origHeight
            )
            val resizedW = (origWidth * scale).roundToInt().coerceAtLeast(1)
            val resizedH = (origHeight * scale).roundToInt().coerceAtLeast(1)
            val padX = (MODEL_INPUT_SIZE - resizedW) / 2f
            val padY = (MODEL_INPUT_SIZE - resizedH) / 2f

            val buffer = preallocatedInputBuffer
            buffer.rewind()

            val (letterboxed, canvas) = getOrCreateLetterbox()
            canvas.drawColor(Color.rgb(114, 114, 114))

            val dstRect = HalalifyDetection.createRectF(padX, padY, padX + resizedW, padY + resizedH)
            canvas.drawBitmap(inputBitmap, null, dstRect, letterboxPaint)

            letterboxed.getPixels(pixelArray, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

            for (pixel in pixelArray) {
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                buffer.putFloat(r)
                buffer.putFloat(g)
                buffer.putFloat(b)
            }
            buffer.rewind()

            interp.run(buffer, outputArray)

            return parseDetections(outputArray, origWidth, origHeight, scale, padX, padY)
        }
    }

    /**
     * تشغيل الاستدلال على مصفوفة بايتات معالجة مسبقاً بحجم [1, 416, 416, 3] Float32
     */
    fun detect(
        inputBuffer: ByteBuffer,
        sourceWidth: Int = MODEL_INPUT_SIZE,
        sourceHeight: Int = MODEL_INPUT_SIZE
    ): List<HalalifyDetection> {
        synchronized(inferenceLock) {
            val interp = interpreter ?: run {
                safeLogW("Interpreter is not initialized")
                return emptyList()
            }

            val scale = minOf(
                MODEL_INPUT_SIZE.toFloat() / sourceWidth,
                MODEL_INPUT_SIZE.toFloat() / sourceHeight
            )
            val resizedW = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
            val resizedH = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
            val padX = (MODEL_INPUT_SIZE - resizedW) / 2f
            val padY = (MODEL_INPUT_SIZE - resizedH) / 2f

            inputBuffer.rewind()
            interp.run(inputBuffer, outputArray)

            return parseDetections(outputArray, sourceWidth, sourceHeight, scale, padX, padY)
        }
    }

    /**
     * فك مصفوفة الخرج واستخراج الصناديق وتطبيق NMS
     */
    internal fun parseDetections(
        output: Array<Array<FloatArray>>,
        origWidth: Int = MODEL_INPUT_SIZE,
        origHeight: Int = MODEL_INPUT_SIZE,
        confidenceThreshold: Float = this.confidenceThreshold,
        iouThreshold: Float = this.iouThreshold
    ): List<HalalifyDetection> {
        val scale = minOf(
            MODEL_INPUT_SIZE.toFloat() / origWidth,
            MODEL_INPUT_SIZE.toFloat() / origHeight
        )
        val resizedW = (origWidth * scale).roundToInt().coerceAtLeast(1)
        val resizedH = (origHeight * scale).roundToInt().coerceAtLeast(1)
        val padX = (MODEL_INPUT_SIZE - resizedW) / 2f
        val padY = (MODEL_INPUT_SIZE - resizedH) / 2f
        return parseDetections(output, origWidth, origHeight, scale, padX, padY, confidenceThreshold, iouThreshold)
    }

    /**
     * فك تفصيلي لمصفوفة الخرج بناءً على بارامترات الـ Letterbox المحددة
     */
    internal fun parseDetections(
        output: Array<Array<FloatArray>>,
        origWidth: Int,
        origHeight: Int,
        scale: Float,
        padX: Float,
        padY: Float,
        confidenceThreshold: Float = this.confidenceThreshold,
        iouThreshold: Float = this.iouThreshold
    ): List<HalalifyDetection> {
        if (output.isEmpty() || output[0].isEmpty()) {
            lastMaxRawFemaleConfidence = 0f
            return emptyList()
        }

        val matrix = output[0]
        val isChannelsFirst = matrix.size == OUTPUT_CHANNELS
        val isChannelsLast = matrix[0].size == OUTPUT_CHANNELS

        if (!isChannelsFirst && !isChannelsLast) {
            safeLogE("Unsupported output shape: [${matrix.size}, ${matrix[0].size}]")
            lastMaxRawFemaleConfidence = 0f
            return emptyList()
        }

        val numCandidates = if (isChannelsFirst) matrix[0].size else matrix.size
        val candidates = ArrayList<HalalifyDetection>(minOf(numCandidates, 128))

        val ch0Row = if (isChannelsFirst) matrix[0] else null
        val ch1Row = if (isChannelsFirst) matrix[1] else null
        val ch2Row = if (isChannelsFirst) matrix[2] else null
        val ch3Row = if (isChannelsFirst) matrix[3] else null
        val femaleRow = if (isChannelsFirst) matrix[4] else null
        val maleRow   = if (isChannelsFirst) matrix[5] else null
        val ignoreRow = if (isChannelsFirst) matrix[6] else null

        var maxFemale = 0f

        for (i in 0 until numCandidates) {
            val sFemale = femaleRow?.get(i) ?: matrix[i][4]
            val sMale   = maleRow?.get(i)   ?: matrix[i][5]
            val sIgnore = ignoreRow?.get(i) ?: matrix[i][6]

            if (!sFemale.isFinite() || !sMale.isFinite() || !sIgnore.isFinite()) continue

            if (sFemale > maxFemale) {
                maxFemale = sFemale
            }

            // الشرط المحدد: إذا كانت القناة 4 (female) أكبر من القنوات الأخرى وثقتها >= 0.35f
            if (sFemale < confidenceThreshold || sFemale <= sMale || sFemale <= sIgnore) {
                continue
            }

            val raw0 = ch0Row?.get(i) ?: matrix[i][0]
            val raw1 = ch1Row?.get(i) ?: matrix[i][1]
            val raw2 = ch2Row?.get(i) ?: matrix[i][2]
            val raw3 = ch3Row?.get(i) ?: matrix[i][3]

            // فك التبديل الحتمي للقنوات الناتج عن تحويل ONNX -> TFLite:
            // Channel 0 = raw_x1 = width / 2.0f
            // Channel 1 = raw_y1 = height / 2.0f
            // Channel 2 = raw_x2 = 2.0f * cx
            // Channel 3 = raw_y2 = 2.0f * cy
            var cx = raw2 * 0.5f
            var cy = raw3 * 0.5f
            var w  = raw0 * 2.0f
            var h  = raw1 * 2.0f

            if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) {
                continue
            }

            // فحص ومعايرة الإحداثيات إذا كانت طبيعية [0..1] أو بكسلات [0..416]
            val largestCoord = maxOf(abs(cx), abs(cy), abs(w), abs(h))
            if (largestCoord <= 1.5f) {
                cx *= MODEL_INPUT_SIZE
                cy *= MODEL_INPUT_SIZE
                w  *= MODEL_INPUT_SIZE
                h  *= MODEL_INPUT_SIZE
            }

            val x1 = cx - w * 0.5f
            val y1 = cy - h * 0.5f
            val x2 = cx + w * 0.5f
            val y2 = cy + h * 0.5f

            // فك الـ Letterbox: طرح الحشو والقسمة على نسبة التصغير (scale)
            val unletterboxX1 = (x1 - padX) / scale
            val unletterboxY1 = (y1 - padY) / scale
            val unletterboxX2 = (x2 - padX) / scale
            val unletterboxY2 = (y2 - padY) / scale

            // تطبيع الإحداثيات لمساحة العرض الأصلية 0..1
            val normLeft = (unletterboxX1 / origWidth).coerceIn(0f, 1f)
            val normTop = (unletterboxY1 / origHeight).coerceIn(0f, 1f)
            val normRight = (unletterboxX2 / origWidth).coerceIn(0f, 1f)
            val normBottom = (unletterboxY2 / origHeight).coerceIn(0f, 1f)

            if (normRight <= normLeft || normBottom <= normTop) continue

            candidates.add(
                HalalifyDetection(
                    isFemale = true,
                    confidence = sFemale,
                    boundingBox = HalalifyDetection.createRectF(normLeft, normTop, normRight, normBottom)
                )
            )
        }

        lastMaxRawFemaleConfidence = maxFemale

        if (candidates.isEmpty()) return emptyList()

        return applyNms(candidates, iouThreshold)
    }

    /**
     * تطبيق خوارزمية Non-Maximum Suppression (NMS) لدمج الصناديق المتراكبة
     */
    internal fun applyNms(
        candidates: MutableList<HalalifyDetection>,
        iouThreshold: Float,
        maxDetections: Int = MAX_DETECTIONS
    ): List<HalalifyDetection> {
        candidates.sortByDescending { it.confidence }

        val selected = ArrayList<HalalifyDetection>(minOf(candidates.size, maxDetections))

        for (candidate in candidates) {
            var suppressed = false
            for (chosen in selected) {
                if (calculateIoU(candidate.boundingBox, chosen.boundingBox) > iouThreshold) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) {
                selected.add(candidate)
                if (selected.size >= maxDetections) break
            }
        }

        return selected
    }

    /**
     * حساب نسبة التقاطع إلى الاتحاد (IoU) بين صندوقين
     */
    internal fun calculateIoU(a: RectF, b: RectF): Float {
        val intersectionLeft = maxOf(a.left, b.left)
        val intersectionTop = maxOf(a.top, b.top)
        val intersectionRight = minOf(a.right, b.right)
        val intersectionBottom = minOf(a.bottom, b.bottom)

        val intersectionW = maxOf(0f, intersectionRight - intersectionLeft)
        val intersectionH = maxOf(0f, intersectionBottom - intersectionTop)
        val intersectionArea = intersectionW * intersectionH

        if (intersectionArea <= 0f) return 0f

        val areaA = maxOf(0f, a.right - a.left) * maxOf(0f, a.bottom - a.top)
        val areaB = maxOf(0f, b.right - b.left) * maxOf(0f, b.bottom - b.top)
        val unionArea = areaA + areaB - intersectionArea

        return if (unionArea > 0f) intersectionArea / unionArea else 0f
    }

    val isInitialized: Boolean
        get() = synchronized(inferenceLock) { interpreter != null }

    override fun close() {
        synchronized(inferenceLock) {
            try {
                interpreter?.close()
            } catch (e: Exception) {
                safeLogE("Error closing TFLite interpreter", e)
            } finally {
                interpreter = null
            }
            try {
                letterboxedBitmap?.let {
                    if (!it.isRecycled) it.recycle()
                }
            } catch (e: Exception) {
                safeLogW("Error recycling letterboxed bitmap: ${e.message}")
            }
            letterboxedBitmap = null
            letterboxCanvas = null
        }
    }
}
