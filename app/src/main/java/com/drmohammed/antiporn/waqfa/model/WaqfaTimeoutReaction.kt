package com.drmohammed.antiporn.waqfa.model

import android.content.Context

/**
 * الأنواع المتاحة لردود الفعل عند انتهاء وقت جلسة «وَقْفَة».
 */
enum class WaqfaTimeoutReactionType {
    /**
     * إخراج المستخدم فوراً إلى الشاشة الرئيسية (Home Screen).
     */
    EXIT_ONLY,

    /**
     * إخراج المستخدم فوراً وتجميد التطبيق مؤقتاً عبر DPM لمدة تهدئة (الافتراضي 3 دقائق).
     */
    EXIT_AND_FREEZE,

    /**
     * إظهار تنبيه واضح للمستخدم دون إخراجه قسرياً.
     */
    NOTIFY_ONLY
}

/**
 * نمط الاستراتيجية (Strategy Pattern) لردود فعل انتهاء وقت جلسة «وَقْفَة».
 * يتيح التوسع المستقبلي لإضافة ردود فعل جديدة دون تعديل الكود الأساسي (OCP).
 */
sealed interface WaqfaTimeoutReaction {
    val type: WaqfaTimeoutReactionType

    object ExitOnly : WaqfaTimeoutReaction {
        override val type: WaqfaTimeoutReactionType = WaqfaTimeoutReactionType.EXIT_ONLY
    }

    data class ExitAndFreeze(val cooldownMinutes: Int = 3) : WaqfaTimeoutReaction {
        override val type: WaqfaTimeoutReactionType = WaqfaTimeoutReactionType.EXIT_AND_FREEZE
    }

    object NotifyOnly : WaqfaTimeoutReaction {
        override val type: WaqfaTimeoutReactionType = WaqfaTimeoutReactionType.NOTIFY_ONLY
    }

    companion object {
        fun fromType(type: WaqfaTimeoutReactionType, cooldownMinutes: Int = 3): WaqfaTimeoutReaction {
            return when (type) {
                WaqfaTimeoutReactionType.EXIT_ONLY -> ExitOnly
                WaqfaTimeoutReactionType.EXIT_AND_FREEZE -> ExitAndFreeze(cooldownMinutes.coerceAtLeast(1))
                WaqfaTimeoutReactionType.NOTIFY_ONLY -> NotifyOnly
            }
        }
    }
}
