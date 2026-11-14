package com.drmohammed.antiporn.ai.core.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.drmohammed.antiporn.ai.core.model.NudeNetClass
import com.drmohammed.antiporn.ai.core.model.NudeNetDetection
import com.drmohammed.antiporn.ai.core.model.NudeNetResult
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * محرك الاستدلال لنموذج NudeNet v3 (YOLOv8 Nano - 320x320)
 * مبني عبر محرك ONNX Runtime Mobile الرسمي لتشريح العورات وتحديد مستويات المخالفة.
 */
class NudeNetDetector(
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET,
    private val confThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    private val iouThreshold: Float = DEFAULT_IOU_THRESHOLD
) : AutoCloseable {

    companion object {
        private const val TAG = "NudeNetDetector"
        const val DEFAULT_MODEL_ASSET = "nudenet_320.onnx"
        const val MODEL_INPUT_SIZE = 320
        const val NUM_CLASSES = 18
        const val OUTPUT_CHANNELS = 22 // 4 coordinates + 18 classes
        const val NUM_CANDIDATES = 2100

        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.35f
        const val DEFAULT_IOU_THRESHOLD = 0.45f
        const val HARD_PORN_THRESHOLD = 0.45f
        const val SOFT_PORN_THRESHOLD = 0.40f
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputName: String = "images"

    private val inferenceLock = Any()
    private var floatBuffer: FloatBuffer? = null
    private var pixelArray: IntArray? = null

    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val letterboxDstRect = RectF()
    private var letterboxBitmap: Bitmap? = null
    private var letterboxCanvas: Canvas? = null

    private fun getOrCreateLetterbox(): Pair<Bitmap, Canvas> {
        var b = letterboxBitmap
        var c = letterboxCanvas
        if (b == null || b.isRecycled || c == null) {
            b = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
            c = Canvas(b)
            letterboxBitmap = b
            letterboxCanvas = c
        }
        return Pair(b, c)
    }

    constructor(context: Context) : this() {
        initialize(context)
    }

    fun initialize(context: Context): Boolean {
        synchronized(inferenceLock) {
            close()
            return try {
                floatBuffer = FloatBuffer.allocate(1 * 3 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
                pixelArray = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)

                val env = OrtEnvironment.getEnvironment()
                ortEnv = env

                val modelBytes = context.assets.open(modelAssetPath).use { it.readBytes() }
                val session = OrtSession.SessionOptions().use { sessionOptions ->
                    sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    sessionOptions.setIntraOpNumThreads(2)
                    env.createSession(modelBytes, sessionOptions)
                }
                ortSession = session

                inputName = session.inputNames.firstOrNull() ?: "images"
                Log.i(TAG, "NudeNetDetector initialized successfully with model: $modelAssetPath (input=$inputName)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize NudeNetDetector", e)
                false
            }
        }
    }

    /**
     * تشغيل الاستدلال التشريحي على صورة الشاشة الملتقطة (Bitmap)
     */
    fun detect(bitmap: Bitmap): NudeNetResult {
        synchronized(inferenceLock) {
            val session = ortSession ?: run {
                Log.w(TAG, "Session is not initialized")
                return NudeNetResult(emptyList(), hasHardPorn = false, hasSoftPorn = false, latencyMs = 0L)
            }
            val env = ortEnv ?: run {
                return NudeNetResult(emptyList(), hasHardPorn = false, hasSoftPorn = false, latencyMs = 0L)
            }

            val tStart = SystemClock.elapsedRealtime()
            val origWidth = bitmap.width
            val origHeight = bitmap.height

            val buffer = floatBuffer ?: FloatBuffer.allocate(1 * 3 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE).also { floatBuffer = it }
            val pixels = pixelArray ?: IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE).also { pixelArray = it }

            // 1. حساب قياسات Letterbox (المحافظة على النسبة الأبعاد الأصلية دون تشويه)
            val scale = min(
                MODEL_INPUT_SIZE.toFloat() / origWidth,
                MODEL_INPUT_SIZE.toFloat() / origHeight
            )
            val resizedW = (origWidth * scale).roundToInt().coerceAtLeast(1)
            val resizedH = (origHeight * scale).roundToInt().coerceAtLeast(1)
            val padX = (MODEL_INPUT_SIZE - resizedW) / 2f
            val padY = (MODEL_INPUT_SIZE - resizedH) / 2f

            val (letterboxed, canvas) = getOrCreateLetterbox()
            canvas.drawColor(Color.BLACK)

            letterboxDstRect.set(padX, padY, padX + resizedW, padY + resizedH)
            canvas.drawBitmap(bitmap, null, letterboxDstRect, letterboxPaint)

            letterboxed.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

            // 2. تعبئة البايتات بنمط NCHW [1, 3, 320, 320] مع تطبيع [0, 1]
            buffer.rewind()
            val channelSize = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE
            val rOffset = 0
            val gOffset = channelSize
            val bOffset = channelSize * 2

            val backingArray = buffer.array()
            for (i in 0 until channelSize) {
                val pixel = pixels[i]
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f

                backingArray[rOffset + i] = r
                backingArray[gOffset + i] = g
                backingArray[bOffset + i] = b
            }
            buffer.position(channelSize * 3)
            buffer.rewind()

            // 3. تشغيل الاستدلال عبر محرك ONNX بأمان تام لمنع تسريب الذاكرة
            val shape = longArrayOf(1, 3, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())
            val tensor = OnnxTensor.createTensor(env, buffer, shape)

            val rawOutput = try {
                session.run(Collections.singletonMap(inputName, tensor)).use { outputs ->
                    if (outputs.size() > 0) {
                        @Suppress("UNCHECKED_CAST")
                        outputs.get(0).value as? Array<Array<FloatArray>>
                    } else {
                        null
                    }
                }
            } finally {
                tensor.close()
            }

            val latency = SystemClock.elapsedRealtime() - tStart

            if (rawOutput == null || rawOutput.isEmpty() || rawOutput[0].isEmpty()) {
                return NudeNetResult(emptyList(), hasHardPorn = false, hasSoftPorn = false, latencyMs = latency)
            }

            val detections = parseDetections(rawOutput[0], origWidth, origHeight, scale, padX, padY)
            val hasHard = detections.any { it.isHardPorn && it.confidence >= HARD_PORN_THRESHOLD }
            val hasSoft = detections.any { it.isSoftPorn && it.confidence >= SOFT_PORN_THRESHOLD }

            return NudeNetResult(detections, hasHardPorn = hasHard, hasSoftPorn = hasSoft, latencyMs = latency)
        }
    }

    /**
     * فك مصفوفة الخرج [22, 2100] وتطبيق خوارزمية NMS
     */
    internal fun parseDetections(
        matrix: Array<FloatArray>,
        origWidth: Int,
        origHeight: Int,
        scale: Float,
        padX: Float,
        padY: Float
    ): List<NudeNetDetection> {
        val numCandidates = matrix[0].size
        val boxes = mutableListOf<RectF>()
        val scores = mutableListOf<Float>()
        val classTypes = mutableListOf<NudeNetClass>()

        for (i in 0 until numCandidates) {
            // البحث عن الفئة ذات أعلى نسبة ثقة بين الفئات الـ 18 (القنوات من 4 إلى 21)
            var maxScore = 0f
            var bestClassId = -1

            for (c in 0 until NUM_CLASSES) {
                val s = matrix[4 + c][i]
                if (s > maxScore) {
                    maxScore = s
                    bestClassId = c
                }
            }

            if (maxScore >= confThreshold && bestClassId != -1) {
                val cls = NudeNetClass.fromId(bestClassId) ?: continue

                val cx = matrix[0][i]
                val cy = matrix[1][i]
                val w = matrix[2][i]
                val h = matrix[3][i]

                val xMin = cx - w / 2f
                val yMin = cy - h / 2f

                // إرجاع الإحداثيات إلى أبعاد الشاشة الحقيقية بإلغاء تأثير الـ Letterbox
                val origX = max(0f, (xMin - padX) / scale)
                val origY = max(0f, (yMin - padY) / scale)
                val origW = w / scale
                val origH = h / scale

                val box = RectF(
                    origX,
                    origY,
                    min(origWidth.toFloat(), origX + origW),
                    min(origHeight.toFloat(), origY + origH)
                )

                boxes.add(box)
                scores.add(maxScore)
                classTypes.add(cls)
            }
        }

        // تطبيق قمع غير القيم العظمى (NMS)
        val selectedIndices = applyNms(boxes, scores, iouThreshold)
        val finalDetections = mutableListOf<NudeNetDetection>()

        for (idx in selectedIndices) {
            finalDetections.add(
                NudeNetDetection(
                    classType = classTypes[idx],
                    confidence = scores[idx],
                    boundingBox = boxes[idx]
                )
            )
        }

        return finalDetections
    }

    internal fun applyNms(boxes: List<RectF>, scores: List<Float>, iouThreshold: Float): List<Int> {
        val sortedIndices = scores.indices.sortedByDescending { scores[it] }.toMutableList()
        val selected = mutableListOf<Int>()

        while (sortedIndices.isNotEmpty()) {
            val current = sortedIndices.removeAt(0)
            selected.add(current)

            val currentBox = boxes[current]
            val iterator = sortedIndices.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                val nextBox = boxes[next]
                if (calculateIoU(currentBox, nextBox) >= iouThreshold) {
                    iterator.remove()
                }
            }
        }

        return selected
    }

    internal fun calculateIoU(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)

        val unionArea = areaA + areaB - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    override fun close() {
        synchronized(inferenceLock) {
            try { ortSession?.close() } catch (_: Exception) { }
            try { ortEnv?.close() } catch (_: Exception) { }
            ortSession = null
            ortEnv = null
            floatBuffer = null
            pixelArray = null
            try {
                letterboxBitmap?.recycle()
            } catch (_: Exception) { }
            letterboxBitmap = null
            letterboxCanvas = null
        }
    }
}
