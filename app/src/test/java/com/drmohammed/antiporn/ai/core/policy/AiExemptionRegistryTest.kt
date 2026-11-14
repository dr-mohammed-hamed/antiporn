package com.drmohammed.antiporn.ai.core.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiExemptionRegistryTest {

    @Test
    fun systemApps_areExempt() {
        assertTrue(AiExemptionRegistry.isExempt("com.android.settings"))
        assertTrue(AiExemptionRegistry.isExempt("com.oppo.launcher"))
        assertTrue(AiExemptionRegistry.isExempt("com.android.systemui"))
        assertTrue(AiExemptionRegistry.isExempt("com.android.dialer"))
        assertTrue(AiExemptionRegistry.isExempt("com.drmohammed.antiporn"))
    }

    @Test
    fun browsersAndSocial_areTargets_notExempt() {
        assertTrue(AiExemptionRegistry.isTarget("com.android.chrome"))
        assertTrue(AiExemptionRegistry.isTarget("com.instagram.android"))
        assertTrue(AiExemptionRegistry.isTarget("com.google.android.youtube"))
        assertTrue(AiExemptionRegistry.isTarget("com.google.android.googlequicksearchbox"))
        assertFalse(AiExemptionRegistry.isExempt("com.android.chrome"))
        assertFalse(AiExemptionRegistry.isExempt("com.instagram.android"))
        assertFalse(AiExemptionRegistry.isExempt("com.google.android.googlequicksearchbox"))
    }

    @Test
    fun storesAndBlacklist_areTargets() {
        assertTrue(AiExemptionRegistry.isTarget("com.android.vending"))
        assertTrue(AiExemptionRegistry.isTarget("com.heytap.market"))
        assertTrue(AiExemptionRegistry.isTarget("org.telegram.messenger"))
        assertFalse(AiExemptionRegistry.isExempt("org.telegram.messenger"))
    }

    @Test
    fun galleryAndCamera_areNotExempt_byDesign() {
        // عمداً: قد تعرض محتوى ملتقطاً — تبقى هدفاً
        assertFalse(AiExemptionRegistry.isExempt("com.coloros.gallery"))
        assertFalse(AiExemptionRegistry.isExempt("com.google.android.apps.photos"))
    }

    @Test
    fun unknownProductivity_isNotTarget_butNotExempt() {
        assertFalse(AiExemptionRegistry.isTarget("com.example.notes"))
        assertFalse(AiExemptionRegistry.isExempt("com.example.notes"))
    }
}
