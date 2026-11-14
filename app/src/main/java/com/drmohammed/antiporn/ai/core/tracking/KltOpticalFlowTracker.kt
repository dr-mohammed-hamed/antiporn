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

    // مصفوفات الإضاءة المحجوزة مسبقاً للهرم البصري رباعي الطبقات (L0..L3) Zero-Allocation
    private val prevPyramid = Array(MAX_PYRAMID_LEVELS) { ByteArray(0) }
    private val currPyramid = Array(MAX_PYRAMID_LEVELS) { ByteArray(0) }

    private val prevLuma: ByteArray get() = prevPyramid[0]
    private val currLuma: ByteArray get() = currPyramid[0]
    private val prevLumaL1: ByteArray get() = prevPyramid[1]
    private val currLumaL1: ByteArray get() = currPyramid[1]

    private val allocatedCapacities = IntArray(MAX_PYRAMID_LEVELS)
    private var lastFrameWidth: Int = 0
    private var lastFrameHeight: Int = 0
    private var hasPrevFrame: Boolean = false

    @Volatile
    var lastGlobalDeltaX: Float = 0f
        private set

    @Volatile
    var lastGlobalDeltaY: Float = 0f
        private set

    // قائمة المسارات النشطة
    private val activeTracks = ArrayList<TrackedModestyBox>(MAX_TRACKS)
    private var nextTrackId: Int = 1

    // مصفوفات الخدش المؤقتة لتتبع الخلفية والحركة العامة والمحلية (Local Neighborhood Flow)
    private val scratchBgPointsX = FloatArray(MAX_BG_POINTS)
    private val scratchBgPointsY = FloatArray(MAX_BG_POINTS)
    private val scratchBgDeltasX = FloatArray(MAX_BG_POINTS)
    private val scratchBgDeltasY = FloatArray(MAX_BG_POINTS)
    private val scratchLocalDeltasX = FloatArray(MAX_BG_POINTS)
    private val scratchLocalDeltasY = FloatArray(MAX_BG_POINTS)
    private val reusablePaddedList = ArrayList<RectF>(MAX_TRACKS)

    @Volatile
    var lastGeneralChangeRatio: Float = 0f

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
        const val DEFAULT_MAX_TTL_MS = 2500L
        const val DEFAULT_SCALE_MIN_CLAMP = 0.95f
        const val DEFAULT_SCALE_MAX_CLAMP = 1.05f
        const val DEFAULT_PYRAMID_LEVELS = 5
        const val MAX_PYRAMID_LEVELS = 5

        const val BG_GRID_X = 8
        const val BG_GRID_Y = 8
        const val MAX_BG_POINTS = BG_GRID_X * BG_GRID_Y

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
     * إرجاع صناديق الحياء النشطة مع نسبة توسيع الأمان كقائمة متزامنة وآمنة (Zero-Allocation Atomic Read).
     */
    @Synchronized
    fun getPaddedBoxes(
        paddingRatio: Float = TrackedModestyBox.DEFAULT_SAFETY_PADDING_RATIO,
        outList: MutableList<RectF>? = null
    ): List<RectF> {
        if (activeTracks.isEmpty()) return emptyList()
        val target = outList ?: reusablePaddedList
        while (target.size < activeTracks.size) {
            target.add(RectF())
        }
        while (target.size > activeTracks.size) {
            target.removeAt(target.size - 1)
        }
        for (i in activeTracks.indices) {
            activeTracks[i].withSafetyPadding(paddingRatio, target[i])
        }
        return target
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
     * تحديث الإرساء المكاني عبر الشرائح (Cross-Tile Spatial Merge) وقفل الذاكرة ضد الصناديق الزومبي.
     */
    @Synchronized
    fun updateAnchorsCrossTile(
        detections: List<RectF>,
        tileNormRect: RectF,
        frameWidth: Int,
        frameHeight: Int,
        timestampMs: Long
    ) {
        initOrUpdateAnchorsInternal(
            detections = detections,
            isFemaleList = null,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            timestampMs = timestampMs,
            targetTileRect = tileNormRect
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
        timestampMs: Long,
        targetTileRect: RectF? = null
    ) {
        val safeWidth = if (frameWidth > 0) frameWidth else 1
        val safeHeight = if (frameHeight > 0) frameHeight else 1

        // 1. تحويل جميع الكشوفات الواردة إلى إحداثيات موحدة [0..1] مع تطبيق الدمج المكاني
        val mergedDetections = KltMathHelper.mergeBoxesSpatially(detections, maxGapNorm = 40f / safeHeight)
        val numDetections = minOf(mergedDetections.size, MAX_TRACKS)
        val numTracks = minOf(activeTracks.size, MAX_TRACKS)

        for (j in 0 until numDetections) {
            val d = mergedDetections[j]
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

        // دمج المسارات النشطة مكانياً لتوحيد أجزاء الجسم المنفصلة
        mergeActiveTracksSpatially(safeHeight)

        // 4. مكافحة الصناديق الزومبي واعية الاحتواء (Containment-Aware Anti-Zombie Latch):
        // لا نحسب غياب الصندوق إلا إذا كانت الشريحة المفحوصة تغطي 80% أو أكثر من مساحته
        if (targetTileRect != null) {
            for (i in 0 until numTracks) {
                if (!matchedTrackFlags[i]) {
                    val track = activeTracks[i]
                    val interArea = KltMathHelper.calculateIntersectionArea(track.boundingBox, targetTileRect)
                    val trackArea = KltMathHelper.calculateArea(track.boundingBox)
                    val coverage = if (trackArea > 0f) interArea / trackArea else 0f

                    if (coverage >= 0.80f) {
                        val ringChange = track.lastOuterRingChangeRatio
                        if (ringChange < 0.20f) {
                            // سكون تام للمحيط الخارجي (صورة ثابتة): تمديد الصلاحية لمنع وميض الـ 2.5s
                            track.lastAnchorTimestampMs = timestampMs
                            track.ageFrames = 0
                        } else if (ringChange >= 0.40f && lastGeneralChangeRatio >= 0.45f) {
                            // تغير بنيوي شامل للمشهد: إسقاط سريع لتفادي الزومبي
                            track.ageFrames += 2
                        } else if (ringChange >= 0.20f) {
                            // حركة فيديو اعتيادية: زيادة تدريجية ناعمة مع مهلة 8 نبضات (~1.6 ثانية)
                            track.ageFrames += 1
                        }

                        if (track.ageFrames >= 8) {
                            track.confidence = 0f
                        }
                    }
                }
            }
        }

        // 5. حذف المسارات القديمة التي تجاوزت مدة البقاء المسموحة (TTL)
        pruneExpiredTracks(timestampMs)
    }

    private fun mergeActiveTracksSpatially(safeHeight: Int) {
        if (activeTracks.size <= 1) return
        val maxGapNorm = 40f / safeHeight
        var mergedAny = true
        while (mergedAny) {
            mergedAny = false
            var i = 0
            while (i < activeTracks.size) {
                var j = i + 1
                while (j < activeTracks.size) {
                    val a = activeTracks[i]
                    val b = activeTracks[j]
                    if (KltMathHelper.canMergeSpatially(a.boundingBox, b.boundingBox, maxGapNorm)) {
                        val newL = minOf(a.boundingBox.left, b.boundingBox.left)
                        val newT = minOf(a.boundingBox.top, b.boundingBox.top)
                        val newR = maxOf(a.boundingBox.right, b.boundingBox.right)
                        val newB = maxOf(a.boundingBox.bottom, b.boundingBox.bottom)
                        TrackedModestyBox.setRect(a.boundingBox, newL, newT, newR, newB)
                        val fw = if (lastFrameWidth > 0) lastFrameWidth else 1
                        val fh = if (lastFrameHeight > 0) lastFrameHeight else 1
                        TrackedModestyBox.setRect(
                            a.rawBoundingBox,
                            newL * fw,
                            newT * fh,
                            newR * fw,
                            newB * fh
                        )
                        a.confidence = maxOf(a.confidence, b.confidence)
                        a.validInliers = maxOf(a.validInliers, b.validInliers)
                        a.ageFrames = minOf(a.ageFrames, b.ageFrames)
                        activeTracks.removeAt(j)
                        mergedAny = true
                    } else {
                        j++
                    }
                }
                i++
            }
        }
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

        // 1. استخراج الإضاءة (Luma) إلى currPyramid[0] وبناء الهرم الرباعي L0..L3 دون تخصيص ذاكرة
        KltPyramidHelper.extractLuma(rawBuffer, width, height, rowStride, pixelStride, currPyramid[0])
        var curW = width
        var curH = height
        for (lvl in 1 until numPyramidLevels) {
            KltPyramidHelper.buildPyramidLevel(currPyramid[lvl - 1], currPyramid[lvl], curW, curH)
            curW = (curW shr 1).coerceAtLeast(1)
            curH = (curH shr 1).coerceAtLeast(1)
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
            lastGlobalDeltaX = 0f
            lastGlobalDeltaY = 0f
            return activeTracks
        }

        if (activeTracks.isEmpty()) {
            swapLumaBuffers()
            lastGlobalDeltaX = 0f
            lastGlobalDeltaY = 0f
            return activeTracks
        }

        // 2. تتبع حركة الخلفية والتمرير العام (Background Motion Flow Vector dx, dy)
        // استبعاد هوامش النظام الثابتة (أعلى 10% لشريط الحالة وأسفل 10% لشريط التنقل)
        var bgValidCount = 0
        val topUiMargin = height * 0.10f
        val bottomUiMargin = height * 0.90f
        val leftUiMargin = width * 0.05f
        val rightUiMargin = width * 0.95f

        val stepX = (rightUiMargin - leftUiMargin) / (BG_GRID_X + 1)
        val stepY = (bottomUiMargin - topUiMargin) / (BG_GRID_Y + 1)

        for (gy in 1..BG_GRID_Y) {
            val py = topUiMargin + gy * stepY
            for (gx in 1..BG_GRID_X) {
                val px = leftUiMargin + gx * stepX

                // استبعاد أي نقطة تقع داخل أو بجوار صناديق التعتيم النشطة
                var insideBox = false
                for (t in activeTracks) {
                    val box = t.rawBoundingBox
                    if (px >= (box.left - 4f) && px <= (box.right + 4f) &&
                        py >= (box.top - 4f) && py <= (box.bottom + 4f)
                    ) {
                        insideBox = true
                        break
                    }
                }
                if (insideBox) continue

                val tracked = solver.trackPointPyramidalMulti(
                    prevPyramid = prevPyramid,
                    currPyramid = currPyramid,
                    ptX = px,
                    ptY = py,
                    width = width,
                    height = height,
                    outDelta = scratchL0Delta,
                    activeLevels = numPyramidLevels
                )
                if (tracked) {
                    scratchBgPointsX[bgValidCount] = px
                    scratchBgPointsY[bgValidCount] = py
                    scratchBgDeltasX[bgValidCount] = scratchL0Delta[0]
                    scratchBgDeltasY[bgValidCount] = scratchL0Delta[1]
                    bgValidCount++
                }
            }
        }

        var globalDx = 0f
        var globalDy = 0f
        if (bgValidCount >= 4) {
            KltMathHelper.insertionSortFloats(scratchBgDeltasX, bgValidCount)
            KltMathHelper.insertionSortFloats(scratchBgDeltasY, bgValidCount)
            val rawDx = scratchBgDeltasX[bgValidCount / 2]
            val rawDy = scratchBgDeltasY[bgValidCount / 2]

            // Deadband filter: إذا كانت الحركة أقل من 0.5 بكسل نعتبرها سكوناً تاماً لمنع الزحف
            if (kotlin.math.abs(rawDx) >= 0.5f) globalDx = rawDx
            if (kotlin.math.abs(rawDy) >= 0.5f) globalDy = rawDy
        }

        lastGlobalDeltaX = globalDx
        lastGlobalDeltaY = globalDy
        val hasGlobalScroll = globalDx != 0f || globalDy != 0f

        // 3. تحديث كل صندوق: التدفق البصري للجوار المحلي (Local Neighborhood Flow) + التتبع الموضعي للنقاط غير المحجوبة
        for (trackIdx in activeTracks.indices) {
            val track = activeTracks[trackIdx]
            val rawBox = track.rawBoundingBox

            // حساب التغير في الحزام المحيط خارج الصندوق لتغذية منطق مكافحة الزومبي والتعتيم الذاتي
            track.lastOuterRingChangeRatio = calculateOuterRingChangeRatio(rawBox, prevLuma, currLuma, width, height)

            // تجميع نقاط الخلفية الواقعة في الجوار المحلي للصندوق (نطاق رأسي ±25% من ارتفاع الشاشة)
            val trackCenterY = (rawBox.top + rawBox.bottom) * 0.5f
            var localBgCount = 0
            val localBandY = height * 0.25f

            for (k in 0 until bgValidCount) {
                val py = scratchBgPointsY[k]
                if (kotlin.math.abs(py - trackCenterY) <= localBandY) {
                    scratchLocalDeltasX[localBgCount] = scratchBgDeltasX[k]
                    scratchLocalDeltasY[localBgCount] = scratchBgDeltasY[k]
                    localBgCount++
                }
            }

            val trackDx: Float
            val trackDy: Float
            if (localBgCount >= 3) {
                KltMathHelper.insertionSortFloats(scratchLocalDeltasX, localBgCount)
                KltMathHelper.insertionSortFloats(scratchLocalDeltasY, localBgCount)
                val rawLocDx = scratchLocalDeltasX[localBgCount / 2]
                val rawLocDy = scratchLocalDeltasY[localBgCount / 2]
                trackDx = if (kotlin.math.abs(rawLocDx) >= 0.5f) rawLocDx else 0f
                trackDy = if (kotlin.math.abs(rawLocDy) >= 0.5f) rawLocDy else 0f
            } else {
                // عزل الجوار المحلي: عند نقص نقاط المحيط، لا نسقط في حركة التمرير العامة للشاشة (مثل التعليقات)
                trackDx = 0f
                trackDy = 0f
            }

            val hasTrackMotion = trackDx != 0f || trackDy != 0f

            // إذا تحرك الجوار المحلي للصندوق، يتحرك الصندوق معه فوراً وبسلاسة
            if (hasTrackMotion) {
                val newL = rawBox.left + trackDx
                val newT = rawBox.top + trackDy
                val newR = rawBox.right + trackDx
                val newB = rawBox.bottom + trackDy

                // إذا خرج الصندوق بالكامل من حدود الشاشة، يُحذف فوراً
                if (newB <= 0f || newT >= height.toFloat() || newR <= 0f || newL >= width.toFloat()) {
                    track.confidence = 0f
                    track.validInliers = 0
                    continue
                }

                TrackedModestyBox.setRect(rawBox, newL, newT, newR, newB)
                TrackedModestyBox.setRect(
                    track.boundingBox,
                    newL / width,
                    newT / height,
                    newR / width,
                    newB / height
                )
                track.confidence = 1.0f
                track.ageFrames = 0
                continue
            }

            // الشاشة ساكنة دون تمرير عام: فحص حركة الهدف الموضعي إن وُجد
            val boxW = rawBox.right - rawBox.left
            val boxH = rawBox.bottom - rawBox.top

            if (boxW < 8f || boxH < 8f) {
                track.ageFrames++
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

                val success = solver.trackPointPyramidalMulti(
                    prevPyramid = prevPyramid,
                    currPyramid = currPyramid,
                    ptX = px,
                    ptY = py,
                    width = width,
                    height = height,
                    outDelta = scratchL0Delta,
                    activeLevels = numPyramidLevels
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
                // النقاط داخل المربع محجوبة بالتعتيم: الاحتفاظ التام بموقع الصندوق دون إسقاط الثقة
                track.validInliers = validInlierCount
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
        for (lvl in 0 until numPyramidLevels) {
            val tmp = prevPyramid[lvl]
            prevPyramid[lvl] = currPyramid[lvl]
            currPyramid[lvl] = tmp
        }
    }

    private fun ensureBuffers(width: Int, height: Int) {
        var w = width
        var h = height
        for (lvl in 0 until numPyramidLevels) {
            val size = w * h
            if (size > allocatedCapacities[lvl]) {
                prevPyramid[lvl] = ByteArray(size)
                currPyramid[lvl] = ByteArray(size)
                allocatedCapacities[lvl] = size
            }
            w = (w shr 1).coerceAtLeast(1)
            h = (h shr 1).coerceAtLeast(1)
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
            val keep = track.confidence > 0.01f
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

    /**
     * حساب نسبة تغير الإضاءة في حزام محيط خارجي (Outer Context Ring) بعرض 16px خارج حدود الصندوق.
     * يستبعد النقاط المعتمة داخل الصندوق ويرصد بدقة هل تغير المشهد المحيط بالهدف أم لا.
     */
    private fun calculateOuterRingChangeRatio(
        rawBox: RectF,
        prevLuma: ByteArray,
        currLuma: ByteArray,
        width: Int,
        height: Int
    ): Float {
        if (prevLuma.isEmpty() || currLuma.isEmpty() || width <= 0 || height <= 0) return 0f
        val ringThickness = 16
        val l = (rawBox.left - ringThickness).toInt().coerceIn(0, width - 1)
        val t = (rawBox.top - ringThickness).toInt().coerceIn(0, height - 1)
        val r = (rawBox.right + ringThickness).toInt().coerceIn(0, width - 1)
        val b = (rawBox.bottom + ringThickness).toInt().coerceIn(0, height - 1)

        val boxL = rawBox.left.toInt()
        val boxT = rawBox.top.toInt()
        val boxR = rawBox.right.toInt()
        val boxB = rawBox.bottom.toInt()

        var changedCount = 0
        var sampleCount = 0
        val step = 4

        for (y in t..b step step) {
            val rowOffset = y * width
            for (x in l..r step step) {
                // استبعاد أي نقطة تقع داخل مساحة التعتيم
                if (x in boxL..boxR && y in boxT..boxB) continue

                val idx = rowOffset + x
                if (idx < prevLuma.size && idx < currLuma.size) {
                    val p = prevLuma[idx].toInt() and 0xFF
                    val c = currLuma[idx].toInt() and 0xFF
                    if (kotlin.math.abs(p - c) > 20) {
                        changedCount++
                    }
                    sampleCount++
                }
            }
        }

        return if (sampleCount > 0) changedCount.toFloat() / sampleCount.toFloat() else 0f
    }
}
