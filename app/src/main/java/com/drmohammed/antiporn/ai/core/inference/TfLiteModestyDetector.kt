package com.drmohammed.antiporn.ai.core.inference

import android.content.Context
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
 * محرك الاستدلال المبني على مكتبة TensorFlow Lite
 * يشغل نموذج YOLO11-Nano المخصص لحفظ العورات بحجم ذاكرة مسبق الحجز ودعم لمسرع العتاد
 */
class TfLiteModestyDetector(
    private val modelAssetPath: String = "custom_modesty_yolo11n_320.tflite",
    private val postProcessor: ModestyPostProcessor = ModestyPostProcessor()
) : IModestyDetector {

    private var interpreter: Interpreter? = null
    override var inputWidth: Int = 320
        private set
    override var inputHeight: Int = 320
        private set
    override var isChannelsLast: Boolean = true
        private set

    private var outputArray: Array<Array<FloatArray>>? = null
    // MAJOR(M7): Interpreter.run is not thread-safe — serialize all detect() calls.
    private val inferenceLock = Any()

    companion object {
        private const val TAG = "TfLiteModestyDetector"
    }

    override fun initialize(context: Context): Boolean {
        // إغلاق أي مفسر سابق قبل إعادة التهيئة لتفادي تسرب الذاكرة الأصلية.
        try { interpreter?.close() } catch (_: Exception) { }
        interpreter = null
        return try {
            val assetFd = context.assets.openFd(modelAssetPath)
            val modelBuffer = try {
                FileInputStream(assetFd.fileDescriptor).use { inputStream ->
                    inputStream.channel.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)
                }
            } finally {
                try { assetFd.close() } catch (_: Exception) { }
            }

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(true)
            }

            val interp = Interpreter(modelBuffer, options)
            this.interpreter = interp

            // فحص أبعاد مصفوفة الإدخال — فشل صاخب بدل الافتراض الصامت.
            // أي فشل يحرر المفسر ويصفّر الحقل لتفادي dangling closed interpreter.
            val inputShape = interp.getInputTensor(0).shape()
            if (inputShape.size != 4) {
                Log.e(TAG, "Unsupported input rank: ${inputShape.size}")
                try { interp.close() } catch (_: Exception) { }
                this.interpreter = null
                return false
            }

            // فحص أبعاد مصفوفة الإخراج وتخصيص المصفوفة مسبقاً — فشل صاخب.
            val outputShape = interp.getOutputTensor(0).shape()
            if (outputShape.size != 3) {
                Log.e(TAG, "Unsupported output rank: ${outputShape.contentToString()}")
                try { interp.close() } catch (_: Exception) { }
                this.interpreter = null
                return false
            }
            val dim1 = outputShape[1]
            val dim2 = outputShape[2]
            if (!((dim1 == 7 && dim2 == 2100) || (dim1 == 2100 && dim2 == 7))) {
                Log.e(TAG, "Unsupported YOLO output dims: ${outputShape.contentToString()}")
                try { interp.close() } catch (_: Exception) { }
                this.interpreter = null
                return false
            }

            isChannelsLast = when {
                inputShape[3] == 3 -> true
                inputShape[1] == 3 -> false
                else -> {
                    Log.e(TAG, "Cannot resolve channel axis in ${inputShape.contentToString()}")
                    try { interp.close() } catch (_: Exception) { }
                    this.interpreter = null
                    return false
                }
            }
            if (isChannelsLast) {
                inputHeight = inputShape[1]
                inputWidth = inputShape[2]
            } else {
                inputHeight = inputShape[2]
                inputWidth = inputShape[3]
            }
            // MAJOR(M8): cap model-reported dims (tampered model → OOM in extractor/differencer).
            if (inputWidth <= 0 || inputHeight <= 0 || inputWidth > 640 || inputHeight > 640) {
                Log.e(TAG, "Rejecting absurd input dims: ${inputWidth}x${inputHeight}")
                try { interp.close() } catch (_: Exception) { }
                this.interpreter = null
                return false
            }
            outputArray = Array(1) { Array(dim1) { FloatArray(dim2) } }

            Log.i(TAG, "TFLite Modesty model loaded successfully. InShape: ${inputShape.contentToString()}, OutShape: ${outputShape.contentToString()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite Modesty detector", e)
            try { interpreter?.close() } catch (_: Exception) { }
            interpreter = null
            outputArray = null
            false
        }
    }

    override fun detect(inputBuffer: ByteBuffer): ModestyInferenceResult {
        synchronized(inferenceLock) {
            val interp = interpreter ?: return ModestyInferenceResult.EMPTY
            val out = outputArray ?: return ModestyInferenceResult.EMPTY

            val startTime = SystemClock.elapsedRealtime()

            return try {
                inputBuffer.rewind()
                interp.run(inputBuffer, out)
                val latency = SystemClock.elapsedRealtime() - startTime

                val detections = postProcessor.process(out)
                val hasAnchor = detections.any {
                    it.classType == ModestyClass.SAFE_MODEST_ANCHOR && it.confidence >= 0.60f
                }
                val topViolation = detections
                    .filter { it.classType.isViolation }
                    .firstOrNull { violation ->
                        val threshold = if (hasAnchor) 0.70f else 0.35f
                        violation.confidence >= threshold
                    }

                ModestyInferenceResult(
                    detections = detections,
                    topViolation = topViolation,
                    hasViolation = topViolation != null,
                    latencyMs = latency
                )
            } catch (e: Exception) {
                Log.e(TAG, "Inference error", e)
                ModestyInferenceResult.EMPTY
            }
        }
    }

    override fun close() {
        try {
            interpreter?.close()
            interpreter = null
            outputArray = null
            Log.d(TAG, "TFLite Modesty detector closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TFLite interpreter", e)
        }
    }
}
