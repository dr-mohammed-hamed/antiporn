package com.drmohammed.antiporn.ai.core.tracking

import android.graphics.RectF
import com.drmohammed.antiporn.ai.core.model.HalalifyDetection
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * محرك تتبع التدفق البصري فائق السرعة والخالي تماماً من تخصيص الذاكرة (Zero-Allocation KLT Tracker).
 *
 * يعتمد على خوارزمية Pyramidal Lucas-Kanade الهرمية لتتبع صناديق الحياء (Modesty Boxes)
 * بدقة وسلاسة بين نبضات استدلال Halalify (60 FPS Tracking)، مما يمنع وميض وتذبذب التعتيم
 * مع استهلاك طاقة ضئيل للغاية للبطارية والمعالج.
 *
 * مميزات المحرك:
 * 1. Zero-Allocation في الحلقة الساخنة (Hot Loop): جميع مصفوفات الإضاءة والنقاط والدلتا محجوزة مسبقاً.
 * 2. هرم بصري ثنائي الطبقات (2-Level Image Pyramid) عبر [KltPyramidHelper] للتعامل مع القفزات والحركات السريعة.
 * 3. حل معادلات الحركة التكراري الدقيق عبر [KltSolver].
 * 4. حساب الوسيط الإحصائي (Median Filter) للإزاحة والمقياس (Scale) عبر [KltMathHelper].
 * 5. إرساء ذكي وتنعيم متبقي (Residual Smoothing) مع كاشف Halalify لمنع القفزات المفاجئة.
 */
class KltOpticalFlowTracker(
    private val windowRadius: Int = DEFAULT_WINDOW_RADIUS,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val minDeterminant: Float = DEFAULT_MIN_DETERMINANT,
    private val maxResidual: Float = DEFAULT_MAX_RESIDUAL,
    private val convergenceEpsilon: Float = DEFAULT_CONVERGENCE_EPSILON,
    private val minInliers: Int = DEFAULT_MIN_INLIERS,
    private val anchorIouThreshold: Float = DEFAULT_ANCHOR_IOU_THRESHOLD,
    private val anchorSmoothingAlpha: Float = DEFAULT_ANCHOR_SMOOTHING_ALPHA,
    var maxTtlMs: Long = DEFAULT_MAX_TTL_MS,
    private val scaleMinClamp: Float = DEFAULT_SCALE_MIN_CLAMP,
    private val scaleMaxClamp: Float = DEFAULT_SCALE_MAX_CLAMP,
    private val numPyramidLevels: Int = DEFAULT_PYRAMID_LEVELS
) {
    // معالج حل معادلات التدفق البصري Lucas-Kanade
    private val solver = KltSolver(
        windowRadius = windowRadius,
        maxIterations = maxIterations,
        minDeterminant = minDeterminant,
        maxResidual = maxResidual,
        convergenceEpsilon = convergenceEpsilon,
        numPyramidLevels = numPyramidLevels
    )

    // مصفوفات الإضاءة (Luma Buffers) المحجوزة مسبقاً للطبقة الكاملة والطبقة المصغرة
    private var prevLuma: ByteArray = ByteArray(0)
    private var currLuma: ByteArray = ByteArray(0)
    private var prevLumaL1: ByteArray = ByteArray(0)
    private var currLumaL1: ByteArray = ByteArray(0)

    private var allocatedLumaCapacity: Int = 0
    private var allocatedL1Capacity: Int = 0
    private var lastFrameWidth: Int = 0
    private var lastFrameHeight: Int = 0
    private var hasPrevFrame: Boolean = false

    // قائمة المسارات النشطة
    private val activeTracks = ArrayList<TrackedModestyBox>(MAX_TRACKS)
    private var nextTrackId: Int = 1

    // مصفوفات الخدش المؤقتة للنقاط لتفادي أي Garbage Collection (Zero-Allocation)
    private val scratchPrevPointsX = FloatArray(MAX_POINTS_PER_BOX)
    private val scratchPrevPointsY = FloatArray(MAX_POINTS_PER_BOX)
    private val scratchCurrPointsX = FloatArray(MAX_POINTS_PER_BOX)
    private val scratchCurrPointsY = FloatArray(MAX_POINTS_PER_BOX)
    private val scratchPointValid = BooleanArray(MAX_POINTS_PER_BOX)
    private val scratchDeltasX = FloatArray(MAX_POINTS_PER_BOX)
    private val scratchDeltasY = FloatArray(MAX_POINTS_PER_BOX)
    private val scratchScales = FloatArray(MAX_POINTS_PER_BOX * MAX_POINTS_PER_BOX)

    // مصفوفة خدش لإرجاع دلتا النقطة المفردة دون إنشاء كائنات
    private val scratchL0Delta = FloatArray(2)

    // مصفوفات خدش لمطابقة الإرساء (Anchoring)
    private val matchedDetectionFlags = BooleanArray(MAX_TRACKS)
    private val matchedTrackFlags = BooleanArray(MAX_TRACKS)
    private val scratchNormalizedDetections = Array(MAX_TRACKS) { RectF() }

    companion object {
        const val DEFAULT_WINDOW_RADIUS = 3 // نافذة 7x7
        const val DEFAULT_MAX_ITERATIONS = 4
        const val DEFAULT_MIN_DETERMINANT = 1.0f
        const val DEFAULT_MAX_RESIDUAL = 30.0f
        const val DEFAULT_CONVERGENCE_EPSILON = 0.01f
        const val DEFAULT_MIN_INLIERS = 4
        const val DEFAULT_ANCHOR_IOU_THRESHOLD = 0.30f
        const val DEFAULT_ANCHOR_SMOOTHING_ALPHA = 0.75f
        const val DEFAULT_MAX_TTL_MS = 1200L
        const val DEFAULT_SCALE_MIN_CLAMP = 0.95f
        const val DEFAULT_SCALE_MAX_CLAMP = 1.05f
        const val DEFAULT_PYRAMID_LEVELS = 2

        const val GRID_SIDE = 5
        const val MAX_POINTS_PER_BOX = GRID_SIDE * GRID_SIDE // 25 نقطة
        const val MAX_TRACKS = 32
        const val MAX_BUFFER_DIM = 1920
    }

    /**
     * التحقق من وجود مسارات نشطة يتم تتبعها حالياً.
     */
    @Synchronized
    fun hasActiveTracks(): Boolean {
        return activeTracks.isNotEmpty()
    }

    /**
     * إرجاع عدد المسارات النشطة حالياً.
     */
    @Synchronized
    fun getActiveTrackCount(): Int {
        return activeTracks.size
    }

    /**
     * إرجاع نسخة آمنة ومتزامنة من المسارات النشطة الحالية لمنع التعديل المتزامن (Defensive Copy).
     */
    @Synchronized
    fun getActiveTracks(): List<TrackedModestyBox> {
        val copyList = ArrayList<TrackedModestyBox>(activeTracks.size)
        for (i in activeTracks.indices) {
            val t = activeTracks[i]
            copyList.add(
                t.copy(
                    boundingBox = TrackedModestyBox.createRectF(
                        t.boundingBox.left,
                        t.boundingBox.top,
                        t.boundingBox.right,
                        t.boundingBox.bottom
                    ),
                    rawBoundingBox = TrackedModestyBox.createRectF(
                        t.rawBoundingBox.left,
                        t.rawBoundingBox.top,
                        t.rawBoundingBox.right,
                        t.rawBoundingBox.bottom
                    )
                )
            )
        }
        return copyList
    }

    /**
     * إرجاع صناديق الحياء النشطة مع نسبة توسيع الأمان كقائمة متزامنة وآمنة (Atomic Read).
     */
    @Synchronized
    fun getPaddedBoxes(paddingRatio: Float = TrackedModestyBox.DEFAULT_SAFETY_PADDING_RATIO): List<RectF> {
        if (activeTracks.isEmpty()) return emptyList()
        val paddedList = ArrayList<RectF>(activeTracks.size)
        for (i in activeTracks.indices) {
            paddedList.add(activeTracks[i].withSafetyPadding(paddingRatio))
        }
        return paddedList
    }

    /**
     * مسح جميع المسارات النشطة وإعادة تعيين مخازن الإطارات السابقة (عند تغيير المشهد مثلاً).
     */
    @Synchronized
    fun flush() {
        activeTracks.clear()
        hasPrevFrame = false
        lastFrameWidth = 0
        lastFrameHeight = 0
    }

    /**
     * تهيئة أو إرساء صناديق التتبع من مخرجات كاشف Halalify.
     */
    @Synchronized
    fun initOrUpdateAnchors(
        detections: List<RectF>,
        frameWidth: Int,
        frameHeight: Int,
        timestampMs: Long
    ) {
        initOrUpdateAnchorsInternal(
            detections = detections,
            isFemaleList = null,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            timestampMs = timestampMs
        )
    }

    /**
     * تحميل كشوفات Halalify المتضمنة لتصنيف الجنس مباشرة.
     */
    @Synchronized
    fun initOrUpdateAnchorsFromHalalify(
        detections: List<HalalifyDetection>,
        frameWidth: Int,
        frameHeight: Int,
        timestampMs: Long
    ) {
        if (detections.isEmpty()) {
            initOrUpdateAnchorsInternal(
                detections = emptyList(),
                isFemaleList = null,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMs = timestampMs
            )
            return
        }

        val boxes = ArrayList<RectF>(detections.size)
        val females = ArrayList<Boolean>(detections.size)
        for (i in detections.indices) {
            val d = detections[i]
            boxes.add(d.boundingBox)
            females.add(d.isFemale)
        }

        initOrUpdateAnchorsInternal(
            detections = boxes,
            isFemaleList = females,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            timestampMs = timestampMs
        )
    }

    /**
     * المنطق الجوهري لربط وإرساء المسارات بالكشوفات (Anchor Association & Smoothing).
     */
    private fun initOrUpdateAnchorsInternal(
        detections: List<RectF>,
        isFemaleList: List<Boolean>?,
        frameWidth: Int,
        frameHeight: Int,
        timestampMs: Long
    ) {
        val safeWidth = if (frameWidth > 0) frameWidth else 1
        val safeHeight = if (frameHeight > 0) frameHeight else 1

        val numDetections = minOf(detections.size, MAX_TRACKS)
        val numTracks = minOf(activeTracks.size, MAX_TRACKS)

        // 1. تحويل جميع الكشوفات الواردة إلى إحداثيات موحدة [0..1]
        for (j in 0 until numDetections) {
            val d = detections[j]
            KltMathHelper.normalizeRect(d, safeWidth, safeHeight, scratchNormalizedDetections[j])
            matchedDetectionFlags[j] = false
        }
        for (i in 0 until numTracks) {
            matchedTrackFlags[i] = false
        }

        // 2. مطابقة جشعة (Greedy IoU Matching) لأعلى تقاطع IoU >= anchorIouThreshold
        while (true) {
            var bestIou = anchorIouThreshold
            var bestTrackIdx = -1
            var bestDetIdx = -1

            for (i in 0 until numTracks) {
                if (matchedTrackFlags[i]) continue
                val trackBox = activeTracks[i].boundingBox

                for (j in 0 until numDetections) {
                    if (matchedDetectionFlags[j]) continue
                    val detBox = scratchNormalizedDetections[j]

                    val iou = KltMathHelper.calculateIoU(trackBox, detBox)
                    if (iou > bestIou) {
                        bestIou = iou
                        bestTrackIdx = i
                        bestDetIdx = j
                    }
                }
            }

            if (bestTrackIdx >= 0 && bestDetIdx >= 0) {
                matchedTrackFlags[bestTrackIdx] = true
                matchedDetectionFlags[bestDetIdx] = true

                val track = activeTracks[bestTrackIdx]
                val detBox = scratchNormalizedDetections[bestDetIdx]
                val alpha = anchorSmoothingAlpha

                // EMA Smoothing على الإحداثيات الموحدة
                val sLeft = (alpha * detBox.left + (1f - alpha) * track.boundingBox.left).coerceIn(0f, 1f)
                val sTop = (alpha * detBox.top + (1f - alpha) * track.boundingBox.top).coerceIn(0f, 1f)
                val sRight = (alpha * detBox.right + (1f - alpha) * track.boundingBox.right).coerceIn(sLeft + 0.001f, 1f)
                val sBottom = (alpha * detBox.bottom + (1f - alpha) * track.boundingBox.bottom).coerceIn(sTop + 0.001f, 1f)

                TrackedModestyBox.setRect(track.boundingBox, sLeft, sTop, sRight, sBottom)
                TrackedModestyBox.setRect(
                    track.rawBoundingBox,
                    sLeft * safeWidth,
                    sTop * safeHeight,
                    sRight * safeWidth,
                    sBottom * safeHeight
                )
                track.lastAnchorTimestampMs = timestampMs
                track.confidence = 1.0f
                track.validInliers = MAX_POINTS_PER_BOX
                track.ageFrames = 0
            } else {
                break
            }
        }

        // 3. إنشاء مسارات جديدة للكشوفات التي لم تتطابق مع أي مسار قائم
        for (j in 0 until numDetections) {
            if (!matchedDetectionFlags[j] && activeTracks.size < MAX_TRACKS) {
                val detBox = scratchNormalizedDetections[j]
                val rawLeft = detBox.left * safeWidth
                val rawTop = detBox.top * safeHeight
                val rawRight = detBox.right * safeWidth
                val rawBottom = detBox.bottom * safeHeight

                val isFemale = isFemaleList?.getOrNull(j) ?: true
                val newTrack = TrackedModestyBox(
                    id = nextTrackId++,
                    boundingBox = TrackedModestyBox.createRectF(detBox.left, detBox.top, detBox.right, detBox.bottom),
                    rawBoundingBox = TrackedModestyBox.createRectF(rawLeft, rawTop, rawRight, rawBottom),
                    confidence = 1.0f,
                    validInliers = MAX_POINTS_PER_BOX,
                    ageFrames = 0,
                    lastAnchorTimestampMs = timestampMs,
                    isFemale = isFemale
                )
                activeTracks.add(newTrack)
            }
        }

        // 4. حذف المسارات القديمة التي تجاوزت مدة البقاء المسموحة (TTL)
        pruneExpiredTracks(timestampMs)
    }

    /**
     * تحديث التتبع لكل إطار فيديو عبر حساب التدفق البصري النقي (Pure KLT Optical Flow).
     */
    @Synchronized
    fun update(
        rawBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestampMs: Long
    ): List<TrackedModestyBox> {
        if (width <= 0 || height <= 0 || width > MAX_BUFFER_DIM || height > MAX_BUFFER_DIM ||
            rowStride <= 0 || pixelStride <= 0
        ) {
            return activeTracks
        }

        pruneExpiredTracks(timestampMs)
        ensureBuffers(width, height)

        // استخراج الإضاءة (Luma) إلى currLuma دون تخصيص ذاكرة
        KltPyramidHelper.extractLuma(rawBuffer, width, height, rowStride, pixelStride, currLuma)

        // بناء الطبقة المصغرة للهرم البصري (Level 1)
        if (numPyramidLevels >= 2) {
            KltPyramidHelper.buildPyramidLevel1(currLuma, currLumaL1, width, height)
        }

        // إذا كان هذا الإطار الأول أو تغيرت أبعاد الشاشة
        if (!hasPrevFrame || width != lastFrameWidth || height != lastFrameHeight) {
            for (i in activeTracks.indices) {
                val t = activeTracks[i]
                TrackedModestyBox.setRect(
                    t.rawBoundingBox,
                    t.boundingBox.left * width,
                    t.boundingBox.top * height,
                    t.boundingBox.right * width,
                    t.boundingBox.bottom * height
                )
            }
            swapLumaBuffers()
            hasPrevFrame = true
            lastFrameWidth = width
            lastFrameHeight = height
            return activeTracks
        }

        if (activeTracks.isEmpty()) {
            swapLumaBuffers()
            return activeTracks
        }

        // معالجة كل صندوق متتبع عبر التدفق البصري Lucas-Kanade
        for (trackIdx in activeTracks.indices) {
            val track = activeTracks[trackIdx]
            val rawBox = track.rawBoundingBox

            val boxW = rawBox.right - rawBox.left
            val boxH = rawBox.bottom - rawBox.top

            if (boxW < 8f || boxH < 8f) {
                track.ageFrames++
                track.validInliers = 0
                track.confidence = (track.confidence * 0.85f).coerceAtLeast(0f)
                continue
            }

            val marginX = boxW * 0.15f
            val marginY = boxH * 0.15f
            val startX = rawBox.left + marginX
            val spanX = boxW - 2f * marginX
            val startY = rawBox.top + marginY
            val spanY = boxH - 2f * marginY

            var pointIdx = 0
            for (gy in 0 until GRID_SIDE) {
                val py = startY + spanY * (gy.toFloat() / (GRID_SIDE - 1).toFloat())
                for (gx in 0 until GRID_SIDE) {
                    val px = startX + spanX * (gx.toFloat() / (GRID_SIDE - 1).toFloat())
                    scratchPrevPointsX[pointIdx] = px
                    scratchPrevPointsY[pointIdx] = py
                    pointIdx++
                }
            }

            var validInlierCount = 0
            for (i in 0 until MAX_POINTS_PER_BOX) {
                val px = scratchPrevPointsX[i]
                val py = scratchPrevPointsY[i]

                val success = solver.trackPointPyramidal(
                    prevLuma = prevLuma,
                    currLuma = currLuma,
                    prevLumaL1 = prevLumaL1,
                    currLumaL1 = currLumaL1,
                    ptX = px,
                    ptY = py,
                    width = width,
                    height = height,
                    outDelta = scratchL0Delta
                )

                if (success) {
                    val dx = scratchL0Delta[0]
                    val dy = scratchL0Delta[1]
                    scratchCurrPointsX[i] = px + dx
                    scratchCurrPointsY[i] = py + dy
                    scratchDeltasX[validInlierCount] = dx
                    scratchDeltasY[validInlierCount] = dy
                    scratchPointValid[i] = true
                    validInlierCount++
                } else {
                    scratchPointValid[i] = false
                }
            }

            if (validInlierCount >= minInliers) {
                KltMathHelper.insertionSortFloats(scratchDeltasX, validInlierCount)
                KltMathHelper.insertionSortFloats(scratchDeltasY, validInlierCount)
                val medDeltaX = scratchDeltasX[validInlierCount / 2]
                val medDeltaY = scratchDeltasY[validInlierCount / 2]

                var scalePairCount = 0
                for (i in 0 until MAX_POINTS_PER_BOX) {
                    if (!scratchPointValid[i]) continue
                    val pxi = scratchPrevPointsX[i]
                    val pyi = scratchPrevPointsY[i]
                    val cxi = scratchCurrPointsX[i]
                    val cyi = scratchCurrPointsY[i]

                    for (j in (i + 1) until MAX_POINTS_PER_BOX) {
                        if (!scratchPointValid[j]) continue
                        val pxj = scratchPrevPointsX[j]
                        val pyj = scratchPrevPointsY[j]
                        val cxj = scratchCurrPointsX[j]
                        val cyj = scratchCurrPointsY[j]

                        val dPrevX = pxi - pxj
                        val dPrevY = pyi - pyj
                        val distPrevSq = dPrevX * dPrevX + dPrevY * dPrevY

                        if (distPrevSq < 16f) continue

                        val dCurrX = cxi - cxj
                        val dCurrY = cyi - cyj
                        val distCurrSq = dCurrX * dCurrX + dCurrY * dCurrY

                        val distPrev = sqrt(distPrevSq)
                        val distCurr = sqrt(distCurrSq)
                        if (distPrev > 0.001f) {
                            scratchScales[scalePairCount++] = distCurr / distPrev
                        }
                    }
                }

                val scaleChange = if (scalePairCount >= 4) {
                    KltMathHelper.insertionSortFloats(scratchScales, scalePairCount)
                    scratchScales[scalePairCount / 2].coerceIn(scaleMinClamp, scaleMaxClamp)
                } else {
                    1.0f
                }

                val centerX = (rawBox.left + rawBox.right) * 0.5f + medDeltaX
                val centerY = (rawBox.top + rawBox.bottom) * 0.5f + medDeltaY
                val newHalfW = (boxW * 0.5f * scaleChange).coerceAtLeast(4f)
                val newHalfH = (boxH * 0.5f * scaleChange).coerceAtLeast(4f)

                val newLeft = (centerX - newHalfW).coerceIn(0f, (width - 2).toFloat())
                val newTop = (centerY - newHalfH).coerceIn(0f, (height - 2).toFloat())
                val newRight = (centerX + newHalfW).coerceIn(newLeft + 2f, width.toFloat())
                val newBottom = (centerY + newHalfH).coerceIn(newTop + 2f, height.toFloat())

                TrackedModestyBox.setRect(rawBox, newLeft, newTop, newRight, newBottom)
                TrackedModestyBox.setRect(
                    track.boundingBox,
                    newLeft / width,
                    newTop / height,
                    newRight / width,
                    newBottom / height
                )
                track.validInliers = validInlierCount
                track.confidence = (validInlierCount.toFloat() / MAX_POINTS_PER_BOX.toFloat()).coerceIn(0f, 1f)
                track.ageFrames++
            } else {
                track.validInliers = validInlierCount
                track.confidence = (track.confidence * 0.70f).coerceAtLeast(0f)
                track.ageFrames++
            }
        }

        compactActiveTracks()
        swapLumaBuffers()
        hasPrevFrame = true
        lastFrameWidth = width
        lastFrameHeight = height

        return activeTracks
    }

    private fun swapLumaBuffers() {
        val tmp0 = prevLuma
        prevLuma = currLuma
        currLuma = tmp0

        val tmp1 = prevLumaL1
        prevLumaL1 = currLumaL1
        currLumaL1 = tmp1
    }

    private fun ensureBuffers(width: Int, height: Int) {
        val sizeL0 = width * height
        if (sizeL0 > allocatedLumaCapacity) {
            prevLuma = ByteArray(sizeL0)
            currLuma = ByteArray(sizeL0)
            allocatedLumaCapacity = sizeL0
        }

        val w1 = width shr 1
        val h1 = height shr 1
        val sizeL1 = w1 * h1
        if (sizeL1 > allocatedL1Capacity) {
            prevLumaL1 = ByteArray(sizeL1)
            currLumaL1 = ByteArray(sizeL1)
            allocatedL1Capacity = sizeL1
        }
    }

    private fun pruneExpiredTracks(currentTimestampMs: Long) {
        var writeIdx = 0
        for (readIdx in 0 until activeTracks.size) {
            val track = activeTracks[readIdx]
            val isLive = (currentTimestampMs - track.lastAnchorTimestampMs) <= maxTtlMs
            if (isLive) {
                if (writeIdx != readIdx) {
                    activeTracks[writeIdx] = track
                }
                writeIdx++
            }
        }
        while (activeTracks.size > writeIdx) {
            activeTracks.removeAt(activeTracks.size - 1)
        }
    }

    private fun compactActiveTracks() {
        var writeIdx = 0
        for (readIdx in 0 until activeTracks.size) {
            val track = activeTracks[readIdx]
            val keep = track.confidence >= 0.05f || track.ageFrames <= 15
            if (keep) {
                if (writeIdx != readIdx) {
                    activeTracks[writeIdx] = track
                }
                writeIdx++
            }
        }
        while (activeTracks.size > writeIdx) {
            activeTracks.removeAt(activeTracks.size - 1)
        }
    }
}
