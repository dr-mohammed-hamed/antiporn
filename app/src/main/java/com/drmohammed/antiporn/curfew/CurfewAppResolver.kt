package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import android.util.Log
import android.view.inputmethod.InputMethodManager
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.shields.AppSuspensionShield

/**
 * Resolves packages to suspend during night curfew while safeguarding essential
 * system services, user whitelisted productivity apps, dialer, camera, keyboards,
 * and dynamically detecting all Quran, Islamic, and Athan apps on the device.
 */
object CurfewAppResolver {

    private const val TAG = "AntiPorn_CurfewResolver"

    /**
     * Dynamically identifies if an installed application is a Quran, Islamic, Prayer, or Athan app
     * by evaluating both its package name and human-readable localized application label.
     */
    fun isIslamicOrQuranApp(pm: PackageManager, appInfo: ApplicationInfo): Boolean {
        val pkg = appInfo.packageName.lowercase()
        val islamicPkgKeywords = listOf(
            "quran", "moshaf", "mushaf", "koran", "furqan",
            "surah", "tilawat", "athkar", "azkar", "athan", "adthan",
            "islam", "tafsir", "hadith", "sunnah", "muslim", "alroqia",
            "hisn", "zekr", "dhikr", "salat", "namaz"
        )
        if (islamicPkgKeywords.any { pkg.contains(it) }) {
            return true
        }

        val label = try {
            pm.getApplicationLabel(appInfo).toString().lowercase()
        } catch (e: Exception) {
            ""
        }
        val labelTokens = label.split(Regex("[\\s_\\-.,;:!?/\\\\\\[\\]()]+")).filter { it.isNotBlank() }
        val islamicTokens = setOf(
            "قرآن", "قران", "مصحف", "آية", "اية", "أذكار", "اذكار",
            "تفسير", "أذان", "اذان", "صلاة", "quran",
            "mushaf", "ayah", "athan", "azkar", "athkar", "tafsir",
            "hadith", "islamic", "muslim"
        )
        if (labelTokens.any { islamicTokens.contains(it) }) return true

        val islamicPhrases = listOf("حصن المسلم", "القرآن الكريم", "المصحف الشريف", "تفسير القرآن")
        return islamicPhrases.any { label.contains(it) }
    }

    /**
     * Dynamically identifies if an application is an audiobook, podcast, or spoken audio player.
     */
    fun isAudiobookOrPodcastApp(pm: PackageManager, appInfo: ApplicationInfo): Boolean {
        val pkg = appInfo.packageName.lowercase()
        val keywords = listOf(
            "audiobook", "podcast", "podcasts", "antennapod", "castbox",
            "pocketcasts", "storytel", "wajeez", "akhdar", "abjjad", "rufoof", "dhad"
        )
        if (keywords.any { pkg.contains(it) }) return true

        val label = try { pm.getApplicationLabel(appInfo).toString().lowercase() } catch (e: Exception) { "" }
        val labelTokens = label.split(Regex("[\\s_\\-.,;:!?/\\\\\\[\\]()]+")).filter { it.isNotBlank() }
        val audioTokens = setOf("بودكاست", "audiobook", "podcast", "podcasts", "audiobooks")
        if (labelTokens.any { audioTokens.contains(it) }) return true

        val phrases = listOf("كتب صوتية", "كتب صوتيه", "كتاب صوتي")
        return phrases.any { label.contains(it) }
    }

    /**
     * Dynamically identifies if an application is a medical, pediatric dose, or study/clinical reference app.
     */
    fun isMedicalOrStudyApp(pm: PackageManager, appInfo: ApplicationInfo): Boolean {
        val pkg = appInfo.packageName.lowercase()
        val keywords = listOf(
            "pedidose", "pedi_dose", "pediatric", "medscape", "uptodate", "lexicomp",
            "sanford", "pharma", "clinical", "medical"
        )
        if (keywords.any { pkg.contains(it) }) return true

        val label = try { pm.getApplicationLabel(appInfo).toString().lowercase() } catch (e: Exception) { "" }
        val labelTokens = label.split(Regex("[\\s_\\-.,;:!?/\\\\\\[\\]()]+")).filter { it.isNotBlank() }
        val exactMatchTokens = setOf(
            "جرعات", "أدوية", "ادوية", "طبي", "طبية", "صيدلة", "medical", "pharma", "pediatric", "pedidose"
        )
        if (labelTokens.any { exactMatchTokens.contains(it) }) return true

        val phrases = listOf("جرعات الأطفال", "مرجع طبي", "معجم طبي")
        return phrases.any { label.contains(it) }
    }

    /**
     * Dynamically identifies if an application is an alternative YouTube player, NewPipe client/fork, or local media player.
     */
    fun isMediaOrAlternativePlayerApp(pm: PackageManager, appInfo: ApplicationInfo): Boolean {
        val pkg = appInfo.packageName.lowercase()
        val keywords = listOf("newpipe", "puretube", "tubular", "musicolet", "mxtech", "mxplayer")
        if (keywords.any { pkg.contains(it) }) return true

        val label = try { pm.getApplicationLabel(appInfo).toString().lowercase() } catch (e: Exception) { "" }
        val labelTokens = label.split(Regex("[\\s_\\-.,;:!?/\\\\\\[\\]()]+")).filter { it.isNotBlank() }
        val playerTokens = setOf("newpipe", "puretube", "tubular", "mxplayer")
        if (labelTokens.any { playerTokens.contains(it) }) return true

        val mediaPhrases = listOf("mx player", "مشغل mx")
        return mediaPhrases.any { label.contains(it) }
    }

    /**
     * Dynamically identifies if an application is classified as a game by the Android system.
     */
    fun isGameApp(appInfo: ApplicationInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appInfo.category == ApplicationInfo.CATEGORY_GAME) return true
        }
        @Suppress("DEPRECATION")
        return (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    }

    /**
     * Resolves all system immune packages dynamically (keyboards, launcher, dialer, camera, SMS, contacts, Quran, Audiobooks, Medical apps, and Games).
     */
    fun resolveImmunePackages(context: Context): Set<String> {
        val immune = mutableSetOf<String>()
        val pm = context.packageManager

        // 1. Static Critical & User Whitelist
        immune.addAll(CurfewConfig.SYSTEM_CRITICAL_PACKAGES)
        immune.addAll(CurfewConfig.STATIC_WHITELIST)
        immune.add(context.packageName)

        // 2. Dynamic Discovery of ALL Quran, Islamic, Audiobook, Podcast, Medical, and Game apps installed on the phone
        try {
            val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            for (app in installedApps) {
                if (AppPolicyRegistry.isExemptFromCurfew(app.packageName) || isIslamicOrQuranApp(pm, app) || isAudiobookOrPodcastApp(pm, app) || isMedicalOrStudyApp(pm, app) || isMediaOrAlternativePlayerApp(pm, app) || isGameApp(app)) {
                    immune.add(app.packageName)
                    Log.i(TAG, "Exempted Whitelist app dynamically: ${app.packageName}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error scanning installed apps for whitelisted packages", e)
        }

        // 3. Default Home Launcher
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val homeInfo = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            homeInfo?.activityInfo?.packageName?.let {
                immune.add(it)
                Log.d(TAG, "Resolved home launcher: $it")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving home launcher", e)
        }

        // 4. Input Method Editors (Keyboards: Gboard, SwiftKey, FlorisBoard, etc.)
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.inputMethodList?.forEach { imi ->
                val pkg = imi.packageName
                if (!AppSuspensionShield.isBlacklisted(pkg)) {
                    immune.add(pkg)
                    Log.d(TAG, "Resolved keyboard IME: $pkg")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving input methods", e)
        }

        // 5. Default Dialer (Phone)
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage?.let {
                immune.add(it)
                Log.d(TAG, "Resolved default dialer: $it")
            }
            val dialIntent = Intent(Intent.ACTION_DIAL)
            val dialResolve = pm.resolveActivity(dialIntent, PackageManager.MATCH_DEFAULT_ONLY)
            dialResolve?.activityInfo?.packageName?.let { immune.add(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving dialer", e)
        }

        // 6. Default SMS / Messages
        try {
            Telephony.Sms.getDefaultSmsPackage(context)?.let {
                immune.add(it)
                Log.d(TAG, "Resolved default SMS: $it")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving SMS package", e)
        }

        // 7. Camera Apps (STRICTLY excluding Gallery apps)
        try {
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            val cameraActivities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(cameraIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(cameraIntent, 0)
            }
            for (resolveInfo in cameraActivities) {
                val pkg = resolveInfo.activityInfo.packageName
                if (!CurfewConfig.EXCLUDED_GALLERY_PACKAGES.contains(pkg)) {
                    immune.add(pkg)
                    Log.d(TAG, "Resolved camera package: $pkg")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving camera packages", e)
        }

        // 8. Contacts Provider / App
        try {
            val contactsIntent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
            val contactsActivities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(contactsIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(contactsIntent, 0)
            }
            for (resolveInfo in contactsActivities) {
                immune.add(resolveInfo.activityInfo.packageName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving contacts packages", e)
        }

        // 9. Calculator, Clock, and Recorder
        try {
            immune.addAll(CurfewConfig.KNOWN_CALCULATOR_PACKAGES)
            immune.addAll(CurfewConfig.KNOWN_CLOCK_PACKAGES)
            immune.addAll(CurfewConfig.KNOWN_RECORDER_PACKAGES)

            // Dynamic Calculator
            val calcIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR)
            pm.resolveActivity(calcIntent, 0)?.activityInfo?.packageName?.let { immune.add(it) }

            // Dynamic Clock
            val clockIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            pm.resolveActivity(clockIntent, 0)?.activityInfo?.packageName?.let { immune.add(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving utility packages", e)
        }

        // Strictly ensure no blacklisted, app store, or curfew search loophole package ever gains immunity
        immune.removeAll { AppSuspensionShield.isBlacklisted(it) }
        immune.removeAll(CurfewConfig.KNOWN_APP_STORES)
        immune.removeAll(CurfewConfig.CURFEW_SEARCH_APPS)

        return immune
    }

    /**
     * Determines all installed user applications that must be suspended during curfew.
     * Encompasses all launchable activities, user-installed non-system apps, and Play Store / OEM App Stores.
     */
    fun getCurfewTargetPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val immunePackages = resolveImmunePackages(context)
        val targets = mutableSetOf<String>()

        // 1. All Launchable Applications
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchablePackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }.map { it.activityInfo.packageName }
            targets.addAll(launchablePackages)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying launchable activities", e)
        }

        // 2. All User-Installed Applications (Non-system 3rd party apps, including newly installed ones)
        try {
            val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            for (app in installedApps) {
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (!isSystem || isUpdatedSystem) {
                    targets.add(app.packageName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying installed applications", e)
        }

        // 3. OEM App Stores (Realme Market, Xiaomi, etc.) & Search Loophole Apps
        targets.addAll(CurfewConfig.OEM_APP_STORES)
        targets.addAll(CurfewConfig.CURFEW_SEARCH_APPS)

        // Filter out immune packages, self, Google Play Store, and Google Search.
        // Google Play Store and Google Search are kept unsuspended so games can access background IPC services,
        // while their UI is protected via CurfewForegroundWatcher.
        val finalTargets = targets
            .filterNot { immunePackages.contains(it) }
            .filterNot { it == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE }
            .filterNot { it == CurfewConfig.GOOGLE_SEARCH_PACKAGE }
            .toSet()
        Log.i(TAG, "Curfew target resolution: ${finalTargets.size} packages targeted for curfew")
        return finalTargets
    }
}
