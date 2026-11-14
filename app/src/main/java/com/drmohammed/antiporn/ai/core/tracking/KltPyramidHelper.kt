package com.drmohammed.antiporn.ai.core.tracking

import java.nio.ByteBuffer

/**
 * محرك استخراج الإضاءة (Luma) وبناء الهرم البصري ثنائي الطبقات (2-Level Pyramid).
 */
internal object KltPyramidHelper {

    /**
     * استخراج قناة الإضاءة (Luma) بدقة وسرعة من مخزن البايتات الخام دون تخصيص ذاكرة.
     */
    fun extractLuma(
        rawBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        dstLuma: ByteArray
    ) {
        val bufferCap = rawBuffer.capacity()

        if (pixelStride == 1) {
            // معالجة YUV أو Luma خالصة مباشرة
            for (y in 0 until height) {
                val srcRow = y * rowStride
                val dstRow = y * width
                for (x in 0 until width) {
                    val offset = srcRow + x
                    if (offset < bufferCap) {
                        dstLuma[dstRow + x] = rawBuffer.get(offset)
                    }
                }
            }
        } else {
            // معالجة RGBA_8888 (حساب الإضاءة السريعة Y = (77*R + 150*G + 29*B) >> 8)
            for (y in 0 until height) {
                val srcRow = y * rowStride
                val dstRow = y * width
                for (x in 0 until width) {
                    val offset = srcRow + x * pixelStride
                    if (offset + 2 < bufferCap) {
                        val r = rawBuffer.get(offset).toInt() and 0xFF
                        val g = rawBuffer.get(offset + 1).toInt() and 0xFF
                        val b = rawBuffer.get(offset + 2).toInt() and 0xFF
                        val luma = (r * 77 + g * 150 + b * 29) ushr 8
                        dstLuma[dstRow + x] = luma.toByte()
                    }
                }
            }
        }
    }

    /**
     * بناء طبقة هرمية بتصغير الإضاءة بمقدار 2x عبر أخذ متوسط كتل 2x2.
     */
    fun buildPyramidLevel(
        srcLuma: ByteArray,
        dstLuma: ByteArray,
        srcWidth: Int,
        srcHeight: Int
    ) {
        val dstWidth = srcWidth shr 1
        val dstHeight = srcHeight shr 1

        for (dy in 0 until dstHeight) {
            val sy0 = dy shl 1
            val row0 = sy0 * srcWidth
            val row1 = (sy0 + 1) * srcWidth
            val outRow = dy * dstWidth

            for (dx in 0 until dstWidth) {
                val sx0 = dx shl 1
                val p00 = srcLuma[row0 + sx0].toInt() and 0xFF
                val p01 = srcLuma[row0 + sx0 + 1].toInt() and 0xFF
                val p10 = srcLuma[row1 + sx0].toInt() and 0xFF
                val p11 = srcLuma[row1 + sx0 + 1].toInt() and 0xFF

                val avg = (p00 + p01 + p10 + p11 + 2) ushr 2
                dstLuma[outRow + dx] = avg.toByte()
            }
        }
    }

    /**
     * استكمال فرعي للبكسل ثنائي الخطية (Sub-pixel Bilinear Interpolation).
     */
    fun sampleBilinear(
        img: ByteArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float
    ): Float {
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = x0 + 1
        val y1 = y0 + 1

        val fx = x - x0
        val fy = y - y0

        val row0 = y0 * width
        val row1 = y1 * width

        val p00 = img[row0 + x0].toInt() and 0xFF
        val p10 = img[row0 + x1].toInt() and 0xFF
        val p01 = img[row1 + x0].toInt() and 0xFF
        val p11 = img[row1 + x1].toInt() and 0xFF

        val w00 = (1f - fx) * (1f - fy)
        val w10 = fx * (1f - fy)
        val w01 = (1f - fx) * fy
        val w11 = fx * fy

        return p00 * w00 + p10 * w10 + p01 * w01 + p11 * w11
    }
}
