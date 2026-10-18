package com.drmohammed.antiporn.curfew

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
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

    fun getCurfewStartHour(context: Context): Int = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().curfewStartHour
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        START_HOUR
    }

    fun getCurfewStartMinute(context: Context): Int = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().curfewStartMinute
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        START_MINUTE
    }

    fun getCurfewEndHour(context: Context): Int = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().curfewEndHour
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        END_HOUR
    }

    fun getCurfewEndMinute(context: Context): Int = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().curfewEndMinute
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        END_MINUTE
    }

    fun isVpnBlackholeEnabled(context: Context): Boolean = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().isVpnBlackholeEnabled
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        false
    }


    const val PREFS_NAME = "antiporn_curfew_prefs"
    const val KEY_CURFEW_ENABLED = "key_curfew_enabled"
    const val KEY_CURFEW_SUSPENDED_PACKAGES = "key_curfew_suspended_packages"
    const val KEY_MANUAL_TEST_ACTIVE = "key_manual_test_active"
    const val KEY_CURFEW_VPN_BYPASS_PACKAGES = "key_curfew_vpn_bypass_packages"

    /**
     * Packages explicitly permitted to bypass the curfew blackhole VPN.
     * Returns an empty set by default to enforce 100% complete blackout,
     * while being fully architected to support dynamic user/admin exemptions.
     */
    fun getVpnBypassedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun setVpnBypassedPackages(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, packages).apply()
    }

    fun addVpnBypassedPackage(context: Context, packageName: String) {
        val current = getVpnBypassedPackages(context).toMutableSet()
        current.add(packageName)
        setVpnBypassedPackages(context, current)
    }

    fun removeVpnBypassedPackage(context: Context, packageName: String) {
        val current = getVpnBypassedPackages(context).toMutableSet()
        current.remove(packageName)
        setVpnBypassedPackages(context, current)
    }

    /**
     * Explicit Whitelist of user productivity, financial, religious, and essential applications.
     * Sourced from central [AppPolicyRegistry].
     */
    val STATIC_WHITELIST: Set<String> get() = AppPolicyRegistry.getCurfewStaticWhitelist()

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
    val SYSTEM_CRITICAL_PACKAGES: Set<String> get() = AppPolicyRegistry.getSystemCriticalPackages()

    const val GOOGLE_PLAY_STORE_PACKAGE = "com.android.vending"
    const val GOOGLE_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"

    /**
     * Third-party OEM App Stores that must be suspended AND hidden during curfew.
     * Note: Google Play Store is kept unhidden and unsuspended so games can access its
     * background licensing, Play Games, and In-App Billing IPC services.
     * Play Store UI is locked via CurfewForegroundWatcher (auto-kickout) and DISALLOW_INSTALL_APPS.
     */
    val OEM_APP_STORES: Set<String> get() = AppPolicyRegistry.getOemAppStores()

    /**
     * All known app stores (OEM stores + Google Play Store).
     */
    val KNOWN_APP_STORES: Set<String> = OEM_APP_STORES + GOOGLE_PLAY_STORE_PACKAGE

    /**
     * Search loophole apps (Google Go) that must be hidden and suspended
     * during curfew only, and restored in the morning.
     * Note: Google Search (com.google.android.googlequicksearchbox) is kept unhidden and unsuspended
     * for system IPC background services, while its UI and Gemini Assistant are locked via CurfewForegroundWatcher.
     */
    val CURFEW_SEARCH_APPS: Set<String> = setOf(
        "com.google.android.apps.searchlite"        // Google Go
    )
}
