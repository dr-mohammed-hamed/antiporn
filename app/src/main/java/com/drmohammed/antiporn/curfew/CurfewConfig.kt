package com.drmohammed.antiporn.curfew

/**
 * Configuration and immutable constants for the Scheduled Night Curfew system.
 * Curfew window: 21:30 (9:30 PM) to 07:00 (7:00 AM) every day.
 */
object CurfewConfig {

    const val START_HOUR = 21
    const val START_MINUTE = 30

    const val END_HOUR = 7
    const val END_MINUTE = 0

    const val PREFS_NAME = "antiporn_curfew_prefs"
    const val KEY_CURFEW_ENABLED = "key_curfew_enabled"
    const val KEY_CURFEW_SUSPENDED_PACKAGES = "key_curfew_suspended_packages"
    const val KEY_MANUAL_TEST_ACTIVE = "key_manual_test_active"

    /**
     * Explicit Whitelist of user productivity, financial, religious, and essential applications.
     * These apps must NEVER be suspended during curfew.
     */
    val STATIC_WHITELIST: Set<String> = setOf(
        // User Custom Business Apps
        "com.dr.tech.daftar_albahr",           // دفتر البحر
        "com.dr.tech.hafeez_pos_micro_erp",    // حفيظ

        // Quran & Islamic Apps
        "com.islamicapp.moshafy",              // مصحفي
        "com.quran.labs.androidquran",         // Quran Android
        "com.simppro.quran.tafseer.offline",   // المصحف والتفسير
        "com.simppro.quran.offline",           // المصحف الشريف
        "com.surahapp",                        // سورة
        "com.simt.ayah",                       // آية Ayah
        "com.madina.quran",                    // مصحف المدينة
        "com.quran.karem",                     // القرآن الكريم
        "com.surah.quran",                     // سور القرآن
        "com.athanotify",                      // أذان نوتيفاي Athanotify

        // Medical & Pediatric Apps (Work & Study)
        "com.companyname.pedi_dose_app",       // Pedi Dose
        "com.medscape.android",                // Medscape
        "com.uptodate.android",                // UpToDate
        "com.lexicomp.android",                // Lexicomp

        // Audiobooks & Podcasts
        "de.ph1b.audiobook",                   // Voice Audiobook Player
        "de.danoeh.antennapod",                // AntennaPod Podcast Player
        "ak.alizandro.smartaudiobookplayer",   // Smart AudioBook Player
        "com.storytel.app",                    // Storytel
        "com.wajeez",                          // Wajeez
        "com.akhdar",                          // Akhdar
        "com.abjjad",                          // Abjjad
        "com.rufoof.app",                      // Rufoof
        "com.dhad",                            // Dhad

        // Camera Apps (Device Specific GCams)
        "com.googleCamera.Wichaya8",           // GCam Wichaya
        "com.android.MGC",                     // GCam MGC
        "org.codeaurora.snapcam",              // Snap Camera

        // Two-Factor Authentication (2FA) & Security
        "com.authy.authy",                     // Twilio Authy
        "com.google.android.apps.authenticator2", // Google Authenticator
        "com.azure.authenticator",             // Microsoft Authenticator
        "org.fedorahosted.freeotp",            // FreeOTP
        "com.duosecurity.duomobile",           // Duo Mobile

        // Essential Financial & Daily Tools
        "com.egyptianbanks.instapay",          // InstaPay Egypt
        "in.krosbits.musicolet",               // Musicolet Audio Player
        "com.xodo.pdf.reader",                 // Xodo PDF Reader & Editor
        "com.google.android.keep",             // Google Keep Notes
        "com.google.android.apps.kids.familylinkhelper", // Family Link
        "com.google.android.apps.kids.familylink",       // Family Link Parent

        // Device Management Self-Protection
        "com.drmohammed.antiporn"              // AntiPorn Guard
    )

    /**
     * Known vendor calculator packages.
     */
    val KNOWN_CALCULATOR_PACKAGES: Set<String> = setOf(
        "com.google.android.calculator",
        "com.coloros.calculator",
        "com.oplus.calculator",
        "com.sec.android.app.popupcalculator",
        "com.android.calculator2",
        "com.miui.calculator"
    )

    /**
     * Known vendor clock and alarm packages.
     */
    val KNOWN_CLOCK_PACKAGES: Set<String> = setOf(
        "com.google.android.deskclock",
        "com.coloros.alarmclock",
        "com.oplus.alarmclock",
        "com.sec.android.app.clockpackage",
        "com.android.deskclock",
        "com.asus.deskclock"
    )

    /**
     * Known vendor voice and sound recorder packages.
     */
    val KNOWN_RECORDER_PACKAGES: Set<String> = setOf(
        "com.google.android.apps.recorder",
        "com.coloros.soundrecorder",
        "com.oplus.soundrecorder",
        "com.sec.android.app.voicenote",
        "com.android.soundrecorder"
    )

    /**
     * Explicit Gallery packages that must NEVER be treated as Camera.
     * Guaranteed to be blocked during curfew even if vendor binds gallery to camera app.
     */
    val EXCLUDED_GALLERY_PACKAGES: Set<String> = setOf(
        "com.coloros.gallery3d",
        "com.oplus.gallery",
        "com.google.android.apps.photos",
        "com.sec.android.gallery3d",
        "com.android.gallery3d",
        "com.miui.gallery"
    )

    /**
     * Critical system framework packages that must NEVER be suspended.
     */
    val SYSTEM_CRITICAL_PACKAGES: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    /**
     * App Stores that must be suspended AND hidden during curfew.
     */
    val KNOWN_APP_STORES: Set<String> = setOf(
        "com.android.vending",               // Google Play Store
        "com.heytap.market",                 // Realme / Oppo App Market
        "com.oppo.market",
        "com.sec.android.app.samsungapps",   // Samsung Galaxy Store
        "com.xiaomi.mipicks",                // Xiaomi GetApps
        "com.huawei.appmarket"               // Huawei AppGallery
    )

    /**
     * Search loophole apps (Google App / Google Go) that must be hidden and suspended
     * during curfew only, exactly like Play Store, and restored in the morning.
     */
    val CURFEW_SEARCH_APPS: Set<String> = setOf(
        "com.google.android.googlequicksearchbox", // Google App (تطبيق بحث جوجل)
        "com.google.android.apps.searchlite"        // Google Go
    )
}
