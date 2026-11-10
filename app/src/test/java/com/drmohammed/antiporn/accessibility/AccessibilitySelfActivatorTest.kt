package com.drmohammed.antiporn.accessibility

import android.content.ComponentName
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySelfActivatorTest {

    @Test
    fun testTemporarilyCloakedFlagTransitions() {
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getPackageName(): String = "com.drmohammed.antiporn"
        }

        // Test cloaking sets the flag to true
        AccessibilitySelfActivator.disableAndCloakSilently(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        // enableSilently without forceUncloak should be skipped while cloaked
        val resultWhileCloaked = AccessibilitySelfActivator.enableSilently(dummyContext, forceUncloak = false)
        assertFalse(resultWhileCloaked)
        assertTrue(AccessibilitySelfActivator.isTemporarilyCloaked)

        // enableSilently with forceUncloak resets the flag
        AccessibilitySelfActivator.enableSilently(dummyContext, forceUncloak = true)
        assertFalse(AccessibilitySelfActivator.isTemporarilyCloaked)
    }

    @Test
    fun testColonSeparatedServiceParsingLogic() {
        val longComponent = "com.drmohammed.antiporn/com.drmohammed.antiporn.accessibility.HisnAccessibilityService"
        val shortComponent = "com.drmohammed.antiporn/.accessibility.HisnAccessibilityService"
        val otherService = "com.google.android.marvin.talkback/.TalkBackService"

        val rawServicesWithShort = "$otherService:$shortComponent"
        val tokensWithShort = rawServicesWithShort.split(":").map { it.trim() }

        assertTrue(tokensWithShort.contains(shortComponent))
        assertFalse(tokensWithShort.contains(longComponent))

        // Ensure filtering removes our service without removing talkback
        val filtered = tokensWithShort.filter { it != longComponent && it != shortComponent }
        assertEquals(listOf(otherService), filtered)

        // Ensure adding appends cleanly
        val updated = (filtered + longComponent).joinToString(":")
        assertEquals("$otherService:$longComponent", updated)
    }

    @Test
    fun testComponentMatchesCanonicalClassName() {
        assertEquals(
            "com.drmohammed.antiporn.accessibility.HisnAccessibilityService",
            HisnAccessibilityService::class.java.name
        )
    }
}
