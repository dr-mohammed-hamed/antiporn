package com.drmohammed.antiporn.core.policy

import java.util.concurrent.ConcurrentHashMap

/**
 * Single Source of Truth (SSOT) and Central Policy Engine for all applications in AntiPorn.
 *
 * Developers only need to modify this file to add, whitelist, or blacklist any application.
 * Supports exact package names and wildcards (e.g. "org.schabi.newpipe.*", "com.dr.tech.*").
 *
 * Enforces Strict Security Hierarchy:
 * 1. ALWAYS_IMMUNE (AntiPorn, Puregram) -> Never touched, suspended, or uninstalled.
 * 2. PERMANENT_BLACKLIST (Telegram, TikTok, Proxy Browsers) -> Frozen and silently uninstalled 24/7. Never exemptible.
 * 3. RESTRICTED_BROWSER (Chromium Browsers) -> Managed policies (SafeSearch, no incognito).
 * 4. CURFEW_EXEMPT (Business, Quran, Media, Medical, Games) -> Allowed during night curfew.
 * 5. DEFAULT_CURFEW_TARGET -> All other apps suspended at night (20:00 - 07:00).
 */
object AppPolicyRegistry {

    val RULES: List<AppRule> = listOf(
        // =========================================================================
        // 1. ABSOLUTE IMMUNITY (NEVER SUSPEND, NEVER UNINSTALL)
        // =========================================================================
        AppRule("com.drmohammed.antiporn", AppClassification.ALWAYS_IMMUNE, "AntiPorn Self-Protection"),
        AppRule("org.puregram.messenger", AppClassification.ALWAYS_IMMUNE, "Puregram Custom Messenger"),

        // =========================================================================
        // 2. SYSTEM CRITICAL FRAMEWORKS
        // =========================================================================
        AppRule("android", AppClassification.SYSTEM_CRITICAL, "Android Core Framework"),
        AppRule("com.android.systemui", AppClassification.SYSTEM_CRITICAL, "System UI"),
        AppRule("com.google.android.gms", AppClassification.SYSTEM_CRITICAL, "Google Play Services"),
        AppRule("com.google.android.gsf", AppClassification.SYSTEM_CRITICAL, "Google Services Framework"),
        AppRule("com.android.settings", AppClassification.SYSTEM_CRITICAL, "System Settings"),
        AppRule("com.google.android.packageinstaller", AppClassification.SYSTEM_CRITICAL, "Google Package Installer"),
        AppRule("com.android.packageinstaller", AppClassification.SYSTEM_CRITICAL, "AOSP Package Installer"),
        AppRule("com.coloros.packageinstaller", AppClassification.SYSTEM_CRITICAL, "ColorOS / Realme Package Installer"),
        AppRule("com.oplus.packageinstaller", AppClassification.SYSTEM_CRITICAL, "OPlus Package Installer"),
        AppRule("com.coloros.safecenter", AppClassification.SYSTEM_CRITICAL, "ColorOS Security Center"),
        AppRule("com.samsung.android.packageinstaller", AppClassification.SYSTEM_CRITICAL, "Samsung Package Installer"),
        AppRule("com.miui.packageinstaller", AppClassification.SYSTEM_CRITICAL, "MIUI Package Installer"),

        // =========================================================================
        // 3. GOOGLE IPC SERVICES (Kept unsuspended for background licensing & AI)
        // =========================================================================
        AppRule("com.android.vending", AppClassification.GOOGLE_IPC_SERVICE, "Google Play Store IPC Backend"),
        AppRule("com.google.android.googlequicksearchbox", AppClassification.GOOGLE_IPC_SERVICE, "Google Search Assistant IPC"),

        // =========================================================================
        // 4. PERMANENT BLACKLIST: TELEGRAM CLIENTS & FORKS
        // =========================================================================
        AppRule("org.telegram.messenger", AppClassification.PERMANENT_BLACKLIST, "Telegram Official"),
        AppRule("org.telegram.messenger.web", AppClassification.PERMANENT_BLACKLIST, "Telegram Web APK"),
        AppRule("org.telegram.messenger.beta", AppClassification.PERMANENT_BLACKLIST, "Telegram Beta"),
        AppRule("org.telegram.plus", AppClassification.PERMANENT_BLACKLIST, "Plus Messenger"),
        AppRule("nekox.messenger", AppClassification.PERMANENT_BLACKLIST, "NekoX"),
        AppRule("org.thunderdog.challegram", AppClassification.PERMANENT_BLACKLIST, "Telegram X"),
        AppRule("com.iMe.android", AppClassification.PERMANENT_BLACKLIST, "iMe Messenger"),
        AppRule("org.aka.messenger", AppClassification.PERMANENT_BLACKLIST, "Turrit / Aka"),
        AppRule("com.turrit.telegram", AppClassification.PERMANENT_BLACKLIST, "Turrit"),
        AppRule("org.vidogram.messenger", AppClassification.PERMANENT_BLACKLIST, "Vidogram"),
        AppRule("org.telegram.BNavigation", AppClassification.PERMANENT_BLACKLIST, "Telegram BNavigation"),

        // =========================================================================
        // 5. PERMANENT BLACKLIST: OPEN SOCIAL NETWORKS (Reddit, Twitter, Tumblr, VK)
        // =========================================================================
        AppRule("com.reddit.frontpage", AppClassification.PERMANENT_BLACKLIST, "Reddit Official"),
        AppRule("com.rubenmayayo.reddit", AppClassification.PERMANENT_BLACKLIST, "Boost for Reddit"),
        AppRule("com.laurencedawson.reddit_sync", AppClassification.PERMANENT_BLACKLIST, "Sync for Reddit"),
        AppRule("ml.docilealligator.infinityforreddit", AppClassification.PERMANENT_BLACKLIST, "Infinity for Reddit"),
        AppRule("free.reddit.news", AppClassification.PERMANENT_BLACKLIST, "Relay for Reddit"),
        AppRule("com.andrewshu.android.reddit", AppClassification.PERMANENT_BLACKLIST, "RIF is fun"),
        AppRule("com.twitter.android", AppClassification.PERMANENT_BLACKLIST, "Twitter / X"),
        AppRule("com.twitter.android.lite", AppClassification.PERMANENT_BLACKLIST, "Twitter / X Lite"),
        AppRule("com.tumblr", AppClassification.PERMANENT_BLACKLIST, "Tumblr"),
        AppRule("com.vkontakte.android", AppClassification.PERMANENT_BLACKLIST, "VK Official"),
        AppRule("com.vk.im", AppClassification.PERMANENT_BLACKLIST, "VK Me Messenger"),

        // =========================================================================
        // 6. PERMANENT BLACKLIST: PROXY & BYPASS BROWSERS
        // =========================================================================
        AppRule("org.mozilla.firefox", AppClassification.PERMANENT_BLACKLIST, "Firefox Browser"),
        AppRule("org.mozilla.firefox_beta", AppClassification.PERMANENT_BLACKLIST, "Firefox Beta"),
        AppRule("org.mozilla.fenix", AppClassification.PERMANENT_BLACKLIST, "Firefox Fenix"),
        AppRule("org.torproject.torbrowser", AppClassification.PERMANENT_BLACKLIST, "Tor Browser"),
        AppRule("com.duckduckgo.mobile.android", AppClassification.PERMANENT_BLACKLIST, "DuckDuckGo Browser"),
        AppRule("com.cloudmosa.puffinFree", AppClassification.PERMANENT_BLACKLIST, "Puffin Free Browser"),
        AppRule("com.cloudmosa.puffin", AppClassification.PERMANENT_BLACKLIST, "Puffin Browser"),
        AppRule("com.kiwibrowser.browser", AppClassification.PERMANENT_BLACKLIST, "Kiwi Browser"),
        AppRule("com.opera.touch", AppClassification.PERMANENT_BLACKLIST, "Opera Touch"),
        AppRule("com.opera.browser", AppClassification.PERMANENT_BLACKLIST, "Opera Browser with Free VPN"),
        AppRule("com.alohamobile.browser", AppClassification.PERMANENT_BLACKLIST, "Aloha Browser"),
        AppRule("com.alohamobile.browser.lite", AppClassification.PERMANENT_BLACKLIST, "Aloha Lite Browser"),
        AppRule("com.UCMobile.intl", AppClassification.PERMANENT_BLACKLIST, "UC Browser International"),
        AppRule("com.uc.browser.en", AppClassification.PERMANENT_BLACKLIST, "UC Browser English"),

        // =========================================================================
        // 7. PERMANENT BLACKLIST: RANDOM & ANONYMOUS CHAT APPS
        // =========================================================================
        AppRule("chat.ometv", AppClassification.PERMANENT_BLACKLIST, "OmeTV"),
        AppRule("com.monkey.cool", AppClassification.PERMANENT_BLACKLIST, "Monkey Cool"),
        AppRule("com.chatous.chatous", AppClassification.PERMANENT_BLACKLIST, "Chatous"),
        AppRule("com.kik.android", AppClassification.PERMANENT_BLACKLIST, "Kik Messenger"),
        AppRule("com.camfrog.android.phone", AppClassification.PERMANENT_BLACKLIST, "Camfrog"),
        AppRule("com.connected2.me", AppClassification.PERMANENT_BLACKLIST, "Connected2.me"),
        AppRule("com.azar.live", AppClassification.PERMANENT_BLACKLIST, "Azar"),
        AppRule("com.livu.live", AppClassification.PERMANENT_BLACKLIST, "LivU"),

        // =========================================================================
        // 8. PERMANENT BLACKLIST: TIKTOK VARIANTS & CLIENTS
        // =========================================================================
        AppRule("com.zhiliaoapp.musically", AppClassification.PERMANENT_BLACKLIST, "TikTok Global"),
        AppRule("com.zhiliaoapp.musically.go", AppClassification.PERMANENT_BLACKLIST, "TikTok Lite"),
        AppRule("com.ss.android.ugc.trill", AppClassification.PERMANENT_BLACKLIST, "TikTok Asia"),
        AppRule("com.ss.android.ugc.aweme", AppClassification.PERMANENT_BLACKLIST, "Douyin TikTok China"),
        AppRule("com.ss.android.ugc.aweme.lite", AppClassification.PERMANENT_BLACKLIST, "Douyin Lite"),
        AppRule("com.ss.android.ugc.aweme.mobile", AppClassification.PERMANENT_BLACKLIST, "Douyin Mobile"),
        AppRule("com.zhiliao.musically", AppClassification.PERMANENT_BLACKLIST, "Musical.ly Legacy"),

        // =========================================================================
        // 9. RESTRICTED CHROMIUM BROWSERS (InPrivate disabled, SafeSearch locked)
        // =========================================================================
        AppRule("com.android.chrome", AppClassification.RESTRICTED_BROWSER, "Google Chrome"),
        AppRule("com.chrome.beta", AppClassification.RESTRICTED_BROWSER, "Google Chrome Beta"),
        AppRule("com.chrome.dev", AppClassification.RESTRICTED_BROWSER, "Google Chrome Dev"),
        AppRule("com.chrome.canary", AppClassification.RESTRICTED_BROWSER, "Google Chrome Canary"),
        AppRule("com.brave.browser", AppClassification.RESTRICTED_BROWSER, "Brave Browser"),
        AppRule("com.brave.browser_beta", AppClassification.RESTRICTED_BROWSER, "Brave Beta"),
        AppRule("com.brave.browser_nightly", AppClassification.RESTRICTED_BROWSER, "Brave Nightly"),
        AppRule("com.microsoft.emmx", AppClassification.RESTRICTED_BROWSER, "Microsoft Edge"),
        AppRule("com.microsoft.emmx.canary", AppClassification.RESTRICTED_BROWSER, "Microsoft Edge Canary"),
        AppRule("com.sec.android.app.sbrowser", AppClassification.RESTRICTED_BROWSER, "Samsung Internet"),
        AppRule("com.opera.mini.native", AppClassification.RESTRICTED_BROWSER, "Opera Mini"),
        AppRule("com.vivaldi.browser", AppClassification.RESTRICTED_BROWSER, "Vivaldi Browser"),

        // =========================================================================
        // 10. OEM APP STORES (Suspended and hidden during night curfew)
        // =========================================================================
        AppRule("com.heytap.market", AppClassification.OEM_APP_STORE, "Realme / Oppo App Market"),
        AppRule("com.oppo.market", AppClassification.OEM_APP_STORE, "Oppo Market"),
        AppRule("com.sec.android.app.samsungapps", AppClassification.OEM_APP_STORE, "Samsung Galaxy Store"),
        AppRule("com.xiaomi.mipicks", AppClassification.OEM_APP_STORE, "Xiaomi GetApps"),
        AppRule("com.huawei.appmarket", AppClassification.OEM_APP_STORE, "Huawei AppGallery"),

        // =========================================================================
        // 11. SEARCH LOOPHOLE APPS (Suspended during curfew)
        // =========================================================================
        AppRule("com.google.android.apps.searchlite", AppClassification.SEARCH_LOOPHOLE, "Google Go Search Lite"),

        // =========================================================================
        // 12. CURFEW EXEMPT: USER CUSTOM BUSINESS & PRODUCTIVITY (com.dr.tech.*)
        // =========================================================================
        AppRule("com.dr.tech.*", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "User Business Suite Wildcard"),
        AppRule("com.dr.tech.daftar_albahr", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Daftar Al-Bahr Micro-ERP"),
        AppRule("com.dr.tech.hafeez_pos_micro_erp", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Hafeez POS Micro-ERP"),

        // =========================================================================
        // 13. CURFEW EXEMPT: CLEAN MEDIA PLAYERS & ALTERNATIVE YOUTUBE (NewPipe & Forks)
        // =========================================================================
        AppRule("com.dr.tech.puretube", AppClassification.CURFEW_EXEMPT_MEDIA, "PureTube YouTube Player"),
        AppRule("org.schabi.newpipe.*", AppClassification.CURFEW_EXEMPT_MEDIA, "NewPipe Official, Debug & Forks Wildcard"),
        AppRule("org.schabi.newpipe", AppClassification.CURFEW_EXEMPT_MEDIA, "NewPipe Official Client"),
        AppRule("org.schabi.newpipe.debug", AppClassification.CURFEW_EXEMPT_MEDIA, "NewPipe Debug / Custom Variant"),
        AppRule("org.polymorphicshade.tubular", AppClassification.CURFEW_EXEMPT_MEDIA, "Tubular NewPipe Fork"),
        AppRule("in.krosbits.musicolet", AppClassification.CURFEW_EXEMPT_MEDIA, "Musicolet Music Player"),
        AppRule("com.mxtech.videoplayer.ad", AppClassification.CURFEW_EXEMPT_MEDIA, "MX Player Free"),
        AppRule("com.mxtech.videoplayer.pro", AppClassification.CURFEW_EXEMPT_MEDIA, "MX Player Pro"),
        AppRule("com.mxtech.videoplayer.*", AppClassification.CURFEW_EXEMPT_MEDIA, "MX Player Wildcard"),
        AppRule("de.ph1b.audiobook", AppClassification.CURFEW_EXEMPT_MEDIA, "Voice Audiobook Player"),
        AppRule("de.danoeh.antennapod", AppClassification.CURFEW_EXEMPT_MEDIA, "AntennaPod Podcast Player"),
        AppRule("ak.alizandro.smartaudiobookplayer", AppClassification.CURFEW_EXEMPT_MEDIA, "Smart AudioBook Player"),
        AppRule("com.storytel.app", AppClassification.CURFEW_EXEMPT_MEDIA, "Storytel"),
        AppRule("com.wajeez", AppClassification.CURFEW_EXEMPT_MEDIA, "Wajeez Books"),
        AppRule("com.akhdar", AppClassification.CURFEW_EXEMPT_MEDIA, "Akhdar Book Summaries"),
        AppRule("com.abjjad", AppClassification.CURFEW_EXEMPT_MEDIA, "Abjjad Reading"),
        AppRule("com.rufoof.app", AppClassification.CURFEW_EXEMPT_MEDIA, "Rufoof Books"),
        AppRule("com.dhad", AppClassification.CURFEW_EXEMPT_MEDIA, "Dhad Audiobooks"),

        // =========================================================================
        // 14. CURFEW EXEMPT: QURAN & ISLAMIC APPLICATIONS
        // =========================================================================
        AppRule("com.islamicapp.moshafy", AppClassification.CURFEW_EXEMPT_ISLAMIC, "Moshafy Quran"),
        AppRule("com.quran.labs.androidquran", AppClassification.CURFEW_EXEMPT_ISLAMIC, "Quran Android Labs"),
        AppRule("com.simppro.quran.tafseer.offline", AppClassification.CURFEW_EXEMPT_ISLAMIC, "Quran Tafseer Offline"),
        AppRule("com.simppro.quran.offline", AppClassification.CURFEW_EXEMPT_ISLAMIC, "Quran Offline"),
        AppRule("com.surahapp", AppClassification.CURFEW_EXEMPT_ISLAMIC, "Surah App"),
        AppRule("com.simt.ayah", AppClassification.CURFEW_EXEMPT_ISLAMIC, "Ayah Quran"),
        AppRule("com.madina.quran", AppClassification.CURFEW_EXEMPT_ISLAMIC, "Madina Quran"),
        AppRule("com.quran.karem", AppClassification.CURFEW_EXEMPT_ISLAMIC, "Quran Kareem"),
        AppRule("com.surah.quran", AppClassification.CURFEW_EXEMPT_ISLAMIC, "Surah Quran"),
        AppRule("com.athanotify", AppClassification.CURFEW_EXEMPT_ISLAMIC, "Athanotify Prayer Times"),

        // =========================================================================
        // 15. CURFEW EXEMPT: MEDICAL & PEDIATRIC DOSING (Work & Study)
        // =========================================================================
        AppRule("com.companyname.pedi_dose_app", AppClassification.CURFEW_EXEMPT_MEDICAL, "Pedi Dose"),
        AppRule("com.medscape.android", AppClassification.CURFEW_EXEMPT_MEDICAL, "Medscape"),
        AppRule("com.uptodate.android", AppClassification.CURFEW_EXEMPT_MEDICAL, "UpToDate"),
        AppRule("com.lexicomp.android", AppClassification.CURFEW_EXEMPT_MEDICAL, "Lexicomp"),

        // =========================================================================
        // 16. CURFEW EXEMPT: 2FA AUTHENTICATORS, SECURITY & DAILY UTILITIES
        // =========================================================================
        AppRule("com.authy.authy", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Twilio Authy"),
        AppRule("com.google.android.apps.authenticator2", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Google Authenticator"),
        AppRule("com.azure.authenticator", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Microsoft Authenticator"),
        AppRule("org.fedorahosted.freeotp", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "FreeOTP"),
        AppRule("com.duosecurity.duomobile", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Duo Mobile"),
        AppRule("com.egyptianbanks.instapay", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "InstaPay Egypt"),
        AppRule("com.xodo.pdf.reader", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Xodo PDF Reader"),
        AppRule("com.google.android.keep", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Google Keep Notes"),
        AppRule("com.ticktick.task", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "TickTick To-Do & Tasks"),
        AppRule("com.ticktick.*", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "TickTick Wildcard"),
        AppRule("com.google.android.apps.kids.familylinkhelper", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Family Link Helper"),
        AppRule("com.google.android.apps.kids.familylink", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Family Link Parent"),
        // AI Assistants (Suspended during curfew to prevent night browsing and YouTube link loopholes)
        AppRule("com.google.android.apps.bard", AppClassification.DEFAULT_CURFEW_TARGET, "Google Gemini AI Assistant"),

        // GCam Cameras
        AppRule("com.googleCamera.Wichaya8", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "GCam Wichaya"),
        AppRule("com.android.MGC", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "GCam MGC"),
        AppRule("org.codeaurora.snapcam", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Snap Camera"),

        // =========================================================================
        // CURFEW EXEMPT: ESSENTIAL COMMUNICATION (Calls & Family Messages)
        // =========================================================================
        AppRule("com.whatsapp", AppClassification.CURFEW_EXEMPT_COMMUNICATION, "WhatsApp Messenger"),

        // =========================================================================
        // 17. CURFEW EXEMPT: PERMITTED GAMES & EMULATORS
        // =========================================================================
        AppRule("com.axlebolt.standoff2", AppClassification.CURFEW_EXEMPT_GAME, "Standoff 2 FPS"),
        AppRule("com.AlfaBravo.CombatMaster", AppClassification.CURFEW_EXEMPT_GAME, "Combat Master FPS"),
        AppRule("com.nekki.shadowfightarena", AppClassification.CURFEW_EXEMPT_GAME, "Shadow Fight 4 Arena"),
        AppRule("com.zeptolab.bulletecho.google", AppClassification.CURFEW_EXEMPT_GAME, "Bullet Echo PvP"),
        AppRule("com.smokingguninc.phobies", AppClassification.CURFEW_EXEMPT_GAME, "Phobies Strategy"),
        AppRule("yio.tro.antiyoy.android", AppClassification.CURFEW_EXEMPT_GAME, "Antiyoy Strategy"),
        AppRule("air.com.RustyLake.CubeEscapeCollection", AppClassification.CURFEW_EXEMPT_GAME, "Cube Escape Collection"),
        AppRule("org.ppsspp.ppsspp", AppClassification.CURFEW_EXEMPT_GAME, "PPSSPP PSP Emulator"),
        AppRule("xyz.aethersx2.android", AppClassification.CURFEW_EXEMPT_GAME, "AetherSX2 PS2 Emulator"),
        AppRule("com.frogmind.badland", AppClassification.CURFEW_EXEMPT_GAME, "BADLAND Adventure"),
        AppRule("com.nvsgames.snap", AppClassification.CURFEW_EXEMPT_GAME, "MARVEL SNAP Card Strategy"),
        AppRule("com.coloros.gamespaceui", AppClassification.CURFEW_EXEMPT_GAME, "Realme/OPPO Game Space UI"),

        // =========================================================================
        // 18. SYSTEM CALCULATORS, CLOCKS, AND SOUND RECORDERS
        // =========================================================================
        AppRule("com.google.android.calculator", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Google Calculator"),
        AppRule("com.coloros.calculator", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "ColorOS Calculator"),
        AppRule("com.oplus.calculator", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "OPlus Calculator"),
        AppRule("com.sec.android.app.popupcalculator", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Samsung Calculator"),
        AppRule("com.android.calculator2", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "AOSP Calculator"),
        AppRule("com.miui.calculator", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "MIUI Calculator"),

        AppRule("com.google.android.deskclock", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Google Clock"),
        AppRule("com.coloros.alarmclock", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "ColorOS Clock"),
        AppRule("com.oplus.alarmclock", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "OPlus Clock"),
        AppRule("com.sec.android.app.clockpackage", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Samsung Clock"),
        AppRule("com.android.deskclock", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "AOSP Clock"),
        AppRule("com.asus.deskclock", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Asus Clock"),

        AppRule("com.google.android.apps.recorder", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Google Recorder"),
        AppRule("com.coloros.soundrecorder", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "ColorOS Recorder"),
        AppRule("com.oplus.soundrecorder", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "OPlus Recorder"),
        AppRule("com.sec.android.app.voicenote", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "Samsung Voice Note"),
        AppRule("com.android.soundrecorder", AppClassification.CURFEW_EXEMPT_PRODUCTIVITY, "AOSP Sound Recorder")
    )

    // Precomputed exact-match maps for high-speed O(1) resolution
    private val exactMatchRules: Map<String, AppRule> = RULES
        .filterNot { it.isWildcard }
        .associateBy { it.pattern.lowercase() }

    // Precompiled wildcard rules
    private val wildcardRules: List<AppRule> = RULES.filter { it.isWildcard }

    // Dynamic cache for query results
    private val classificationCache = ConcurrentHashMap<String, AppClassification>()

    /**
     * Resolves the primary classification of an application package adhering to strict hierarchy.
     */
    fun classify(packageName: String): AppClassification {
        val lowerPkg = packageName.lowercase()
        return classificationCache.computeIfAbsent(lowerPkg) { pkg ->
            // Priority 0: Always Immune
            if (pkg == "com.drmohammed.antiporn" || pkg == "org.puregram.messenger") {
                return@computeIfAbsent AppClassification.ALWAYS_IMMUNE
            }

            // Check exact matches
            exactMatchRules[pkg]?.let { rule ->
                return@computeIfAbsent rule.classification
            }

            // Check wildcard rules in registered order
            for (rule in wildcardRules) {
                if (rule.matches(pkg)) {
                    return@computeIfAbsent rule.classification
                }
            }

            AppClassification.DEFAULT_CURFEW_TARGET
        }
    }

    /**
     * Determines whether an application has absolute immunity from suspension, hiding, or uninstallation.
     */
    fun isImmune(packageName: String): Boolean {
        val cls = classify(packageName)
        return cls == AppClassification.ALWAYS_IMMUNE || cls == AppClassification.SYSTEM_CRITICAL
    }

    /**
     * Determines whether an application is a permanent 24/7 security threat (Telegram, TikTok, Proxy Browsers).
     */
    fun isPermanentlyBlacklisted(packageName: String): Boolean {
        // Under Unified Zero-Trust architecture, no package is permanently blacklisted or silently uninstalled;
        // all non-system applications are subject to Zero-Trust freeze until supervisor approval.
        return false
    }

    /**
     * Determines whether an application is exempt from night curfew suspensions.
     */
    fun isExemptFromCurfew(packageName: String): Boolean {
        if (packageName.equals("org.puregram.messenger", ignoreCase = true)) return false
        if (isPermanentlyBlacklisted(packageName)) return false
        val cls = classify(packageName)
        return when (cls) {
            AppClassification.ALWAYS_IMMUNE -> packageName.equals("com.drmohammed.antiporn", ignoreCase = true)
            AppClassification.SYSTEM_CRITICAL,
            AppClassification.CURFEW_EXEMPT_COMMUNICATION,
            AppClassification.CURFEW_EXEMPT_PRODUCTIVITY,
            AppClassification.CURFEW_EXEMPT_ISLAMIC,
            AppClassification.CURFEW_EXEMPT_MEDIA,
            AppClassification.CURFEW_EXEMPT_MEDICAL,
            AppClassification.CURFEW_EXEMPT_GAME -> true
            else -> false
        }
    }

    /**
     * Determines whether an application is a restricted Chromium browser.
     */
    fun isRestrictedBrowser(packageName: String): Boolean {
        return classify(packageName) == AppClassification.RESTRICTED_BROWSER
    }

    /**
     * Determines whether an application is an OEM App Store (Realme, Samsung, etc.).
     */
    fun isOemAppStore(packageName: String): Boolean {
        return classify(packageName) == AppClassification.OEM_APP_STORE
    }

    /**
     * Determines whether an application is an App Store (OEM or Google Play Store).
     */
    fun isAppStore(packageName: String): Boolean {
        return isOemAppStore(packageName) || packageName.equals("com.android.vending", ignoreCase = true)
    }

    /**
     * Determines whether an application is a search loophole app (Google Go).
     */
    fun isSearchLoophole(packageName: String): Boolean {
        return classify(packageName) == AppClassification.SEARCH_LOOPHOLE
    }

    /**
     * Returns all explicit immune package names.
     */
    fun getImmunePackages(): Set<String> {
        return RULES.filter { it.classification == AppClassification.ALWAYS_IMMUNE && !it.isWildcard }
            .map { it.pattern }.toSet()
    }

    /**
     * Returns all explicit blacklisted packages.
     */
    fun getBlacklistedPackages(): Set<String> {
        // Under Unified Zero-Trust architecture, blacklist is empty to prevent automatic silent uninstallations.
        return emptySet()
    }

    /**
     * Returns all explicit target browsers for Chromium restrictions.
     */
    fun getTargetBrowserPackages(): List<String> {
        return RULES.filter { it.classification == AppClassification.RESTRICTED_BROWSER && !it.isWildcard }
            .map { it.pattern }
    }

    /**
     * Returns all explicit curfew static whitelist packages.
     */
    fun getCurfewStaticWhitelist(): Set<String> {
        return RULES.filter { !it.isWildcard && isExemptFromCurfew(it.pattern) }
            .map { it.pattern }.toSet()
    }

    /**
     * Returns all OEM app store packages.
     */
    fun getOemAppStores(): Set<String> {
        return RULES.filter { it.classification == AppClassification.OEM_APP_STORE && !it.isWildcard }
            .map { it.pattern }.toSet()
    }

    /**
     * Returns all system critical framework packages.
     */
    fun getSystemCriticalPackages(): Set<String> {
        return RULES.filter { it.classification == AppClassification.SYSTEM_CRITICAL && !it.isWildcard }
            .map { it.pattern }.toSet()
    }

    /**
     * Clears cached classification lookups (useful for unit testing or dynamic reloads).
     */
    fun clearCache() {
        classificationCache.clear()
    }
}
