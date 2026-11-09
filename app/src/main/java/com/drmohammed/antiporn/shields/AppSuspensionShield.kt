package com.drmohammed.antiporn.shields

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Pluggable security shield targeting unauthorized and unrestricted applications:
 * Suspends Telegram clients, open social networks, proxy/bypass browsers,
 * and random/anonymous chat applications.
 */
object AppSuspensionShield : ProtectionShield {

    private const val TAG = "AntiPorn_AppSuspension"
    const val ACTION_UNINSTALL_STATUS = "com.drmohammed.antiporn.ACTION_UNINSTALL_STATUS"

    override val id: String = "app_suspension"
    override val titleResId: Int = R.string.card_app_suspension_title

    /**
     * Packages that must NEVER be suspended or uninstalled under any circumstances.
     * Delegates to central [AppPolicyRegistry].
     */
    val IMMUNE_PACKAGES: Set<String> get() = AppPolicyRegistry.getImmunePackages()

    /**
     * Telegram & official / unofficial clients / forks.
     */
    val TELEGRAM_PACKAGES: Set<String> = setOf(
        "org.telegram.messenger",       // Official Play Store
        "org.telegram.messenger.web",   // Official Website APK
        "org.telegram.messenger.beta",  // Telegram Beta
        "org.telegram.plus",            // Plus Messenger
        "nekox.messenger",              // NekoX
        "org.thunderdog.challegram",    // Telegram X
        "com.iMe.android",              // iMe Messenger
        "org.aka.messenger",            // Turrit / Aka
        "com.turrit.telegram",          // Turrit
        "org.vidogram.messenger",       // Vidogram
        "org.telegram.BNavigation"      // Telegram BNavigation
    )

    /**
     * Open Social Networks (Reddit clients, X/Twitter, Tumblr, VK).
     */
    val OPEN_SOCIAL_NETWORKS: Set<String> = setOf(
        // Reddit & top clients
        "com.reddit.frontpage",                  // Reddit Official
        "com.rubenmayayo.reddit",                // Boost for Reddit
        "com.laurencedawson.reddit_sync",        // Sync for Reddit
        "ml.docilealligator.infinityforreddit",  // Infinity for Reddit
        "free.reddit.news",                      // Relay for Reddit
        "com.andrewshu.android.reddit",          // RIF (reddit is fun)

        // X / Twitter
        "com.twitter.android",                   // X / Twitter
        "com.twitter.android.lite",              // X Lite / Twitter Lite

        // Tumblr
        "com.tumblr",                            // Tumblr

        // VK
        "com.vkontakte.android",                 // VK Official
        "com.vk.im"                              // VK Me / Messenger
    )

    /**
     * Proxy / Bypass Browsers (Tor, Firefox variants, DuckDuckGo, Aloha, Opera, UC, Puffin, Kiwi).
     */
    val PROXY_BROWSERS: Set<String> = setOf(
        // Firefox variants
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",

        // Tor Browser
        "org.torproject.torbrowser",

        // DuckDuckGo
        "com.duckduckgo.mobile.android",

        // Puffin
        "com.cloudmosa.puffinFree",
        "com.cloudmosa.puffin",

        // Kiwi Browser
        "com.kiwibrowser.browser",

        // Opera variants
        "com.opera.touch",
        "com.opera.browser",

        // Aloha Browser
        "com.alohamobile.browser",
        "com.alohamobile.browser.lite",

        // UC Browser
        "com.UCMobile.intl",
        "com.uc.browser.en"
    )

    /**
     * Random / Anonymous Chat Apps (OmeTV, Monkey, Chatous, Kik, Camfrog, Connected2.me, Azar, LivU).
     */
    val RANDOM_CHAT_APPS: Set<String> = setOf(
        "chat.ometv",                    // OmeTV
        "com.monkey.cool",               // Monkey
        "com.chatous.chatous",           // Chatous
        "com.kik.android",               // Kik
        "com.camfrog.android.phone",     // Camfrog
        "com.connected2.me",             // Connected2.me
        "com.azar.live",                 // Azar
        "com.livu.live"                  // LivU
    )

    /**
     * TikTok variants & clients (TikTok Global, TikTok Lite, Douyin, regional variants).
     */
    val TIKTOK_PACKAGES: Set<String> = setOf(
        "com.zhiliaoapp.musically",       // TikTok Global / Main
        "com.zhiliaoapp.musically.go",    // TikTok Lite
        "com.ss.android.ugc.trill",       // TikTok Asia / Southeast Asia
        "com.ss.android.ugc.aweme",       // Douyin (TikTok China)
        "com.ss.android.ugc.aweme.lite",  // Douyin Lite
        "com.ss.android.ugc.aweme.mobile",// Douyin Mobile
        "com.zhiliao.musically"           // Legacy / Alternative Musical.ly
    )

    /**
     * Aggregated set of all blacklisted packages, sourced from central [AppPolicyRegistry].
     */
    val BLACKLISTED_PACKAGES: Set<String> get() = AppPolicyRegistry.getBlacklistedPackages()

    private val shieldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processingPackages = ConcurrentHashMap.newKeySet<String>()

    fun isImmune(packageName: String): Boolean = AppPolicyRegistry.isImmune(packageName)

    fun isBlacklisted(packageName: String): Boolean = AppPolicyRegistry.isPermanentlyBlacklisted(packageName)

    fun getCategoryForPackage(packageName: String): String? {
        if (isImmune(packageName)) return null
        return when {
            TELEGRAM_PACKAGES.contains(packageName) -> "Telegram"
            OPEN_SOCIAL_NETWORKS.contains(packageName) -> "Open Social Network"
            PROXY_BROWSERS.contains(packageName) -> "Proxy Browser"
            RANDOM_CHAT_APPS.contains(packageName) -> "Random Chat App"
            TIKTOK_PACKAGES.contains(packageName) -> "TikTok"
            else -> null
        }
    }

    fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun uninstallSilently(context: Context, packageName: String) {
        Log.w(TAG, "Silent uninstallation is permanently disabled under Unified Zero-Trust architecture. Skipping uninstall for $packageName")
        return
    }

    fun freezeAndUninstall(context: Context, dpm: DevicePolicyManager, admin: ComponentName, packageName: String) {
        if (packageName == context.packageName || isImmune(packageName)) {
            Log.w(TAG, "Immunity guard: Cannot freeze or uninstall immune or self package ($packageName)")
            return
        }
        if (!isPackageInstalled(context.packageManager, packageName)) {
            Log.d(TAG, "Package $packageName is not installed; skipping freeze/uninstall")
            return
        }
        if (!processingPackages.add(packageName)) {
            Log.d(TAG, "Package $packageName is already being processed; skipping concurrent freeze/uninstall")
            return
        }

        try {
            // Check-Before-Write: unblock uninstall only if currently blocked
            try {
                if (dpm.isUninstallBlocked(admin, packageName)) {
                    dpm.setUninstallBlocked(admin, packageName, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not unblock uninstall for $packageName", e)
            }

            // Check-Before-Write: suspend only if not already suspended
            try {
                val isSuspended = try { dpm.isPackageSuspended(admin, packageName) } catch (e: Exception) { false }
                if (!isSuspended) {
                    val failedPackages = dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
                    if (failedPackages?.contains(packageName) == true) {
                        Log.w(TAG, "Failed to suspend package: $packageName")
                    } else {
                        Log.i(TAG, "Suspended package: $packageName")
                    }
                } else {
                    Log.d(TAG, "Package $packageName is already suspended; skipping setPackagesSuspended")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to suspend package: $packageName", e)
            }

            uninstallSilently(context, packageName)
        } finally {
            processingPackages.remove(packageName)
        }
    }

    fun handlePackageAdded(context: Context, packageName: String) {
        if (isImmune(packageName) || !isBlacklisted(packageName)) return
        val appContext = context.applicationContext
        if (!com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(appContext)) {
            Log.w(TAG, "Cannot freeze $packageName: App is not Device/Profile Owner")
            return
        }
        Log.i(TAG, "Freezing and uninstalling blacklisted app on install: $packageName")
        com.drmohammed.antiporn.telemetry.SafeDpmGateway.executeRawBlocking(
            context = appContext,
            tag = com.drmohammed.antiporn.telemetry.BlackBoxRecorder.TAG_SHIELD,
            actionName = "handleBlacklistedPackageAdded",
            metadata = "pkg=$packageName"
        ) { dpm, admin ->
            freezeAndUninstall(appContext, dpm, admin, packageName)
        }
    }

    /**
     * High-speed helper to scan and enforce blacklist on all installed packages immediately.
     * Can be called synchronously or asynchronously without excessive overhead.
     */
    fun enforceBlacklistFast(context: Context) {
        val appContext = context.applicationContext
        if (!com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(appContext)) {
            Log.w(TAG, "enforceBlacklistFast skipped: not Device/Profile Owner")
            return
        }

        val pm = appContext.packageManager
        com.drmohammed.antiporn.telemetry.SafeDpmGateway.executeRawBlocking(
            context = appContext,
            tag = com.drmohammed.antiporn.telemetry.BlackBoxRecorder.TAG_SHIELD,
            actionName = "enforceBlacklistFast"
        ) { dpm, admin ->
            for (pkg in BLACKLISTED_PACKAGES) {
                if (!isImmune(pkg) && isPackageInstalled(pm, pkg)) {
                    freezeAndUninstall(appContext, dpm, admin, pkg)
                }
            }
        }
    }

    fun enforceBlacklistFastAsync(context: Context) {
        shieldScope.launch {
            try {
                enforceBlacklistFast(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error in enforceBlacklistFastAsync", e)
            }
        }
    }

    override fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldResult {
        if (!ProtectionEngine.isDeviceOwner(context)) {
            return ShieldResult(id, false, context.getString(R.string.toast_not_device_owner))
        }

        return try {
            val pm = context.packageManager
            val installedTargets = BLACKLISTED_PACKAGES.filter { !isImmune(it) && isPackageInstalled(pm, it) }
            for (pkg in installedTargets) {
                freezeAndUninstall(context, dpm, admin, pkg)
            }
            val msg = if (installedTargets.isEmpty()) {
                context.getString(R.string.shield_app_suspension_none_found)
            } else {
                context.getString(R.string.shield_app_suspension_blocked_count, installedTargets.size)
            }
            ShieldResult(shieldId = id, success = true, message = msg)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying AppSuspensionShield", e)
            ShieldResult(id, false, context.getString(R.string.toast_error_applying, e.localizedMessage ?: "Unknown"))
        }
    }

    override fun checkStatus(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldStatus {
        if (!ProtectionEngine.isDeviceOwner(context)) {
            return ShieldStatus.Disabled(context.getString(R.string.card_device_owner_inactive))
        }

        return try {
            val pm = context.packageManager
            val installedTargets = BLACKLISTED_PACKAGES.filter { !isImmune(it) && isPackageInstalled(pm, it) }
            if (installedTargets.isEmpty()) {
                return ShieldStatus.Active(context.getString(R.string.card_app_suspension_active))
            }

            val unSuspended = installedTargets.filter { pkg ->
                try { !dpm.isPackageSuspended(admin, pkg) } catch (e: Exception) {
                    Log.w(TAG, "Error reading package suspension for $pkg", e)
                    true
                }
            }

            if (unSuspended.isNotEmpty()) {
                ShieldStatus.NeedsEnforcement(context.getString(R.string.card_app_suspension_pending, unSuspended.size))
            } else {
                ShieldStatus.Active(context.getString(R.string.card_app_suspension_suspended, installedTargets.size))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating AppSuspensionShield status", e)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_app_suspension_inactive))
        }
    }
}
