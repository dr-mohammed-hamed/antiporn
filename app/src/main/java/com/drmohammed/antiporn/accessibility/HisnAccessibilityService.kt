package com.drmohammed.antiporn.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
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

    private val screenshotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
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
        instance = this
        eventBus.setAccessibilityActive(true)
        Timber.tag(TAG).i("HisnAccessibilityService connected to Android Framework (Instant 0ms Engine Active)")
        com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordA11y(
            context = applicationContext,
            action = "SERVICE_CONNECTED",
            isSuccess = true,
            details = "Instant 0ms Engine Active"
        )
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
        if (instance === this) {
            instance = null
        }
        if (::eventBus.isInitialized) {
            eventBus.setAccessibilityActive(false)
        }
        com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordA11y(
            context = applicationContext,
            action = "SERVICE_UNBIND",
            isSuccess = true
        )
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("HisnAccessibilityService destroyed")
        try {
            screenshotExecutor.shutdown()
        } catch (_: Exception) {}
        if (instance === this) {
            instance = null
        }
        if (::eventBus.isInitialized) {
            eventBus.setAccessibilityActive(false)
        }
        com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordA11y(
            context = applicationContext,
            action = "SERVICE_DESTROYED",
            isSuccess = true
        )
    }

    /**
     * هل التقاط الشباك النظيف بدون طبقة التعتيم مدعوم في النظام الحالي.
     * متاح حصرياً على أندرويد 14+ (API 34+) لأن takeScreenshot في أندرويد 11-13 تلتقط التعتيم أيضاً.
     */
    fun isCleanWindowCaptureSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * أخذ لقطة شاشة نظيفة 100% للنافذة التفاعلية بدون طبقة التعتيم (TYPE_ACCESSIBILITY_OVERLAY).
     * متاح على نظام أندرويد 14+ (API 34+) عبر takeScreenshotOfWindow مع استخراج حدود الشباك بدقة.
     */
    fun captureCleanWindowBitmap(onResult: (Bitmap?, Rect) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val interactiveWindow = try {
                windows.filter { win ->
                    win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    win.root?.packageName != applicationContext.packageName
                }.maxByOrNull { win ->
                    val b = Rect()
                    win.getBoundsInScreen(b)
                    val area = (b.right - b.left).toLong() * (b.bottom - b.top).toLong()
                    val focusBonus = if (win.isFocused) 1_000_000_000L else 0L
                    focusBonus + area
                }
            } catch (e: Exception) {
                null
            }

            if (interactiveWindow != null) {
                val windowBounds = Rect()
                try {
                    interactiveWindow.getBoundsInScreen(windowBounds)
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Error getting window bounds: ${e.message}")
                }

                try {
                    takeScreenshotOfWindow(
                        interactiveWindow.id,
                        screenshotExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshotResult: ScreenshotResult) {
                                val hwBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bitmap = try {
                                    val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                    val copied = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                    try { wrapped?.recycle() } catch (_: Exception) {}
                                    copied
                                } catch (e: Exception) {
                                    null
                                } finally {
                                    try { hwBuffer.close() } catch (_: Exception) {}
                                }
                                onResult(bitmap, windowBounds)
                            }

                            override fun onFailure(errorCode: Int) {
                                Timber.tag(TAG).d("takeScreenshotOfWindow failed (code: $errorCode), fallback to projection")
                                onResult(null, windowBounds)
                            }
                        }
                    )
                    return
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Error invoking takeScreenshotOfWindow: ${e.message}")
                }
            }
        }

        // على الأنظمة الأقدم من أندرويد 14، لا يوجد التقاط نافذة معزول بدون حجب ذاتي
        onResult(null, Rect())
    }

    companion object {
        private const val TAG = "AntiPorn_A11yService"

        @Volatile
        var instance: HisnAccessibilityService? = null
            private set
    }
}
