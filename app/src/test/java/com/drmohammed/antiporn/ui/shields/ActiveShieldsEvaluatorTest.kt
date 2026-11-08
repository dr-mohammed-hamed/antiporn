package com.drmohammed.antiporn.ui.shields

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveShieldsEvaluatorTest {

    @Test
    fun testIsHostAdultFiltering_knownFamilyPresets_returnTrue() {
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering(DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering("family-filter-dns.cleanbrowsing.org"))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering("dns-family.adguard.com"))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering("family.adguard-dns.com"))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering("family.cloudflare-dns.com"))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering("family.cloudflare-dns.com"))
        assertTrue(ActiveShieldsEvaluator.isHostAdultFiltering("my-custom-nextdns-id.dns.nextdns.io"))
    }

    @Test
    fun testIsHostAdultFiltering_unfilteredPublicDns_returnFalse() {
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("adguard-dns.com"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("dns.google"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("1.1.1.1"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("one.one.one.one"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("8.8.8.8"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("p0.freedns.controld.com"))
        assertFalse(ActiveShieldsEvaluator.isHostAdultFiltering("custom-unfiltered.example.org"))
    }
}
