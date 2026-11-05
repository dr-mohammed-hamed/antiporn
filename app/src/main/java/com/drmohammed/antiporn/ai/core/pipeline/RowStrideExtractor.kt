package com.drmohammed.antiporn.ai.core.pipeline

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * مستخرج البكسلات المحصن ضد حشو سطور الذاكرة (Row Stride & Padding)
 * يقرأ سطور الصورة سطراً بسطر ويتجاوز الـ Padding مع تحويل سريع في أقل من 1ms
 * يدعم كلاً من Float32 وFloat16 (Half-precision) عبر جداول تحويل مسبقة الحساب
 */
class RowStrideExtractor(
    private val width: Int = 224,
    private val height: Int = 224,
    private val isChannelsLast: Boolean = true, // NHWC: [1, H, W, 3] مقابل NCHW: [1, 3, H, W]
    private val normalization: Normalization = Normalization.IMAGENET_MEAN_STD,
    private val dataType: DataType = DataType.FLOAT32
) {
    init {
        // Memory protection: cap construction dims to avoid OOM on unexpected 4K input.
        require(width in 1..MAX_DIM && height in 1..MAX_DIM) { "Absurd extractor dims ${width}x${height}" }
    }
    /** نمط تطبيع قيم البكسل قبل الاستدلال. */
    enum class Normalization {
        /** [0,1] الخام — متوافق مع YOLO. */
        UNIT_01,

        /** تطبيع ImageNet القياسي (timm): (x/255 - mean)/std. */
        IMAGENET_MEAN_STD
    }

    /** نوع دقة البيانات المطلوب تغذيتها للنموذج. */
    enum class DataType {
        FLOAT32,
        FLOAT16
    }

    companion object {
        const val MAX_DIM = 640

        /**
         * تحويل قيمة float إلى 16-bit half precision (IEEE 754-2008) بصيغة Short
         * متوافق 100% مع JVM وأندرويد دون الحاجة لأي مكتبات خارجية.
         */
        fun floatToHalf(fval: Float): Short {
            val fbits = java.lang.Float.floatToIntBits(fval)
            val sign = (fbits ushr 16) and 0x8000
            val valBits = (fbits and 0x7fffffff) + 0x1000
            if (valBits >= 0x47800000) {
                if ((fbits and 0x7fffffff) >= 0x47800000) {
                    if (valBits < 0x7f800000) return (sign or 0x7c00).toShort()
                    return (sign or 0x7c00 or ((fbits and 0x007fffff) ushr 13)).toShort()
                }
                return (sign or 0x7bff).toShort()
            }
            if (valBits >= 0x38800000) return (sign or ((valBits - 0x38000000) ushr 13)).toShort()
            if (valBits < 0x33000000) return sign.toShort()
            val sub = (fbits and 0x7fffffff) ushr 23
            return (sign or ((((fbits and 0x7fffff) or 0x800000) + (0x800000 ushr (sub - 102))) ushr (126 - sub))).toShort()
        }

        // جدول تحويل Float16 المحسوب مسبقاً لـ 256 قيمة لتحقيق تحويل بكسل في زمن يقارب الصفر
        val FP16_UNIT_LOOKUP = ShortArray(256) { floatToHalf(it / 255.0f) }
    }
    // مصفوفة بايتات السطر الواحد — تُحجم حسب pixelStride الفعلي وقت التشغيل وعرض الصورة.
    // الحد الأدنى 1080 يغطي شاشات الهواتف القياسية؛ ويعاد التحجيم عند الحاجة لتفادي الـ OOM.
    private var rowBytes = ByteArray(maxOf(width, 1080) * 4)

    private val bytesPerElement = if (dataType == DataType.FLOAT16) 2 else 4

    // مصفوفة الذاكرة المباشرة بحجم العتاد الأصلي Native Byte Order.
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * width * height * 3 * bytesPerElement).apply {
        order(ByteOrder.nativeOrder())
    }
    private val floatBuffer: FloatBuffer? = if (dataType == DataType.FLOAT32) inputBuffer.asFloatBuffer() else null
    private val shortBuffer: ShortBuffer? = if (dataType == DataType.FLOAT16) inputBuffer.asShortBuffer() else null

    // جدول القسمة السريع لـ 256 قيمة لتحقيق تحويل البايت في زمن يقارب الصفر
    private val normLookup = FloatArray(256) { it / 255.0f }

    // جداول ImageNet لكل قناة: (x/255 - mean)/std — محسوبة مسبقاً لتفادي القسمة وقت التشغيل
    private val imagenetR = FloatArray(256) { ((it / 255.0f) - 0.485f) / 0.229f }
    private val imagenetG = FloatArray(256) { ((it / 255.0f) - 0.456f) / 0.224f }
    private val imagenetB = FloatArray(256) { ((it / 255.0f) - 0.406f) / 0.225f }

    /**
     * استخراج البكسلات من كائن Image وتحويلها إلى مصفوفة الذاكرة المعيارية للنموذج
     */
    @Synchronized
    fun extract(image: Image): ByteBuffer {
        inputBuffer.rewind()
        floatBuffer?.rewind()
        shortBuffer?.rewind()

        val planes = image.planes
        // Defensive validation: validate planes/dims/strides explicitly before buffer operations.
        require(planes.isNotEmpty()) { "Image has no planes" }
        require(image.width > 0 && image.height > 0) {
            "Invalid image dimensions: ${image.width}x${image.height}"
        }
        val plane = planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride // عادة 4 في RGBA_8888
        require(pixelStride in 1..4 && rowStride >= image.width * pixelStride) {
            "Unsupported strides: row=$rowStride px=$pixelStride w=${image.width}"
        }
        val rowCopyLen = image.width * pixelStride
        if (rowBytes.size < rowCopyLen) {
            rowBytes = ByteArray(rowCopyLen)
        }
        val channelSize = width * height
        val scaleX = image.width.toFloat() / width
        val scaleY = image.height.toFloat() / height

        if (dataType == DataType.FLOAT16) {
            val sb = checkNotNull(shortBuffer) { "shortBuffer null in FLOAT16 mode" }
            if (isChannelsLast) {
                // نمط NHWC: [1, H, W, 3]
                for (y in 0 until height) {
                    val srcY = (y * scaleY).toInt().coerceAtMost(image.height - 1)
                    buffer.position(srcY * rowStride)
                    val readLen = minOf(rowCopyLen, buffer.remaining())
                    buffer.get(rowBytes, 0, readLen)

                    for (x in 0 until width) {
                        val srcX = (x * scaleX).toInt().coerceAtMost(image.width - 1)
                        val offset = srcX * pixelStride
                        val r = rowBytes[offset].toInt() and 0xFF
                        val g = rowBytes[offset + 1].toInt() and 0xFF
                        val b = rowBytes[offset + 2].toInt() and 0xFF

                        sb.put(FP16_UNIT_LOOKUP[r])
                        sb.put(FP16_UNIT_LOOKUP[g])
                        sb.put(FP16_UNIT_LOOKUP[b])
                    }
                }
            } else {
                // نمط NCHW: [1, 3, H, W]
                val rOffset = 0
                val gOffset = channelSize
                val bOffset = channelSize * 2

                for (y in 0 until height) {
                    val srcY = (y * scaleY).toInt().coerceAtMost(image.height - 1)
                    buffer.position(srcY * rowStride)
                    val readLen = minOf(rowCopyLen, buffer.remaining())
                    buffer.get(rowBytes, 0, readLen)

                    val yOffset = y * width
                    for (x in 0 until width) {
                        val srcX = (x * scaleX).toInt().coerceAtMost(image.width - 1)
                        val offset = srcX * pixelStride
                        val r = rowBytes[offset].toInt() and 0xFF
                        val g = rowBytes[offset + 1].toInt() and 0xFF
                        val b = rowBytes[offset + 2].toInt() and 0xFF

                        val idx = yOffset + x
                        sb.put(rOffset + idx, FP16_UNIT_LOOKUP[r])
                        sb.put(gOffset + idx, FP16_UNIT_LOOKUP[g])
                        sb.put(bOffset + idx, FP16_UNIT_LOOKUP[b])
                    }
                }
            }
        } else {
            val fb = checkNotNull(floatBuffer) { "floatBuffer null in FLOAT32 mode" }
            if (isChannelsLast) {
                // نمط NHWC: [1, H, W, 3] (النمط القياسي لـ TFLite)
                for (y in 0 until height) {
                    val srcY = (y * scaleY).toInt().coerceAtMost(image.height - 1)
                    buffer.position(srcY * rowStride)
                    val readLen = minOf(rowCopyLen, buffer.remaining())
                    buffer.get(rowBytes, 0, readLen)

                    for (x in 0 until width) {
                        val srcX = (x * scaleX).toInt().coerceAtMost(image.width - 1)
                        val offset = srcX * pixelStride
                        val r = rowBytes[offset].toInt() and 0xFF
                        val g = rowBytes[offset + 1].toInt() and 0xFF
                        val b = rowBytes[offset + 2].toInt() and 0xFF

                        if (normalization == Normalization.IMAGENET_MEAN_STD) {
                            fb.put(imagenetR[r])
                            fb.put(imagenetG[g])
                            fb.put(imagenetB[b])
                        } else {
                            fb.put(normLookup[r])
                            fb.put(normLookup[g])
                            fb.put(normLookup[b])
                        }
                    }
                }
            } else {
                // نمط NCHW: [1, 3, H, W]
                val rOffset = 0
                val gOffset = channelSize
                val bOffset = channelSize * 2

                for (y in 0 until height) {
                    val srcY = (y * scaleY).toInt().coerceAtMost(image.height - 1)
                    buffer.position(srcY * rowStride)
                    val readLen = minOf(rowCopyLen, buffer.remaining())
                    buffer.get(rowBytes, 0, readLen)

                    val yOffset = y * width
                    for (x in 0 until width) {
                        val srcX = (x * scaleX).toInt().coerceAtMost(image.width - 1)
                        val offset = srcX * pixelStride
                        val r = rowBytes[offset].toInt() and 0xFF
                        val g = rowBytes[offset + 1].toInt() and 0xFF
                        val b = rowBytes[offset + 2].toInt() and 0xFF

                        val idx = yOffset + x
                        if (normalization == Normalization.IMAGENET_MEAN_STD) {
                            fb.put(rOffset + idx, imagenetR[r])
                            fb.put(gOffset + idx, imagenetG[g])
                            fb.put(bOffset + idx, imagenetB[b])
                        } else {
                            fb.put(rOffset + idx, normLookup[r])
                            fb.put(gOffset + idx, normLookup[g])
                            fb.put(bOffset + idx, normLookup[b])
                        }
                    }
                }
            }
        }

        inputBuffer.rewind()
        return inputBuffer
    }
}
