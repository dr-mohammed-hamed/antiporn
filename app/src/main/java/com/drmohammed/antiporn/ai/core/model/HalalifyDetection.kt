package com.drmohammed.antiporn.ai.core.model

import android.graphics.RectF

/**
 * نتيجة رصد وتصنيف الكائنات بواسطة نموذج Halalify v2
 *
 * @property isFemale هل تم تصنيف الكائن كأنثى (female / الفئة 0)
 * @property confidence نسبة ثقة النموذج في التصنيف (0.0 .. 1.0)
 * @property boundingBox إحداثيات الصندوق المحيط بعد فك Letterbox بالنسبة لأبعاد الإطار الأصلي (0.0 .. 1.0)
 */
data class HalalifyDetection(
    val isFemale: Boolean,
    val confidence: Float,
    val boundingBox: RectF
) {
    /**
     * تحويل إحداثيات الصندوق إلى بكسلات فعلية بناءً على أبعاد الصورة الأصلية
     */
    fun toPixelRect(width: Int, height: Int): RectF {
        val isNormalized = boundingBox.left in 0f..1.05f &&
                boundingBox.right in 0f..1.05f &&
                boundingBox.top in 0f..1.05f &&
                boundingBox.bottom in 0f..1.05f
        return if (isNormalized) {
            RectF().apply {
                left = boundingBox.left * width
                top = boundingBox.top * height
                right = boundingBox.right * width
                bottom = boundingBox.bottom * height
            }
        } else {
            RectF().apply {
                left = boundingBox.left
                top = boundingBox.top
                right = boundingBox.right
                bottom = boundingBox.bottom
            }
        }
    }

    companion object {
        /**
         * إنشاء كائن RectF مع إسناد الحقول مباشرة لضمان التوافق مع اختبارات JVM Unit Tests
         */
        fun createRectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
            return RectF().apply {
                this.left = left
                this.top = top
                this.right = right
                this.bottom = bottom
            }
        }
    }
}
