package com.drmohammed.antiporn.ai.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileNetOutputDecoderTest {

    private val decoder = MobileNetOutputDecoder(suspicionThreshold = 0.10f)

    @Test
    fun imagenet_bikiniSpike_isSuspicious() {
        val logits = FloatArray(1000) { -2f }
        logits[445] = 6f // bikini
        val out = arrayOf(logits)
        val res = decoder.decode(out)
        assertTrue("Expected suspicious, score=${res.suspicionScore}", res.isSuspicious)
        assertTrue(res.suspicionScore >= 0.10f)
        assertEquals(445, res.topClassId)
    }

    @Test
    fun imagenet_uniformLogits_isClean() {
        val logits = FloatArray(1000) { 0f }
        val res = decoder.decode(arrayOf(logits))
        // uniform prob per class = 0.001 → sum of 7 ≈ 0.007 < 0.10
        assertFalse("Expected clean, score=${res.suspicionScore}", res.isSuspicious)
        assertTrue(res.suspicionScore < 0.10f)
    }

    @Test
    fun imagenet_dogSpike_isClean() {
        val logits = FloatArray(1000) { -2f }
        logits[207] = 6f // golden retriever (neutral)
        val res = decoder.decode(arrayOf(logits))
        assertFalse(res.isSuspicious)
    }

    @Test
    fun binarySigmoid_high_isSuspicious() {
        val res = decoder.decode(arrayOf(floatArrayOf(3f))) // sigmoid(3) ≈ 0.95
        assertTrue(res.isSuspicious)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedSize_throws() {
        decoder.decode(arrayOf(FloatArray(5)))
    }
}
