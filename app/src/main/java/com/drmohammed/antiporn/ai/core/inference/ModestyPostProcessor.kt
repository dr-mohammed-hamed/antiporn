package com.drmohammed.antiporn.ai.core.inference

import android.graphics.RectF
import com.drmohammed.antiporn.ai.core.model.ModestyClass
import com.drmohammed.antiporn.ai.core.model.ModestyDetection
import kotlin.math.max
import kotlin.math.min

/**
 * معالج فك مصفوفات YOLO11-Nano وتطبيق خوارزمية NMS
 * مصمم لتفادي الـ Garbage Collector Pauses وإعادة استخدام الهياكل في الذاكرة
 */
class ModestyPostProcessor(
    private val confidenceThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f,
    private val inputSize: Float = 320.0f
) {
    // قائمة مسبقة الحجز للمرشحين لمنع تكرار إنشاء الكائنات
    private val candidatePool = ArrayList<ModestyDetection>(100)
    private val filteredDetections = ArrayList<ModestyDetection>(20)

    /**
     * فك مصفوفة الخرج [1, 7, 2100] أو [1, 2100, 7]
     */
    @Synchronized
    fun process(outputArray: Array<Array<FloatArray>>): List<ModestyDetection> {
        candidatePool.clear()
        filteredDetections.clear()

        // MAJOR(M12): guard empty outer arrays (upstream catch is not a contract).
        if (outputArray.isEmpty() || outputArray[0].isEmpty() || outputArray[0][0].isEmpty()) return emptyList()
        val dim1 = outputArray[0].size
        val dim2 = outputArray[0][0].size
        // MAJOR(M13): derive box count dynamically — hardcoded 2100 drops 6300 boxes on 640-input models.
        val numBoxes = if (dim1 == 7) dim2 else dim1

        if (dim1 == 7) {
            // نمط Channels-First: [1, 7, 2100]
            val matrix = outputArray[0]
            val cxRow = matrix[0]
            val cyRow = matrix[1]
            val wRow = matrix[2]
            val hRow = matrix[3]
            val c0Row = matrix[4]
            val c1Row = matrix[5]
            val c2Row = matrix[6]

            for (i in 0 until numBoxes) {
                val score0 = c0Row[i]
                val score1 = c1Row[i]
                val score2 = c2Row[i]

                var bestClassId = 0
                var maxScore = score0
                if (score1 > maxScore) {
                    maxScore = score1
                    bestClassId = 1
                }
                if (score2 > maxScore) {
                    maxScore = score2
                    bestClassId = 2
                }

                // MAJOR(M12): NaN/Inf confidences must never reach UI (Inf*100 → Int.MAX_VALUE toast).
                if (!maxScore.isFinite() || maxScore < confidenceThreshold) continue
                val cx = cxRow[i] * inputSize
                val cy = cyRow[i] * inputSize
                val w = wRow[i] * inputSize
                val h = hRow[i] * inputSize

                val x1 = max(0f, cx - w / 2f)
                val y1 = max(0f, cy - h / 2f)
                val x2 = min(inputSize, cx + w / 2f)
                val y2 = min(inputSize, cy + h / 2f)
                // MAJOR(M12): drop non-finite/inverted boxes before NMS/IoU.
                if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite() || x2 <= x1 || y2 <= y1) continue

                val clazz = ModestyClass.fromId(bestClassId) ?: continue
                candidatePool.add(ModestyDetection(clazz, maxScore, RectF(x1, y1, x2, y2)))
            }
        } else if (dim2 == 7) {
            // نمط Channels-Last: [1, 2100, 7]
            val matrix = outputArray[0]
            for (i in 0 until numBoxes) {
                val row = matrix[i]
                val score0 = row[4]
                val score1 = row[5]
                val score2 = row[6]

                var bestClassId = 0
                var maxScore = score0
                if (score1 > maxScore) {
                    maxScore = score1
                    bestClassId = 1
                }
                if (score2 > maxScore) {
                    maxScore = score2
                    bestClassId = 2
                }

                // MAJOR(M12): NaN/Inf confidences must never reach UI.
                if (!maxScore.isFinite() || maxScore < confidenceThreshold) continue
                val cx = row[0] * inputSize
                val cy = row[1] * inputSize
                val w = row[2] * inputSize
                val h = row[3] * inputSize

                val x1 = max(0f, cx - w / 2f)
                val y1 = max(0f, cy - h / 2f)
                val x2 = min(inputSize, cx + w / 2f)
                val y2 = min(inputSize, cy + h / 2f)
                if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite() || x2 <= x1 || y2 <= y1) continue

                val clazz = ModestyClass.fromId(bestClassId) ?: continue
                candidatePool.add(ModestyDetection(clazz, maxScore, RectF(x1, y1, x2, y2)))
            }
        }

        // تطبيق خوارزمية NMS (Non-Maximum Suppression)
        if (candidatePool.isEmpty()) {
            return emptyList()
        }

        // فرز المرشحين تنازلياً حسب الثقة
        candidatePool.sortByDescending { it.confidence }

        for (candidate in candidatePool) {
            var shouldKeep = true
            for (selected in filteredDetections) {
                if (calculateIoU(candidate.boundingBox, selected.boundingBox) > iouThreshold) {
                    shouldKeep = false
                    break
                }
            }
            if (shouldKeep) {
                filteredDetections.add(candidate)
                if (filteredDetections.size >= 10) break
            }
        }

        return ArrayList(filteredDetections)
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        if (interLeft >= interRight || interTop >= interBottom) return 0.0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = areaA + areaB - interArea

        return if (unionArea <= 0.0f) 0.0f else (interArea / unionArea)
    }
}
