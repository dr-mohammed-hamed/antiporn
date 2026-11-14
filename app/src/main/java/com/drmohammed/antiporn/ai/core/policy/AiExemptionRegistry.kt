package com.drmohammed.antiporn.ai.core.policy

import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import java.util.Locale

/**
 * السجل المعياري الوحيد لاستثناءات فحص الذكاء الاصطناعي (AI Exemption SSOT).
 *
 * مستقل تماماً عن [AppPolicyRegistry] الخاص بالحظر الليلي حتى لا يتكسر أي سلوك إنفاذ قائم.
 * القاعدة الذهبية: **الهدف يغلب الإعفاء** — أي حزمة مصنفة كهدف فحص لا تُعفى أبداً
 * حتى لو وردت في قائمة النظام (يمنع إخفاء WebView داخل تطبيقات تبدو آمنة).
 *
 * مصمم كـ pure-Kotlin بلا أي اعتماد على Android Context ليسهل اختباره وحدوياً (JUnit).
 */
object AiExemptionRegistry {

    /**
     * قائمة مغلقة صارمة: تطبيقات نظام Realme 6 Pro المؤكدة النظافة بلا أي WebView أو عارض وسائط.
     * (لانشر + إعدادات + هاتف + أدوات أساسية فقط — المعرض والكاميرا والملفات تبقى هدفاً عمداً).
     */
    private val STRICT_SYSTEM_EXEMPT: Set<String> = setOf(
        // إطار النظام
        "android",
        "com.android.systemui",
        // الشاشة الرئيسية (Realme/ColorOS/AOSP)
        "com.oppo.launcher",
        "com.coloros.launcher",
        "com.realme.launcher",
        "com.android.launcher3",
        // الإعدادات
        "com.android.settings",
        "com.coloros.settings",
        "com.oplus.settings",
        // الهاتف وجهات الاتصال والرسائل النصية (بلا وسائط)
        "com.android.dialer",
        "com.android.contacts",
        "com.android.server.telecom",
        "com.android.phone",
        "com.android.mms",
        "com.android.mms.service",
        "com.android.cellbroadcastreceiver",
        // الساعة والآلة الحاسبة ومسجل الصوت (أدوات نظام خالصة)
        "com.google.android.deskclock",
        "com.coloros.alarmclock",
        "com.oplus.alarmclock",
        "com.android.deskclock",
        "com.google.android.calculator",
        "com.coloros.calculator",
        "com.oplus.calculator",
        "com.android.calculator2",
        "com.google.android.apps.recorder",
        "com.coloros.soundrecorder",
        "com.oplus.soundrecorder",
        "com.android.soundrecorder",
        // تطبيقنا نفسه يعفى دائماً (شاشة التجربة)
        "com.drmohammed.antiporn"
    )

    /**
     * حزم السوشيال والريلز والشورتس — هدف فحص دائم.
     * (نُقلت إلى هنا من ContextualGatingWatcher لتوحيد المصدر).
     */
    private val SOCIAL_FEED_PACKAGES: Set<String> = setOf(
        "com.zhiliaoapp.musically", // TikTok
        "com.ss.android.ugc.trill", // TikTok Lite/Asia
        "com.instagram.android",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.google.android.youtube",
        "com.snapchat.android",
        "com.twitter.android",
        "tv.twitch.android.app",
        "com.google.android.googlequicksearchbox"
    )

    /**
     * هل الحزمة هدف فحص؟ المتصفحات المقيدة + السوشيال + القائمة السوداء + المتاجر.
     * المتاجر (Play والعينية) هدف عمداً لمنع ثغرة التثبيت الجانبي وقت التجربة.
     */
    fun isTarget(packageName: String): Boolean {
        val lower = packageName.lowercase(Locale.ROOT)
        if (AppPolicyRegistry.isRestrictedBrowser(packageName)) return true
        if (AppPolicyRegistry.isPermanentlyBlacklisted(packageName)) return true
        if (AppPolicyRegistry.isAppStore(packageName)) return true
        if (SOCIAL_FEED_PACKAGES.contains(lower)) return true
        if (com.drmohammed.antiporn.shields.AppSuspensionShield.TELEGRAM_PACKAGES.contains(lower)) return true
        if (com.drmohammed.antiporn.shields.AppSuspensionShield.OPEN_SOCIAL_NETWORKS.contains(lower)) return true
        if (com.drmohammed.antiporn.shields.AppSuspensionShield.PROXY_BROWSERS.contains(lower)) return true
        if (com.drmohammed.antiporn.shields.AppSuspensionShield.RANDOM_CHAT_APPS.contains(lower)) return true
        if (com.drmohammed.antiporn.shields.AppSuspensionShield.TIKTOK_PACKAGES.contains(lower)) return true
        // يوتيوب الرسمي هدف (ريلز وشورتس) حتى لو كان معفياً من الحظر الليلي إعلامياً
        if (packageName.equals("com.google.android.youtube", ignoreCase = true)) return true
        return false
    }

    /**
     * هل الحزمة معفاة من الفحص؟ شرط مزدوج: في القائمة المغلقة وليست هدفاً.
     */
    fun isExempt(packageName: String): Boolean {
        if (isTarget(packageName)) return false
        return STRICT_SYSTEM_EXEMPT.contains(packageName.lowercase(Locale.ROOT))
    }

    /**
     * نسخة لعرض القائمة في السجلات والتشخيص (للتجربة الميدانية).
     */
    fun getExemptPackages(): Set<String> = STRICT_SYSTEM_EXEMPT.toSet()
}
