package com.drmohammed.antiporn.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.drmohammed.antiporn.accessibility.bus.ForegroundAppEventBus
import com.drmohammed.antiporn.accessibility.filter.TransientWindowEventFilter
import com.drmohammed.antiporn.accessibility.hook.AccessibilityHookRegistry
import timber.log.Timber

/**
 * محرك خدمة الوصول المركزي لتطبيق الحصن (Hisn Accessibility Service).
 * يوفر رصداً لحظياً بسرعة 0ms لتغير نوافذ التطبيقات النشطة في الواجهة (TYPE_WINDOW_STATE_CHANGED).
 * يعمل بنمط الأحداث الصرفة (Zero-Polling) لتوفير 100% من استهلاك المعالج الناتج عن الفحص الدوري.
 */
class HisnAccessibilityService : AccessibilityService() {

    private lateinit var eventBus: ForegroundAppEventBus
    private lateinit var windowFilter: TransientWindowEventFilter

    override fun onCreate() {
        super.onCreate()
        eventBus = ForegroundAppEventBus.getInstance(applicationContext)
        windowFilter = TransientWindowEventFilter.getInstance(applicationContext)
        Timber.tag(TAG).i("HisnAccessibilityService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        eventBus.setAccessibilityActive(true)
        Timber.tag(TAG).i("HisnAccessibilityService connected to Android Framework (Instant 0ms Engine Active)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val evt = event ?: return

        when (evt.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(evt)
            }
            else -> {
                // إتاحة معالجة الأحداث المتقدمة لخطافات المرحلة القادمة (Phase 2)
                AccessibilityHookRegistry.dispatchRawEvent(this, evt)
            }
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString()

        // 1. فلترة النوافذ العابرة ولوحات المفاتيح وواجهة النظام
        if (windowFilter.isTransientOrIgnored(packageName, className)) {
            return
        }

        // 2. فحص خطافات التوسع المستقبلي (مثل نظام التخفي الفوري لتطبيقات البنوك)
        if (AccessibilityHookRegistry.dispatchWindowState(this, packageName, className)) {
            Timber.tag(TAG).d("Window state event consumed by registered hook for $packageName")
            return
        }

        // 3. إرسال الحدث إلى الناقل المركزي لتغذية منظومة «وَقْفَة» ودرع الذكاء الاصطناعي
        eventBus.emitFromAccessibility(packageName, className)
    }

    override fun onInterrupt() {
        Timber.tag(TAG).w("HisnAccessibilityService onInterrupt called by system")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.tag(TAG).w("HisnAccessibilityService unbinding from system")
        if (::eventBus.isInitialized) {
            eventBus.setAccessibilityActive(false)
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("HisnAccessibilityService destroyed")
        if (::eventBus.isInitialized) {
            eventBus.setAccessibilityActive(false)
        }
    }

    companion object {
        private const val TAG = "AntiPorn_A11yService"
    }
}
