package com.drmohammed.antiporn.ai.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ScreenBlurOverlayManagerTest {

    private class TestOverlayScheduler : OverlayScheduler {
        val postedRunnables = mutableListOf<Runnable>()
        val delayedTasks = mutableListOf<DelayedTask>()
        var cancelledCallbacksCount = 0

        data class DelayedTask(val runnable: Runnable, val delayMs: Long)

        override fun post(runnable: Runnable) {
            postedRunnables.add(runnable)
            runnable.run() // تنفيذ فوري في بيئة الاختبار
        }

        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            delayedTasks.add(DelayedTask(runnable, delayMs))
        }

        override fun removeCallbacks(runnable: Runnable) {
            val removed = delayedTasks.removeAll { it.runnable == runnable }
            if (removed) {
                cancelledCallbacksCount++
            }
        }

        fun triggerAllDelayed() {
            val snapshot = delayedTasks.toList()
            delayedTasks.clear()
            for (task in snapshot) {
                task.runnable.run()
            }
        }
    }

    private fun createRect(l: Float, t: Float, r: Float, b: Float): RectF {
        val rect = RectF()
        rect.left = l
        rect.top = t
        rect.right = r
        rect.bottom = b
        return rect
    }

    /**
     * سياق وهمي لتفادي خطأ Method not mocked في بيئة JVM Unit Test
     */
    private val stubContext = object : Context() {
        override fun getAssets() = throw UnsupportedOperationException()
        override fun getResources() = throw UnsupportedOperationException()
        override fun getPackageManager() = throw UnsupportedOperationException()
        override fun getContentResolver() = throw UnsupportedOperationException()
        override fun getMainLooper() = throw UnsupportedOperationException()
        override fun getApplicationContext() = this
        override fun setTheme(resid: Int) {}
        override fun getTheme() = throw UnsupportedOperationException()
        override fun getClassLoader() = throw UnsupportedOperationException()
        override fun getPackageName() = "com.drmohammed.antiporn"
        override fun getApplicationInfo() = throw UnsupportedOperationException()
        override fun getPackageResourcePath() = throw UnsupportedOperationException()
        override fun getPackageCodePath() = throw UnsupportedOperationException()
        override fun getSharedPreferences(name: String?, mode: Int) = throw UnsupportedOperationException()
        override fun moveSharedPreferencesFrom(sourceContext: Context?, name: String?) = false
        override fun deleteSharedPreferences(name: String?) = false
        override fun openFileInput(name: String?) = throw UnsupportedOperationException()
        override fun openFileOutput(name: String?, mode: Int) = throw UnsupportedOperationException()
        override fun deleteFile(name: String?) = false
        override fun getFileStreamPath(name: String?) = throw UnsupportedOperationException()
        override fun getDataDir() = throw UnsupportedOperationException()
        override fun getFilesDir() = throw UnsupportedOperationException()
        override fun getNoBackupFilesDir() = throw UnsupportedOperationException()
        override fun getExternalFilesDir(type: String?) = throw UnsupportedOperationException()
        override fun getExternalFilesDirs(type: String?) = throw UnsupportedOperationException()
        override fun getObbDir() = throw UnsupportedOperationException()
        override fun getObbDirs() = throw UnsupportedOperationException()
        override fun getCacheDir() = throw UnsupportedOperationException()
        override fun getCodeCacheDir() = throw UnsupportedOperationException()
        override fun getExternalCacheDir() = throw UnsupportedOperationException()
        override fun getExternalCacheDirs() = throw UnsupportedOperationException()
        override fun getExternalMediaDirs() = throw UnsupportedOperationException()
        override fun fileList() = emptyArray<String>()
        override fun getDir(name: String?, mode: Int) = throw UnsupportedOperationException()
        override fun openOrCreateDatabase(name: String?, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) = throw UnsupportedOperationException()
        override fun openOrCreateDatabase(name: String?, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?) = throw UnsupportedOperationException()
        override fun moveDatabaseFrom(sourceContext: Context?, name: String?) = false
        override fun deleteDatabase(name: String?) = false
        override fun getDatabasePath(name: String?) = throw UnsupportedOperationException()
        override fun databaseList() = emptyArray<String>()
        override fun getWallpaper() = throw UnsupportedOperationException()
        override fun peekWallpaper() = throw UnsupportedOperationException()
        override fun getWallpaperDesiredMinimumWidth() = 0
        override fun getWallpaperDesiredMinimumHeight() = 0
        override fun setWallpaper(bitmap: android.graphics.Bitmap?) {}
        override fun setWallpaper(data: java.io.InputStream?) {}
        override fun clearWallpaper() {}
        override fun startActivity(intent: android.content.Intent?) {}
        override fun startActivity(intent: android.content.Intent?, options: Bundle?) {}
        override fun startActivities(intents: Array<out android.content.Intent>?) {}
        override fun startActivities(intents: Array<out android.content.Intent>?, options: Bundle?) {}
        override fun startIntentSender(intent: android.content.IntentSender?, fillInIntent: android.content.Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int) {}
        override fun startIntentSender(intent: android.content.IntentSender?, fillInIntent: android.content.Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?) {}
        override fun sendBroadcast(intent: android.content.Intent?) {}
        override fun sendBroadcast(intent: android.content.Intent?, receiverPermission: String?) {}
        override fun sendOrderedBroadcast(intent: android.content.Intent?, receiverPermission: String?) {}
        override fun sendOrderedBroadcast(intent: android.content.Intent, receiverPermission: String?, resultReceiver: android.content.BroadcastReceiver?, scheduler: Handler?, initialCode: Int, initialData: String?, initialExtras: Bundle?) {}
        override fun sendBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?) {}
        override fun sendBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?, receiverPermission: String?) {}
        override fun sendOrderedBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?, receiverPermission: String?, resultReceiver: android.content.BroadcastReceiver?, scheduler: Handler?, initialCode: Int, initialData: String?, initialExtras: Bundle?) {}
        override fun sendStickyBroadcast(intent: android.content.Intent?) {}
        override fun sendStickyOrderedBroadcast(intent: android.content.Intent?, resultReceiver: android.content.BroadcastReceiver?, scheduler: Handler?, initialCode: Int, initialData: String?, initialExtras: Bundle?) {}
        override fun removeStickyBroadcast(intent: android.content.Intent?) {}
        override fun sendStickyBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?) {}
        override fun sendStickyOrderedBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?, resultReceiver: android.content.BroadcastReceiver?, scheduler: Handler?, initialCode: Int, initialData: String?, initialExtras: Bundle?) {}
        override fun removeStickyBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?) {}
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?) = null
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, flags: Int) = null
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, broadcastPermission: String?, scheduler: Handler?) = null
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, broadcastPermission: String?, scheduler: Handler?, flags: Int) = null
        override fun unregisterReceiver(receiver: android.content.BroadcastReceiver?) {}
        override fun startService(service: android.content.Intent?) = null
        override fun startForegroundService(service: android.content.Intent?) = null
        override fun stopService(service: android.content.Intent?) = false
        override fun bindService(service: android.content.Intent, conn: android.content.ServiceConnection, flags: Int) = false
        override fun unbindService(conn: android.content.ServiceConnection) {}
        override fun startInstrumentation(className: android.content.ComponentName, profileFile: String?, arguments: Bundle?) = false
        override fun getSystemService(name: String): Any? = null
        override fun getSystemServiceName(serviceClass: Class<*>) = null
        override fun checkPermission(permission: String, pid: Int, uid: Int) = 0
        override fun checkCallingPermission(permission: String) = 0
        override fun checkCallingOrSelfPermission(permission: String) = 0
        override fun checkSelfPermission(permission: String) = 0
        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {}
        override fun enforceCallingPermission(permission: String, message: String?) {}
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
        override fun grantUriPermission(toPackage: String?, uri: android.net.Uri?, modeFlags: Int) {}
        override fun revokeUriPermission(uri: android.net.Uri?, modeFlags: Int) {}
        override fun revokeUriPermission(toPackage: String?, uri: android.net.Uri?, modeFlags: Int) {}
        override fun checkUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, modeFlags: Int) = 0
        override fun checkCallingUriPermission(uri: android.net.Uri?, modeFlags: Int) = 0
        override fun checkCallingOrSelfUriPermission(uri: android.net.Uri?, modeFlags: Int) = 0
        override fun checkUriPermission(uri: android.net.Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int) = 0
        override fun enforceUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, modeFlags: Int, message: String?) {}
        override fun enforceCallingUriPermission(uri: android.net.Uri?, modeFlags: Int, message: String?) {}
        override fun enforceCallingOrSelfUriPermission(uri: android.net.Uri?, modeFlags: Int, message: String?) {}
        override fun enforceUriPermission(uri: android.net.Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int, message: String?) {}
        override fun createPackageContext(packageName: String?, flags: Int) = this
        override fun createContextForSplit(splitName: String?) = this
        override fun createConfigurationContext(overrideConfiguration: android.content.res.Configuration) = this
        override fun createDisplayContext(display: Display) = this
        override fun createDeviceProtectedStorageContext() = this
        override fun isDeviceProtectedStorage() = false
    }

    private fun createMockWindowManager(
        onAddView: (View, ViewGroup.LayoutParams) -> Unit = { _, _ -> },
        onRemoveView: (View) -> Unit = {}
    ): WindowManager {
        return Proxy.newProxyInstance(
            WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java)
        ) { _, method, args ->
            when (method.name) {
                "addView" -> {
                    onAddView(args[0] as View, args[1] as ViewGroup.LayoutParams)
                    null
                }
                "removeView" -> {
                    onRemoveView(args[0] as View)
                    null
                }
                else -> null
            }
        } as WindowManager
    }

    // =========================================================================
    // 1. اختبارات حساب الإحداثيات (Coordinate Mapping Tests)
    // =========================================================================

    @Test
    fun testMapBoundingBox_normalizedCoordinates() {
        val normBox = createRect(0.1f, 0.2f, 0.6f, 0.75f)
        val targetWidth = 1000
        val targetHeight = 2000

        val mapped = ScreenBlurOverlayManager.mapBoundingBox(normBox, targetWidth, targetHeight)

        assertEquals(100f, mapped.left, 0.01f)
        assertEquals(400f, mapped.top, 0.01f)
        assertEquals(600f, mapped.right, 0.01f)
        assertEquals(1500f, mapped.bottom, 0.01f)
    }

    @Test
    fun testMapBoundingBox_modelInput320Coordinates() {
        val modelBox = createRect(32f, 64f, 160f, 240f)
        val targetWidth = 640
        val targetHeight = 960

        val mapped = ScreenBlurOverlayManager.mapBoundingBox(
            box = modelBox,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            sourceWidth = 320,
            sourceHeight = 320
        )

        // Scale factors: X = 640/320 = 2.0, Y = 960/320 = 3.0
        assertEquals(64f, mapped.left, 0.01f)
        assertEquals(192f, mapped.top, 0.01f)
        assertEquals(320f, mapped.right, 0.01f)
        assertEquals(720f, mapped.bottom, 0.01f)
    }

    @Test
    fun testMapBoundingBox_clampingToBounds() {
        val outOfBoundsBox = createRect(-100f, -50f, 1500f, 2500f)
        val targetWidth = 1080
        val targetHeight = 1920

        val mapped = ScreenBlurOverlayManager.mapBoundingBox(outOfBoundsBox, targetWidth, targetHeight)

        assertEquals(0f, mapped.left, 0.01f)
        assertEquals(0f, mapped.top, 0.01f)
        assertEquals(1080f, mapped.right, 0.01f)
        assertEquals(1920f, mapped.bottom, 0.01f)
    }

    @Test
    fun testMapBoundingBox_invertedCoordinates_correctsMinMax() {
        val invertedBox = createRect(0.8f, 0.9f, 0.2f, 0.3f)
        val mapped = ScreenBlurOverlayManager.mapBoundingBox(invertedBox, 1000, 1000)

        assertEquals(200f, mapped.left, 0.01f)
        assertEquals(300f, mapped.top, 0.01f)
        assertEquals(800f, mapped.right, 0.01f)
        assertEquals(900f, mapped.bottom, 0.01f)
    }

    @Test
    fun testMapBoundingBox_invalidDimensions_returnsEmpty() {
        val box = createRect(10f, 20f, 100f, 100f)
        val zeroWidth = ScreenBlurOverlayManager.mapBoundingBox(box, 0, 1000)
        assertEquals(0f, zeroWidth.left, 0.01f)
        assertEquals(0f, zeroWidth.right, 0.01f)

        val nonFiniteBox = createRect(Float.NaN, 0f, 100f, 100f)
        val nonFiniteResult = ScreenBlurOverlayManager.mapBoundingBox(nonFiniteBox, 1000, 1000)
        assertEquals(0f, nonFiniteResult.left, 0.01f)
        assertEquals(0f, nonFiniteResult.right, 0.01f)
    }

    // =========================================================================
    // 2. اختبارات تخزين الصناديق والمناطق النشطة (Box Storage Tests)
    // =========================================================================

    @Test
    fun testBoxStorage_initialStateIsEmpty() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        assertFalse(manager.hasActiveRegions)
        assertTrue(manager.getActiveRegions().isEmpty())
    }

    @Test
    fun testBoxStorage_updateStoresValidBoxes() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        val rect1 = createRect(50f, 100f, 200f, 300f)
        val rect2 = createRect(300f, 400f, 500f, 600f)

        manager.updateRegions(listOf(rect1, rect2))

        val active = manager.getActiveRegions()
        assertEquals(2, active.size)
        assertEquals(50f, active[0].left, 0.01f)
        assertEquals(100f, active[0].top, 0.01f)
        assertEquals(200f, active[0].right, 0.01f)
        assertEquals(300f, active[0].bottom, 0.01f)
        assertTrue(manager.hasActiveRegions)
    }

    @Test
    fun testBoxStorage_filtersOutDegenerateOrInvalidRects() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        val valid = createRect(10f, 10f, 50f, 50f)
        val zeroWidth = createRect(50f, 50f, 50f, 100f) // right <= left
        val invertedHeight = createRect(50f, 100f, 80f, 20f) // bottom <= top

        manager.updateRegions(listOf(valid, zeroWidth, invertedHeight))

        val active = manager.getActiveRegions()
        assertEquals(1, active.size)
        assertEquals(10f, active[0].left, 0.01f)
        assertEquals(50f, active[0].right, 0.01f)
    }

    @Test
    fun testBoxStorage_deepCopyIntegrity() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        val mutableRect = createRect(10f, 20f, 30f, 40f)
        manager.updateRegions(listOf(mutableRect))

        // Mutation after passing to manager must not corrupt stored box
        mutableRect.left = 999f
        val active = manager.getActiveRegions()
        assertEquals(1, active.size)
        assertEquals(10f, active[0].left, 0.01f)

        // Modifying snapshot list must not corrupt internal storage
        active[0].left = 888f
        val freshActive = manager.getActiveRegions()
        assertEquals(10f, freshActive[0].left, 0.01f)
    }

    // =========================================================================
    // 3. اختبارات مؤقت الثبات والتنعيم (Smoothing & Persistence Timer Tests)
    // =========================================================================

    @Test
    fun testPersistence_defaultPersistenceDurationIs400Ms() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        assertEquals(400L, manager.persistenceMs)
        assertEquals(400L, ScreenBlurOverlayManager.DEFAULT_PERSISTENCE_MS)
    }

    @Test
    fun testPersistence_schedulesClearAfter400Ms() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        manager.updateRegions(listOf(createRect(10f, 20f, 100f, 200f)))

        assertEquals(1, scheduler.delayedTasks.size)
        assertEquals(400L, scheduler.delayedTasks[0].delayMs)
        assertTrue(manager.hasActiveRegions)
    }

    @Test
    fun testPersistence_emptyRegionsDoNotClearImmediately_smoothingActive() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        // Frame 1: رصد منطقة مخالفة
        manager.updateRegions(listOf(createRect(10f, 20f, 100f, 200f)))
        assertEquals(1, manager.getActiveRegions().size)

        // Frame 2: إطار لم يتم رصد مخالفة فيه (قائمة فارغة)
        manager.updateRegions(emptyList())

        // يجب ألا تختفي الصناديق فوراً بل تثبت لمنع الوميض والرمشة
        assertEquals(1, manager.getActiveRegions().size)
        assertTrue(manager.hasActiveRegions)
    }

    @Test
    fun testPersistence_timerExpirySmoothlyClearsActiveBoxes() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        manager.updateRegions(listOf(createRect(10f, 20f, 100f, 200f)))
        assertTrue(manager.hasActiveRegions)

        // انقضاء الـ 400ms دون رصد جديد
        scheduler.triggerAllDelayed()

        // تختفي الصناديق بسلاسة
        assertFalse(manager.hasActiveRegions)
        assertTrue(manager.getActiveRegions().isEmpty())
    }

    @Test
    fun testPersistence_newDetectionResetsTimerAndUpdatesCoordinates() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        // رصد أولي
        manager.updateRegions(listOf(createRect(10f, 10f, 50f, 50f)))
        assertEquals(1, scheduler.delayedTasks.size)

        // رصد جديد بإحداثيات مختلفة
        manager.updateRegions(listOf(createRect(60f, 60f, 120f, 120f)))

        // إلغاء المؤقت القديم وإعادة ضبطه
        assertEquals(1, scheduler.cancelledCallbacksCount)
        assertEquals(1, scheduler.delayedTasks.size) // تمت تصفية القديم وإضافة الجديد
        assertEquals(400L, scheduler.delayedTasks[0].delayMs)

        // الإحداثيات تم تحديثها
        val active = manager.getActiveRegions()
        assertEquals(1, active.size)
        assertEquals(60f, active[0].left, 0.01f)
    }

    @Test
    fun testClearImmediate_clearsInstantlyWithoutWaiting() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        manager.updateRegions(listOf(createRect(10f, 20f, 100f, 200f)))
        assertTrue(manager.hasActiveRegions)

        manager.clearImmediate()

        assertFalse(manager.hasActiveRegions)
        assertTrue(manager.getActiveRegions().isEmpty())
    }

    // =========================================================================
    // 4. اختبارات معلمات النافذة والأعلام الإلزامية (LayoutParams & Flags Tests)
    // =========================================================================

    @Test
    fun testLayoutParams_mandatoryFlagsVerification() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        val params = manager.createLayoutParams()

        // الأعلام الإلزامية
        val flags = params.flags
        assertTrue("Must have FLAG_NOT_FOCUSABLE", (flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
        assertTrue("Must have FLAG_NOT_TOUCHABLE", (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0)
        assertTrue("Must have FLAG_LAYOUT_IN_SCREEN", (flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0)
        assertTrue("Must have FLAG_LAYOUT_NO_LIMITS", (flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) != 0)
        assertFalse("Must NOT have FLAG_SECURE to avoid blacking out MediaProjection", (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)
        assertTrue("Must have FLAG_HARDWARE_ACCELERATED", (flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0)

        // التنسيق والأبعاد
        assertEquals(PixelFormat.TRANSLUCENT, params.format)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.height)

        // نمط النافذة TYPE_APPLICATION_OVERLAY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        }
    }

    // =========================================================================
    // 5. اختبارات دورة الحياة الآمنة والتحصين (Lifecycle & Hardening Tests)
    // =========================================================================

    @Test
    fun testCanDrawOverlays_respectsPermissionChecker() {
        val scheduler = TestOverlayScheduler()
        var permissionGranted = false

        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { permissionGranted }
        )

        assertFalse(manager.canDrawOverlays(stubContext))
        assertFalse(manager.attachOverlay()) // لا يجب أن ترتبط بدون إذن

        permissionGranted = true
        assertTrue(manager.canDrawOverlays(stubContext))
    }

    @Test
    fun testLifecycle_safeDestroyAndClose() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        manager.updateRegions(listOf(createRect(10f, 20f, 100f, 200f)))
        assertTrue(manager.hasActiveRegions)

        manager.destroy()

        assertTrue(manager.isClosed)
        assertFalse(manager.hasActiveRegions)
        assertTrue(manager.getActiveRegions().isEmpty())

        // لا يجب أن تقبل تحديثات بعد الإغلاق
        manager.updateRegions(listOf(createRect(50f, 50f, 80f, 80f)))
        assertFalse(manager.hasActiveRegions)
    }

    @Test
    fun testLifecycle_detachWhenNotAttached_doesNotThrow() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        // استدعاء detachOverlay وهي غير مربوطة لا يجب أن يرمي أي خطأ
        manager.detachOverlay()
        assertFalse(manager.isAttached)
    }

    @Test
    fun testCalculateRectIoU() {
        // متطابقان: IoU = 1.0
        val a = createRect(0f, 0f, 100f, 100f)
        val b = createRect(0f, 0f, 100f, 100f)
        assertEquals(1.0f, ScreenBlurOverlayManager.calculateRectIoU(a, b), 0.0001f)

        // متباعدان تماماً: IoU = 0.0
        val c = createRect(200f, 200f, 300f, 300f)
        assertEquals(0.0f, ScreenBlurOverlayManager.calculateRectIoU(a, c), 0.0001f)

        // تداخل 50% أفقياً: interArea = 50 * 100 = 5000, unionArea = 10000 + 10000 - 5000 = 15000 -> IoU = 1/3
        val d = createRect(50f, 0f, 150f, 100f)
        assertEquals(1f / 3f, ScreenBlurOverlayManager.calculateRectIoU(a, d), 0.001f)
    }

    @Test
    fun testEmaSmoothing_smoothsJitteringBoxes() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        // الإطار الأول: صندوق أولي
        manager.updateRegions(listOf(createRect(100f, 200f, 300f, 800f)))
        val first = manager.getActiveRegions()[0]
        assertEquals(100f, first.left, 0.001f)
        assertEquals(200f, first.top, 0.001f)
        assertEquals(300f, first.right, 0.001f)
        assertEquals(800f, first.bottom, 0.001f)

        // الإطار الثاني: اهتزاز بسيط (مثلاً قفزة 50 بكسل في القاع و 20 في اليمين)
        // newBox: L:100, T:200, R:320, B:850
        // التنعيم الحركي: alpha = 0.40f
        // smoothedRight = 300 + 0.40 * (320 - 300) = 308
        // smoothedBottom = 800 + 0.40 * (850 - 800) = 820
        manager.updateRegions(listOf(createRect(100f, 200f, 320f, 850f)))
        val smoothed = manager.getActiveRegions()[0]
        assertEquals(100f, smoothed.left, 0.01f)
        assertEquals(200f, smoothed.top, 0.01f)
        assertEquals(308f, smoothed.right, 0.01f)
        assertEquals(820f, smoothed.bottom, 0.01f)
    }

    @Test
    fun testEmaSmoothing_disjointBoxSnapsImmediately() {
        val scheduler = TestOverlayScheduler()
        val manager = ScreenBlurOverlayManager(
            context = stubContext,
            scheduler = scheduler,
            canDrawOverlaysChecker = { false }
        )

        // صندوق في أعلى الشاشة
        manager.updateRegions(listOf(createRect(0f, 0f, 100f, 100f)))

        // صندوق جديد تماماً في أسفل الشاشة (لا يوجد تداخل، IoU < 0.25)
        manager.updateRegions(listOf(createRect(500f, 800f, 600f, 900f)))
        val current = manager.getActiveRegions()[0]

        // يجب أن يظهر الصندوق الجديد فوراً بكامل أبعاده دون تنعيم بطيء
        assertEquals(500f, current.left, 0.001f)
        assertEquals(800f, current.top, 0.001f)
        assertEquals(600f, current.right, 0.001f)
        assertEquals(900f, current.bottom, 0.001f)
    }
}
