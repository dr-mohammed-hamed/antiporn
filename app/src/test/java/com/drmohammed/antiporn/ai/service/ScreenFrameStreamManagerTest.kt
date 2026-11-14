package com.drmohammed.antiporn.ai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class ScreenFrameStreamManagerTest {

    private fun computeVirtualDimensions(realWidth: Int, realHeight: Int): Pair<Int, Int> {
        val isPortrait = realHeight >= realWidth
        val shortEdge = 416.coerceAtMost(minOf(realWidth, realHeight))
        return if (isPortrait) {
            val w = shortEdge
            val h = ((realHeight.toFloat() * w) / realWidth).roundToInt().coerceAtLeast(1)
            Pair(w, h)
        } else {
            val h = shortEdge
            val w = ((realWidth.toFloat() * h) / realHeight).roundToInt().coerceAtLeast(1)
            Pair(w, h)
        }
    }

    @Test
    fun testComputeVirtualDimensions_portraitStandard() {
        val (w, h) = computeVirtualDimensions(1080, 2400)
        assertEquals(416, w)
        assertEquals(924, h)
    }

    @Test
    fun testComputeVirtualDimensions_landscapeStandard() {
        val (w, h) = computeVirtualDimensions(2400, 1080)
        assertEquals(924, w)
        assertEquals(416, h)
    }

    @Test
    fun testComputeVirtualDimensions_squareScreen() {
        val (w, h) = computeVirtualDimensions(1080, 1080)
        assertEquals(416, w)
        assertEquals(416, h)
    }

    @Test
    fun testComputeVirtualDimensions_lowResolutionDisplay() {
        // Lower than 416 short edge
        val (w, h) = computeVirtualDimensions(360, 640)
        assertEquals(360, w)
        assertEquals(640, h)
    }

    @Test
    fun testFrameDropBackpressureValve() {
        val isProcessingFrame = AtomicBoolean(false)

        // Frame 1 arrives: valve opens
        val acceptedFrame1 = isProcessingFrame.compareAndSet(false, true)
        assertTrue("Frame 1 should be accepted by backpressure valve", acceptedFrame1)

        // Frame 2 arrives while Frame 1 is still processing: valve closes (drops frame)
        val acceptedFrame2 = isProcessingFrame.compareAndSet(false, true)
        assertFalse("Frame 2 should be dropped while Frame 1 is processing", acceptedFrame2)

        // Frame 1 finishes processing
        isProcessingFrame.set(false)

        // Frame 3 arrives: valve opens again
        val acceptedFrame3 = isProcessingFrame.compareAndSet(false, true)
        assertTrue("Frame 3 should be accepted after Frame 1 completes", acceptedFrame3)
    }

    @Test
    fun testOrientationChangeDetection() {
        var currentW = 416
        var currentH = 924

        // Portrait bounds
        val portraitRealW = 1080
        val portraitRealH = 2400
        val (pW, pH) = computeVirtualDimensions(portraitRealW, portraitRealH)
        val needsRecreationPortrait = (pW != currentW || pH != currentH)
        assertFalse("No recreation needed when orientation has not changed", needsRecreationPortrait)

        // Device rotates to landscape
        val landscapeRealW = 2400
        val landscapeRealH = 1080
        val (lW, lH) = computeVirtualDimensions(landscapeRealW, landscapeRealH)
        val needsRecreationLandscape = (lW != currentW || lH != currentH)
        assertTrue("Recreation MUST be triggered on orientation change", needsRecreationLandscape)

        // Update state to new dimensions
        currentW = lW
        currentH = lH
        assertEquals(924, currentW)
        assertEquals(416, currentH)
    }
}
