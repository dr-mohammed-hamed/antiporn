package com.drmohammed.antiporn.ai.core.inference

import android.graphics.RectF
import com.drmohammed.antiporn.ai.core.model.ModestyClass
import com.drmohammed.antiporn.ai.core.model.ModestyDetection
import kotlin.math.max
import kotlin.math.min

/**
 * معالج فك مصفوفات YOLO11-Nano وتطبيق خوارزمية NMS وPerson-Gating الصارم
 * مصمم لتفادي الـ Garbage Collector Pauses وإعادة استخدام الهياكل في الذاكرة
 */
class ModestyPostProcessor(
    private val confidenceThreshold: Float = 0.55f,
    private val iouThreshold: Float = 0.45f,
    private val inputSize: Float = 320.0f
) {
    companion object {
        private const val PERSON_CONFIDENCE_THRESHOLD = 0.35f
        private const val ANCHOR_CONFIDENCE_THRESHOLD = 0.60f
        private const val CONTAINMENT_RATIO_THRESHOLD = 0.75f
        private const val MAX_NMS_DETECTIONS = 15

        fun createRectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
            return RectF().apply {
                this.left = left
                this.top = top
                this.right = right
                this.bottom = bottom
            }
        }
    }

    // قائمة مسبقة الحجز للمرشحين لمنع تكرار إنشاء الكائنات
    private val candidatePool = ArrayList<ModestyDetection>(128)
    private val filteredDetections = ArrayList<ModestyDetection>(24)

    // مصفوفة أولية مسبقة الحجز لتخزين إحداثيات الأشخاص [left, top, right, bottom] بدون إنشاء كائنات
    private var personBoxesCoords = FloatArray(256) // تكفي لـ 64 شخصاً افتراضياً
    private var personBoxCount = 0

    private fun ensurePersonBoxCapacity(requiredBoxes: Int) {
        val requiredFloats = requiredBoxes * 4
        if (requiredFloats > personBoxesCoords.size) {
            val newCapacity = max(requiredFloats, personBoxesCoords.size * 2)
            personBoxesCoords = personBoxesCoords.copyOf(newCapacity)
        }
    }

    /**
     * فك مصفوفة الخرج [1, 8, numBoxes] أو [1, numBoxes, 8] وتطبيق Person-Gating الصارم
     */
    @Synchronized
    fun process(outputArray: Array<Array<FloatArray>>): List<ModestyDetection> {
        candidatePool.clear()
        filteredDetections.clear()
        personBoxCount = 0

        // فحص مصفوفات الإدخال ضد الفراغ
        if (outputArray.isEmpty() || outputArray[0].isEmpty() || outputArray[0][0].isEmpty()) return emptyList()
        val dim1 = outputArray[0].size
        val dim2 = outputArray[0][0].size

        val isChannelsFirst = dim1 == 8
        val isChannelsLast = dim2 == 8

        // النموذج الموحد الجديد ذو 4 فئات يتطلب بُعداً يساوي 8 (4 إحداثيات + 4 فئات)
        if (!isChannelsFirst && !isChannelsLast) {
            return emptyList()
        }

        val numBoxes = if (isChannelsFirst) dim2 else dim1
        if (numBoxes <= 0) return emptyList()

        if (isChannelsFirst) {
            val matrix = outputArray[0]
            val cxRow = matrix[0]
            val cyRow = matrix[1]
            val wRow = matrix[2]
            val hRow = matrix[3]
            val c0Row = matrix[4] // PERSON
            val c1Row = matrix[5] // EXPOSED_SKIN
            val c2Row = matrix[6] // TIGHT_AND_CLEAVAGE
            val c3Row = matrix[7] // SAFE_MODEST_ANCHOR

            // Pass 1: رصد مرساة الحشمة وجمع صناديق الأشخاص
            for (i in 0 until numBoxes) {
                val cx = cxRow[i]
                val cy = cyRow[i]
                val w = wRow[i]
                val h = hRow[i]

                if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) continue

                // في YOLO11 الإحداثيات تكون بالفعل بكسلات مباشرة [0.0 .. inputSize] ولا تضرب في inputSize
                val x1 = max(0f, cx - w / 2f)
                val y1 = max(0f, cy - h / 2f)
                val x2 = min(inputSize, cx + w / 2f)
                val y2 = min(inputSize, cy + h / 2f)
                if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite() || x2 <= x1 || y2 <= y1) continue

                val s0 = c0Row[i]
                val s1 = c1Row[i]
                val s2 = c2Row[i]
                val s3 = c3Row[i]
                if (!s0.isFinite() || !s1.isFinite() || !s2.isFinite() || !s3.isFinite()) continue

                if (s3 >= ANCHOR_CONFIDENCE_THRESHOLD) {
                    if (s3 >= s0 && s3 >= s1 && s3 >= s2) {
                        candidatePool.add(ModestyDetection(ModestyClass.SAFE_MODEST_ANCHOR, s3, createRectF(x1, y1, x2, y2)))
                    }
                }

                // تصنيف إنسان (الفئة 0) بثقة كافية وتفوق على باقي الفئات
                if (s0 >= PERSON_CONFIDENCE_THRESHOLD && s0 >= s1 && s0 >= s2 && s0 >= s3) {
                    ensurePersonBoxCapacity(personBoxCount + 1)
                    val offset = personBoxCount * 4
                    personBoxesCoords[offset] = x1
                    personBoxesCoords[offset + 1] = y1
                    personBoxesCoords[offset + 2] = x2
                    personBoxesCoords[offset + 3] = y2
                    personBoxCount++

                    candidatePool.add(ModestyDetection(ModestyClass.PERSON, s0, createRectF(x1, y1, x2, y2)))
                }
            }

            // تحييد فوري في غياب تام لأي جسد بشري (كائنات جامدة)
            if (personBoxCount == 0) {
                return emptyList()
            }

            // Pass 2: فحص المخالفات وتطبيق الاحتواء الصارم داخل صناديق الأشخاص
            for (i in 0 until numBoxes) {
                    val s0 = c0Row[i]
                    val s1 = c1Row[i]
                    val s2 = c2Row[i]
                    val s3 = c3Row[i]
                    if (!s0.isFinite() || !s1.isFinite() || !s2.isFinite() || !s3.isFinite()) continue

                    var bestClassId = 0
                    var maxScore = s0
                    if (s1 > maxScore) {
                        maxScore = s1
                        bestClassId = 1
                    }
                    if (s2 > maxScore) {
                        maxScore = s2
                        bestClassId = 2
                    }
                    if (s3 > maxScore) {
                        maxScore = s3
                        bestClassId = 3
                    }

                    if ((bestClassId != 1 && bestClassId != 2) || maxScore < confidenceThreshold) continue

                    val cx = cxRow[i]
                    val cy = cyRow[i]
                    val w = wRow[i]
                    val h = hRow[i]
                    if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) continue

                    val x1 = max(0f, cx - w / 2f)
                    val y1 = max(0f, cy - h / 2f)
                    val x2 = min(inputSize, cx + w / 2f)
                    val y2 = min(inputSize, cy + h / 2f)
                    if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite() || x2 <= x1 || y2 <= y1) continue

                    val vArea = (x2 - x1) * (y2 - y1)
                    if (!vArea.isFinite() || vArea <= 0f) continue

                    var maxContainment = 0f
                    for (pIdx in 0 until personBoxCount) {
                        val pOffset = pIdx * 4
                        val pLeft = personBoxesCoords[pOffset]
                        val pTop = personBoxesCoords[pOffset + 1]
                        val pRight = personBoxesCoords[pOffset + 2]
                        val pBottom = personBoxesCoords[pOffset + 3]

                        val interLeft = max(x1, pLeft)
                        val interTop = max(y1, pTop)
                        val interRight = min(x2, pRight)
                        val interBottom = min(y2, pBottom)

                        if (interLeft < interRight && interTop < interBottom) {
                            val interArea = (interRight - interLeft) * (interBottom - interTop)
                            val containment = interArea / vArea
                            if (containment > maxContainment) {
                                maxContainment = containment
                            }
                        }
                    }

                    if (maxContainment >= CONTAINMENT_RATIO_THRESHOLD) {
                        val clazz = if (bestClassId == 1) ModestyClass.EXPOSED_SKIN else ModestyClass.TIGHT_AND_CLEAVAGE
                        candidatePool.add(ModestyDetection(clazz, maxScore, createRectF(x1, y1, x2, y2)))
                    }
                }
        } else {
            // نمط Channels-Last: [1, numBoxes, 8]
            val matrix = outputArray[0]

            // Pass 1: رصد مرساة الحشمة وجمع صناديق الأشخاص
            for (i in 0 until numBoxes) {
                val row = matrix[i]
                val cx = row[0]
                val cy = row[1]
                val w = row[2]
                val h = row[3]

                if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) continue

                val x1 = max(0f, cx - w / 2f)
                val y1 = max(0f, cy - h / 2f)
                val x2 = min(inputSize, cx + w / 2f)
                val y2 = min(inputSize, cy + h / 2f)
                if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite() || x2 <= x1 || y2 <= y1) continue

                val s0 = row[4]
                val s1 = row[5]
                val s2 = row[6]
                val s3 = row[7]
                if (!s0.isFinite() || !s1.isFinite() || !s2.isFinite() || !s3.isFinite()) continue

                if (s3 >= ANCHOR_CONFIDENCE_THRESHOLD) {
                    if (s3 >= s0 && s3 >= s1 && s3 >= s2) {
                        candidatePool.add(ModestyDetection(ModestyClass.SAFE_MODEST_ANCHOR, s3, createRectF(x1, y1, x2, y2)))
                    }
                }

                if (s0 >= PERSON_CONFIDENCE_THRESHOLD && s0 >= s1 && s0 >= s2 && s0 >= s3) {
                    ensurePersonBoxCapacity(personBoxCount + 1)
                    val offset = personBoxCount * 4
                    personBoxesCoords[offset] = x1
                    personBoxesCoords[offset + 1] = y1
                    personBoxesCoords[offset + 2] = x2
                    personBoxesCoords[offset + 3] = y2
                    personBoxCount++

                    candidatePool.add(ModestyDetection(ModestyClass.PERSON, s0, createRectF(x1, y1, x2, y2)))
                }
            }

            if (personBoxCount == 0) {
                return emptyList()
            }

            // Pass 2: فحص المخالفات وتطبيق الاحتواء الصارم
            for (i in 0 until numBoxes) {
                    val row = matrix[i]
                    val s0 = row[4]
                    val s1 = row[5]
                    val s2 = row[6]
                    val s3 = row[7]
                    if (!s0.isFinite() || !s1.isFinite() || !s2.isFinite() || !s3.isFinite()) continue

                    var bestClassId = 0
                    var maxScore = s0
                    if (s1 > maxScore) {
                        maxScore = s1
                        bestClassId = 1
                    }
                    if (s2 > maxScore) {
                        maxScore = s2
                        bestClassId = 2
                    }
                    if (s3 > maxScore) {
                        maxScore = s3
                        bestClassId = 3
                    }

                    if ((bestClassId != 1 && bestClassId != 2) || maxScore < confidenceThreshold) continue

                    val cx = row[0]
                    val cy = row[1]
                    val w = row[2]
                    val h = row[3]
                    if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) continue

                    val x1 = max(0f, cx - w / 2f)
                    val y1 = max(0f, cy - h / 2f)
                    val x2 = min(inputSize, cx + w / 2f)
                    val y2 = min(inputSize, cy + h / 2f)
                    if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite() || x2 <= x1 || y2 <= y1) continue

                    val vArea = (x2 - x1) * (y2 - y1)
                    if (!vArea.isFinite() || vArea <= 0f) continue

                    var maxContainment = 0f
                    for (pIdx in 0 until personBoxCount) {
                        val pOffset = pIdx * 4
                        val pLeft = personBoxesCoords[pOffset]
                        val pTop = personBoxesCoords[pOffset + 1]
                        val pRight = personBoxesCoords[pOffset + 2]
                        val pBottom = personBoxesCoords[pOffset + 3]

                        val interLeft = max(x1, pLeft)
                        val interTop = max(y1, pTop)
                        val interRight = min(x2, pRight)
                        val interBottom = min(y2, pBottom)

                        if (interLeft < interRight && interTop < interBottom) {
                            val interArea = (interRight - interLeft) * (interBottom - interTop)
                            val containment = interArea / vArea
                            if (containment > maxContainment) {
                                maxContainment = containment
                            }
                        }
                    }

                    if (maxContainment >= CONTAINMENT_RATIO_THRESHOLD) {
                        val clazz = if (bestClassId == 1) ModestyClass.EXPOSED_SKIN else ModestyClass.TIGHT_AND_CLEAVAGE
                        candidatePool.add(ModestyDetection(clazz, maxScore, createRectF(x1, y1, x2, y2)))
                    }
                }
        }

        // تطبيق خوارزمية NMS (Non-Maximum Suppression)
        if (candidatePool.isEmpty()) {
            return emptyList()
        }

        // فرز المرشحين: تقديم مرساة الحشمة لتثبيط المخالفات المتداخلة موضعياً، ثم حسب الثقة
        candidatePool.sortWith(
            compareByDescending<ModestyDetection> { it.classType == ModestyClass.SAFE_MODEST_ANCHOR }
                .thenByDescending { it.confidence }
        )

        for (candidate in candidatePool) {
            var shouldKeep = true
            for (selected in filteredDetections) {
                val suppresses = (candidate.classType == selected.classType) ||
                    (candidate.classType.isViolation && selected.classType.isViolation) ||
                    (candidate.classType.isViolation && selected.classType == ModestyClass.SAFE_MODEST_ANCHOR)
                if (suppresses && calculateIoU(candidate.boundingBox, selected.boundingBox) > iouThreshold) {
                    shouldKeep = false
                    break
                }
            }
            if (shouldKeep) {
                filteredDetections.add(candidate)
                if (filteredDetections.size >= MAX_NMS_DETECTIONS) break
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

        return if (!unionArea.isFinite() || unionArea <= 0.0f) 0.0f else (interArea / unionArea)
    }
}
