package com.drmohammed.antiporn.ai.core.tracking

import android.graphics.RectF

/**
 * نموذج يمثل صندوق حياء متتبع عبر التدفق البصري (KLT Optical Flow).
 *
 * يوفر تتبعاً عالي الدقة وسلساً بين إطارات استدلال الذكاء الاصطناعي لمنع الوميض والتسرب البصري.
 *
 * @property id المعرف الفريد للهدف المتتبع عبر الزمن.
 * @property boundingBox الإحداثيات الطبيعية الموحدة للصندوق المتتبع (0.0 .. 1.0).
 * @property rawBoundingBox الإحداثيات الفعلية بالبكسل على إطار العرض الافتراضي.
 * @property confidence نسبة ثقة المتتبع أو نسبة النقاط الداخلية المتطابقة (Inlier ratio: 0.0 .. 1.0).
 * @property validInliers عدد النقاط النشطة التي تم تتبعها بنجاح في الإطار الأخير.
 * @property ageFrames عدد الإطارات المتتالية التي تم تتبع هذا الصندوق فيها دون تحديث من كاشف Halalify.
 * @property lastAnchorTimestampMs التوقيت الزمني (بالمللي ثانية) لآخر إرساء وتطابق ناجح مع Halalify.
 * @property isFemale مؤشر يحدد هل الهدف المصنف هو أنثى (افتراضياً true).
 */
data class TrackedModestyBox(
    val id: Int,
    val boundingBox: RectF,
    val rawBoundingBox: RectF,
    var confidence: Float,
    var validInliers: Int,
    var ageFrames: Int,
    var lastAnchorTimestampMs: Long,
    val isFemale: Boolean = true
) {
    /**
     * توسيع الصندوق بنسبة أمان محددة (افتراضياً 12.5% من كل اتجاه) مع حصر الإحداثيات في [0.0, 1.0].
     *
     * @param paddingRatio نسبة التوسيع من أبعاد الصندوق (0.125f = 12.5%).
     * @param outRect كائن اختياري لكتابة النتيجة بداخله لتفادي إنشاء كائنات جديدة في حلقات التحديث (Zero-Allocation).
     * @return كائن [RectF] يحتوي الإحداثيات الموسعة والمقيدة.
     */
    fun withSafetyPadding(
        paddingRatio: Float = DEFAULT_SAFETY_PADDING_RATIO,
        outRect: RectF? = null
    ): RectF {
        val width = boundingBox.right - boundingBox.left
        val height = boundingBox.bottom - boundingBox.top
        val padX = width * paddingRatio
        val padY = height * paddingRatio

        val l = (boundingBox.left - padX).coerceIn(0f, 1f)
        val t = (boundingBox.top - padY).coerceIn(0f, 1f)
        val r = (boundingBox.right + padX).coerceIn(0f, 1f)
        val b = (boundingBox.bottom + padY).coerceIn(0f, 1f)

        return (outRect ?: RectF()).apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    /**
     * تحويل الصندوق إلى إحداثيات بكسل مطلقة بناءً على أبعاد الشاشة المستهدفة.
     *
     * @param targetWidth عرض الشاشة المستهدفة بالبكسل.
     * @param targetHeight ارتفاع الشاشة المستهدفة بالبكسل.
     * @param outRect كائن اختياري لكتابة النتيجة بداخله (Zero-Allocation).
     */
    fun toPixelRect(
        targetWidth: Int,
        targetHeight: Int,
        outRect: RectF? = null
    ): RectF {
        val l = (boundingBox.left * targetWidth).coerceIn(0f, targetWidth.toFloat())
        val t = (boundingBox.top * targetHeight).coerceIn(0f, targetHeight.toFloat())
        val r = (boundingBox.right * targetWidth).coerceIn(0f, targetWidth.toFloat())
        val b = (boundingBox.bottom * targetHeight).coerceIn(0f, targetHeight.toFloat())

        return (outRect ?: RectF()).apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    /**
     * فحص ما إذا كان الصندوق قد تجاوز مدة البقاء المسموحة دون تحديث من كاشف Halalify.
     */
    fun isExpired(currentTimestampMs: Long, maxTtlMs: Long): Boolean {
        return (currentTimestampMs - lastAnchorTimestampMs) > maxTtlMs
    }

    companion object {
        /** نسبة التوسيع الافتراضية لمنع الحواف المكشوفة (12.5%) */
        const val DEFAULT_SAFETY_PADDING_RATIO = 0.125f

        /**
         * إنشاء كائن RectF مع إسناد الحقول بشكل صريح لضمان التوافق التام مع اختبارات JVM Unit Tests.
         */
        fun createRectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
            return RectF().apply {
                this.left = left
                this.top = top
                this.right = right
                this.bottom = bottom
            }
        }

        /**
         * إسناد إحداثيات RectF مباشرة إلى الحقول لتفادي استدعاء توابع Android غير المهيأة في بيئة JVM Unit Tests.
         */
        fun setRect(rect: RectF, left: Float, top: Float, right: Float, bottom: Float) {
            rect.left = left
            rect.top = top
            rect.right = right
            rect.bottom = bottom
        }
    }
}
