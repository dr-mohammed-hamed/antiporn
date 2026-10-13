package com.drmohammed.antiporn.ai.core.model

import android.graphics.RectF

/**
 * كائن رصد يمثل صندوق إحاطة مع فئته ونسبة الثقة
 */
data class ModestyDetection(
    val classType: ModestyClass,
    val confidence: Float,
    val boundingBox: RectF
)
