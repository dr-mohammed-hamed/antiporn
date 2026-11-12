package com.drmohammed.antiporn.shields

import com.drmohammed.antiporn.core.policy.AppPolicyRegistry

/**
 * Category-indexed package inventories for high-risk, circumvention,
 * social media, anonymous chat, and local exploit applications.
 * Derived directly from [AppPolicyRegistry.RULES] to maintain a single source of truth.
 */
internal object AppBlacklistRegistry {

    private fun packagesForCategory(category: String): Set<String> =
        AppPolicyRegistry.RULES
            .asSequence()
            .filter { !it.isWildcard && it.highRiskCategory == category }
            .map { it.pattern }
            .toSet()

    val TELEGRAM_PACKAGES: Set<String> by lazy {
        packagesForCategory(AppPolicyRegistry.CATEGORY_TELEGRAM)
    }

    val OPEN_SOCIAL_NETWORKS: Set<String> by lazy {
        packagesForCategory(AppPolicyRegistry.CATEGORY_OPEN_SOCIAL)
    }

    val PROXY_BROWSERS: Set<String> by lazy {
        packagesForCategory(AppPolicyRegistry.CATEGORY_PROXY_BROWSER)
    }

    val RANDOM_CHAT_APPS: Set<String> by lazy {
        packagesForCategory(AppPolicyRegistry.CATEGORY_RANDOM_CHAT)
    }

    val TIKTOK_PACKAGES: Set<String> by lazy {
        packagesForCategory(AppPolicyRegistry.CATEGORY_TIKTOK)
    }

    val LOCAL_ADB_TOOLS: Set<String> by lazy {
        packagesForCategory(AppPolicyRegistry.CATEGORY_LOCAL_ADB)
    }
}
