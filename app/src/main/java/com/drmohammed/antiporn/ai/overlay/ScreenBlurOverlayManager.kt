package com.drmohammed.antiporn.ai.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import timber.log.Timber

/**
 * مدير التعتيم والتظليل الموضعي اللحظي لشاشة الهاتف (ScreenBlurOverlayManager).
 *
 * يقوم بإنشاء نافذة WindowManager عليا فوق جميع التطبيقات بنمط TYPE_APPLICATION_OVERLAY
 * لرسم مستطيلات تظليل داكنة أنيقة ودائرية الأركان فوق المناطق المحددة (العورات/المخالفات).
 *
 * الميزات الجوهرية:
 * 1. عدم استخدام FLAG_SECURE لمنع قيام SurfaceFlinger بتسويد شاشة العرض الافتراضية لـ MediaProjection.
 * 2. عدم اعتراض لمسات ونقرات المستخدم عبر FLAG_NOT_TOUCHABLE بنسبة 100%.
 * 3. ثبات وتنعيم زمني (Smoothing & Persistence) مدته 400ms لتفادي التقطيع بين الإطارات.
 * 4. إدارة دورة حياة آمنة ومحصنة مع تحرير الموارد وتفادي أي استثناءات runtime.
 */
class ScreenBlurOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager? = null,
    handler: Handler? = null,
    scheduler: OverlayScheduler? = null,
    val persistenceMs: Long = DEFAULT_PERSISTENCE_MS,
    private val canDrawOverlaysChecker: ((Context) -> Boolean)? = null
) : AutoCloseable {

    constructor(context: Context) : this(
        context = context,
        windowManager = null,
        handler = null,
        scheduler = null,
        persistenceMs = DEFAULT_PERSISTENCE_MS,
        canDrawOverlaysChecker = null
    )

    private val lock = Any()
    private val activeRegions = mutableListOf<RectF>()
    private val preallocatedMapped = ArrayList<RectF>(32)

    private var activeWm: WindowManager? = null
    private var currentAttachedType: Int = 0

    private val wmInstance: WindowManager? by lazy {
        windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
    }

    private val overlayScheduler: OverlayScheduler by lazy {
        when {
            scheduler != null -> scheduler
            handler != null -> HandlerOverlayScheduler(handler)
            else -> HandlerOverlayScheduler(Handler(Looper.getMainLooper()))
        }
    }

    @VisibleForTesting
    internal var overlayView: BlurOverlayView? = null

    @Volatile
    var isAttached: Boolean = false
        private set

    @Volatile
    var isClosed: Boolean = false
        private set

    @Volatile
    var isStaticLatch: Boolean = false
        private set

    @Volatile
    var isPendingClear: Boolean = false
        private set

    @Volatile
    var isKltTrackingActive: Boolean = false

    var onRegionsCleared: (() -> Unit)? = null

    private val clearRunnable = Runnable {
        if (isStaticLatch) {
            // أثناء القفل الثابت للمشهد الساكن، لا نسمح بإلغاء التعتيم
            return@Runnable
        }
        isPendingClear = false
        synchronized(lock) {
            activeRegions.clear()
        }
        overlayView?.clearRegions()
        onRegionsCleared?.invoke()
    }

    companion object {
        internal const val TAG = "ScreenBlurOverlay"

        /** مدة الثبات الافتراضية لمنع الرمشة (400 مللي ثانية) */
        const val DEFAULT_PERSISTENCE_MS = 400L

        /** لون التظليل الداكن الأنيق: معتم تماماً بنسبة 100% (Color.argb(255, 18, 20, 26)) لمنع أي تسريب بصري */
        const val COLOR_OVERLAY_FILL = 0xFF12141A.toInt()

        /** لون الإطار الناعم المحيط بالتعتيم: معتم تماماً (Color.argb(255, 45, 50, 65)) */
        const val COLOR_OVERLAY_STROKE = 0xFF2D3241.toInt()

        /** نصف قطر تدوير أركان المستطيلات (Rounded Rectangles) */
        const val DEFAULT_CORNER_RADIUS_DP = 16f

        /** سُمك إطار التحديد الناعم بالـ DP */
        const val DEFAULT_STROKE_WIDTH_DP = 2f

        /**
         * الأعلام الإلزامية لنافذة التعتيم:
         * - FLAG_NOT_FOCUSABLE: عدم سلب التركيز من لوحة المفاتيح أو التطبيقات.
         * - FLAG_NOT_TOUCHABLE: تمرير كافة اللمسات والنقرات بسلاسة 100% للتطبيق أسفله.
         * - FLAG_LAYOUT_IN_SCREEN: التمدد على كامل مساحة الشاشة شاملاً أشرطة النظام.
         * - FLAG_LAYOUT_NO_LIMITS: التمدد بحرية وتجاوز حدود الشاشة.
         * - تم استبعاد FLAG_SECURE: لمنع SurfaceFlinger من تسويد شاشة العرض الافتراضية الخاصة بـ MediaProjection.
         * - FLAG_HARDWARE_ACCELERATED: تسريع العتاد بـ GPU لرسم المستطيلات بمعدل 60/120 إطار/ثانية.
         */
        val MANDATORY_FLAGS: Int =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        /**
         * تحويل إحداثيات الصندوق إلى أبعاد الشاشة بدقة مع معالجة الإحداثيات النسبية
         * والمصفوفية (320x320) وتطويق الإحداثيات (Clamping) ضمن أبعاد الشاشة.
         */
        fun mapBoundingBox(
            box: RectF,
            targetWidth: Int,
            targetHeight: Int,
            sourceWidth: Int = 320,
            sourceHeight: Int = 320,
            outRect: RectF = RectF()
        ): RectF {
            val result = outRect
            if (targetWidth <= 0 || targetHeight <= 0) {
                result.left = 0f
                result.top = 0f
                result.right = 0f
                result.bottom = 0f
                return result
            }
            if (!box.left.isFinite() || !box.top.isFinite() || !box.right.isFinite() || !box.bottom.isFinite()) {
                result.left = 0f
                result.top = 0f
                result.right = 0f
                result.bottom = 0f
                return result
            }

            val isNormalized = box.left in 0f..1.05f && box.right in 0f..1.05f &&
                    box.top in 0f..1.05f && box.bottom in 0f..1.05f

            val l: Float
            val t: Float
            val r: Float
            val b: Float

            when {
                isNormalized -> {
                    l = box.left * targetWidth
                    t = box.top * targetHeight
                    r = box.right * targetWidth
                    b = box.bottom * targetHeight
                }
                sourceWidth > 0 && sourceHeight > 0 &&
                        box.right <= (sourceWidth * 1.15f) && box.bottom <= (sourceHeight * 1.15f) &&
                        (targetWidth != sourceWidth || targetHeight != sourceHeight) -> {
                    val scaleX = targetWidth.toFloat() / sourceWidth
                    val scaleY = targetHeight.toFloat() / sourceHeight
                    l = box.left * scaleX
                    t = box.top * scaleY
                    r = box.right * scaleX
                    b = box.bottom * scaleY
                }
                else -> {
                    l = box.left
                    t = box.top
                    r = box.right
                    b = box.bottom
                }
            }

            val minX = minOf(l, r).coerceIn(0f, targetWidth.toFloat())
            val minY = minOf(t, b).coerceIn(0f, targetHeight.toFloat())
            val maxX = maxOf(l, r).coerceIn(0f, targetWidth.toFloat())
            val maxY = maxOf(t, b).coerceIn(0f, targetHeight.toFloat())

            result.left = minX
            result.top = minY
            result.right = maxX
            result.bottom = maxY
            return result
        }

        /**
         * حساب نسبة التقاطع إلى الاتحاد (IoU) بين مستطيلين لتتبع الصناديق عبر الإطارات.
         */
        fun calculateRectIoU(a: RectF, b: RectF): Float {
            val interLeft = maxOf(a.left, b.left)
            val interTop = maxOf(a.top, b.top)
            val interRight = minOf(a.right, b.right)
            val interBottom = minOf(a.bottom, b.bottom)

            val interWidth = maxOf(0f, interRight - interLeft)
            val interHeight = maxOf(0f, interBottom - interTop)
            val interArea = interWidth * interHeight

            if (interArea <= 0f) return 0f

            val areaA = maxOf(0f, a.right - a.left) * maxOf(0f, a.bottom - a.top)
            val areaB = maxOf(0f, b.right - b.left) * maxOf(0f, b.bottom - b.top)
            val unionArea = areaA + areaB - interArea

            return if (unionArea > 0f) interArea / unionArea else 0f
        }

        private fun logI(tag: String, msg: String) {
            try {
                Timber.tag(tag).i(msg)
            } catch (_: Throwable) {
                // Ignore fallback in unmocked environments
            }
        }

        private fun logW(tag: String, msg: String, tr: Throwable? = null) {
            try {
                if (tr != null) Timber.tag(tag).w(tr, msg) else Timber.tag(tag).w(msg)
            } catch (_: Throwable) {
                // Ignore fallback in unmocked environments
            }
        }

        private fun logE(tag: String, msg: String, tr: Throwable? = null) {
            try {
                if (tr != null) Timber.tag(tag).e(tr, msg) else Timber.tag(tag).e(msg)
            } catch (_: Throwable) {
                // Ignore fallback in unmocked environments
            }
        }
    }

    /**
     * تحديث صناديق التعتيم النشطة.
     * يمكن استدعاؤها من أي خيط عمل (Background Worker Thread).
     *
     * آلية الثبات (Smoothing & Persistence):
     * - عند استلام قائمة غير فارغة: يتم تحديث الإحداثيات وإعادة جدولة مؤقت الإخفاء لمدة persistenceMs.
     * - عند استلام قائمة فارغة: لا يتم مسح الصناديق فوراً بل يُترك المؤقت ليكمل مدته لمنع الرمشة.
     */
    fun updateRegions(regions: List<RectF>) {
        if (isClosed) return

        postToMainThread {
            updateRegionsInternal(regions)
        }
    }

    /**
     * تحديث صناديق التعتيم بعد تحويلها لأبعاد الشاشة المحددة (Zero-Allocation Hot Path).
     */
    fun updateRegionsMapped(
        rawRegions: List<RectF>,
        targetWidth: Int,
        targetHeight: Int,
        sourceWidth: Int = 320,
        sourceHeight: Int = 320
    ) {
        if (isClosed) return
        synchronized(lock) {
            while (preallocatedMapped.size < rawRegions.size) {
                preallocatedMapped.add(RectF())
            }
            val snapshot = ArrayList<RectF>(rawRegions.size)
            for (i in rawRegions.indices) {
                mapBoundingBox(rawRegions[i], targetWidth, targetHeight, sourceWidth, sourceHeight, preallocatedMapped[i])
                val mapped = preallocatedMapped[i]
                val copy = RectF().apply {
                    left = mapped.left
                    top = mapped.top
                    right = mapped.right
                    bottom = mapped.bottom
                }
                snapshot.add(copy)
            }
            updateRegions(snapshot)
        }
    }

    @MainThread
    private fun updateRegionsInternal(regions: List<RectF>) {
        if (isClosed) return

        // تصفية الصناديق السليمة فقط
        val validRegions = regions.filter { r ->
            r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite() &&
                    r.right > r.left && r.bottom > r.top
        }

        if (validRegions.isEmpty()) {
            // فك القفل الثباتي وجدولة مؤقت الإزالة (persistenceMs) لمنع تعليق المربع (Ghost Box)
            isStaticLatch = false
            synchronized(lock) {
                if (activeRegions.isNotEmpty()) {
                    isPendingClear = true
                    overlayScheduler.removeCallbacks(clearRunnable)
                    overlayScheduler.postDelayed(clearRunnable, persistenceMs)
                } else {
                    isPendingClear = false
                }
            }
            return
        }

        // إعادة ضبط القفل الثباتي وحالة الإزالة المعلقة عند استلام تحديث جديد حتى تتمكن آلية التثبيت من إدارته
        isStaticLatch = false
        isPendingClear = false

        val processedRegions = ArrayList<RectF>(validRegions.size)

        synchronized(lock) {
            if (isKltTrackingActive) {
                // رسم مباشر دقيق 100% دون تأخير EMA مزدوج لمنع تأخر المربع خلف حركة KLT السريعة
                for (newBox in validRegions) {
                    val copy = RectF()
                    copy.left = newBox.left
                    copy.top = newBox.top
                    copy.right = newBox.right
                    copy.bottom = newBox.bottom
                    processedRegions.add(copy)
                }
            } else {
                for (newBox in validRegions) {
                    var bestMatch: RectF? = null
                    var bestIou = 0.25f // عتبة مطابقة الصناديق عبر الإطارات

                    for (prevBox in activeRegions) {
                        val iou = calculateRectIoU(prevBox, newBox)
                        if (iou > bestIou) {
                            bestIou = iou
                            bestMatch = prevBox
                        }
                    }

                    if (bestMatch != null) {
                        // التنعيم الحركي (Exponential Moving Average): 40% إطار جديد و 60% تاريخي
                        // لإلغاء القفزات اللحظية والاهتزاز الناتج عن تقلبات شبكة YOLO
                        val alpha = 0.40f
                        val smoothed = RectF()
                        smoothed.left = bestMatch.left + alpha * (newBox.left - bestMatch.left)
                        smoothed.top = bestMatch.top + alpha * (newBox.top - bestMatch.top)
                        smoothed.right = bestMatch.right + alpha * (newBox.right - bestMatch.right)
                        smoothed.bottom = bestMatch.bottom + alpha * (newBox.bottom - bestMatch.bottom)
                        processedRegions.add(smoothed)
                    } else {
                        // صندوق جديد أو انتقال مكاني كبير: استجابة فورية 100% دون أي تأخير
                        val copy = RectF()
                        copy.left = newBox.left
                        copy.top = newBox.top
                        copy.right = newBox.right
                        copy.bottom = newBox.bottom
                        processedRegions.add(copy)
                    }
                }
            }

            activeRegions.clear()
            activeRegions.addAll(processedRegions)
        }

        // إلغاء مؤقت المسح طالما توجد مناطق تظليل نشطة معروضة
        overlayScheduler.removeCallbacks(clearRunnable)
        isPendingClear = false
        if (!isKltTrackingActive) {
            overlayScheduler.postDelayed(clearRunnable, persistenceMs)
        }

        // التأكد من ربط الـ Overlay بالـ WindowManager أو ترقية نوع النافذة إذا تم تفعيل AccessibilityService
        val a11y = com.drmohammed.antiporn.accessibility.HisnAccessibilityService.instance
        val desiredType = if (a11y != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        if (!isAttached || currentAttachedType != desiredType) {
            val attached = attachOverlay()
            if (!attached) return
        }

        // تحديث الرسم في الـ View
        overlayView?.updateRegions(activeRegions)
        logI(TAG, "[Shield] Rendered on screen: ${activeRegions.size} region(s) -> ${activeRegions.joinToString { "[L:${it.left.toInt()} T:${it.top.toInt()} R:${it.right.toInt()} B:${it.bottom.toInt()}]" }}")
    }

    /**
     * تثبيت دائم للصناديق النشطة حالياً لمنع سقوطها أو وميضها طالما أن الشاشة ساكنة.
     * يلغي مؤقت الحذف ويقفل الحالة (Static Latch) للحفاظ على الدرع البصري بنسبة 100%.
     */
    fun retainActiveRegions() {
        if (isClosed || isPendingClear || isStaticLatch) return
        isStaticLatch = true
        postToMainThread {
            overlayScheduler.removeCallbacks(clearRunnable)
        }
    }

    /**
     * مسح فوري وسريع (<50ms) للصناديق النشطة عند اكتشاف مغادرة المحتوى بالتمرير السريع.
     * يمنع تعليق المربع الأسود فوق المحتوى البريء (Ghost Box).
     */
    fun dismissFast() {
        if (isClosed) return
        isStaticLatch = false
        isPendingClear = false
        clearImmediate()
    }

    /**
     * مسح فوري لجميع صناديق التعتيم دون انتظار المؤقت.
     */
    fun clearImmediate() {
        isStaticLatch = false
        isPendingClear = false
        synchronized(lock) {
            activeRegions.clear()
        }
        postToMainThread {
            overlayScheduler.removeCallbacks(clearRunnable)
            overlayView?.clearRegions()
            onRegionsCleared?.invoke()
        }
    }

    /**
     * جلب نسخة غير قابلة للتعديل من الصناديق النشطة حالياً.
     */
    fun getActiveRegions(): List<RectF> {
        synchronized(lock) {
            return activeRegions.map {
                val copy = RectF()
                copy.left = it.left
                copy.top = it.top
                copy.right = it.right
                copy.bottom = it.bottom
                copy
            }
        }
    }

    val hasActiveRegions: Boolean
        get() = synchronized(lock) { activeRegions.isNotEmpty() }

    /**
     * التحقق من منح إذن نافذة النظام SYSTEM_ALERT_WINDOW ومحاولة منحها تلقائياً عبر Device Owner
     */
    fun canDrawOverlays(context: Context): Boolean {
        val checker = canDrawOverlaysChecker
        if (checker != null) {
            return checker(context)
        }
        return try {
            if (Settings.canDrawOverlays(context)) {
                true
            } else {
                tryAutoGrantDeviceOwner(context)
                Settings.canDrawOverlays(context)
            }
        } catch (e: Exception) {
            logE(TAG, "Error checking Settings.canDrawOverlays", e)
            false
        }
    }

    private fun tryAutoGrantDeviceOwner(context: Context) {
        try {
            if (com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(context)) {
                com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPermissionGrantStateBlocking(
                    context = context,
                    packageName = context.packageName,
                    permission = android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                    grantState = android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }
        } catch (e: Throwable) {
            logW(TAG, "Failed to auto-grant overlay permission via DeviceOwner", e)
        }
    }

    /**
     * إنشاء معلمات النافذة القياسية LayoutParams
     */
    fun createLayoutParams(
        windowType: Int = if (com.drmohammed.antiporn.accessibility.HisnAccessibilityService.instance != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
    ): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            MANDATORY_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.width = WindowManager.LayoutParams.MATCH_PARENT
            this.height = WindowManager.LayoutParams.MATCH_PARENT
            this.type = windowType
            this.flags = MANDATORY_FLAGS
            this.format = PixelFormat.TRANSLUCENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                this.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                this.fitInsetsTypes = 0
                this.fitInsetsSides = 0
            }
        }
        try {
            params.gravity = Gravity.TOP or Gravity.START
        } catch (e: Throwable) {
            logW(TAG, "Overlay property setup ignored: ${e.message}")
        }
        try {
            params.title = "AntiPornScreenBlurOverlay"
        } catch (e: Throwable) {
            logW(TAG, "Overlay property setup ignored: ${e.message}")
        }
        return params
    }

    /**
     * ربط الـ View بالـ WindowManager بأمان.
     * يدعم TYPE_ACCESSIBILITY_OVERLAY عبر سياق HisnAccessibilityService عندما تكون متصلة.
     */
    @MainThread
    fun attachOverlay(): Boolean {
        if (isClosed) return false

        val a11y = com.drmohammed.antiporn.accessibility.HisnAccessibilityService.instance
        val desiredType = if (a11y != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        if (isAttached) {
            if (currentAttachedType == desiredType) {
                return true
            }
            detachOverlay()
        }

        if (a11y == null && !canDrawOverlays(context)) {
            logW(TAG, "Cannot attach overlay: Settings.canDrawOverlays is false and A11y not connected")
            return false
        }

        val targetContext = a11y ?: context
        val wm = (targetContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: wmInstance ?: run {
            logE(TAG, "Cannot attach overlay: WindowManager not available")
            return false
        }

        return try {
            val view = overlayView ?: BlurOverlayView(targetContext).also { overlayView = it }
            val params = createLayoutParams(desiredType)
            wm.addView(view, params)
            activeWm = wm
            currentAttachedType = desiredType
            isAttached = true
            logI(TAG, "ScreenBlurOverlay attached successfully with type: $desiredType (A11yActive=${a11y != null})")
            true
        } catch (e: Exception) {
            logE(TAG, "Failed to attach overlay view to WindowManager", e)
            isAttached = false
            activeWm = null
            currentAttachedType = 0
            false
        }
    }

    /**
     * إزالة الـ View من الـ WindowManager بأمان مع كتل try-catch محصنة.
     */
    @MainThread
    fun detachOverlay() {
        overlayScheduler.removeCallbacks(clearRunnable)
        if (!isAttached) return

        try {
            overlayView?.let { view ->
                (activeWm ?: wmInstance)?.removeView(view)
            }
        } catch (e: IllegalArgumentException) {
            logW(TAG, "Overlay view was not attached to WindowManager during detach", e)
        } catch (e: Exception) {
            logE(TAG, "Failed to remove overlay view from WindowManager", e)
        } finally {
            isAttached = false
            overlayView = null
            activeWm = null
            currentAttachedType = 0
        }
    }

    /**
     * إغلاق وتحرير كافة الموارد وإلغاء الاشتراكات بأمان.
     */
    fun destroy() {
        if (isClosed) return
        isClosed = true
        isStaticLatch = false
        isPendingClear = false
        synchronized(lock) {
            activeRegions.clear()
        }
        postToMainThread {
            overlayScheduler.removeCallbacks(clearRunnable)
            detachOverlay()
        }
    }

    override fun close() {
        destroy()
    }

    private fun postToMainThread(action: Runnable) {
        val currentLooper = try { Looper.myLooper() } catch (_: Throwable) { null }
        val mainLooper = try { Looper.getMainLooper() } catch (_: Throwable) { null }

        if (currentLooper != null && mainLooper != null && currentLooper == mainLooper) {
            action.run()
        } else {
            overlayScheduler.post(action)
        }
    }

    @VisibleForTesting
    internal fun triggerPersistenceExpiryForTesting() {
        isStaticLatch = false
        clearRunnable.run()
    }
}

/**
 * واجهة جدولة لتسهيل اختبار المؤقت الزمني بدون الاعتماد القسري على Looper أندرويد في الـ Unit Tests.
 */
interface OverlayScheduler {
    fun post(runnable: Runnable)
    fun postDelayed(runnable: Runnable, delayMs: Long)
    fun removeCallbacks(runnable: Runnable)
}

/**
 * التطبيق الافتراضي لجدولة المهام عبر Handler نظام أندرويد.
 */
class HandlerOverlayScheduler(
    private val handler: Handler
) : OverlayScheduler {
    override fun post(runnable: Runnable) {
        handler.post(runnable)
    }

    override fun postDelayed(runnable: Runnable, delayMs: Long) {
        handler.postDelayed(runnable, delayMs)
    }

    override fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }
}

/**
 * عنصر View مخصص لرسم مستطيلات التعتيم الأنيقة مع تدوير الأركان والإطار الناعم.
 */
class BlurOverlayView(context: Context) : View(context) {

    private val regions = ArrayList<RectF>()

    private val density: Float = try {
        context.resources.displayMetrics.density
    } catch (_: Throwable) {
        1.0f
    }

    private val cornerRadius = ScreenBlurOverlayManager.DEFAULT_CORNER_RADIUS_DP * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ScreenBlurOverlayManager.COLOR_OVERLAY_FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ScreenBlurOverlayManager.DEFAULT_STROKE_WIDTH_DP * density
        color = ScreenBlurOverlayManager.COLOR_OVERLAY_STROKE
    }

    init {
        setWillNotDraw(false)
        try {
            setBackgroundColor(Color.TRANSPARENT)
        } catch (e: Throwable) {
            Log.w(ScreenBlurOverlayManager.TAG, "Overlay property setup ignored: ${e.message}")
        }
    }

    fun updateRegions(newRegions: List<RectF>) {
        while (regions.size < newRegions.size) {
            regions.add(RectF())
        }
        while (regions.size > newRegions.size) {
            regions.removeAt(regions.size - 1)
        }
        for (i in newRegions.indices) {
            val src = newRegions[i]
            val dst = regions[i]
            dst.left = src.left
            dst.top = src.top
            dst.right = src.right
            dst.bottom = src.bottom
        }
        try {
            invalidate()
        } catch (e: Throwable) {
            Log.w(ScreenBlurOverlayManager.TAG, "Overlay property setup ignored: ${e.message}")
        }
    }

    fun clearRegions() {
        regions.clear()
        try {
            invalidate()
        } catch (e: Throwable) {
            Log.w(ScreenBlurOverlayManager.TAG, "Overlay property setup ignored: ${e.message}")
        }
    }

    fun getRegionsSnapshot(): List<RectF> {
        return regions.map {
            val copy = RectF()
            copy.left = it.left
            copy.top = it.top
            copy.right = it.right
            copy.bottom = it.bottom
            copy
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (regions.isEmpty()) return

        for (rect in regions) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
        }
    }
}
