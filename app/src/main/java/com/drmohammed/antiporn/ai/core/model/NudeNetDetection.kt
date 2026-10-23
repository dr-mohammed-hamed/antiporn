package com.drmohammed.antiporn.ai.core.model

import android.graphics.RectF

/**
 * مستويات الخطورة المعتمدة شرعياً وهندسياً لنموذج NudeNet v3
 */
enum class NudeNetThreatLevel {
    /**
     * إباحية صريحة مغلظة (أعضاء تناسلية، عري كامل، ممارسات صريحة)
     * الإجراء: اهتزاز قوي مزدوج + Toast تحذير أحمر + إنذار المشرف
     */
    HARD_PORN,

    /**
     * عري جزئي صريح ولباس فاضح (ملابس سباحة/داخلية، بطن مكشوف، مؤخرة مكشوفة)
     * الإجراء: نبضة اهتزاز تنبيهية + Toast تنبيه برتقالي
     */
    SOFT_PORN,

    /**
     * وجوه ولباس عادي مكسو (غض بصر مرئي فقط)
     * الإجراء: تظليل مرئي عائم (Blur) دون أي اهتزاز أو إغلاق للهاتف
     */
    VISUAL_BLUR_ONLY,

    /**
     * فئات الرجال أو الأطراف العادية التي لا تستوجب أي إجراء
     */
    SAFE_IGNORE
}

/**
 * الفئات الـ 18 التشريحية لنموذج NudeNet v3 مع تصنيف مستوى الخطورة الدقيق
 */
enum class NudeNetClass(
    val classId: Int,
    val rawLabel: String,
    val labelAr: String,
    val threatLevel: NudeNetThreatLevel
) {
    FEMALE_GENITALIA_COVERED(0, "FEMALE_GENITALIA_COVERED", "ملابس سباحة/داخلية", NudeNetThreatLevel.SOFT_PORN),
    FACE_FEMALE(1, "FACE_FEMALE", "وجه أنثى", NudeNetThreatLevel.VISUAL_BLUR_ONLY),
    BUTTOCKS_EXPOSED(2, "BUTTOCKS_EXPOSED", "مؤخرة مكشوفة", NudeNetThreatLevel.SOFT_PORN),
    FEMALE_BREAST_EXPOSED(3, "FEMALE_BREAST_EXPOSED", "صدر مكشوف", NudeNetThreatLevel.HARD_PORN),
    FEMALE_GENITALIA_EXPOSED(4, "FEMALE_GENITALIA_EXPOSED", "عورة مغلظة مكشوفة", NudeNetThreatLevel.HARD_PORN),
    MALE_BREAST_EXPOSED(5, "MALE_BREAST_EXPOSED", "صدر رجل", NudeNetThreatLevel.SAFE_IGNORE),
    ANUS_EXPOSED(6, "ANUS_EXPOSED", "عورة مغلظة مكشوفة", NudeNetThreatLevel.HARD_PORN),
    FEET_EXPOSED(7, "FEET_EXPOSED", "أقدام مكشوفة", NudeNetThreatLevel.SAFE_IGNORE),
    BELLY_COVERED(8, "BELLY_COVERED", "بطن مغطى", NudeNetThreatLevel.SAFE_IGNORE),
    FEET_COVERED(9, "FEET_COVERED", "أقدام مغطاة", NudeNetThreatLevel.SAFE_IGNORE),
    ARMPITS_COVERED(10, "ARMPITS_COVERED", "إبط مغطى", NudeNetThreatLevel.SAFE_IGNORE),
    ARMPITS_EXPOSED(11, "ARMPITS_EXPOSED", "إبط مكشوف", NudeNetThreatLevel.SAFE_IGNORE),
    FACE_MALE(12, "FACE_MALE", "وجه رجل", NudeNetThreatLevel.SAFE_IGNORE),
    BELLY_EXPOSED(13, "BELLY_EXPOSED", "بطن مكشوف", NudeNetThreatLevel.SOFT_PORN),
    MALE_GENITALIA_EXPOSED(14, "MALE_GENITALIA_EXPOSED", "عورة مغلظة مكشوفة", NudeNetThreatLevel.HARD_PORN),
    ANUS_COVERED(15, "ANUS_COVERED", "منطقة مغطاة", NudeNetThreatLevel.SAFE_IGNORE),
    FEMALE_BREAST_COVERED(16, "FEMALE_BREAST_COVERED", "لباس ضيق ومجسم للصدر", NudeNetThreatLevel.VISUAL_BLUR_ONLY),
    BUTTOCKS_COVERED(17, "BUTTOCKS_COVERED", "لباس مجسم", NudeNetThreatLevel.VISUAL_BLUR_ONLY);

    companion object {
        private val ID_MAP = values().associateBy { it.classId }

        fun fromId(id: Int): NudeNetClass? = ID_MAP[id]
    }
}

/**
 * يمثل رصداً تشريحياً محدداً من نموذج NudeNet v3
 */
data class NudeNetDetection(
    val classType: NudeNetClass,
    val confidence: Float,
    val boundingBox: RectF
) {
    val isHardPorn: Boolean
        get() = classType.threatLevel == NudeNetThreatLevel.HARD_PORN

    val isSoftPorn: Boolean
        get() = classType.threatLevel == NudeNetThreatLevel.SOFT_PORN

    val isVisualBlur: Boolean
        get() = classType.threatLevel == NudeNetThreatLevel.VISUAL_BLUR_ONLY
}

/**
 * نتيجة الاستدلال الشاملة لنموذج NudeNet v3 متضمنة التشخيص الزمني
 */
data class NudeNetResult(
    val detections: List<NudeNetDetection>,
    val hasHardPorn: Boolean,
    val hasSoftPorn: Boolean,
    val latencyMs: Long
)
