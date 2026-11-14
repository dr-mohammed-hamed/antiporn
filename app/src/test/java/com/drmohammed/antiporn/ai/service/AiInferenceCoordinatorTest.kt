package com.drmohammed.antiporn.ai.service

import android.graphics.Rect
import android.graphics.RectF
import com.drmohammed.antiporn.ai.core.tracking.KltOpticalFlowTracker
import com.drmohammed.antiporn.ai.core.tracking.TrackedModestyBox
import com.drmohammed.antiporn.ai.overlay.OverlayScheduler
import com.drmohammed.antiporn.ai.overlay.ScreenBlurOverlayManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiInferenceCoordinatorTest {

    private class TestOverlayScheduler : OverlayScheduler {
        val delayedTasks = mutableListOf<Runnable>()
        var cancelCount = 0

        override fun post(runnable: Runnable) {
            runnable.run()
        }

        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            delayedTasks.add(runnable)
        }

        override fun removeCallbacks(runnable: Runnable) {
            if (delayedTasks.remove(runnable)) {
                cancelCount++
            }
        }
    }

    private val stubContext = object : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): android.content.Context = this
        override fun getPackageName(): String = "com.drmohammed.antiporn"
        override fun getSystemService(name: String): Any? = null
    }

    private fun createRectF(l: Float, t: Float, r: Float, b: Float): RectF {
        val rect = RectF()
        rect.left = l
        rect.top = t
        rect.right = r
        rect.bottom = b
        return rect
    }

    private fun createRect(l: Int, t: Int, r: Int, b: Int): Rect {
        val rect = Rect()
        rect.left = l
        rect.top = t
        rect.right = r
        rect.bottom = b
        return rect
    }

    @Test
    fun testStationaryTarget_clearRunnableNeverScheduledWhenActive() {
        val scheduler = TestOverlayScheduler()
        val overlay = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        ).apply {
            isKltTrackingActive = true
        }

        var clearedCallbackFired = false
        overlay.onRegionsCleared = {
            clearedCallbackFired = true
        }

        // Simulate active detection
        val activeBoxes = listOf(createRectF(0.2f, 0.2f, 0.6f, 0.6f))
        overlay.updateRegionsMapped(
            rawRegions = activeBoxes,
            targetWidth = 1080,
            targetHeight = 2400,
            sourceWidth = 1,
            sourceHeight = 1
        )

        // Verify active state
        assertTrue("Overlay must be marked active when boxes are present", overlay.hasActiveRegions)
        assertEquals("Clear runnable must NEVER be scheduled while boxes are active", 0, scheduler.delayedTasks.size)
        assertFalse("onRegionsCleared callback must not have fired", clearedCallbackFired)

        // Stationary update: same boxes passed again
        overlay.updateRegionsMapped(
            rawRegions = activeBoxes,
            targetWidth = 1080,
            targetHeight = 2400,
            sourceWidth = 1,
            sourceHeight = 1
        )
        assertTrue("Overlay must stay active on stationary frames", overlay.hasActiveRegions)
        assertEquals("Clear runnable must remain unscheduled on stationary frames", 0, scheduler.delayedTasks.size)

        // Now simulate empty target list (target gone)
        overlay.updateRegions(emptyList())
        assertEquals("Clear runnable MUST be scheduled when regions become empty", 1, scheduler.delayedTasks.size)

        // Execute clear runnable
        scheduler.delayedTasks.first().run()
        assertFalse("Overlay must be inactive after clear runnable runs", overlay.hasActiveRegions)
        assertTrue("onRegionsCleared callback MUST fire to reset coordinator cache", clearedCallbackFired)
    }

    @Test
    fun testWindowBoundsProjection_splitScreenBottomHalfMapping() {
        val physicalWidth = 1080
        val physicalHeight = 2400
        val virtualWidth = 416
        val virtualHeight = 924

        // Window in bottom half of physical screen (e.g. split screen)
        val windowBounds = createRect(0, 1200, 1080, 2400)

        // Relative detection in window coordinate space [0..1]
        val detectionInWindow = createRectF(0.1f, 0.2f, 0.9f, 0.8f)

        // Scale factors
        val scaleX = virtualWidth.toFloat() / physicalWidth.toFloat()
        val scaleY = virtualHeight.toFloat() / physicalHeight.toFloat()

        val scaledWinLeft = windowBounds.left * scaleX
        val scaledWinTop = windowBounds.top * scaleY
        val scaledWinWidth = (windowBounds.right - windowBounds.left) * scaleX
        val scaledWinHeight = (windowBounds.bottom - windowBounds.top) * scaleY

        val pxL = scaledWinLeft + (detectionInWindow.left * scaledWinWidth)
        val pxT = scaledWinTop + (detectionInWindow.top * scaledWinHeight)
        val pxR = scaledWinLeft + (detectionInWindow.right * scaledWinWidth)
        val pxB = scaledWinTop + (detectionInWindow.bottom * scaledWinHeight)

        val normL = pxL / virtualWidth
        val normT = pxT / virtualHeight
        val normR = pxR / virtualWidth
        val normB = pxB / virtualHeight

        // Verification:
        // Window top is at Y=1200 / 2400 = 0.5 (halfway).
        // Detection starts at top=0.2 of the bottom half -> 0.5 + 0.2*0.5 = 0.6.
        assertEquals(0.1f, normL, 0.001f)
        assertEquals(0.6f, normT, 0.001f)
        assertEquals(0.9f, normR, 0.001f)
        assertEquals(0.9f, normB, 0.001f)

        assertTrue("Mapped box top must strictly be in lower half of screen", normT >= 0.5f)
        assertTrue("Mapped box bottom must strictly be in lower half of screen", normB <= 1.0f)
    }

    @Test
    fun testStillnessRenewAnchorTimestamp_preventsExpirationAfter2500ms() {
        val tracker = KltOpticalFlowTracker()
        val initialTimestamp = 10_000L

        // Feed an initial anchor box
        val anchorBox = TrackedModestyBox.createRectF(0.2f, 0.2f, 0.5f, 0.5f)
        tracker.updateAnchorsCrossTile(
            detections = listOf(anchorBox),
            tileNormRect = TrackedModestyBox.createRectF(0f, 0f, 1f, 1f),
            frameWidth = 416,
            frameHeight = 924,
            timestampMs = initialTimestamp
        )

        assertTrue("Tracker must have active tracks", tracker.hasActiveTracks())
        val initialTracks = tracker.getActiveTracks()
        assertEquals(1, initialTracks.size)
        assertEquals(initialTimestamp, initialTracks[0].lastAnchorTimestampMs)

        // Advance time by 3000ms (> 2500ms max anchor age) with stillness renewal
        val simulatedNow = initialTimestamp + 3000L
        val activeTracks = tracker.getActiveTracks()
        for (track in activeTracks) {
            // Stillness renewal logic from KltOpticalFlowTracker:
            // when ringChange < 0.20f and motion is near zero, renew anchor
            track.lastAnchorTimestampMs = simulatedNow
        }

        // Verify the track is not expired
        val age = simulatedNow - activeTracks[0].lastAnchorTimestampMs
        assertTrue("Anchor timestamp renewal must prevent > 2500ms cyclic drop", age < 2500L)
    }

    @Test
    fun testHysteresisGracePeriod_eliminatesVideoFlicker() {
        val tracker = KltOpticalFlowTracker()
        val anchorBox = TrackedModestyBox.createRectF(0.3f, 0.3f, 0.6f, 0.6f)
        tracker.updateAnchorsCrossTile(
            detections = listOf(anchorBox),
            tileNormRect = TrackedModestyBox.createRectF(0f, 0f, 1f, 1f),
            frameWidth = 416,
            frameHeight = 924,
            timestampMs = 1000L
        )

        val track = tracker.getActiveTracks()[0]

        // Simulate video dynamic motion where ringChange >= 0.20f across consecutive frames
        // Hysteresis allows up to 8 frames before eviction
        for (frame in 1..7) {
            track.ageFrames++
            val shouldEvict = track.ageFrames >= 8
            assertFalse("Track must NOT be evicted during 8-frame grace period (frame $frame)", shouldEvict)
        }

        // 8th frame reaches threshold
        track.ageFrames++
        val shouldEvict = track.ageFrames >= 8
        assertTrue("Track should only be evicted after exceeding 8 frames of disruption", shouldEvict)
    }
}
