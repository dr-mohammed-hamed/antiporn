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

    fun calculateIntersectionArea(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        return (interRight - interLeft) * (interBottom - interTop)
    }

    fun calculateArea(r: RectF): Float {
        val w = maxOf(0f, r.right - r.left)
        val h = maxOf(0f, r.bottom - r.top)
        return w * h
    }

    /**
     * التحقق مما إذا كان صندوقان مؤهلين للدمج المكاني (Cross-Tile Spatial Merge):
     * 1. تداخل أفقي >= 50% من عرض الصندوق الأصغر.
     * 2. تلامس أو تداخل رأسي، أو وجود فجوة رأسية لا تتجاوز maxGapNorm (افتراضياً 40px / frameHeight).
     * 3. أو احتواء أحد الصندوقين للآخر بنسبة >= 70%.
     */
    fun canMergeSpatially(a: RectF, b: RectF, maxGapNorm: Float): Boolean {
        val wA = maxOf(0.001f, a.right - a.left)
        val wB = maxOf(0.001f, b.right - b.left)
        val minW = minOf(wA, wB)

        val interX = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val horizOverlapRatio = interX / minW

        val areaA = calculateArea(a)
        val areaB = calculateArea(b)
        val interArea = calculateIntersectionArea(a, b)
        val minArea = minOf(areaA, areaB)
        if (minArea > 0f && (interArea / minArea) >= 0.70f) {
            return true
        }

        if (horizOverlapRatio >= 0.50f) {
            val verticalGap = if (a.bottom <= b.top) {
                b.top - a.bottom
            } else if (b.bottom <= a.top) {
                a.top - b.bottom
            } else {
                0f
            }
            if (verticalGap <= maxGapNorm) {
                return true
            }
        }

        return false
    }

    /**
     * تنفيذ الدمج المكاني التكراري لقائمة صناديق لتوحيد أجزاء الجسم الممتدة عبر الشرائح.
     */
    fun mergeBoxesSpatially(boxes: List<RectF>, maxGapNorm: Float): List<RectF> {
        if (boxes.size <= 1) return boxes
        val result = ArrayList<RectF>(boxes.size)
        for (b in boxes) {
            val copy = TrackedModestyBox.createRectF(b.left, b.top, b.right, b.bottom)
            result.add(copy)
        }

        var mergedAny = true
        while (mergedAny) {
            mergedAny = false
            var i = 0
            while (i < result.size) {
                var j = i + 1
                while (j < result.size) {
                    val boxA = result[i]
                    val boxB = result[j]
                    if (canMergeSpatially(boxA, boxB, maxGapNorm)) {
                        boxA.left = minOf(boxA.left, boxB.left)
                        boxA.top = minOf(boxA.top, boxB.top)
                        boxA.right = maxOf(boxA.right, boxB.right)
                        boxA.bottom = maxOf(boxA.bottom, boxB.bottom)
                        result.removeAt(j)
                        mergedAny = true
                    } else {
                        j++
                    }
                }
                i++
            }
        }
        return result
    }
}
