package com.drmohammed.antiporn.accessibility.filter

import android.content.Context
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import timber.log.Timber

/**
 * مرشح النوافذ المؤقتة والعابرة ولوحات المفاتيح لخدمة الوصول.
 * يمنع تسريب الأحداث المؤقتة التي قد تقطع جلسات وقفة أو تسبب تكرار الفحص غير المبرر.
 */
class TransientWindowEventFilter(private val context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var lastImeCacheTimeMs: Long = 0L

    @Volatile
    private var cachedImePackages: Set<String> = emptySet()

    /**
     * يفحص ما إذا كان الحدث وارداً من نافذة عابرة أو لوحة مفاتيح يجب تجاهلها.
     *
     * @return true إذا كان الحدث عابراً ويجب إسقاطه، false إذا كان تغيراً حقيقياً في الواجهة.
     */
    fun isTransientOrIgnored(packageName: String, className: String?): Boolean {
        if (packageName.isBlank()) return true

        // 1. استثناء واجهة النظام المؤقتة (شريط الإشعارات، لوحة الصوت، إعدادات سريعة)
        if (packageName == SYSTEM_UI_PACKAGE || packageName == ANDROID_FRAMEWORK_PACKAGE) {
            Timber.tag(TAG).d("Ignored system UI / framework transient window: $packageName ($className)")
            return true
        }

        // 2. استثناء النوافذ المنبثقة التافهة (Popups / Toasts)
        if (className != null && (className.contains("Toast") || className.contains("PopupWindow"))) {
            Timber.tag(TAG).d("Ignored popup / toast window: $className")
            return true
        }

        // 3. استثناء لوحات المفاتيح المفعلة بالجهاز ديناميكياً (مع كاش 60 ثانية لتفادي استهلاك Binder IPC)
        if (isImePackage(packageName)) {
            Timber.tag(TAG).d("Ignored transient IME event: $packageName ($className)")
            return true
        }

        return false
    }

    private fun isImePackage(packageName: String): Boolean {
        val now = try {
            SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.currentTimeMillis()
        }
        if (lastImeCacheTimeMs == 0L || (now - lastImeCacheTimeMs > IME_CACHE_TTL_MS)) {
            cachedImePackages = fetchEnabledImePackages()
            lastImeCacheTimeMs = now
        }
        return cachedImePackages.contains(packageName)
    }

    private fun fetchEnabledImePackages(): Set<String> {
        return try {
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val imes = imm?.enabledInputMethodList ?: emptyList()
            imes.mapNotNull { it?.packageName?.takeIf { p -> p.isNotBlank() } }.toSet()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed resolving enabled input method packages")
            emptySet()
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WindowFilter"
        private const val IME_CACHE_TTL_MS = 60_000L // دقيقة واحدة
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val ANDROID_FRAMEWORK_PACKAGE = "android"

        @Volatile
        private var instance: TransientWindowEventFilter? = null

        fun getInstance(context: Context): TransientWindowEventFilter {
            return instance ?: synchronized(this) {
                instance ?: TransientWindowEventFilter(context.applicationContext).also { instance = it }
            }
        }
    }
}
