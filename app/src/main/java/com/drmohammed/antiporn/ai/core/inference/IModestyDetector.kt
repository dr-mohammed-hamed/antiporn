package com.drmohammed.antiporn.ai.core.inference

import android.content.Context
import com.drmohammed.antiporn.ai.core.model.ModestyInferenceResult
import java.nio.ByteBuffer

/**
 * واجهة معيارية مجردة لمحرك الاستدلال لحفظ العورات
 * تسمح بفصل منطق النموذج عن أندرويد وتسهل نقله لأي مشروع آخر
 */
interface IModestyDetector : AutoCloseable {
    /** Runtime-resolved input geometry — parsed from tensor shape in initialize(). */
    val inputWidth: Int
    val inputHeight: Int
    val isChannelsLast: Boolean
    val isFloat16: Boolean
        get() = false

    /**
     * تهيئة النموذج وتحميل الأوزان في الذاكرة
     */
    fun initialize(context: Context): Boolean

    /**
     * تشغيل الاستدلال على مصفوفة الإدخال المعالجة مسبقاً
     */
    fun detect(inputBuffer: ByteBuffer): ModestyInferenceResult

    /**
     * إغلاق الجلسة وتحرير موارد العتاد
     */
    override fun close()
}
