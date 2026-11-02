package com.drmohammed.antiporn.curfew

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import java.util.Calendar
import java.util.TimeZone

/**
 * Configuration and immutable constants for the Scheduled Night Curfew system.
 * Curfew window: 20:00 (8:00 PM) to 07:00 (7:00 AM) every day.
 * Default Timezone: Africa/Cairo (Cairo, Egypt).
 */
object CurfewConfig {

    const val DEFAULT_TIMEZONE_ID = "Africa/Cairo"
    const val CAIRO_TIMEZONE_ID = "Africa/Cairo"
    const val MAKKAH_TIMEZONE_ID = "Asia/Riyadh"

    /**
     * Resolves the default fallback timezone dynamically from the device's system timezone,
     * validated against known IANA identifiers. Falls back to Cairo (Africa/Cairo) if unresolvable.
     */
    fun getDefaultFallbackTimeZone(): String {
        return try {
            val localId = TimeZone.getDefault().id
            if (!localId.isNullOrBlank() && com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry.isValidIanaId(localId)) {
                localId
            } else {
                DEFAULT_TIMEZONE_ID
            }
        } catch (_: Exception) {
            DEFAULT_TIMEZONE_ID
        }
    }

    fun getTargetTimeZoneId(context: Context): String {
        val tzId = try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context)
                .getCurrentPolicy().timeZoneId
        } catch (e: Exception) {
            Log.w("AntiPorn_CurfewConfig", "Failed reading policy timezone, falling back to default", e)
            getDefaultFallbackTimeZone()
        }

        return if (tzId.isNotBlank() && com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry.isValidIanaId(tzId)) {
            tzId
        } else {
            getDefaultFallbackTimeZone()
        }
    }

    fun getCurfewCalendar(): Calendar = TrustedTimeProvider.getCurfewCalendar()

    fun getCurfewCalendar(context: Context): Calendar = TrustedTimeProvider.getCurfewCalendar(context)

    const val START_HOUR = 20
    const val START_MINUTE = 0

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

    fun isCurfewEnabled(context: Context): Boolean = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().isCurfewEnabled
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        true
    }

    fun isInstantInternetKillEnabled(context: Context): Boolean = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().isInstantInternetKillEnabled
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        false
    }

    fun isWeekendCurfewEnabled(context: Context): Boolean = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().isWeekendCurfewEnabled
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        false
    }

    fun getWeekendCurfewStartHour(context: Context): Int = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().weekendCurfewStartHour
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        START_HOUR
    }

    fun getWeekendCurfewStartMinute(context: Context): Int = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().weekendCurfewStartMinute
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        START_MINUTE
    }

    fun getWeekendCurfewEndHour(context: Context): Int = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().weekendCurfewEndHour
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        END_HOUR
    }

    fun getWeekendCurfewEndMinute(context: Context): Int = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().weekendCurfewEndMinute
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        END_MINUTE
    }

    /**
     * Central Single Source of Truth Feature Flag for Night Curfew Internet Blackout.
     * Set to true to enforce 100% complete data blackout by default in standalone versions.
     * Set to false to cleanly disable the blackhole feature across the entire codebase.
     */
    const val DEFAULT_VPN_BLACKHOLE_ENABLED = true

    fun isVpnBlackholeEnabled(context: Context): Boolean = try {
        com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy().isVpnBlackholeEnabled
    } catch (e: Exception) {
        Log.w("AntiPorn_CurfewConfig", "Failed reading config, falling back to default", e)
        DEFAULT_VPN_BLACKHOLE_ENABLED
    }

    const val PREFS_NAME = "antiporn_curfew_prefs"
    const val KEY_CURFEW_ENABLED = "key_curfew_enabled"
    const val KEY_CURFEW_SUSPENDED_PACKAGES = "key_curfew_suspended_packages"
    const val KEY_MANUAL_TEST_ACTIVE = "key_manual_test_active"
    const val KEY_CURFEW_VPN_BYPASS_PACKAGES = "key_curfew_vpn_bypass_packages"
    const val KEY_CURFEW_SNOOZE_END_WALL = "key_curfew_snooze_end_wall"
    const val ACTION_CURFEW_SNOOZE = "com.drmohammed.antiporn.ACTION_CURFEW_SNOOZE"
    const val ACTION_CURFEW_SNOOZE_END = "com.drmohammed.antiporn.ACTION_CURFEW_SNOOZE_END"

    /**
     * Packages explicitly permitted to bypass the curfew blackhole VPN.
     * Guaranteed to always include AntiPorn self-package so background IPC,
     * Firebase FCM push notifications, and Firestore policy synchronization remain active.
     */
    fun getVpnBypassedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val custom = prefs.getStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, emptySet())?.toSet() ?: emptySet()
        return custom + context.packageName
    }

    fun setVpnBypassedPackages(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val toSave = packages - context.packageName
        prefs.edit().putStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, toSave).apply()
    }

    fun addVpnBypassedPackage(context: Context, packageName: String) {
        if (packageName == context.packageName) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, current).apply()
    }

    fun removeVpnBypassedPackage(context: Context, packageName: String) {
        if (packageName == context.packageName) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_CURFEW_VPN_BYPASS_PACKAGES, current).apply()
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
