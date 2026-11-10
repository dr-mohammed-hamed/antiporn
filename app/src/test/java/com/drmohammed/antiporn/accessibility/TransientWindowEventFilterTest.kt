package com.drmohammed.antiporn.accessibility

import com.drmohammed.antiporn.accessibility.filter.TransientWindowEventFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientWindowEventFilterTest {

    @Test
    fun testTransientAndSystemFilterRules() {
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val filter = TransientWindowEventFilter(dummyContext)

        // Blank package
        assertTrue("Blank package should be ignored", filter.isTransientOrIgnored("", null))
        assertTrue("Whitespace package should be ignored", filter.isTransientOrIgnored("   ", null))

        // System UI & Android framework
        assertTrue("com.android.systemui should be ignored", filter.isTransientOrIgnored("com.android.systemui", "com.android.systemui.statusbar.phone.NotificationShadeWindowView"))
        assertTrue("android framework overlay should be ignored", filter.isTransientOrIgnored("android", "com.android.internal.app.ResolverActivity"))

        // Popups and toasts
        assertTrue("PopupWindow should be ignored", filter.isTransientOrIgnored("com.example.app", "android.widget.PopupWindow"))
        assertTrue("Toast should be ignored", filter.isTransientOrIgnored("com.example.app", "android.widget.Toast"))

        // Standard user applications (should NOT be ignored)
        assertFalse("YouTube main activity should NOT be ignored", filter.isTransientOrIgnored("com.google.android.youtube", "com.google.android.youtube.HomeActivity"))
        assertFalse("Chrome browser should NOT be ignored", filter.isTransientOrIgnored("com.android.chrome", "org.chromium.chrome.browser.ChromeTabbedActivity"))
        assertFalse("Launcher activity should NOT be ignored", filter.isTransientOrIgnored("com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher.NexusLauncherActivity"))
    }
}
