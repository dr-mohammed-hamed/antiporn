package com.drmohammed.antiporn.ai.core.pipeline

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * كاشف التغير اللحظي للإطارات (Level 0: Frame Differencing)
 * ينفذ فحصاً رياضياً سريعاً في الذاكرة في أقل من 0.2ms عبر عينات بكسلات متباعدة
 * إذا كانت الشاشة ثابتة، يتم إهمال الإطار فوراً لتوفير طاقة المعالج والبطارية بنسبة 100%
 */
class FrameDifferencer(
    private val stepX: Int = 8, // قفزة أفقية كل 8 بكسلات
    private val stepY: Int = 4, // قفزة رأسية كل 4 أسطر
    private val pixelDiffThreshold: Int = 15, // حد الفارق في قيمة الإضاءة للبكسل الواحد
    private val changeRatioThreshold: Float = 0.03f // إذا تغير أكثر من 3% من العينات تعتبر الشاشة متحركة
) {
    private var previousSamples: IntArray? = null
    private var currentSamples: IntArray? = null

    companion object {
        /** Upper bound shared with detectors — caps IntArray allocs against tampered dims. */
        const val MAX_DIM = 640
    }

    /**
     * فحص ما إذا كان الإطار قد تغير مقارنة بالإطار السابق باستخدام بايتات الصورة الخام RGBA
     * @param rawBuffer مصفوفة البايتات الخام المباشرة من Image.planes[0].buffer
     * @param width عرض الصورة بالبكسل (افتراضياً 320)
     * @param height ارتفاع الصورة بالبكسل (افتراضياً 320)
     * @param rowStride عدد البايتات بين سطر وآخر متضمناً الحشو (Padding)
     * @param pixelStride تباعد البكسل في الذاكرة (عادة 4 لـ RGBA_8888)
     */
    @Synchronized
    fun hasScreenChanged(
        rawBuffer: ByteBuffer,
        width: Int = 320,
        height: Int = 320,
        rowStride: Int = 320 * 4,
        pixelStride: Int = 4
    ): Boolean {
        // CRITICAL: degenerate dims must fail CLOSED (scan) — never silent-skip (fail-open).
        if (width <= 0 || height <= 0 || width > MAX_DIM || height > MAX_DIM) return true
        if (rowStride <= 0 || pixelStride <= 0 || pixelStride > 4) return true
        val samplesX = (width + stepX - 1) / stepX
        val samplesY = (height + stepY - 1) / stepY
        val totalSamples = samplesX * samplesY
        if (totalSamples <= 0) return true

        if (previousSamples == null || previousSamples?.size != totalSamples) {
            previousSamples = IntArray(totalSamples)
            currentSamples = IntArray(totalSamples)
        }

        val prev = previousSamples ?: return true
        val curr = currentSamples ?: return true

        var changedCount = 0
        var sampleIdx = 0
        val bufferCapacity = rawBuffer.capacity()

        for (y in 0 until height step stepY) {
            val rowOffset = y * rowStride
            for (x in 0 until width step stepX) {
                val byteOffset = rowOffset + x * pixelStride
                if (byteOffset + 2 < bufferCapacity && sampleIdx < totalSamples) {
                    val r = rawBuffer.get(byteOffset).toInt() and 0xFF
                    val g = rawBuffer.get(byteOffset + 1).toInt() and 0xFF
                    val b = rawBuffer.get(byteOffset + 2).toInt() and 0xFF

                    // حساب الإضاءة السريعة Y = 0.299R + 0.587G + 0.114B (تقريب عدد صحيح دقيق)
                    val luma = (r * 299 + g * 587 + b * 114) / 1000
                    curr[sampleIdx] = luma

                    if (abs(luma - prev[sampleIdx]) > pixelDiffThreshold) {
                        changedCount++
                    }

                    prev[sampleIdx] = luma
                    sampleIdx++
                }
            }
        }

        val changeRatio = if (totalSamples > 0) changedCount.toFloat() / totalSamples.toFloat() else 0f
        return changeRatio >= changeRatioThreshold
    }

    @Synchronized
    fun reset() {
        previousSamples = null
        currentSamples = null
    }
}
