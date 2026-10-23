package com.drmohammed.antiporn.ai.core.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class RowStrideExtractorTest {

    @Test
    fun testFloatToHalf_standardValues() {
        // 0.0f -> 0x0000
        val zeroHalf = RowStrideExtractor.floatToHalf(0.0f)
        assertEquals(0.toShort(), zeroHalf)

        // 1.0f in IEEE 754 half is 0x3c00 (15360)
        val oneHalf = RowStrideExtractor.floatToHalf(1.0f)
        assertEquals(15360.toShort(), oneHalf)

        // 0.5f in IEEE 754 half is 0x3800 (14336)
        val halfHalf = RowStrideExtractor.floatToHalf(0.5f)
        assertEquals(14336.toShort(), halfHalf)
    }

    @Test
    fun testFp16UnitLookup_tableIntegrity() {
        assertEquals(256, RowStrideExtractor.FP16_UNIT_LOOKUP.size)
        // index 0: 0/255 = 0.0f -> 0
        assertEquals(0.toShort(), RowStrideExtractor.FP16_UNIT_LOOKUP[0])
        // index 255: 255/255 = 1.0f -> 0x3c00
        assertEquals(15360.toShort(), RowStrideExtractor.FP16_UNIT_LOOKUP[255])
        // Monotonically non-decreasing bits for positive range
        for (i in 0 until 255) {
            val a = RowStrideExtractor.FP16_UNIT_LOOKUP[i].toInt() and 0xFFFF
            val b = RowStrideExtractor.FP16_UNIT_LOOKUP[i + 1].toInt() and 0xFFFF
            assertTrue("Lookup table must be monotonic: i=$i ($a <= $b)", a <= b)
        }
    }

    @Test
    fun testFrameDifferencer_burstFramesOnReset() {
        val differencer = FrameDifferencer()
        differencer.reset()

        val width = 320
        val height = 320
        val dummyBuffer = ByteBuffer.allocateDirect(width * height * 4)

        // Frame 1 after reset must return true due to forced burst
        val frame1 = differencer.hasScreenChanged(dummyBuffer, width, height, width * 4, 4)
        assertTrue("Frame 1 after reset must be scanned (forced burst)", frame1)

        // Frame 2 after reset must return true due to forced burst (burst=2)
        val frame2 = differencer.hasScreenChanged(dummyBuffer, width, height, width * 4, 4)
        assertTrue("Frame 2 after reset must be scanned (forced burst)", frame2)
    }
}
