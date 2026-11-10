package com.drmohammed.antiporn.accessibility

import com.drmohammed.antiporn.accessibility.bus.ForegroundAppEventBus
import com.drmohammed.antiporn.accessibility.hook.AccessibilityEventHook
import com.drmohammed.antiporn.accessibility.hook.AccessibilityHookRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAppEventBusTest {

    @Test
    fun testEventBusAccessibilityStateTransitions() {
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getPackageName(): String = "com.drmohammed.antiporn"
        }
        val bus = ForegroundAppEventBus(dummyContext)

        assertFalse(bus.isAccessibilityActive.value)
        assertNull(bus.activePackage.value)

        bus.setAccessibilityActive(true)
        assertTrue(bus.isAccessibilityActive.value)

        bus.setAccessibilityActive(false)
        assertFalse(bus.isAccessibilityActive.value)
    }

    @Test
    fun testEmitUpdatesActivePackageStateFlow() {
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getPackageName(): String = "com.drmohammed.antiporn"
        }
        val bus = ForegroundAppEventBus(dummyContext)

        bus.emitFromAccessibility("com.google.android.youtube", "HomeActivity")
        assertEquals("com.google.android.youtube", bus.activePackage.value)

        bus.emitFromFallbackWatcher("com.android.chrome")
        assertEquals("com.android.chrome", bus.activePackage.value)
    }

    @Test
    fun testFallbackEventsDroppedWhenAccessibilityActive() {
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getPackageName(): String = "com.drmohammed.antiporn"
        }
        val bus = ForegroundAppEventBus(dummyContext)

        bus.setAccessibilityActive(true)
        bus.emitFromAccessibility("com.google.android.youtube", "HomeActivity")
        assertEquals("com.google.android.youtube", bus.activePackage.value)

        // Stale event from fallback watcher must be dropped
        bus.emitFromFallbackWatcher("com.instagram.android")
        assertEquals("com.google.android.youtube", bus.activePackage.value)
    }

    @Test
    fun testHookRegistryDispatchAndConsumption() {
        var hookInvoked = false
        val testHook = object : AccessibilityEventHook {
            override fun onWindowStateChanged(
                service: android.accessibilityservice.AccessibilityService,
                packageName: String,
                className: String?
            ): Boolean {
                if (packageName == "com.financial.stealth.test") {
                    hookInvoked = true
                    return true // Consume event
                }
                return false
            }
        }

        AccessibilityHookRegistry.registerHook(testHook)

        // Mock accessibility service dummy
        val dummyService = object : android.accessibilityservice.AccessibilityService() {
            override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
            override fun onInterrupt() {}
        }

        val consumed = AccessibilityHookRegistry.dispatchWindowState(
            dummyService,
            "com.financial.stealth.test",
            "MainActivity"
        )

        assertTrue("Hook should consume event", consumed)
        assertTrue("Hook should be invoked", hookInvoked)

        val notConsumed = AccessibilityHookRegistry.dispatchWindowState(
            dummyService,
            "com.google.android.youtube",
            "HomeActivity"
        )
        assertFalse("Unmatched event should not be consumed", notConsumed)

        AccessibilityHookRegistry.unregisterHook(testHook)
    }
}
