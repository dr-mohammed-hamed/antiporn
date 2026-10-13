package com.drmohammed.antiporn.ai.core.pipeline

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * مستخرج البكسلات المحصن ضد حشو سطور الذاكرة (Row Stride & Padding)
 * يقرأ سطور الصورة سطراً بسطر ويتجاوز الـ Padding مع تحويل سريع لـ Float32 في أقل من 2ms
 */
class RowStrideExtractor(
    private val width: Int = 224,
    private val height: Int = 224,
    private val isChannelsLast: Boolean = true, // NHWC: [1, H, W, 3] مقابل NCHW: [1, 3, H, W]
    private val normalization: Normalization = Normalization.IMAGENET_MEAN_STD
) {
    init {
        // MAJOR(M8): cap construction dims — allocateDirect(201MB) on tampered 4K dims → OOM.
        require(width in 1..MAX_DIM && height in 1..MAX_DIM) { "Absurd extractor dims ${width}x${height}" }
    }
    /** نمط تطبيع قيم البكسل قبل الاستدلال. */
    enum class Normalization {
        /** [0,1] الخام — متوافق مع YOLO. */
        UNIT_01,

        /** تطبيع ImageNet القياسي (timm): (x/255 - mean)/std. */
        IMAGENET_MEAN_STD
    }

    companion object {
        const val MAX_DIM = 640
    }
    // مصفوفة بايتات السطر الواحد — تُحجم حسب pixelStride الفعلي وقت التشغيل (انظر extract).
    // الحد الأقصى 4 B/px يغطي RGBA_8888؛ الأحجام الأصغر تُقرأ جزئياً فقط.
    private val rowBytes = ByteArray(width * 4)

    // مصفوفة الذاكرة المباشرة بحجم العتاد الأصلي Native Byte Order.
    // MAJOR(M7): private — exposed mutable ByteBuffer aliases floatBuffer and breaks detect() locking.
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * width * height * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val floatBuffer: FloatBuffer = inputBuffer.asFloatBuffer()

    // جدول القسمة السريع لـ 256 قيمة لتحقيق تحويل البايت في زمن يقارب الصفر
    private val normLookup = FloatArray(256) { it / 255.0f }

    // جداول ImageNet لكل قناة: (x/255 - mean)/std — محسوبة مسبقاً لتفادي القسمة وقت التشغيل
    private val imagenetR = FloatArray(256) { ((it / 255.0f) - 0.485f) / 0.229f }
    private val imagenetG = FloatArray(256) { ((it / 255.0f) - 0.456f) / 0.224f }
    private val imagenetB = FloatArray(256) { ((it / 255.0f) - 0.406f) / 0.225f }

    /**
     * استخراج البكسلات من كائن Image وتحويلها إلى مصفوفة Float32 المعيارية للنموذج
     */
    @Synchronized
    fun extract(image: Image): ByteBuffer {
        inputBuffer.rewind()
        floatBuffer.rewind()

        val planes = image.planes
        // MAJOR(M4): never rely on AIOOBE — validate planes/dims/strides loudly (service catch drops frame).
        require(planes.isNotEmpty()) { "Image has no planes" }
        require(image.width >= width && image.height >= height) {
            "Image ${image.width}x${image.height} smaller than extractor ${width}x${height}"
        }
        val plane = planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride // عادة 4 في RGBA_8888
        require(pixelStride in 1..4 && rowStride >= width * pixelStride) {
            "Unsupported strides: row=$rowStride px=$pixelStride w=$width"
        }
        // MAJOR(M4): copy width*pixelStride bytes per row (not hardcoded width*4).
        val rowCopyLen = width * pixelStride

        val channelSize = width * height

        if (isChannelsLast) {
            // نمط NHWC: [1, H, W, 3] (النمط القياسي لـ TFLite)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(rowBytes, 0, rowCopyLen)

                var offset = 0
                for (x in 0 until width) {
                    val r = rowBytes[offset].toInt() and 0xFF
                    val g = rowBytes[offset + 1].toInt() and 0xFF
                    val b = rowBytes[offset + 2].toInt() and 0xFF
                    // alpha = rowBytes[offset + 3] يتم تجاهله

                    if (normalization == Normalization.IMAGENET_MEAN_STD) {
                        floatBuffer.put(imagenetR[r])
                        floatBuffer.put(imagenetG[g])
                        floatBuffer.put(imagenetB[b])
                    } else {
                        floatBuffer.put(normLookup[r])
                        floatBuffer.put(normLookup[g])
                        floatBuffer.put(normLookup[b])
                    }

                    offset += pixelStride
                }
            }
        } else {
            // نمط NCHW: [1, 3, H, W]
            val rOffset = 0
            val gOffset = channelSize
            val bOffset = channelSize * 2

            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(rowBytes, 0, rowCopyLen)

                val yOffset = y * width
                var offset = 0
                for (x in 0 until width) {
                    val r = rowBytes[offset].toInt() and 0xFF
                    val g = rowBytes[offset + 1].toInt() and 0xFF
                    val b = rowBytes[offset + 2].toInt() and 0xFF

                    val idx = yOffset + x
                    if (normalization == Normalization.IMAGENET_MEAN_STD) {
                        floatBuffer.put(rOffset + idx, imagenetR[r])
                        floatBuffer.put(gOffset + idx, imagenetG[g])
                        floatBuffer.put(bOffset + idx, imagenetB[b])
                    } else {
                        floatBuffer.put(rOffset + idx, normLookup[r])
                        floatBuffer.put(gOffset + idx, normLookup[g])
                        floatBuffer.put(bOffset + idx, normLookup[b])
                    }

                    offset += pixelStride
                }
            }
        }

        inputBuffer.rewind()
        return inputBuffer
    }
}
