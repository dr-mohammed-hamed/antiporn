package com.drmohammed.antiporn.ai.core.inference

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.drmohammed.antiporn.ai.core.model.ModestyClass
import com.drmohammed.antiporn.ai.core.model.ModestyDetection
import com.drmohammed.antiporn.ai.core.model.ModestyInferenceResult
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * مصنف MobileNetV4 التجريبي الوحيد (يحل محل YOLO في مرحلة التجربة).
 *
 * - الدخل يُحسم runtime من شكل التنسور (المتوقع [1,224,224,3] NHWC).
 * - الخرج الأساسي [1,1000] يُفك عبر [MobileNetOutputDecoder] إلى درجة شبهة
 *   (مجموع softmax لفئات 445/459/638/639/655/775/842، عتبة 0.10).
 * - يطبق نفس واجهة [IModestyDetector] ليبقى التبديل مع YOLO بسطر واحد.
 * - عند الاشتباه يُنتج كشفاً واحداً بإطار كامل (مصنف صورة لا كاشف صناديق).
 */
class MobileNetV4ModestyDetector(
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET,
    private val decoder: MobileNetOutputDecoder = MobileNetOutputDecoder()
) : IModestyDetector {

    override var inputWidth: Int = 224
        private set
    override var inputHeight: Int = 224
        private set
    override var isChannelsLast: Boolean = true
        private set
    var inputConfig: AiInputConfig? = null
        private set

    /** آخر درجة شبهة خام (للتشخيص والمعايرة في Toast التجريبي). */
    @Volatile
    var lastSuspicionScore: Float = 0f
        private set

    @Volatile
    var lastTopClassId: Int = -1
        private set

    private var interpreter: Interpreter? = null
    private var output2d: Array<FloatArray>? = null
    // MAJOR(M7): Interpreter.run is not thread-safe — serialize all detect() calls.
    private val inferenceLock = Any()

    companion object {
        const val DEFAULT_MODEL_ASSET = "mobilenetv4_conv_small_224_fp16.tflite"
        const val MODEL_DISPLAY_NAME = "MobileNetV4-small-224 (ImageNet suspicion ≥10%)"
        private const val TAG = "MobileNetV4Detector"
        /** Upper bound for model-reported dims — guards RowStride/FrameDifferencer allocs. */
        const val MAX_INPUT_DIM = 640
    }

    override fun initialize(context: Context): Boolean {
        // إغلاق أي مفسر سابق قبل إعادة التهيئة لتفادي تسرب الذاكرة الأصلية.
        try { interpreter?.close() } catch (e: Exception) { Log.d(TAG, "Interpreter close notice: ${e.message}") }
        interpreter = null
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
                try { assetFd.close() } catch (e: Exception) { Log.w(TAG, "Error closing assetFd: ${e.message}") }
            }

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(true)
            }
            val interp = Interpreter(modelBuffer, options)
            this.interpreter = interp

            val inShape = interp.getInputTensor(0).shape()
            val outShape = interp.getOutputTensor(0).shape()
            Log.i(TAG, "InShape: ${inShape.contentToString()}, OutShape: ${outShape.contentToString()}")

            // فشل صاخب مع تحرير المفسر وتصفير الحقل — لا يُبقي مفسراً مغلقاً أو معطوباً.
            // --- تحليل شكل الدخل ---
            if (inShape.size != 4) {
                Log.e(TAG, "Unsupported input rank: ${inShape.size}")
                try { interp.close() } catch (e: Exception) { Log.d(TAG, "Interpreter close notice: ${e.message}") }
                this.interpreter = null
                return false
            }
            val kind: AiInputConfig.OutputKind
            val outSize = if (outShape.size == 2) outShape[1] else -1
            kind = when (outSize) {
                1000 -> AiInputConfig.OutputKind.IMAGENET_1000
                1 -> AiInputConfig.OutputKind.BINARY_SIGMOID
                2 -> AiInputConfig.OutputKind.BINARY_SOFTMAX
                else -> {
                    Log.e(TAG, "Unsupported output shape: ${outShape.contentToString()}")
                    try { interp.close() } catch (e: Exception) { Log.d(TAG, "Interpreter close notice: ${e.message}") }
                    this.interpreter = null
                    return false
                }
            }
            // NHWC [1,H,W,3] مقابل NCHW [1,3,H,W]
            if (inShape[3] == 3) {
                isChannelsLast = true
                inputHeight = inShape[1]
                inputWidth = inShape[2]
            } else if (inShape[1] == 3) {
                isChannelsLast = false
                inputHeight = inShape[2]
                inputWidth = inShape[3]
            } else {
                Log.e(TAG, "Cannot resolve channel axis in $inShape")
                try { interp.close() } catch (e: Exception) { Log.d(TAG, "Interpreter close notice: ${e.message}") }
                this.interpreter = null
                return false
            }
            // MAJOR(M8): never trust model-reported dims verbatim (tampered model → 201MB alloc → OOM).
            if (inputWidth <= 0 || inputHeight <= 0 || inputWidth > MAX_INPUT_DIM || inputHeight > MAX_INPUT_DIM) {
                Log.e(TAG, "Rejecting absurd input dims: ${inputWidth}x${inputHeight}")
                try { interp.close() } catch (e: Exception) { Log.d(TAG, "Interpreter close notice: ${e.message}") }
                this.interpreter = null
                return false
            }
            inputConfig = AiInputConfig(inputWidth, inputHeight, isChannelsLast, kind)
            output2d = Array(1) { FloatArray(outSize) }

            Log.i(TAG, "MobileNetV4 ready: ${inputWidth}x${inputHeight} channelsLast=$isChannelsLast kind=$kind")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MobileNetV4 detector ($modelAssetPath)", e)
            try { interpreter?.close() } catch (e: Exception) { Log.d(TAG, "Interpreter close notice: ${e.message}") }
            interpreter = null
            output2d = null
            inputConfig = null
            false
        }
    }

    override fun detect(inputBuffer: ByteBuffer): ModestyInferenceResult {
        synchronized(inferenceLock) {
            val interp = interpreter ?: return ModestyInferenceResult.EMPTY
            val out = output2d ?: return ModestyInferenceResult.EMPTY
            val start = SystemClock.elapsedRealtime()
            return try {
                inputBuffer.rewind()
                interp.run(inputBuffer, out)
                val latency = SystemClock.elapsedRealtime() - start
                val decoded = decoder.decode(out)
                lastSuspicionScore = decoded.suspicionScore
                lastTopClassId = decoded.topClassId

                if (!decoded.isSuspicious) {
                    return ModestyInferenceResult(
                        detections = emptyList(),
                        topViolation = null,
                        hasViolation = false,
                        latencyMs = latency
                    )
                }
                val size = (inputConfig?.width ?: 224).toFloat()
                val detection = ModestyDetection(
                    ModestyClass.EXPOSED_SKIN,
                    decoded.suspicionScore.coerceIn(0f, 1f),
                    RectF(0f, 0f, size, size)
                )
                ModestyInferenceResult(
                    detections = listOf(detection),
                    topViolation = detection,
                    hasViolation = true,
                    latencyMs = latency
                )
            } catch (e: Exception) {
                Log.e(TAG, "MobileNetV4 inference error", e)
                ModestyInferenceResult.EMPTY
            }
        }
    }

    override fun close() {
        try {
            interpreter?.close()
            interpreter = null
            output2d = null
            Log.d(TAG, "MobileNetV4 detector closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing MobileNetV4 interpreter", e)
        }
    }
}
