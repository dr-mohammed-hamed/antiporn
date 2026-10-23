package com.drmohammed.antiporn.ai.diagnostic

import android.graphics.RectF
import com.drmohammed.antiporn.ai.core.model.ModestyClass
import com.drmohammed.antiporn.ai.core.model.ModestyDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramAlertDispatcherTest {

    @Test
    fun testBuildAlertCaption_containsAllRequiredFields() {
        val violation = ModestyDetection(
            classType = ModestyClass.EXPOSED_SKIN,
            confidence = 0.887f,
            boundingBox = RectF(10f, 20f, 100f, 150f)
        )
        val latencyMs = 42L

        val caption = TelegramAlertDispatcher.buildAlertCaption(violation, latencyMs)

        // التحقق من ترويسة التقرير
        assertTrue(caption.contains("🛡️ تقرير رصد ميداني - AntiPorn AI Sentinel"))
        // التحقق من اسم الفئة بالعربية والإنجليزية
        assertTrue(caption.contains(ModestyClass.EXPOSED_SKIN.labelAr))
        assertTrue(caption.contains(ModestyClass.EXPOSED_SKIN.labelEn))
        // التحقق من نسبة الثقة المحسوبة (88%)
        assertTrue(caption.contains("88%"))
        // التحقق من زمن الفحص
        assertTrue(caption.contains("42 ms"))
        // التحقق من دليل الألوان
        assertTrue(caption.contains("🟩 أخضر = جسد بشري | 🟥 أحمر = العورة المشتبه بها"))
    }

    @Test
    fun testBuildAlertCaption_tightAndCleavageViolation() {
        val violation = ModestyDetection(
            classType = ModestyClass.TIGHT_AND_CLEAVAGE,
            confidence = 0.952f,
            boundingBox = RectF(50f, 60f, 200f, 250f)
        )
        val latencyMs = 60L

        val caption = TelegramAlertDispatcher.buildAlertCaption(violation, latencyMs)

        assertTrue(caption.contains(ModestyClass.TIGHT_AND_CLEAVAGE.labelAr))
        assertTrue(caption.contains(ModestyClass.TIGHT_AND_CLEAVAGE.labelEn))
        assertTrue(caption.contains("95%"))
        assertTrue(caption.contains("60 ms"))
    }

    @Test
    fun testMapBoundingBox_normalizedCoordinates() {
        // صندوق إحداثياته نسبية [0..1]
        val normBox = RectF().apply {
            left = 0.1f
            top = 0.2f
            right = 0.5f
            bottom = 0.8f
        }
        val mapped = TelegramAlertDispatcher.mapBoundingBox(normBox, 1000, 2000)

        assertEquals(100f, mapped.left, 0.01f)
        assertEquals(400f, mapped.top, 0.01f)
        assertEquals(500f, mapped.right, 0.01f)
        assertEquals(1600f, mapped.bottom, 0.01f)
    }

    @Test
    fun testMapBoundingBox_modelSizeScaledCoordinates() {
        // صندوق إحداثياته ضمن حجم النموذج (320x320) والصورة بحجم شاشة (640x640)
        val modelBox = RectF().apply {
            left = 32f
            top = 64f
            right = 160f
            bottom = 240f
        }
        val mapped = TelegramAlertDispatcher.mapBoundingBox(modelBox, 640, 640)

        assertEquals(64f, mapped.left, 0.01f)
        assertEquals(128f, mapped.top, 0.01f)
        assertEquals(320f, mapped.right, 0.01f)
        assertEquals(480f, mapped.bottom, 0.01f)
    }

    @Test
    fun testMapBoundingBox_matching320Coordinates() {
        // صندوق بنفس حجم الصورة 320x320
        val box = RectF().apply {
            left = 10f
            top = 20f
            right = 150f
            bottom = 200f
        }
        val mapped = TelegramAlertDispatcher.mapBoundingBox(box, 320, 320)

        assertEquals(10f, mapped.left, 0.01f)
        assertEquals(20f, mapped.top, 0.01f)
        assertEquals(150f, mapped.right, 0.01f)
        assertEquals(200f, mapped.bottom, 0.01f)
    }
}
