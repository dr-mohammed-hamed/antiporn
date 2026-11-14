package com.drmohammed.antiporn

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drmohammed.antiporn.ai.core.inference.HalalifyGenderDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class HalalifyDiagnosticTest {

    companion object {
        private const val TAG = "HalalifyDiagTest"
    }

    @Test
    fun testHalalifyOnCoupleImage() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val detector = HalalifyGenderDetector(appContext)
        assertTrue("Halalify failed to initialize!", detector.isInitialized)

        val bitmap = try {
            testContext.assets.open("test_couple.jpg").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            appContext.assets.open("test_couple.jpg").use { BitmapFactory.decodeStream(it) }
        }

        Log.i(TAG, "Testing couple image: ${bitmap.width}x${bitmap.height}")
        val detections = detector.detect(bitmap)

        Log.i(TAG, "Detection count: ${detections.size}, MaxRawFemale: ${detector.lastMaxRawFemaleConfidence}")
        detections.forEachIndexed { idx, det ->
            val pxRect = det.toPixelRect(bitmap.width, bitmap.height)
            Log.i(TAG, "Det[$idx]: conf=${det.confidence}, normBox=${det.boundingBox}, pxBox=$pxRect")
        }

        val outBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outBitmap)
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        val textPaint = Paint().apply {
            color = Color.RED
            textSize = 32f
        }

        detections.forEach { det ->
            val r = det.toPixelRect(bitmap.width, bitmap.height)
            canvas.drawRect(r, paint)
            canvas.drawText("Female: ${(det.confidence * 100).toInt()}%", r.left, (r.top - 10f).coerceAtLeast(30f), textPaint)
        }

        val outFile = File(appContext.cacheDir, "halalify_couple_result.png")
        FileOutputStream(outFile).use { outBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "Saved couple test result to: ${outFile.absolutePath}")

        detector.close()
    }

    @Test
    fun testHalalifyOnTallScreenshot() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val detector = HalalifyGenderDetector(appContext)
        assertTrue("Halalify failed to initialize!", detector.isInitialized)

        val bitmap = try {
            testContext.assets.open("test_screenshot_tall.jpg").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            appContext.assets.open("test_screenshot_tall.jpg").use { BitmapFactory.decodeStream(it) }
        }

        Log.i(TAG, "Testing tall screenshot: ${bitmap.width}x${bitmap.height}")
        val detections = detector.detect(bitmap)

        Log.i(TAG, "Screenshot detection count: ${detections.size}, MaxRawFemale: ${detector.lastMaxRawFemaleConfidence}")
        detections.forEachIndexed { idx, det ->
            val pxRect = det.toPixelRect(bitmap.width, bitmap.height)
            Log.i(TAG, "Screenshot Det[$idx]: conf=${det.confidence}, normBox=${det.boundingBox}, pxBox=$pxRect")
        }

        val outBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outBitmap)
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }

        detections.forEach { det ->
            val r = det.toPixelRect(bitmap.width, bitmap.height)
            canvas.drawRect(r, paint)
        }

        val outFile = File(appContext.cacheDir, "halalify_screenshot_result.png")
        FileOutputStream(outFile).use { outBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "Saved screenshot test result to: ${outFile.absolutePath}")

        detector.close()
    }
}
