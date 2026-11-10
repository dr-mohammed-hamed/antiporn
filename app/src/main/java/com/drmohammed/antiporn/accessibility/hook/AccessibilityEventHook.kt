package com.drmohammed.antiporn.accessibility.hook

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CopyOnWriteArrayList
import timber.log.Timber

/**
 * واجهة خطافات التوسع المستقبلي لخدمة الوصول (Phase 2 Expansion Hooks).
 * تمكّن من تركيب ميزات متقدمة (مثل نظام التخفي لتطبيقات البنوك FinancialStealthHook،
 * ونظام حجب الريلز والشورتس InAppContentFilterHook) دون تعديل كلاس الخدمة الأساسي.
 */
interface AccessibilityEventHook {

    /**
     * يُستدعى فور تغير نافذة التطبيق في الواجهة (TYPE_WINDOW_STATE_CHANGED).
     *
     * @return true إذا تعامل الخطاف مع الحدث واستهلكه بالكامل (يوقف مسار المعالجة اللاحق)، false خلاف ذلك.
     */
    fun onWindowStateChanged(
        service: AccessibilityService,
        packageName: String,
        className: String?
    ): Boolean = false

    /**
     * يُستدعى لأي حدث وصول خام إذا احتاج الخطاف معالجة فحص محتوى متقدم (Phase 2).
     */
    fun onRawAccessibilityEvent(
        service: AccessibilityService,
        event: AccessibilityEvent
    ): Boolean = false
}

/**
 * سجل خطافات خدمة الوصول. آمن تماماً للخيوط المتزامنة (Thread-Safe).
 */
object AccessibilityHookRegistry {

    private const val TAG = "AntiPorn_HookRegistry"
    private val hooks = CopyOnWriteArrayList<AccessibilityEventHook>()

    fun registerHook(hook: AccessibilityEventHook) {
        if (!hooks.contains(hook)) {
            hooks.add(hook)
            Timber.tag(TAG).d("Registered AccessibilityEventHook: ${hook.javaClass.simpleName}")
        }
    }

    fun unregisterHook(hook: AccessibilityEventHook) {
        hooks.remove(hook)
        Timber.tag(TAG).d("Unregistered AccessibilityEventHook: ${hook.javaClass.simpleName}")
    }

    fun clearHooks() {
        hooks.clear()
    }

    /**
     * توزيع حدث تغير النافذة على كافة الخطافات المسجلة.
     *
     * @return true إذا قام أي خطاف باستهلاك الحدث، false إذا وجب استمرار المسار القياسي.
     */
    fun dispatchWindowState(
        service: AccessibilityService,
        packageName: String,
        className: String?
    ): Boolean {
        for (hook in hooks) {
            try {
                if (hook.onWindowStateChanged(service, packageName, className)) {
                    Timber.tag(TAG).d("Event consumed by hook: ${hook.javaClass.simpleName}")
                    return true
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error executing hook ${hook.javaClass.simpleName}")
            }
        }
        return false
    }

    /**
     * توزيع الأحداث الخام على الخطافات المسجلة (للمرحلة القادمة).
     */
    fun dispatchRawEvent(service: AccessibilityService, event: AccessibilityEvent): Boolean {
        for (hook in hooks) {
            try {
                if (hook.onRawAccessibilityEvent(service, event)) {
                    return true
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error executing raw hook ${hook.javaClass.simpleName}")
            }
        }
        return false
    }
}
