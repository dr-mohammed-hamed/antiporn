package com.drmohammed.antiporn.shields

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSuspensionShieldTest {

    @Test
    fun testTelegramPackagesAreBlacklisted() {
        val telegramPackages = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.telegram.plus",
            "nekox.messenger",
            "org.thunderdog.challegram",
            "com.iMe.android",
            "org.aka.messenger",
            "com.turrit.telegram",
            "org.vidogram.messenger",
            "org.telegram.BNavigation"
        )

        for (pkg in telegramPackages) {
            assertTrue("Expected Telegram package $pkg to be blacklisted", AppSuspensionShield.isBlacklisted(pkg))
            assertEquals("Telegram", AppSuspensionShield.getCategoryForPackage(pkg))
        }
    }

    @Test
    fun testOpenSocialNetworksAreBlacklisted() {
        val socialPackages = listOf(
            // Reddit & top clients
            "com.reddit.frontpage",
            "com.rubenmayayo.reddit",
            "com.laurencedawson.reddit_sync",
            "ml.docilealligator.infinityforreddit",
            "free.reddit.news",
            "com.andrewshu.android.reddit",
            // X / Twitter
            "com.twitter.android",
            "com.twitter.android.lite",
            // Tumblr
            "com.tumblr",
            // VK
            "com.vkontakte.android",
            "com.vk.im"
        )

        for (pkg in socialPackages) {
            assertTrue("Expected open social package $pkg to be blacklisted", AppSuspensionShield.isBlacklisted(pkg))
            assertEquals("Open Social Network", AppSuspensionShield.getCategoryForPackage(pkg))
        }
    }

    @Test
    fun testProxyBrowsersAreBlacklisted() {
        val browsers = listOf(
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.torproject.torbrowser",
            "com.duckduckgo.mobile.android",
            "com.cloudmosa.puffinFree",
            "com.cloudmosa.puffin",
            "com.kiwibrowser.browser",
            "com.opera.touch",
            "com.opera.browser",
            "com.alohamobile.browser",
            "com.alohamobile.browser.lite",
            "com.UCMobile.intl",
            "com.uc.browser.en"
        )

        for (pkg in browsers) {
            assertTrue("Expected proxy browser $pkg to be blacklisted", AppSuspensionShield.isBlacklisted(pkg))
            assertEquals("Proxy Browser", AppSuspensionShield.getCategoryForPackage(pkg))
        }
    }

    @Test
    fun testRandomChatAppsAreBlacklisted() {
        val chatApps = listOf(
            "chat.ometv",
            "com.monkey.cool",
            "com.chatous.chatous",
            "com.kik.android",
            "com.camfrog.android.phone",
            "com.connected2.me",
            "com.azar.live",
            "com.livu.live"
        )

        for (pkg in chatApps) {
            assertTrue("Expected random chat app $pkg to be blacklisted", AppSuspensionShield.isBlacklisted(pkg))
            assertEquals("Random Chat App", AppSuspensionShield.getCategoryForPackage(pkg))
        }
    }

    @Test
    fun testTikTokPackagesAreBlacklisted() {
        val tikTokApps = listOf(
            "com.zhiliaoapp.musically",
            "com.zhiliaoapp.musically.go",
            "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.aweme.mobile",
            "com.zhiliao.musically"
        )

        for (pkg in tikTokApps) {
            assertTrue("Expected TikTok package $pkg to be blacklisted", AppSuspensionShield.isBlacklisted(pkg))
            assertEquals("TikTok", AppSuspensionShield.getCategoryForPackage(pkg))
        }
    }

    @Test
    fun testExplicitImmunitySafeguard() {
        val immuneApps = listOf(
            "org.puregram.messenger",
            "com.drmohammed.antiporn"
        )

        for (pkg in immuneApps) {
            assertTrue("Package $pkg must be marked as immune", AppSuspensionShield.isImmune(pkg))
            assertFalse(
                "Immune package $pkg must NEVER be reported as blacklisted",
                AppSuspensionShield.isBlacklisted(pkg)
            )
            assertFalse(
                "Immune package $pkg must NOT exist in BLACKLISTED_PACKAGES set",
                AppSuspensionShield.BLACKLISTED_PACKAGES.contains(pkg)
            )
            assertNull(
                "Immune package $pkg must return null category",
                AppSuspensionShield.getCategoryForPackage(pkg)
            )
        }

        val overlap = AppSuspensionShield.BLACKLISTED_PACKAGES.intersect(AppSuspensionShield.IMMUNE_PACKAGES)
        assertTrue("BLACKLISTED_PACKAGES and IMMUNE_PACKAGES must have zero overlap", overlap.isEmpty())
    }

    @Test
    fun testSafeAppsAndToolsAreNotBlacklisted() {
        val safeApps = listOf(
            "com.android.settings",
            "com.google.android.apps.messaging",
            "com.google.android.dialer",
            "com.whatsapp",
            "com.android.chrome",
            "com.google.android.youtube",
            "org.fdroid.fdroid",
            "com.termux",
            "com.google.android.googlequicksearchbox",
            "",
            "random.unknown.package"
        )

        for (pkg in safeApps) {
            assertFalse("Expected $pkg to NOT be blacklisted", AppSuspensionShield.isBlacklisted(pkg))
            assertNull("Expected category for $pkg to be null", AppSuspensionShield.getCategoryForPackage(pkg))
        }
    }

    @Test
    fun testPackageCountsAndCategoryIntegrity() {
        assertEquals(11, AppSuspensionShield.TELEGRAM_PACKAGES.size)
        assertEquals(11, AppSuspensionShield.OPEN_SOCIAL_NETWORKS.size)
        assertEquals(14, AppSuspensionShield.PROXY_BROWSERS.size)
        assertEquals(8, AppSuspensionShield.RANDOM_CHAT_APPS.size)
        assertEquals(7, AppSuspensionShield.TIKTOK_PACKAGES.size)

        val totalExpected = AppSuspensionShield.TELEGRAM_PACKAGES.size +
            AppSuspensionShield.OPEN_SOCIAL_NETWORKS.size +
            AppSuspensionShield.PROXY_BROWSERS.size +
            AppSuspensionShield.RANDOM_CHAT_APPS.size +
            AppSuspensionShield.TIKTOK_PACKAGES.size

        assertEquals(51, totalExpected)
        assertEquals(51, AppSuspensionShield.BLACKLISTED_PACKAGES.size)
    }
}
