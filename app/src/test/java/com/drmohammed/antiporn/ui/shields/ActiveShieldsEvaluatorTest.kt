package com.drmohammed.antiporn.ui.shields

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.model.ShieldStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveShieldsEvaluatorTest {

    @Test
    fun testIsHostAdultFiltering_knownFamilyPresets_returnTrue() {
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering(DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering(DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering("family-filter-dns.cleanbrowsing.org"))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering("adult-filter-dns.cleanbrowsing.org"))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering("dns-family.adguard.com"))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering("family.adguard-dns.com"))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering("family.cloudflare-dns.com"))
    }

    @Test
    fun testIsHostAdultFiltering_unfilteredPublicDns_returnFalse() {
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("adguard-dns.com"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("dns.google"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("1.1.1.1"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("one.one.one.one"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("8.8.8.8"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("p0.freedns.controld.com"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("security.cloudflare-dns.com"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("cloudflare-dns.com"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("one.cloudflare-dns.com"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("myfamily.example.org"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("cleanbrowsing.evil.com"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("custom-unfiltered.example.org"))
    }

    @Test
    fun testShouldIncludeCurfewShield_respectsPolicyAndShieldStatus() {
        val activeStatus = ShieldStatus.Active("active")
        val pendingStatus = ShieldStatus.NeedsEnforcement("pending")
        val defaultDisabledPolicy = DevicePolicyBundle.defaultPolicy()
        val enabledPolicy = defaultDisabledPolicy.copy(isCurfewEnabled = true)

        assertFalse(
            "Default-OFF policy must hide curfew shield from Active Shields list",
            ActiveShieldsEvaluator.shouldIncludeCurfewShield(defaultDisabledPolicy, activeStatus)
        )
        assertFalse(
            "Null/unavailable policy must not expose curfew shield",
            ActiveShieldsEvaluator.shouldIncludeCurfewShield(null, activeStatus)
        )
        assertTrue(
            "Enabled policy with Active shield must appear in Active Shields list",
            ActiveShieldsEvaluator.shouldIncludeCurfewShield(enabledPolicy, activeStatus)
        )
        assertFalse(
            "Pending shield must not appear as active even when policy enables curfew",
            ActiveShieldsEvaluator.shouldIncludeCurfewShield(enabledPolicy, pendingStatus)
        )
    }
}
