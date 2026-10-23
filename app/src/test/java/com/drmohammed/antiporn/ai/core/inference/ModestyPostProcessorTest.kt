package com.drmohammed.antiporn.ai.core.inference

import com.drmohammed.antiporn.ai.core.model.ModestyClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModestyPostProcessorTest {

    private val processor = ModestyPostProcessor()

    @Test
    fun testModestyClass_mappingsAndViolations() {
        // Class 0: PERSON
        val person = ModestyClass.fromId(0)
        assertNotNull(person)
        assertEquals(ModestyClass.PERSON, person)
        assertEquals(0, person?.classId)
        assertFalse(person!!.isViolation)

        // Class 1: EXPOSED_SKIN
        val skin = ModestyClass.fromId(1)
        assertNotNull(skin)
        assertEquals(ModestyClass.EXPOSED_SKIN, skin)
        assertEquals(1, skin?.classId)
        assertTrue(skin!!.isViolation)

        // Class 2: TIGHT_AND_CLEAVAGE
        val tight = ModestyClass.fromId(2)
        assertNotNull(tight)
        assertEquals(ModestyClass.TIGHT_AND_CLEAVAGE, tight)
        assertEquals(2, tight?.classId)
        assertTrue(tight!!.isViolation)

        // Class 3: SAFE_MODEST_ANCHOR
        val anchor = ModestyClass.fromId(3)
        assertNotNull(anchor)
        assertEquals(ModestyClass.SAFE_MODEST_ANCHOR, anchor)
        assertEquals(3, anchor?.classId)
        assertFalse(anchor!!.isViolation)

        // Invalid IDs
        assertNull(ModestyClass.fromId(-1))
        assertNull(ModestyClass.fromId(4))
        assertNull(ModestyClass.fromId(99))
    }

    @Test
    fun testEmptyOrInvalidInput_returnsEmpty() {
        assertTrue(processor.process(emptyArray()).isEmpty())
        assertTrue(processor.process(arrayOf(emptyArray())).isEmpty())
        assertTrue(processor.process(arrayOf(arrayOf(floatArrayOf()))).isEmpty())

        // Unsupported channels (e.g. 7 or 9)
        val wrongChannels = Array(1) { Array(7) { FloatArray(10) } }
        assertTrue(processor.process(wrongChannels).isEmpty())
    }

    @Test
    fun testPersonGating_suppressesViolationWhenNoPersonInFrame() {
        // Channels-First: [1, 8, 1]
        // Bbox: cx=100, cy=100, w=50, h=50
        // Classes: person=0.0, skin=0.85, tight=0.0, anchor=0.0
        val matrix = Array(8) { FloatArray(1) }
        matrix[0][0] = 100f // cx
        matrix[1][0] = 100f // cy
        matrix[2][0] = 50f  // w
        matrix[3][0] = 50f  // h
        matrix[4][0] = 0.0f // PERSON
        matrix[5][0] = 0.85f // EXPOSED_SKIN
        matrix[6][0] = 0.0f
        matrix[7][0] = 0.0f

        val output = arrayOf(matrix)
        val detections = processor.process(output)
        // Without any person box, inanimate object violation must be suppressed
        assertTrue("Violation should be suppressed without a person box", detections.isEmpty())
    }

    @Test
    fun testPersonGating_suppressesViolationWhenOutsidePersonBox() {
        // Channels-First: [1, 8, 2]
        // Box 0: PERSON at [50, 50, 100, 100] (cx=50, cy=50, w=100, h=100) -> [0..100, 0..100]
        // Box 1: EXPOSED_SKIN at [250, 250, 40, 40] (cx=250, cy=250, w=40, h=40) -> [230..270, 230..270]
        val matrix = Array(8) { FloatArray(2) }

        // Person
        matrix[0][0] = 50f; matrix[1][0] = 50f; matrix[2][0] = 100f; matrix[3][0] = 100f
        matrix[4][0] = 0.90f // PERSON
        matrix[5][0] = 0.10f
        matrix[6][0] = 0.05f
        matrix[7][0] = 0.0f

        // Skin (distant from person)
        matrix[0][1] = 250f; matrix[1][1] = 250f; matrix[2][1] = 40f; matrix[3][1] = 40f
        matrix[4][1] = 0.10f
        matrix[5][1] = 0.85f // EXPOSED_SKIN
        matrix[6][1] = 0.05f
        matrix[7][1] = 0.0f

        val detections = processor.process(arrayOf(matrix))
        assertTrue("Violation outside person box should be suppressed", detections.none { it.classType.isViolation })
        assertEquals(1, detections.size)
        assertEquals(ModestyClass.PERSON, detections[0].classType)
    }

    @Test
    fun testPersonGating_acceptsViolationContainedInsidePersonBox() {
        // Channels-First: [1, 8, 2]
        // Box 0: PERSON at [100..300, 50..250] -> cx=200, cy=150, w=200, h=200
        // Box 1: EXPOSED_SKIN at [150..200, 100..150] -> cx=175, cy=125, w=50, h=50 (100% inside person)
        val matrix = Array(8) { FloatArray(2) }

        // Person box
        matrix[0][0] = 200f; matrix[1][0] = 150f; matrix[2][0] = 200f; matrix[3][0] = 200f
        matrix[4][0] = 0.85f // PERSON >= 0.35f
        matrix[5][0] = 0.10f
        matrix[6][0] = 0.05f
        matrix[7][0] = 0.0f

        // Skin violation inside person
        matrix[0][1] = 175f; matrix[1][1] = 125f; matrix[2][1] = 50f; matrix[3][1] = 50f
        matrix[4][1] = 0.10f
        matrix[5][1] = 0.80f // EXPOSED_SKIN >= 0.55f
        matrix[6][1] = 0.05f
        matrix[7][1] = 0.0f

        val detections = processor.process(arrayOf(matrix))
        assertEquals(2, detections.size)
        val person = detections.firstOrNull { it.classType == ModestyClass.PERSON }
        assertNotNull(person)
        assertEquals(0.85f, person!!.confidence, 0.001f)
        val violation = detections.firstOrNull { it.classType.isViolation }
        assertNotNull(violation)
        assertEquals(ModestyClass.EXPOSED_SKIN, violation!!.classType)
        assertEquals(0.80f, violation.confidence, 0.001f)
    }

    @Test
    fun testSafeModestAnchor_suppressesAllViolations() {
        // Channels-First: [1, 8, 3]
        // Box 0: PERSON
        // Box 1: EXPOSED_SKIN (inside person)
        // Box 2: SAFE_MODEST_ANCHOR overlapping with skin (confidence = 0.65f >= 0.60f)
        val matrix = Array(8) { FloatArray(3) }

        // Person
        matrix[0][0] = 200f; matrix[1][0] = 150f; matrix[2][0] = 200f; matrix[3][0] = 200f
        matrix[4][0] = 0.85f

        // Skin
        matrix[0][1] = 175f; matrix[1][1] = 125f; matrix[2][1] = 50f; matrix[3][1] = 50f
        matrix[5][1] = 0.90f

        // Anchor overlapping with skin box
        matrix[0][2] = 175f; matrix[1][2] = 125f; matrix[2][2] = 50f; matrix[3][2] = 50f
        matrix[7][2] = 0.65f // SAFE_MODEST_ANCHOR >= 0.60f

        val detections = processor.process(arrayOf(matrix))
        assertTrue("Safe modest anchor overlapping with violation must suppress it via NMS", detections.none { it.classType.isViolation })
        assertTrue("Anchor or person boxes should be retained for visualization", detections.any { it.classType == ModestyClass.SAFE_MODEST_ANCHOR })
    }

    @Test
    fun testSafeModestAnchor_doesNotBypassViolationsAcrossDifferentBoxes() {
        // Channels-First: [1, 8, 3]
        // Box 0: PERSON (covers 0..300)
        // Box 1: EXPOSED_SKIN at (75, 125, 40, 40)
        // Box 2: SAFE_MODEST_ANCHOR at distant location (250, 125, 40, 40)
        val matrix = Array(8) { FloatArray(3) }

        // Person
        matrix[0][0] = 160f; matrix[1][0] = 150f; matrix[2][0] = 300f; matrix[3][0] = 200f
        matrix[4][0] = 0.85f

        // Skin
        matrix[0][1] = 75f; matrix[1][1] = 125f; matrix[2][1] = 40f; matrix[3][1] = 40f
        matrix[5][1] = 0.90f

        // Anchor (non-overlapping)
        matrix[0][2] = 250f; matrix[1][2] = 125f; matrix[2][2] = 40f; matrix[3][2] = 40f
        matrix[7][2] = 0.65f

        val detections = processor.process(arrayOf(matrix))
        assertTrue("Distant violation must NOT be suppressed by anchor", detections.any { it.classType == ModestyClass.EXPOSED_SKIN })
    }

    @Test
    fun testChannelsLast_formatSupported() {
        // Channels-Last: [1, 2, 8]
        val matrix = Array(2) { FloatArray(8) }

        // Box 0: PERSON
        matrix[0][0] = 150f // cx
        matrix[0][1] = 150f // cy
        matrix[0][2] = 200f // w
        matrix[0][3] = 200f // h
        matrix[0][4] = 0.80f // PERSON
        matrix[0][5] = 0.10f
        matrix[0][6] = 0.05f
        matrix[0][7] = 0.0f

        // Box 1: TIGHT_AND_CLEAVAGE inside person
        matrix[1][0] = 150f // cx
        matrix[1][1] = 150f // cy
        matrix[1][2] = 60f  // w
        matrix[1][3] = 60f  // h
        matrix[1][4] = 0.05f
        matrix[1][5] = 0.10f
        matrix[1][6] = 0.75f // TIGHT_AND_CLEAVAGE
        matrix[1][7] = 0.0f

        val detections = processor.process(arrayOf(matrix))
        assertEquals(2, detections.size)
        val person = detections.firstOrNull { it.classType == ModestyClass.PERSON }
        assertNotNull(person)
        val violation = detections.firstOrNull { it.classType.isViolation }
        assertNotNull(violation)
        assertEquals(ModestyClass.TIGHT_AND_CLEAVAGE, violation!!.classType)
        assertEquals(0.75f, violation.confidence, 0.001f)
    }

    @Test
    fun testConfidenceThreshold055_suppressesViolationsBelowThreshold() {
        // Person box
        val matrix = Array(8) { FloatArray(2) }
        matrix[0][0] = 200f; matrix[1][0] = 150f; matrix[2][0] = 200f; matrix[3][0] = 200f
        matrix[4][0] = 0.85f // PERSON

        // Skin violation with confidence 0.45f (< 0.55f default threshold)
        matrix[0][1] = 175f; matrix[1][1] = 125f; matrix[2][1] = 50f; matrix[3][1] = 50f
        matrix[5][1] = 0.45f // EXPOSED_SKIN < 0.55f

        val detections = processor.process(arrayOf(matrix))
        assertTrue(detections.none { it.classType.isViolation })
        assertEquals(1, detections.size)
        assertEquals(ModestyClass.PERSON, detections[0].classType)
    }

    @Test
    fun testNonFiniteAndCorruptedValues_doNotCrash() {
        val matrix = Array(8) { FloatArray(3) }
        // NaN coordinates
        matrix[0][0] = Float.NaN; matrix[1][0] = 100f; matrix[2][0] = 50f; matrix[3][0] = 50f
        matrix[5][0] = 0.9f

        // Infinite score
        matrix[0][1] = 100f; matrix[1][1] = 100f; matrix[2][1] = 50f; matrix[3][1] = 50f
        matrix[5][1] = Float.POSITIVE_INFINITY

        // Zero / Negative width
        matrix[0][2] = 100f; matrix[1][2] = 100f; matrix[2][2] = -10f; matrix[3][2] = 50f
        matrix[5][2] = 0.8f

        val detections = processor.process(arrayOf(matrix))
        assertTrue(detections.isEmpty())
    }
}
