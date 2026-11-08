package com.drmohammed.antiporn.core.network.dns

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.util.BrowserUrlSanitizer

/**
 * Deterministic 6-tier security policy evaluator for local DNS inspection.
 *
 * Evaluation Hierarchy:
 * - Tier 0: Captive Portals & Google System Essentials (Always Pass).
 * - Tier 1: Unbypassable Adult Content (PornKeywordMatcher + BinaryDomainFilter PORN).
 *           Cannot be overridden by custom whitelists.
 * - Tier 2: Telegram Web Shield (t.me, web.telegram.org) when enabled.
 * - Tier 3: Custom Guardian/User Rules (Blocklist / Whitelist-only mode).
 * - Tier 4: Local Ads & Trackers Filter (BinaryDomainFilter ADS).
 * - Tier 5: Anti-Bypass DoH Canary & Apple Private Relay blocking.
 * - Tier 6: YouTube Restricted Mode (SafeSearch VIP response) when enabled.
 */
object DnsPolicyEvaluator {

    sealed class PolicyAction {
        object Pass : PolicyAction()
        object BlockPorn : PolicyAction()
        object BlockAds : PolicyAction()
        object BlockTelegramWeb : PolicyAction()
        object BlockCustom : PolicyAction()
        object BlockDohBypass : PolicyAction()
        object SynthesizeYouTubeRestricted : PolicyAction()
    }

    val TELEGRAM_WEB_DOMAINS: Set<String> = setOf(
        "telegram.org",
        "web.telegram.org",
        "webk.telegram.org",
        "webz.telegram.org",
        "t.me",
        "telegram.me",
        "telegram.dog",
        "telegra.ph",
        "graph.org",
        "telesco.pe"
    )

    val DOH_BYPASS_DOMAINS: Set<String> = setOf(
        "use-application-dns.net",
        "mask.icloud.com",
        "mask-h2.icloud.com",
        "dns.google",
        "cloudflare-dns.com",
        "dns.quad9.net"
    )

    val YOUTUBE_DOMAINS: Set<String> = setOf(
        "youtube.com",
        "m.youtube.com",
        "www.youtube.com",
        "youtubei.googleapis.com",
        "youtube.googleapis.com",
        "youtube-nocookie.com",
        "youtu.be"
    )

    /**
     * Evaluates a parsed DNS query against the active device policy bundle.
     */
    fun evaluate(
        query: DnsPacketParser.ParsedDnsQuery,
        policy: DevicePolicyBundle,
        compiledCustomBlocked: Set<String>
    ): PolicyAction {
        val qName = query.qName.trim().lowercase().trimEnd('.')

        // Tier 0: Captive Portals & Core OS Connectivity
        if (isCaptivePortalOrExempt(qName)) {
            return PolicyAction.Pass
        }

        // Tier 1: Unbypassable Porn Tier (Immutable law)
        if (PornKeywordMatcher.isAdultDomain(qName) ||
            BinaryDomainFilter.checkDomainWithSubdomains(qName) == BinaryDomainFilter.FilterResult.BLOCKED_PORN
        ) {
            return PolicyAction.BlockPorn
        }

        // Tier 2: Telegram Web Shield
        if (policy.isTelegramWebBlocked && isDomainInSet(qName, TELEGRAM_WEB_DOMAINS)) {
            return PolicyAction.BlockTelegramWeb
        }

        // Tier 3: Guardian/User Custom Rules
        if (policy.browserFilterMode == DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY) {
            if (!isDomainInSet(qName, policy.customAllowedUrls)) {
                return PolicyAction.BlockCustom
            }
        } else {
            if (DnsPacketParser.isDomainBlockedCompiled(qName, compiledCustomBlocked)) {
                return PolicyAction.BlockCustom
            }
            if (isDomainInSet(qName, policy.customAllowedUrls)) {
                // Explicitly whitelisted by supervisor -> skip Ads tier
                return PolicyAction.Pass
            }
        }

        // Tier 4: Local Ads & Trackers Filter
        if (BinaryDomainFilter.checkDomainWithSubdomains(qName) == BinaryDomainFilter.FilterResult.BLOCKED_ADS) {
            if (!isDomainInSet(qName, policy.customAllowedUrls)) {
                return PolicyAction.BlockAds
            }
        }

        // Tier 5: Anti-Bypass DoH Canary & Encrypted Relay Blocking
        if (isDomainInSet(qName, DOH_BYPASS_DOMAINS)) {
            return PolicyAction.BlockDohBypass
        }

        // Tier 6: YouTube Restricted Mode VIP Synthesis
        if (policy.isYouTubeRestrictedModeEnabled && isDomainInSet(qName, YOUTUBE_DOMAINS)) {
            return PolicyAction.SynthesizeYouTubeRestricted
        }

        return PolicyAction.Pass
    }

    private val CORE_CONNECTIVITY_PROBES: Set<String> = setOf(
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "clients3.google.com",
        "captive.apple.com",
        "msftconnecttest.com"
    )

    private fun isCaptivePortalOrExempt(qName: String): Boolean {
        if (CORE_CONNECTIVITY_PROBES.contains(qName)) return true
        for (exempt in BrowserUrlSanitizer.CAPTIVE_PORTAL_EXEMPTIONS) {
            if (qName == exempt || qName.endsWith(".$exempt")) {
                return true
            }
        }
        return false
    }

    private fun isDomainInSet(qName: String, domainSet: Set<String>): Boolean {
        if (domainSet.isEmpty()) return false
        if (domainSet.contains(qName)) return true
        for (target in domainSet) {
            val cleanTarget = target.trim().lowercase().removePrefix("www.").removePrefix("*.")
            if (cleanTarget.isBlank()) continue
            if (qName == cleanTarget || qName.endsWith(".$cleanTarget")) {
                return true
            }
        }
        return false
    }
}
