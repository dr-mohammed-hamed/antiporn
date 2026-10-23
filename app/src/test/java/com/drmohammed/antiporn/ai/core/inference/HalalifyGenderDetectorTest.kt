package com.drmohammed.antiporn.ai.core.inference

import android.graphics.RectF
import com.drmohammed.antiporn.ai.core.model.HalalifyDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HalalifyGenderDetectorTest {

    private lateinit var detector: HalalifyGenderDetector

    /**
     * مساعدة لإعداد قنوات الصندوق بما يطابق مصفوفة TFLite الناتجة عن ONNX:
     * Channel 0: w / 2.0f
     * Channel 1: h / 2.0f
     * Channel 2: 2.0f * cx
     * Channel 3: 2.0f * cy
     */
    private fun setCandidateBox(
        matrix: Array<FloatArray>,
        candidateIndex: Int,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float
    ) {
        matrix[0][candidateIndex] = w * 0.5f
        matrix[1][candidateIndex] = h * 0.5f
        matrix[2][candidateIndex] = cx * 2.0f
        matrix[3][candidateIndex] = cy * 2.0f
    }

    @Before
    fun setUp() {
        // Instantiate without real TFLite interpreter for unit testing decoding/NMS/Letterbox logic
        detector = HalalifyGenderDetector(
            interpreter = null,
            confidenceThreshold = 0.35f,
            iouThreshold = 0.45f
        )
    }

    @Test
    fun testHalalifyDetection_dataClassAndToPixelRect() {
        val detection = HalalifyDetection(
            isFemale = true,
            confidence = 0.92f,
            boundingBox = HalalifyDetection.createRectF(0.1f, 0.2f, 0.5f, 0.6f)
        )

        assertTrue(detection.isFemale)
        assertEquals(0.92f, detection.confidence, 0.0001f)
        assertEquals(0.1f, detection.boundingBox.left, 0.0001f)

        // Test conversion to pixel coordinates
        val pixelRect = detection.toPixelRect(1000, 2000)
        assertEquals(100f, pixelRect.left, 0.001f)
        assertEquals(400f, pixelRect.top, 0.001f)
        assertEquals(500f, pixelRect.right, 0.001f)
        assertEquals(1200f, pixelRect.bottom, 0.001f)
    }

    @Test
    fun testDecode_femaleGreaterThanOtherChannelsAndAboveThreshold_detected() {
        // Channels-first: [1, 7, 1]
        val matrix = Array(7) { FloatArray(1) }
        setCandidateBox(matrix, 0, cx = 208f, cy = 208f, w = 100f, h = 100f)
        matrix[4][0] = 0.85f // female >= 0.35f
        matrix[5][0] = 0.10f // male
        matrix[6][0] = 0.05f // ignored

        val output = arrayOf(matrix)
        val detections = detector.parseDetections(output, 416, 416)

        assertEquals(1, detections.size)
        val det = detections[0]
        assertTrue(det.isFemale)
        assertEquals(0.85f, det.confidence, 0.0001f)
    }

    @Test
    fun testDecode_femaleBelowThreshold_suppressed() {
        val matrix = Array(7) { FloatArray(1) }
        setCandidateBox(matrix, 0, cx = 208f, cy = 208f, w = 100f, h = 100f)
        matrix[4][0] = 0.30f // female < 0.35f
        matrix[5][0] = 0.10f
        matrix[6][0] = 0.05f

        val detections = detector.parseDetections(arrayOf(matrix), 416, 416)
        assertTrue("Female below confidence threshold must be suppressed", detections.isEmpty())
    }

    @Test
    fun testDecode_femaleNotGreaterThanMale_suppressed() {
        val matrix = Array(7) { FloatArray(1) }
        setCandidateBox(matrix, 0, cx = 208f, cy = 208f, w = 100f, h = 100f)
        matrix[4][0] = 0.50f // female >= 0.35f but male > female
        matrix[5][0] = 0.70f // male is higher
        matrix[6][0] = 0.10f

        val detections = detector.parseDetections(arrayOf(matrix), 416, 416)
        assertTrue("Female must be strictly greater than male", detections.isEmpty())
    }

    @Test
    fun testDecode_femaleNotGreaterThanIgnored_suppressed() {
        val matrix = Array(7) { FloatArray(1) }
        setCandidateBox(matrix, 0, cx = 208f, cy = 208f, w = 100f, h = 100f)
        matrix[4][0] = 0.40f // female >= 0.35f but ignored > female
        matrix[5][0] = 0.20f
        matrix[6][0] = 0.60f // ignored is higher

        val detections = detector.parseDetections(arrayOf(matrix), 416, 416)
        assertTrue("Female must be strictly greater than ignored class", detections.isEmpty())
    }

    @Test
    fun testDecode_femaleEqualMale_suppressed() {
        val matrix = Array(7) { FloatArray(1) }
        setCandidateBox(matrix, 0, cx = 208f, cy = 208f, w = 100f, h = 100f)
        matrix[4][0] = 0.50f
        matrix[5][0] = 0.50f // Tie
        matrix[6][0] = 0.10f

        val detections = detector.parseDetections(arrayOf(matrix), 416, 416)
        assertTrue("Tie between female and male must not be accepted", detections.isEmpty())
    }

    @Test
    fun testDecode_invalidOrDegenerateCoordinates_suppressed() {
        // Zero width
        val matrix = Array(7) { FloatArray(1) }
        setCandidateBox(matrix, 0, cx = 208f, cy = 208f, w = 0f, h = 100f)
        matrix[4][0] = 0.80f
        matrix[5][0] = 0.10f
        matrix[6][0] = 0.05f

        val detections = detector.parseDetections(arrayOf(matrix), 416, 416)
        assertTrue("Degenerate box width must be suppressed", detections.isEmpty())

        // NaN coordinate
        matrix[0][0] = Float.NaN
        val detectionsNan = detector.parseDetections(arrayOf(matrix), 416, 416)
        assertTrue("NaN coordinates must be suppressed", detectionsNan.isEmpty())
    }

    @Test
    fun testLetterboxUnpadding_squareImage() {
        val matrix = Array(7) { FloatArray(1) }
        // Center box in 416x416: cx=208, cy=208, w=104, h=104
        // x1 = 156, y1 = 156, x2 = 260, y2 = 260
        // Expected normalized: 156/416 = 0.375, 260/416 = 0.625
        setCandidateBox(matrix, 0, cx = 208f, cy = 208f, w = 104f, h = 104f)
        matrix[4][0] = 0.90f
        matrix[5][0] = 0.05f
        matrix[6][0] = 0.05f

        val detections = detector.parseDetections(arrayOf(matrix), 416, 416)
        assertEquals(1, detections.size)

        val box = detections[0].boundingBox
        assertEquals(0.375f, box.left, 0.001f)
        assertEquals(0.375f, box.top, 0.001f)
        assertEquals(0.625f, box.right, 0.001f)
        assertEquals(0.625f, box.bottom, 0.001f)
    }

    @Test
    fun testLetterboxUnpadding_portraitImage() {
        // Source image: 1000 x 2000 (aspect ratio 1:2)
        // scale = min(416/1000, 416/2000) = 416/2000 = 0.208
        // resizedW = 208, padX = (416-208)/2 = 104
        // resizedH = 416, padY = 0
        // Center box in original image: center=(500, 1000), size=(200, 400)
        // Expected normalized in orig image: [0.4, 0.4, 0.6, 0.6]
        // In model coordinates:
        // cx = 104 + 500 * 0.208 = 208
        // cy = 0 + 1000 * 0.208 = 208
        // w = 200 * 0.208 = 41.6
        // h = 400 * 0.208 = 83.2
        val matrix = Array(7) { FloatArray(1) }
        setCandidateBox(matrix, 0, cx = 208f, cy = 208f, w = 41.6f, h = 83.2f)
        matrix[4][0] = 0.88f
        matrix[5][0] = 0.05f
        matrix[6][0] = 0.05f

        val detections = detector.parseDetections(arrayOf(matrix), 1000, 2000)
        assertEquals(1, detections.size)

        val box = detections[0].boundingBox
        assertEquals(0.40f, box.left, 0.01f)
        assertEquals(0.40f, box.top, 0.01f)
        assertEquals(0.60f, box.right, 0.01f)
        assertEquals(0.60f, box.bottom, 0.01f)
    }

    @Test
    fun testLetterboxUnpadding_landscapeImage() {
        // Source image: 2000 x 1000 (aspect ratio 2:1)
        // scale = min(416/2000, 416/1000) = 416/2000 = 0.208
        // resizedW = 416, padX = 0
        // resizedH = 208, padY = (416-208)/2 = 104
        // Center box in original image: center=(1000, 500), size=(400, 200)
        // Expected normalized in orig image: [0.4, 0.4, 0.6, 0.6]
        // In model coordinates:
        // cx = 0 + 1000 * 0.208 = 208
        // cy = 104 + 500 * 0.208 = 208
        // w = 400 * 0.208 = 83.2
        // h = 200 * 0.208 = 41.6
        val matrix = Array(7) { FloatArray(1) }
        setCandidateBox(matrix, 0, cx = 208f, cy = 208f, w = 83.2f, h = 41.6f)
        matrix[4][0] = 0.91f
        matrix[5][0] = 0.04f
        matrix[6][0] = 0.05f

        val detections = detector.parseDetections(arrayOf(matrix), 2000, 1000)
        assertEquals(1, detections.size)

        val box = detections[0].boundingBox
        assertEquals(0.40f, box.left, 0.01f)
        assertEquals(0.40f, box.top, 0.01f)
        assertEquals(0.60f, box.right, 0.01f)
        assertEquals(0.60f, box.bottom, 0.01f)
    }

    @Test
    fun testCalculateIoU() {
        // Identical rectangles: IoU = 1.0
        val a = HalalifyDetection.createRectF(0f, 0f, 10f, 10f)
        val b = HalalifyDetection.createRectF(0f, 0f, 10f, 10f)
        assertEquals(1.0f, detector.calculateIoU(a, b), 0.0001f)

        // Completely disjoint rectangles: IoU = 0.0
        val c = HalalifyDetection.createRectF(20f, 20f, 30f, 30f)
        assertEquals(0.0f, detector.calculateIoU(a, c), 0.0001f)

        // 50% horizontal intersection (width 5, height 10 -> interArea = 50, union = 150 -> IoU = 50/150 = 1/3)
        val d = HalalifyDetection.createRectF(5f, 0f, 15f, 10f)
        assertEquals(1f / 3f, detector.calculateIoU(a, d), 0.001f)
    }

    @Test
    fun testNms_suppressesOverlappingBoxWithLowerConfidence() {
        // Two highly overlapping boxes
        // Candidate 0: conf 0.90, cx=208, cy=208, w=100, h=100
        // Candidate 1: conf 0.70, cx=210, cy=210, w=100, h=100 (almost identical, IoU > 0.8)
        val matrix = Array(7) { FloatArray(2) }

        setCandidateBox(matrix, 0, cx = 208f, cy = 208f, w = 100f, h = 100f)
        matrix[4][0] = 0.90f; matrix[5][0] = 0.05f; matrix[6][0] = 0.05f

        setCandidateBox(matrix, 1, cx = 210f, cy = 210f, w = 100f, h = 100f)
        matrix[4][1] = 0.70f; matrix[5][1] = 0.05f; matrix[6][1] = 0.05f

        val detections = detector.parseDetections(arrayOf(matrix), 416, 416)
        assertEquals("NMS must suppress the overlapping lower confidence box", 1, detections.size)
        assertEquals(0.90f, detections[0].confidence, 0.0001f)
    }

    @Test
    fun testNms_retainsDisjointBoxes() {
        // Two disjoint boxes in different parts of the frame
        val matrix = Array(7) { FloatArray(2) }

        // Top-left female
        setCandidateBox(matrix, 0, cx = 80f, cy = 80f, w = 60f, h = 60f)
        matrix[4][0] = 0.85f; matrix[5][0] = 0.05f; matrix[6][0] = 0.05f

        // Bottom-right female
        setCandidateBox(matrix, 1, cx = 320f, cy = 320f, w = 60f, h = 60f)
        matrix[4][1] = 0.80f; matrix[5][1] = 0.05f; matrix[6][0] = 0.05f

        val detections = detector.parseDetections(arrayOf(matrix), 416, 416)
        assertEquals("Non-overlapping boxes must both be retained", 2, detections.size)
    }

    @Test
    fun testEmptyOrInvalidInput_returnsEmptyList() {
        assertTrue(detector.parseDetections(emptyArray()).isEmpty())
        assertTrue(detector.parseDetections(arrayOf(emptyArray())).isEmpty())

        // Unsupported shape (e.g. 5 channels)
        val invalidMatrix = Array(5) { FloatArray(10) }
        assertTrue(detector.parseDetections(arrayOf(invalidMatrix)).isEmpty())
    }

    @Test
    fun testLastMaxRawFemaleConfidence_computesMaxAcrossAllCandidatesEvenBelowThreshold() {
        assertEquals(0f, detector.lastMaxRawFemaleConfidence, 0.0001f)

        // 3 candidates:
        // cand 0: female 0.12f
        // cand 1: female 0.29f (below threshold 0.35f)
        // cand 2: female 0.05f
        val matrix = Array(7) { FloatArray(3) }
        setCandidateBox(matrix, 0, cx = 208f, cy = 208f, w = 100f, h = 100f)
        matrix[4][0] = 0.12f; matrix[5][0] = 0.05f; matrix[6][0] = 0.05f

        setCandidateBox(matrix, 1, cx = 208f, cy = 208f, w = 100f, h = 100f)
        matrix[4][1] = 0.29f; matrix[5][1] = 0.05f; matrix[6][1] = 0.05f

        setCandidateBox(matrix, 2, cx = 208f, cy = 208f, w = 100f, h = 100f)
        matrix[4][2] = 0.05f; matrix[5][2] = 0.02f; matrix[6][2] = 0.02f

        val detections = detector.parseDetections(arrayOf(matrix), 416, 416)
        assertTrue("Detections should be empty because top female conf is 0.29 < 0.35 threshold", detections.isEmpty())
        assertEquals(0.29f, detector.lastMaxRawFemaleConfidence, 0.0001f)
    }

    @Test
    fun testLastMaxRawFemaleConfidence_emptyInput_resetsToZero() {
        // Set to non-zero first
        val matrix = Array(7) { FloatArray(1) }
        matrix[4][0] = 0.88f
        detector.parseDetections(arrayOf(matrix), 416, 416)
        assertEquals(0.88f, detector.lastMaxRawFemaleConfidence, 0.0001f)

        // Empty input
        detector.parseDetections(emptyArray())
        assertEquals(0f, detector.lastMaxRawFemaleConfidence, 0.0001f)
    }

    @Test
    fun testClose_releasesResourcesAndMarksClosed() {
        assertFalse(detector.isInitialized)
        detector.close()
        assertFalse(detector.isInitialized)
    }
}
