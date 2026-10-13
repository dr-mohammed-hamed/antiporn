package com.drmohammed.antiporn.ai.core.model

/**
 * التصنيفات المعتمدة شرعياً وهندسياً لنموذج YOLO11-Nano لحفظ العورات
 */
enum class ModestyClass(val classId: Int, val labelAr: String, val labelEn: String) {
    /**
     * فئة 0: العورات والجلد المكشوف (شعر، رقبة، أكتاف، بطن، أفخاذ، سيقان)
     */
    EXPOSED_SKIN(0, "عورات وجلد مكشوف", "Exposed Skin"),

    /**
     * فئة 1: الملابس الفاضحة والضيقة المجسمة (كاسيات عاريات، فتحات صدر، ليجن/يوجا)
     */
    TIGHT_AND_CLEAVAGE(1, "ملابس فاضحة ومجسمة", "Tight & Cleavage"),

    /**
     * فئة 2: مرساة الحشمة والأمان (حجاب، خمار، عباءات، معاطف طبية)
     * دورها تحييد الإنذارات الخاطئة
     */
    SAFE_MODEST_ANCHOR(2, "مرساة حشمة وأمان", "Safe Modest Anchor");

    val isViolation: Boolean
        get() = this == EXPOSED_SKIN || this == TIGHT_AND_CLEAVAGE

    companion object {
        fun fromId(id: Int): ModestyClass? {
            return when (id) {
                0 -> EXPOSED_SKIN
                1 -> TIGHT_AND_CLEAVAGE
                2 -> SAFE_MODEST_ANCHOR
                else -> null
            }
        }
    }
}
