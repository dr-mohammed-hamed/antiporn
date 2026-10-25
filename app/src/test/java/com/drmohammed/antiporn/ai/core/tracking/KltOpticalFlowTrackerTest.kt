package com.drmohammed.antiporn.ai.core.tracking

import android.graphics.RectF
import com.drmohammed.antiporn.ai.core.model.HalalifyDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.roundToInt

class KltOpticalFlowTrackerTest {

    private lateinit var tracker: KltOpticalFlowTracker

    @Before
    fun setUp() {
        tracker = KltOpticalFlowTracker(
            windowRadius = 3,
            maxIterations = 4,
            minDeterminant = 0.5f,
            maxResidual = 40.0f,
            convergenceEpsilon = 0.01f,
            minInliers = 3,
            anchorIouThreshold = 0.3f,
            anchorSmoothingAlpha = 0.75f,
            maxTtlMs = 500L
        )
    }

    @Test
    fun testTrackedModestyBox_propertiesAndDefaults() {
        val box = TrackedModestyBox(
            id = 42,
            boundingBox = TrackedModestyBox.createRectF(0.1f, 0.2f, 0.5f, 0.6f),
            rawBoundingBox = TrackedModestyBox.createRectF(100f, 200f, 500f, 600f),
            confidence = 0.85f,
            validInliers = 20,
            ageFrames = 2,
            lastAnchorTimestampMs = 1000L,
            isFemale = true
        )

        assertEquals(42, box.id)
        assertEquals(0.1f, box.boundingBox.left, 0.001f)
        assertEquals(0.5f, box.boundingBox.right, 0.001f)
        assertEquals(100f, box.rawBoundingBox.left, 0.001f)
        assertEquals(0.85f, box.confidence, 0.001f)
        assertEquals(20, box.validInliers)
        assertEquals(2, box.ageFrames)
        assertEquals(1000L, box.lastAnchorTimestampMs)
        assertTrue(box.isFemale)
    }

    @Test
    fun testTrackedModestyBox_withSafetyPadding() {
        val box = TrackedModestyBox(
            id = 1,
            boundingBox = TrackedModestyBox.createRectF(0.2f, 0.2f, 0.4f, 0.4f), // width=0.2, height=0.2
            rawBoundingBox = TrackedModestyBox.createRectF(20f, 20f, 40f, 40f),
            confidence = 1.0f,
            validInliers = 25,
            ageFrames = 0,
            lastAnchorTimestampMs = 500L
        )

        // Default padding ratio = 0.125f (12.5%) -> padX = 0.025f, padY = 0.025f
        val padded = box.withSafetyPadding(0.125f)
        assertEquals(0.175f, padded.left, 0.001f)
        assertEquals(0.175f, padded.top, 0.001f)
        assertEquals(0.425f, padded.right, 0.001f)
        assertEquals(0.425f, padded.bottom, 0.001f)

        // Zero-allocation reusable outRect
        val reusableOut = RectF()
        val result = box.withSafetyPadding(0.125f, reusableOut)
        assertTrue(result === reusableOut)
        assertEquals(0.175f, reusableOut.left, 0.001f)

        // Clamping at boundary [0, 1]
        val edgeBox = TrackedModestyBox(
            id = 2,
            boundingBox = TrackedModestyBox.createRectF(0.05f, 0.05f, 0.95f, 0.95f),
            rawBoundingBox = TrackedModestyBox.createRectF(5f, 5f, 95f, 95f),
            confidence = 1.0f,
            validInliers = 25,
            ageFrames = 0,
            lastAnchorTimestampMs = 500L
        )
        val clampedPadded = edgeBox.withSafetyPadding(0.125f)
        assertEquals(0.0f, clampedPadded.left, 0.001f)
        assertEquals(0.0f, clampedPadded.top, 0.001f)
        assertEquals(1.0f, clampedPadded.right, 0.001f)
        assertEquals(1.0f, clampedPadded.bottom, 0.001f)
    }

    @Test
    fun testTrackedModestyBox_toPixelRectAndIsExpired() {
        val box = TrackedModestyBox(
            id = 1,
            boundingBox = TrackedModestyBox.createRectF(0.1f, 0.2f, 0.5f, 0.6f),
            rawBoundingBox = TrackedModestyBox.createRectF(0f, 0f, 0f, 0f),
            confidence = 1.0f,
            validInliers = 25,
            ageFrames = 0,
            lastAnchorTimestampMs = 1000L
        )

        val pixelRect = box.toPixelRect(1000, 2000)
        assertEquals(100f, pixelRect.left, 0.001f)
        assertEquals(400f, pixelRect.top, 0.001f)
        assertEquals(500f, pixelRect.right, 0.001f)
        assertEquals(1200f, pixelRect.bottom, 0.001f)

        assertFalse(box.isExpired(currentTimestampMs = 1400L, maxTtlMs = 500L))
        assertTrue(box.isExpired(currentTimestampMs = 1600L, maxTtlMs = 500L))
    }

    @Test
    fun testTracker_initialStateAndFlush() {
        assertFalse(tracker.hasActiveTracks())
        assertEquals(0, tracker.getActiveTrackCount())
        assertEquals(0, tracker.getActiveTracks().size)

        // Adding an anchor
        val det = TrackedModestyBox.createRectF(0.2f, 0.2f, 0.6f, 0.6f)
        tracker.initOrUpdateAnchors(listOf(det), 100, 100, 1000L)

        assertTrue(tracker.hasActiveTracks())
        assertEquals(1, tracker.getActiveTrackCount())

        tracker.flush()
        assertFalse(tracker.hasActiveTracks())
        assertEquals(0, tracker.getActiveTrackCount())
    }

    @Test
    fun testTracker_initOrUpdateAnchors_associationAndSmoothing() {
        val initialDet = TrackedModestyBox.createRectF(0.2f, 0.2f, 0.5f, 0.5f)
        tracker.initOrUpdateAnchors(listOf(initialDet), 100, 100, 1000L)

        assertEquals(1, tracker.getActiveTrackCount())
        val firstTrack = tracker.getActiveTracks()[0]
        assertEquals(1, firstTrack.id)
        assertEquals(0.2f, firstTrack.boundingBox.left, 0.001f)
        assertEquals(1000L, firstTrack.lastAnchorTimestampMs)

        // Second anchor matching existing track (IoU >= 0.3)
        // Moved slightly to 0.22, 0.22, 0.52, 0.52
        val movedDet = TrackedModestyBox.createRectF(0.24f, 0.24f, 0.54f, 0.54f)
        tracker.initOrUpdateAnchors(listOf(movedDet), 100, 100, 1100L)

        // Should NOT create new track, but update existing track 1
        assertEquals(1, tracker.getActiveTrackCount())
        val updatedTrack = tracker.getActiveTracks()[0]
        assertEquals(1, updatedTrack.id)
        assertEquals(1100L, updatedTrack.lastAnchorTimestampMs)
        assertEquals(0, updatedTrack.ageFrames)

        // Residual smoothing check: alpha = 0.75
        // expected left = 0.75 * 0.24 + 0.25 * 0.20 = 0.18 + 0.05 = 0.23
        assertEquals(0.23f, updatedTrack.boundingBox.left, 0.005f)
    }

    @Test
    fun testTracker_initOrUpdateAnchors_spawnsNewAndPrunesExpired() {
        val det1 = TrackedModestyBox.createRectF(0.1f, 0.1f, 0.3f, 0.3f)
        tracker.initOrUpdateAnchors(listOf(det1), 100, 100, 1000L)
        assertEquals(1, tracker.getActiveTrackCount())

        // At time 1200L, detection 2 arrives in a completely disjoint region (IoU = 0)
        val det2 = TrackedModestyBox.createRectF(0.7f, 0.7f, 0.9f, 0.9f)
        tracker.initOrUpdateAnchors(listOf(det1, det2), 100, 100, 1200L)
        assertEquals(2, tracker.getActiveTrackCount())

        // At time 1800L, only det2 is detected, det1 is missing
        // Time elapsed since det1 last anchor: 1800 - 1200 = 600ms > maxTtlMs (500ms) -> det1 pruned
        tracker.initOrUpdateAnchors(listOf(det2), 100, 100, 1800L)
        assertEquals(1, tracker.getActiveTrackCount())
        assertEquals(2, tracker.getActiveTracks()[0].id)
    }

    @Test
    fun testTracker_initOrUpdateAnchorsFromHalalify() {
        val detection = HalalifyDetection(
            isFemale = true,
            confidence = 0.95f,
            boundingBox = TrackedModestyBox.createRectF(0.15f, 0.15f, 0.45f, 0.45f)
        )

        tracker.initOrUpdateAnchorsFromHalalify(listOf(detection), 200, 200, 1000L)
        assertEquals(1, tracker.getActiveTrackCount())
        val track = tracker.getActiveTracks()[0]
        assertTrue(track.isFemale)
        assertEquals(0.15f, track.boundingBox.left, 0.001f)
    }

    @Test
    fun testTracker_update_withSyntheticMotion() {
        val width = 128
        val height = 128
        val rowStride = width * 4
        val pixelStride = 4

        // Frame 1: Create a textured square in the center (40..88, 40..88)
        val frame1 = createSyntheticRgbaBuffer(width, height, patchX = 40, patchY = 40, patchSize = 48)

        // Initialize anchor covering the textured patch
        val normDet = TrackedModestyBox.createRectF(
            40f / width,
            40f / height,
            88f / width,
            88f / height
        )
        tracker.initOrUpdateAnchors(listOf(normDet), width, height, 1000L)

        // First update: extracts luma, initializes prevLuma
        val result1 = tracker.update(
            rawBuffer = frame1,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            timestampMs = 1000L
        )
        assertEquals(1, result1.size)
        assertEquals(40f, result1[0].rawBoundingBox.left, 0.01f)

        // Frame 2: Shift the textured patch by (+3, +3) pixels to (43, 43)
        val frame2 = createSyntheticRgbaBuffer(width, height, patchX = 43, patchY = 43, patchSize = 48)

        val result2 = tracker.update(
            rawBuffer = frame2,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            timestampMs = 1016L // ~60 FPS (16ms later)
        )

        assertEquals(1, result2.size)
        val trackedBox = result2[0]

        // Check that optical flow tracked the movement in the positive X and Y direction!
        assertTrue("Tracked left should move right towards 43 (got ${trackedBox.rawBoundingBox.left})",
            trackedBox.rawBoundingBox.left > 41f)
        assertTrue("Tracked top should move down towards 43 (got ${trackedBox.rawBoundingBox.top})",
            trackedBox.rawBoundingBox.top > 41f)

        // Check high confidence and valid inliers
        assertTrue("Valid inliers should be high (got ${trackedBox.validInliers})",
            trackedBox.validInliers >= 5)
        assertTrue("Confidence should be high (got ${trackedBox.confidence})",
            trackedBox.confidence >= 0.2f)
    }

    /**
     * إنشاء مخزن RGBA تركيبي يحتوي على نمط متدرج ناعم ومستمر لاختبار الـ Optical Flow.
     */
    private fun createSyntheticRgbaBuffer(
        width: Int,
        height: Int,
        patchX: Int,
        patchY: Int,
        patchSize: Int
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val inPatch = x in patchX until (patchX + patchSize) && y in patchY until (patchY + patchSize)
                val intensity: Int = if (inPatch) {
                    val px = (x - patchX).toDouble()
                    val py = (y - patchY).toDouble()
                    // نمط جيبي ناعم وغني بالتدرجات لضمان وجود مشتقات مكانية واضحة ومستمرة
                    val v = 128.0 + 45.0 * kotlin.math.sin(px * 0.25) + 45.0 * kotlin.math.cos(py * 0.25)
                    v.roundToInt().coerceIn(0, 255)
                } else {
                    128 // خلفية محايدة
                }

                buffer.put(intensity.toByte()) // R
                buffer.put(intensity.toByte()) // G
                buffer.put(intensity.toByte()) // B
                buffer.put(255.toByte())       // A
            }
        }
        buffer.rewind()
        return buffer
    }

    @Test
    fun testTracker_getActiveTracks_defensiveCopyIsolation() {
        val det = TrackedModestyBox.createRectF(0.2f, 0.2f, 0.6f, 0.6f)
        tracker.initOrUpdateAnchors(listOf(det), 100, 100, 1000L)

        val tracksCopy1 = tracker.getActiveTracks()
        assertEquals(1, tracksCopy1.size)

        // Mutate the returned RectF
        tracksCopy1[0].boundingBox.left = 0.99f
        tracksCopy1[0].rawBoundingBox.left = 999f

        // A second read should have original values, untouched by the external mutation
        val tracksCopy2 = tracker.getActiveTracks()
        assertEquals(0.2f, tracksCopy2[0].boundingBox.left, 0.001f)
        assertEquals(20f, tracksCopy2[0].rawBoundingBox.left, 0.001f)
    }

    @Test
    fun testTracker_getPaddedBoxes() {
        // Empty state
        assertTrue(tracker.getPaddedBoxes().isEmpty())

        val det = TrackedModestyBox.createRectF(0.2f, 0.2f, 0.4f, 0.4f)
        tracker.initOrUpdateAnchors(listOf(det), 100, 100, 1000L)

        // Default padding ratio 0.125f: width=0.2, pad=0.025 -> [0.175, 0.175, 0.425, 0.425]
        val padded = tracker.getPaddedBoxes()
        assertEquals(1, padded.size)
        assertEquals(0.175f, padded[0].left, 0.001f)
        assertEquals(0.175f, padded[0].top, 0.001f)
        assertEquals(0.425f, padded[0].right, 0.001f)
        assertEquals(0.425f, padded[0].bottom, 0.001f)

        // Custom padding ratio 0.25f: width=0.2, pad=0.05 -> [0.15, 0.15, 0.45, 0.45]
        val customPadded = tracker.getPaddedBoxes(0.25f)
        assertEquals(1, customPadded.size)
        assertEquals(0.15f, customPadded[0].left, 0.001f)
        assertEquals(0.45f, customPadded[0].right, 0.001f)
    }

    @Test
    fun testKltSolver_nanResilience() {
        val solver = KltSolver(
            windowRadius = 3,
            maxIterations = 4,
            minDeterminant = 1.0f,
            maxResidual = 30.0f,
            convergenceEpsilon = 0.01f,
            numPyramidLevels = 2
        )
        val dummyLuma = ByteArray(100 * 100)
        val outDelta = FloatArray(2)

        // Test with NaN ptX, ptY, initVx, initVy
        assertFalse(solver.trackPointLK(dummyLuma, dummyLuma, 100, 100, Float.NaN, 50f, 0f, 0f, outDelta))
        assertFalse(solver.trackPointLK(dummyLuma, dummyLuma, 100, 100, 50f, Float.NaN, 0f, 0f, outDelta))
        assertFalse(solver.trackPointLK(dummyLuma, dummyLuma, 100, 100, 50f, 50f, Float.NaN, 0f, outDelta))
        assertFalse(solver.trackPointLK(dummyLuma, dummyLuma, 100, 100, 50f, 50f, 0f, Float.NaN, outDelta))
    }
}
