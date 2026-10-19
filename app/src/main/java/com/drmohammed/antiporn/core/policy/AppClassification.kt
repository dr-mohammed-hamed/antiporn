package com.drmohammed.antiporn.core.policy

/**
 * Categorization of applications defining their security standing,
 * immunity status, curfew behavior, and restriction levels.
 */
enum class AppClassification {
    /**
     * Critical Android OS components and frameworks that must never be modified.
     */
    SYSTEM_CRITICAL,

    /**
     * Absolute immunity: AntiPorn app itself and verified custom communication (Puregram).
     * Guaranteed to never be suspended, uninstalled, or restricted under any circumstance.
     */
    ALWAYS_IMMUNE,

    /**
     * Permanent 24/7 high-threat blacklist: Telegram & forks, TikTok & clients,
     * proxy/bypass browsers (Tor, Firefox, DuckDuckGo), anonymous chat apps, and open social networks.
     * Subject to silent uninstallation, suspension, and hiding. NEVER exemptible.
     */
    PERMANENT_BLACKLIST,

    /**
     * Chromium-based browsers subject to enforced SafeSearch, YouTube Restricted Mode,
     * SafeSites filter, and disabled Incognito/Private browsing.
     */
    RESTRICTED_BROWSER,

    /**
     * Essential communication tools (Dialer, SMS, Contacts, Keyboards) exempt from curfew.
     */
    CURFEW_EXEMPT_COMMUNICATION,

    /**
     * Productivity, business (com.dr.tech.*), 2FA authenticators, financial, and utility tools.
     */
    CURFEW_EXEMPT_PRODUCTIVITY,

    /**
     * Quran, Tafsir, Athan, and Islamic daily remembrance applications.
     */
    CURFEW_EXEMPT_ISLAMIC,

    /**
     * Spoken audiobooks, podcasts, and clean media players (NewPipe, PureTube, Musicolet, AntennaPod).
     */
    CURFEW_EXEMPT_MEDIA,

    /**
     * Medical, clinical reference, and pediatric dosing applications for work/study.
     */
    CURFEW_EXEMPT_MEDICAL,

    /**
     * Whitelisted games and emulators explicitly permitted during curfew.
     */
    CURFEW_EXEMPT_GAME,

    /**
     * Third-party OEM App Stores (Realme, Samsung, Xiaomi, Huawei) hidden and suspended during curfew.
     */
    OEM_APP_STORE,

    /**
     * Search loophole apps (Google Go) suspended during curfew.
     */
    SEARCH_LOOPHOLE,

    /**
     * Google background IPC services (Play Store, Google Search) kept running for background IPC,
     * while their UI is guarded.
     */
    GOOGLE_IPC_SERVICE,

    /**
     * Default classification for arbitrary third-party user applications,
     * suspended during night curfew (20:00 - 07:00).
     */
    DEFAULT_CURFEW_TARGET
}
