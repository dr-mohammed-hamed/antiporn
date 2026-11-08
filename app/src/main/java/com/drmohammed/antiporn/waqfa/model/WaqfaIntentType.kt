package com.drmohammed.antiporn.waqfa.model

/**
 * نوع النية والهدف المسجل من قِبل المستخدم عند اعتراضه بواسطة نظام «وَقْفَة».
 */
enum class WaqfaIntentType {
    /**
     * الدخول لغرض وظيفي أو مهمة محددة واعية (Task-oriented / Purposeful).
     */
    PURPOSEFUL,

    /**
     * الدخول بدافع الهروب، الملل، التوتر، أو بدون هدف محدد (Aimless / Escapism).
     */
    ESCAPISM
}
