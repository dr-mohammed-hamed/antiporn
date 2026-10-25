package com.drmohammed.antiporn.ai.core.tracking

import android.graphics.RectF

/**
 * دوال رياضية وتحويلات هندسية مساعدة لمحرك التتبع البصري KLT (خالية من تخصيص الذاكرة).
 */
internal object KltMathHelper {

    /**
     * ترتيب داخلي سريع لمصفوفة الأعداد العشرية (Insertion Sort) لتفادي إنشاء أي كائنات.
     */
    fun insertionSortFloats(arr: FloatArray, count: Int) {
        for (i in 1 until count) {
            val key = arr[i]
            var j = i - 1
            while (j >= 0 && arr[j] > key) {
                arr[j + 1] = arr[j]
                j--
            }
            arr[j + 1] = key
        }
    }

    /**
     * حساب نسبة التقاطع على الاتحاد (IoU) بين مستطيلين محددين.
     */
    fun calculateIoU(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = areaA + areaB - interArea

        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    /**
     * تحويل إحداثيات الصندوق إلى النطاق الموحد [0..1].
     */
    fun normalizeRect(
        rect: RectF,
        width: Int,
        height: Int,
        outRect: RectF
    ) {
        val isNormalized = rect.left in 0f..1.05f &&
                rect.right in 0f..1.05f &&
                rect.top in 0f..1.05f &&
                rect.bottom in 0f..1.05f

        if (isNormalized) {
            outRect.left = rect.left.coerceIn(0f, 1f)
            outRect.top = rect.top.coerceIn(0f, 1f)
            outRect.right = rect.right.coerceIn(outRect.left, 1f)
            outRect.bottom = rect.bottom.coerceIn(outRect.top, 1f)
        } else {
            val w = width.toFloat()
            val h = height.toFloat()
            val l = (rect.left / w).coerceIn(0f, 1f)
            val t = (rect.top / h).coerceIn(0f, 1f)
            val r = (rect.right / w).coerceIn(l, 1f)
            val b = (rect.bottom / h).coerceIn(t, 1f)
            outRect.left = l
            outRect.top = t
            outRect.right = r
            outRect.bottom = b
        }
    }
}
