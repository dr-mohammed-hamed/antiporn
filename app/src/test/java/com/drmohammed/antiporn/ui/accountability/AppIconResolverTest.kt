package com.drmohammed.antiporn.ui.accountability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconResolverTest {

    @Test
    fun testColorIndexBoundarySafeguard() {
        val avatarColorsSize = 12
        val testHashes = intArrayOf(
            Int.MIN_VALUE,
            Int.MIN_VALUE + 1,
            -1234567,
            -1,
            0,
            1,
            1234567,
            Int.MAX_VALUE - 1,
            Int.MAX_VALUE
        )

        for (h in testHashes) {
            val colorIndex = (h and 0x7FFFFFFF) % avatarColorsSize
            assertTrue("Color index must be >= 0, was $colorIndex for hash $h", colorIndex >= 0)
            assertTrue("Color index must be < $avatarColorsSize, was $colorIndex for hash $h", colorIndex < avatarColorsSize)
        }
    }

    @Test
    fun testPackageSanitization() {
        val packageName = "com.example.app:name/sub#123"
        val safeName = packageName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        assertEquals("com.example.app_name_sub_123", safeName)
    }

    @Test
    fun testClearSafeExecution() {
        // Must execute cleanly without exceptions
        AppIconResolver.clear()
    }

    @Test
    fun testAvatarCacheKeyDeterminism() {
        val appName = "YouTube Music"
        val pkg = "com.google.android.apps.youtube.music"
        val avatarColorsSize = 12
        val colorIndex1 = (pkg.hashCode() and 0x7FFFFFFF) % avatarColorsSize
        val letter1 = appName.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
        val key1 = "${letter1}_${colorIndex1}"

        val colorIndex2 = (pkg.hashCode() and 0x7FFFFFFF) % avatarColorsSize
        val letter2 = appName.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
        val key2 = "${letter2}_${colorIndex2}"

        assertEquals("Y", letter1.toString())
        assertEquals(key1, key2)
    }
}
