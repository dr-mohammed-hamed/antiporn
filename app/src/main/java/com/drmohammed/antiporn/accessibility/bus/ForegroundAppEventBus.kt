package com.drmohammed.antiporn.accessibility.bus

import android.content.Context
import com.drmohammed.antiporn.waqfa.engine.WaqfaInterceptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * ناقل الأحداث المركزي الموحد لحالة التطبيق النشط في الواجهة (Single Source of Truth).
 * يعزل كلاً من خدمة الوصول ومراقب الـ Fallback عن المستهلكين (Waqfa و AI)،
 * ويوفر مرجعاً وحيداً موحداً (Unified Singleton) لكافة مكونات النظام.
 */
class ForegroundAppEventBus(context: Context) {

    private val appContext = context.applicationContext

    private val _isAccessibilityActive = MutableStateFlow(false)
    val isAccessibilityActive: StateFlow<Boolean> = _isAccessibilityActive.asStateFlow()

    private val _activePackage = MutableStateFlow<String?>(null)
    val activePackage: StateFlow<String?> = _activePackage.asStateFlow()

    @Volatile
    private var lastProcessedPackage: String = ""

    fun setAccessibilityActive(active: Boolean) {
        if (_isAccessibilityActive.value != active) {
            _isAccessibilityActive.value = active
            Timber.tag(TAG).i("Accessibility active state changed -> $active")
        }
    }

    /**
     * استقبال حدث فوري (0ms) من خدمة الوصول المركزية (HisnAccessibilityService).
     */
    fun emitFromAccessibility(packageName: String, className: String? = null) {
        if (packageName.isBlank()) return
        dispatchForegroundPackage(packageName, isFromAccessibility = true)
    }

    /**
     * استقبال حدث من مراقب الاستخدام الاحتياطي (WaqfaForegroundWatcher Fallback).
     */
    fun emitFromFallbackWatcher(packageName: String) {
        if (packageName.isBlank()) return
        if (_isAccessibilityActive.value) {
            Timber.tag(TAG).d("Dropped fallback watcher event for $packageName because Accessibility is active")
            return
        }
        dispatchForegroundPackage(packageName, isFromAccessibility = false)
    }

    @Synchronized
    private fun dispatchForegroundPackage(packageName: String, isFromAccessibility: Boolean) {
        if (!isFromAccessibility && _isAccessibilityActive.value) {
            Timber.tag(TAG).d("Dropped fallback dispatch for $packageName because Accessibility is active")
            return
        }

        val isChanged = packageName != lastProcessedPackage
        lastProcessedPackage = packageName
        _activePackage.value = packageName

        Timber.tag(TAG).d("ForegroundAppEventBus dispatch: pkg=$packageName, changed=$isChanged, fromA11y=$isFromAccessibility")

        // 1. تغذية منظومة «وَقْفَة» فوراً وبأعلى أولوية
        try {
            WaqfaInterceptor.evaluateForegroundPackage(
                context = appContext,
                foregroundPackage = packageName,
                isPackageChanged = isChanged
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error evaluating foreground package in WaqfaInterceptor")
        }
    }

    companion object {
        private const val TAG = "AntiPorn_EventBus"

        @Volatile
        private var instance: ForegroundAppEventBus? = null

        /**
         * الوصول للنسخة الوحيدة المعتمدة من الناقل المركزي عبر كافة العمليات والخدمات.
         */
        fun getInstance(context: Context): ForegroundAppEventBus {
            return instance ?: synchronized(this) {
                instance ?: ForegroundAppEventBus(context.applicationContext).also { instance = it }
            }
        }

        /**
         * تعيين المرجع الوحيد (مفيد للاختبارات وللربط مع Koin).
         */
        fun setInstance(bus: ForegroundAppEventBus?) {
            instance = bus
        }
    }
}
