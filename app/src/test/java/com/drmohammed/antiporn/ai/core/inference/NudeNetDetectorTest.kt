package com.drmohammed.antiporn.ai.core.inference

import android.graphics.RectF
import com.drmohammed.antiporn.ai.core.model.NudeNetClass
import com.drmohammed.antiporn.ai.core.model.NudeNetDetection
import com.drmohammed.antiporn.ai.core.model.NudeNetResult
import com.drmohammed.antiporn.ai.core.model.NudeNetThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات الوحدة الصارمة لتصنيفات NudeNet v3 وحساب درجات الخطورة
 */
class NudeNetDetectorTest {

    @Test
    fun testAll18ClassesMappedCorrectly() {
        assertEquals(18, NudeNetClass.values().size)
        for (i in 0 until 18) {
            val cls = NudeNetClass.fromId(i)
            assertNotNull("Class ID $i must map to a valid NudeNetClass", cls)
            assertEquals(i, cls?.classId)
        }
    }

    @Test
    fun testFemaleBreastCovered_strictlyVisualBlurOnly() {
        val covered = NudeNetClass.FEMALE_BREAST_COVERED
        assertEquals(NudeNetThreatLevel.VISUAL_BLUR_ONLY, covered.threatLevel)

        val detection = NudeNetDetection(
            classType = covered,
            confidence = 0.95f,
            boundingBox = RectF(0f, 0f, 100f, 100f)
        )

        assertTrue(detection.isVisualBlur)
        assertFalse("FEMALE_BREAST_COVERED must never be marked as Soft Porn", detection.isSoftPorn)
        assertFalse("FEMALE_BREAST_COVERED must never be marked as Hard Porn", detection.isHardPorn)
    }

    @Test
    fun testHardPornClasses() {
        val hardClasses = listOf(
            NudeNetClass.FEMALE_GENITALIA_EXPOSED,
            NudeNetClass.MALE_GENITALIA_EXPOSED,
            NudeNetClass.FEMALE_BREAST_EXPOSED,
            NudeNetClass.ANUS_EXPOSED
        )

        for (cls in hardClasses) {
            assertEquals("${cls.name} must have threatLevel HARD_PORN", NudeNetThreatLevel.HARD_PORN, cls.threatLevel)
            val det = NudeNetDetection(cls, 0.90f, RectF(0f, 0f, 50f, 50f))
            assertTrue("${cls.name} isHardPorn must be true", det.isHardPorn)
            assertFalse("${cls.name} isSoftPorn must be false", det.isSoftPorn)
        }
    }

    @Test
    fun testSoftPornClasses() {
        val softClasses = listOf(
            NudeNetClass.FEMALE_GENITALIA_COVERED,
            NudeNetClass.BUTTOCKS_EXPOSED,
            NudeNetClass.BELLY_EXPOSED
        )

        for (cls in softClasses) {
            assertEquals("${cls.name} must have threatLevel SOFT_PORN", NudeNetThreatLevel.SOFT_PORN, cls.threatLevel)
            val det = NudeNetDetection(cls, 0.85f, RectF(0f, 0f, 50f, 50f))
            assertTrue("${cls.name} isSoftPorn must be true", det.isSoftPorn)
            assertFalse("${cls.name} isHardPorn must be false", det.isHardPorn)
        }
    }

    @Test
    fun testNudeNetResult_evaluation() {
        val hardDet = NudeNetDetection(NudeNetClass.FEMALE_GENITALIA_EXPOSED, 0.88f, RectF(0f, 0f, 10f, 10f))
        val softDet = NudeNetDetection(NudeNetClass.BELLY_EXPOSED, 0.75f, RectF(0f, 0f, 10f, 10f))
        val blurDet = NudeNetDetection(NudeNetClass.FACE_FEMALE, 0.92f, RectF(0f, 0f, 10f, 10f))

        val result1 = NudeNetResult(
            detections = listOf(hardDet, blurDet),
            hasHardPorn = true,
            hasSoftPorn = false,
            latencyMs = 25L
        )
        assertTrue(result1.hasHardPorn)
        assertFalse(result1.hasSoftPorn)

        val result2 = NudeNetResult(
            detections = listOf(softDet, blurDet),
            hasHardPorn = false,
            hasSoftPorn = true,
            latencyMs = 22L
        )
        assertFalse(result2.hasHardPorn)
        assertTrue(result2.hasSoftPorn)

        val resultModest = NudeNetResult(
            detections = listOf(blurDet),
            hasHardPorn = false,
            hasSoftPorn = false,
            latencyMs = 20L
        )
        assertFalse(resultModest.hasHardPorn)
        assertFalse(resultModest.hasSoftPorn)
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float): RectF {
        return RectF().apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    @Test
    fun testCalculateIoU_identicalBoxes() {
        val detector = NudeNetDetector()
        val boxA = rect(10f, 10f, 50f, 50f)
        val boxB = rect(10f, 10f, 50f, 50f)

        val iou = detector.calculateIoU(boxA, boxB)
        assertEquals(1.0f, iou, 0.001f)
    }

    @Test
    fun testCalculateIoU_disjointBoxes() {
        val detector = NudeNetDetector()
        val boxA = rect(0f, 0f, 10f, 10f)
        val boxB = rect(20f, 20f, 30f, 30f)

        val iou = detector.calculateIoU(boxA, boxB)
        assertEquals(0.0f, iou, 0.001f)
    }

    @Test
    fun testApplyNms_suppressesLowerScoreOverlappingBox() {
        val detector = NudeNetDetector(iouThreshold = 0.5f)
        val boxes = listOf(
            rect(10f, 10f, 50f, 50f), // box 0: score 0.90
            rect(12f, 12f, 52f, 52f), // box 1: score 0.70 (overlaps > 0.5 with box 0)
            rect(100f, 100f, 150f, 150f) // box 2: score 0.85 (distinct)
        )
        val scores = listOf(0.90f, 0.70f, 0.85f)

        val selected = detector.applyNms(boxes, scores, 0.5f)
        assertEquals(2, selected.size)
        assertTrue("Box 0 must be selected", selected.contains(0))
        assertTrue("Box 2 must be selected", selected.contains(2))
        assertFalse("Box 1 must be suppressed by NMS", selected.contains(1))
    }

    @Test
    fun testParseDetections_filtersBelowConfidenceThreshold() {
        val detector = NudeNetDetector(confThreshold = 0.5f)
        // matrix shape: [22, 1]
        val matrix = Array(22) { FloatArray(1) }
        // Center (160, 160), Width 50, Height 50
        matrix[0][0] = 160f
        matrix[1][0] = 160f
        matrix[2][0] = 50f
        matrix[3][0] = 50f
        // Channel 4 + 3 (FEMALE_BREAST_EXPOSED = classId 3)
        matrix[4 + 3][0] = 0.30f // Below 0.50 threshold

        val detections = detector.parseDetections(
            matrix = matrix,
            origWidth = 1080,
            origHeight = 1920,
            scale = 0.1666f,
            padX = 0f,
            padY = 0f
        )

        assertTrue("Detection with confidence below threshold must be dropped", detections.isEmpty())
    }
}
