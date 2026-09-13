package com.drmohammed.antiporn.curfew

import java.util.Calendar
import java.util.TimeZone

/**
 * Configuration and immutable constants for the Scheduled Night Curfew system.
 * Curfew window: 20:30 (8:30 PM) to 07:00 (7:00 AM) every day.
 * Timezone: Asia/Riyadh (Makkah Al-Mukarramah, UTC+3).
 */
object CurfewConfig {

    const val MAKKAH_TIMEZONE_ID = "Asia/Riyadh"

    fun getCurfewCalendar(): Calendar = Calendar.getInstance(TimeZone.getTimeZone(MAKKAH_TIMEZONE_ID))

    const val START_HOUR = 20
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
        "com.dr.tech.puretube",                // PureTube (Alternative YouTube Player)
        "org.schabi.newpipe",                  // NewPipe (Official)
        "org.schabi.newpipe.debug",            // NewPipe (Modified / Debug variant)
        "org.polymorphicshade.tubular",        // Tubular (NewPipe Fork)

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
        "com.google.android.apps.bard",        // Google Gemini (AI Assistant)

        // Device Management Self-Protection
        "com.drmohammed.antiporn",             // AntiPorn Guard

        // Exempted Games & Emulators
        "com.axlebolt.standoff2",              // Standoff 2 (Shooting Game)
        "com.AlfaBravo.CombatMaster",          // Combat Master (FPS Shooting Game)
        "com.nekki.shadowfightarena",          // Shadow Fight 4: Arena (Fighting Game)
        "com.zeptolab.bulletecho.google",      // Bullet Echo (Tactical PvP Shooter Game)
        "com.smokingguninc.phobies",           // Phobies (Turn-based Strategy Game)
        "yio.tro.antiyoy.android",             // Antiyoy (Turn-based Strategy Game)
        "air.com.RustyLake.CubeEscapeCollection", // Cube Escape Collection (Puzzle Game)
        "org.ppsspp.ppsspp",                   // PPSSPP (PSP Games Emulator)
        "xyz.aethersx2.android",               // AetherSX2 (PS2 Games Emulator)
        "com.frogmind.badland",                // BADLAND (Physics Adventure Game)
        "com.nvsgames.snap",                   // MARVEL SNAP (Card Strategy Game)
        "com.coloros.gamespaceui"              // Game Space UI (Realme/OPPO Game Assistant)
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

    const val GOOGLE_PLAY_STORE_PACKAGE = "com.android.vending"
    const val GOOGLE_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"

    /**
     * Third-party OEM App Stores that must be suspended AND hidden during curfew.
     * Note: Google Play Store is kept unhidden and unsuspended so games can access its
     * background licensing, Play Games, and In-App Billing IPC services.
     * Play Store UI is locked via CurfewForegroundWatcher (auto-kickout) and DISALLOW_INSTALL_APPS.
     */
    val OEM_APP_STORES: Set<String> = setOf(
        "com.heytap.market",                 // Realme / Oppo App Market
        "com.oppo.market",
        "com.sec.android.app.samsungapps",   // Samsung Galaxy Store
        "com.xiaomi.mipicks",                // Xiaomi GetApps
        "com.huawei.appmarket"               // Huawei AppGallery
    )

    /**
     * All known app stores (OEM stores + Google Play Store).
     */
    val KNOWN_APP_STORES: Set<String> = OEM_APP_STORES + GOOGLE_PLAY_STORE_PACKAGE

    /**
     * Search loophole apps (Google Go) that must be hidden and suspended
     * during curfew only, and restored in the morning.
     * Note: Google Search (com.google.android.googlequicksearchbox) is kept unhidden and unsuspended
     * so that the Gemini AI Assistant (com.google.android.apps.bard) can run its underlying assistant backend.
     * Search UI and Discover feed are locked via CurfewForegroundWatcher activity-level inspection.
     */
    val CURFEW_SEARCH_APPS: Set<String> = setOf(
        "com.google.android.apps.searchlite"        // Google Go
    )
}
