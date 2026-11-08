package com.drmohammed.antiporn.core.network.dns

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import org.junit.Assert.*
import org.junit.Test

class DnsPolicyEvaluatorTest {

    private fun mockQuery(domain: String, qType: Int = 1): DnsPacketParser.ParsedDnsQuery {
        return DnsPacketParser.ParsedDnsQuery(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1),
            srcPort = 54321,
            dstPort = 53,
            ipId = 100,
            transactionId = 0x1234.toShort(),
            qName = domain,
            qType = qType,
            questionSection = ByteArray(0),
            rawDnsPayload = ByteArray(12)
        )
    }

    @Test
    fun testTier0CaptivePortalsAlwaysPass() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY,
            customAllowedUrls = emptySet()
        )

        val connectivityQuery = mockQuery("connectivitycheck.gstatic.com")
        val action = DnsPolicyEvaluator.evaluate(connectivityQuery, policy, emptySet())
        assertTrue("Captive portal check must pass even under whitelist mode", action is DnsPolicyEvaluator.PolicyAction.Pass)

        val appleQuery = mockQuery("captive.apple.com")
        val appleAction = DnsPolicyEvaluator.evaluate(appleQuery, policy, emptySet())
        assertTrue("Apple captive portal check must pass", appleAction is DnsPolicyEvaluator.PolicyAction.Pass)
    }

    @Test
    fun testTier1UnbypassablePornCannotBeWhitelisted() {
        // Even if guardian attempts to whitelist pornhub.com, Tier 1 MUST block it
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            customAllowedUrls = setOf("pornhub.com", "xvideos.com")
        )

        val pornQuery = mockQuery("www.pornhub.com")
        val action = DnsPolicyEvaluator.evaluate(pornQuery, policy, emptySet())
        assertTrue("Pornography domain must be unbypassably blocked!", action is DnsPolicyEvaluator.PolicyAction.BlockPorn)
    }

    @Test
    fun testTier2TelegramWebBlockedWhenEnabled() {
        val policyBlocked = DevicePolicyBundle.defaultPolicy().copy(isTelegramWebBlocked = true)
        val tgQuery = mockQuery("web.telegram.org")
        val actionBlocked = DnsPolicyEvaluator.evaluate(tgQuery, policyBlocked, emptySet())
        assertTrue("Telegram web must be blocked when policy is true", actionBlocked is DnsPolicyEvaluator.PolicyAction.BlockTelegramWeb)

        val tMeQuery = mockQuery("t.me")
        val actionTme = DnsPolicyEvaluator.evaluate(tMeQuery, policyBlocked, emptySet())
        assertTrue("t.me must be blocked when telegram policy is true", actionTme is DnsPolicyEvaluator.PolicyAction.BlockTelegramWeb)

        val policyAllowed = DevicePolicyBundle.defaultPolicy().copy(isTelegramWebBlocked = false)
        val actionAllowed = DnsPolicyEvaluator.evaluate(tgQuery, policyAllowed, emptySet())
        assertTrue("Telegram web should pass when policy is false", actionAllowed is DnsPolicyEvaluator.PolicyAction.Pass)
    }

    @Test
    fun testTier5DoHCanaryBlocked() {
        val policy = DevicePolicyBundle.defaultPolicy()
        val canaryQuery = mockQuery("use-application-dns.net")
        val action = DnsPolicyEvaluator.evaluate(canaryQuery, policy, emptySet())
        assertTrue("DoH canary domain must be blocked", action is DnsPolicyEvaluator.PolicyAction.BlockDohBypass)

        val icloudQuery = mockQuery("mask.icloud.com")
        val icloudAction = DnsPolicyEvaluator.evaluate(icloudQuery, policy, emptySet())
        assertTrue("Apple Private Relay must be blocked", icloudAction is DnsPolicyEvaluator.PolicyAction.BlockDohBypass)
    }

    @Test
    fun testTier6YouTubeRestrictedModeRewrite() {
        val policyEnabled = DevicePolicyBundle.defaultPolicy().copy(isYouTubeRestrictedModeEnabled = true)
        val ytQuery = mockQuery("www.youtube.com", qType = 1)
        val action = DnsPolicyEvaluator.evaluate(ytQuery, policyEnabled, emptySet())
        assertTrue("YouTube domain must trigger Restricted Mode synthesis", action is DnsPolicyEvaluator.PolicyAction.SynthesizeYouTubeRestricted)

        val policyDisabled = DevicePolicyBundle.defaultPolicy().copy(isYouTubeRestrictedModeEnabled = false)
        val actionDisabled = DnsPolicyEvaluator.evaluate(ytQuery, policyDisabled, emptySet())
        assertTrue("YouTube domain should pass when Restricted Mode is false", actionDisabled is DnsPolicyEvaluator.PolicyAction.Pass)
    }
}
