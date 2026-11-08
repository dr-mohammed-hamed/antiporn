package com.drmohammed.antiporn.core.network.dns

import org.junit.Assert.*
import org.junit.Test

class PornKeywordMatcherTest {

    @Test
    fun testAdultDomainsDetection() {
        val adultDomains = listOf(
            "pornhub.com",
            "www.xvideos.com",
            "m.redtube.com",
            "sub.xnxx.com",
            "xhamster.com",
            "youporn.com",
            "chaturbate.com",
            "onlyfans.com",
            "stripchat.com",
            "livejasmin.com",
            "cam4.com",
            "bongacams.com",
            "brazzers.com",
            "spankbang.com",
            "eporner.com",
            "beeg.com",
            "hentaihaven.xxx",
            "free-xxx-videos.net"
        )

        for (domain in adultDomains) {
            assertTrue("Domain $domain must be detected as adult", PornKeywordMatcher.isAdultDomain(domain))
        }
    }

    @Test
    fun testZeroFalsePositivesForLegitimateDomains() {
        // Scunthorpe problem prevention & legitimate common sites
        val legitimateDomains = listOf(
            "sussex.ac.uk",
            "essex.gov.uk",
            "middlesex.ac.uk",
            "google.com",
            "google-analytics.com",
            "classroom.google.com",
            "analytics.twitter.com",
            "facebook.com",
            "wikipedia.org",
            "github.com",
            "stackoverflow.com",
            "microsoft.com",
            "apple.com",
            "cloudflare.com",
            "amazon.com",
            "scunthorpe.gov.uk",
            "pass.gov.eg",
            "classic.autotrader.co.uk"
        )

        for (domain in legitimateDomains) {
            assertFalse("Domain $domain must NOT be false-flagged as adult!", PornKeywordMatcher.isAdultDomain(domain))
        }
    }
}
