package com.drmohammed.antiporn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class IslamicTimeZoneRegistryTest {

    @Test
    fun testCuratedOptions_containsAllRequiredTimeZones() {
        val options = IslamicTimeZoneRegistry.getAllOptions()
        assertEquals(11, options.size)

        val defaultOption = IslamicTimeZoneRegistry.getDefaultOption()
        assertEquals("Africa/Cairo", defaultOption.ianaTimeZoneId)
        assertEquals("GMT+2", defaultOption.gmtOffsetLabel)
        assertTrue(defaultOption.majorCities.contains("القاهرة"))
        assertTrue(defaultOption.majorCities.contains("القدس الشريف"))
    }

    @Test
    fun testDisplayText_formatting() {
        val options = IslamicTimeZoneRegistry.getAllOptions()
        for (option in options) {
            val expectedDisplay = "${option.getLiveOffsetLabel()} (${option.majorCities})"
            assertEquals(expectedDisplay, option.displayText)
            assertEquals(expectedDisplay, option.toString())
        }
    }

    @Test
    fun testDetectCurrentDeviceOption() {
        val detected = IslamicTimeZoneRegistry.detectCurrentDeviceOption()
        assertNotNull(detected)
        assertTrue(IslamicTimeZoneRegistry.getAllOptions().contains(detected))
    }

    @Test
    fun testFindByIanaId_exactAndCaseInsensitive() {
        val cairo = IslamicTimeZoneRegistry.findByIanaId("Africa/Cairo")
        assertEquals("Africa/Cairo", cairo.ianaTimeZoneId)
        assertEquals("GMT+2", cairo.gmtOffsetLabel)

        val cairoLower = IslamicTimeZoneRegistry.findByIanaId("africa/cairo")
        assertEquals("Africa/Cairo", cairoLower.ianaTimeZoneId)

        val ny = IslamicTimeZoneRegistry.findByIanaId("America/New_York")
        assertEquals("GMT-5", ny.gmtOffsetLabel)
    }

    @Test
    fun testFindByIanaId_nullOrBlankOrInvalid_fallsBackToDefault() {
        val defaultTz = IslamicTimeZoneRegistry.getDefaultOption()

        assertEquals(defaultTz, IslamicTimeZoneRegistry.findByIanaId(null))
        assertEquals(defaultTz, IslamicTimeZoneRegistry.findByIanaId(""))
        assertEquals(defaultTz, IslamicTimeZoneRegistry.findByIanaId("   "))
        assertEquals(defaultTz, IslamicTimeZoneRegistry.findByIanaId("Invalid/Time_Zone"))
    }

    @Test
    fun testIsValidIanaId() {
        assertTrue(IslamicTimeZoneRegistry.isValidIanaId("Asia/Riyadh"))
        assertTrue(IslamicTimeZoneRegistry.isValidIanaId("Africa/Cairo"))
        assertTrue(IslamicTimeZoneRegistry.isValidIanaId("UTC"))
        assertTrue(IslamicTimeZoneRegistry.isValidIanaId("GMT"))

        assertFalse(IslamicTimeZoneRegistry.isValidIanaId(""))
        assertFalse(IslamicTimeZoneRegistry.isValidIanaId("   "))
        assertFalse(IslamicTimeZoneRegistry.isValidIanaId("Fake/NonExistent_Zone"))
    }

    @Test
    fun testAllCuratedIanaIds_areRecognizedByJavaTimeZone() {
        val available = TimeZone.getAvailableIDs().toSet()
        for (option in IslamicTimeZoneRegistry.getAllOptions()) {
            assertTrue("Expected ${option.ianaTimeZoneId} to be recognized by TimeZone", available.contains(option.ianaTimeZoneId))
            val tz = TimeZone.getTimeZone(option.ianaTimeZoneId)
            assertNotNull(tz)
        }
    }
}
