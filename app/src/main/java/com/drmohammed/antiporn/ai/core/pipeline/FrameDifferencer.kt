package com.drmohammed.antiporn.ai.core.pipeline

import android.os.SystemClock
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * كاشف التغير اللحظي للإطارات (Level 0: Frame Differencing)
 * ينفذ فحصاً رياضياً سريعاً في الذاكرة في أقل من 0.2ms عبر عينات بكسلات متباعدة
 * يدعم الفحص الذكي للصور الساكنة (Smart Static Burst) مع نبضة فحص دورية (Heartbeat) كل ثانية واحدة
 */
class FrameDifferencer(
    private val stepX: Int = 8, // قفزة أفقية كل 8 بكسلات
    private val stepY: Int = 4, // قفزة رأسية كل 4 أسطر
    private val pixelDiffThreshold: Int = 15, // حد الفارق في قيمة الإضاءة للبكسل الواحد
    private val changeRatioThreshold: Float = 0.01f, // إذا تغير أكثر من 1% من العينات تعتبر الشاشة متحركة
    private val heartbeatIntervalMs: Long = 1000L,
    private val timeProvider: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.currentTimeMillis()
        }
    }
) {
    private var previousSamples: IntArray? = null
    private var forcedBurstFrames: Int = BURST_FRAMES_ON_RESET
    private var lastScanTimeMs: Long = 0L

    @Volatile
    var lastChangeRatio: Float = 0f
        private set

    companion object {
        /** Upper bound shared with detectors — caps IntArray allocs against tampered dims. */
        const val MAX_DIM = 1920
        const val BURST_FRAMES_ON_RESET = 10
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
        }

        val prev = previousSamples ?: return true

        val now = timeProvider()
        val isHeartbeat = (now - lastScanTimeMs >= heartbeatIntervalMs)
        val isForced = (forcedBurstFrames > 0)
        if (isForced) {
            forcedBurstFrames--
        }

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

                    if (abs(luma - prev[sampleIdx]) > pixelDiffThreshold) {
                        changedCount++
                    }

                    prev[sampleIdx] = luma
                    sampleIdx++
                }
            }
        }

        val changeRatio = if (totalSamples > 0) changedCount.toFloat() / totalSamples.toFloat() else 0f
        lastChangeRatio = changeRatio
        val hasChanged = changeRatio >= changeRatioThreshold || isForced || isHeartbeat

        if (hasChanged) {
            lastScanTimeMs = now
        }

        return hasChanged
    }

    @Synchronized
    fun reset() {
        previousSamples = null
        forcedBurstFrames = BURST_FRAMES_ON_RESET
        lastScanTimeMs = 0L
        lastChangeRatio = 0f
    }

    /**
     * هل المشهد ساكن تماماً (نسبة التغير أقل من العتبة المحددة، افتراضياً 2%)
     */
    fun isSceneStatic(threshold: Float = 0.02f): Boolean {
        return lastChangeRatio < threshold
    }

    /**
     * هل حدثت حركة معتبرة (مثل التمرير السريع باليد، نسبة التغير أكبر من العتبة، افتراضياً 5%)
     */
    fun isSignificantMotion(threshold: Float = 0.05f): Boolean {
        return lastChangeRatio >= threshold
    }
}
