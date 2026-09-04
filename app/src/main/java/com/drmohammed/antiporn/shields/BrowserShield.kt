package com.drmohammed.antiporn.shields

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus

/**
 * Isolated security shield that disables Incognito / Private browsing and enforces
 * Google SafeSearch, YouTube Restricted Mode, and SafeSites filters on Chromium browsers.
 */
object BrowserShield : ProtectionShield {

    private const val TAG = "AntiPorn_BrowserShield"

    override val id: String = "browser"
    override val titleResId: Int = R.string.card_browser_title

    val TARGET_BROWSER_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.microsoft.emmx",
        "com.microsoft.emmx.canary",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.vivaldi.browser"
    )

    fun buildBrowserRestrictionsBundle(): Bundle {
        return Bundle().apply {
            putInt("IncognitoModeAvailability", 1)
            putInt("InPrivateModeAvailability", 1)
            putBoolean("ForceGoogleSafeSearch", true)
            putBoolean("ForceYouTubeSafetyMode", true)
            putInt("ForceYouTubeRestrict", 2)
            putInt("SafeSitesFilterBehavior", 1)
            putBoolean("BrowserGuestModeEnabled", false)
        }
    }

    fun applyBrowserRestrictions(dpm: DevicePolicyManager, admin: ComponentName): Boolean {
        val bundle = buildBrowserRestrictionsBundle()
        var appliedCount = 0
        for (pkg in TARGET_BROWSER_PACKAGES) {
            try {
                dpm.setApplicationRestrictions(admin, pkg, bundle)
                appliedCount++
                Log.d(TAG, "Applied browser restrictions to $pkg")
            } catch (e: Exception) {
                Log.d(TAG, "Could not apply browser restrictions to $pkg: ${e.message}")
            }
        }
        Log.i(TAG, "Applied browser restrictions to $appliedCount/${TARGET_BROWSER_PACKAGES.size} packages")
        return appliedCount > 0
    }

    fun checkBrowserRestrictions(context: Context, dpm: DevicePolicyManager, admin: ComponentName): Boolean {
        val pm = context.packageManager
        val installedBrowsers = TARGET_BROWSER_PACKAGES.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }

        if (installedBrowsers.isNotEmpty()) {
            for (pkg in installedBrowsers) {
                try {
                    val restrictions: Bundle? = try {
                        dpm.getApplicationRestrictions(admin, pkg)
                    } catch (e: Exception) {
                        null
                    }
                    if (restrictions?.getInt("IncognitoModeAvailability", 0) != 1) {
                        Log.w(TAG, "Installed browser $pkg is missing Incognito restriction!")
                        return false
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Could not check restrictions for $pkg: ${e.message}")
                    return false
                }
            }
            return true
        }

        // Fallback when no target browser is installed yet: check if DPM has restrictions recorded
        return checkBrowserRestrictions(dpm, admin)
    }

    fun checkBrowserRestrictions(dpm: DevicePolicyManager, admin: ComponentName): Boolean {
        for (pkg in TARGET_BROWSER_PACKAGES) {
            try {
                val restrictions: Bundle? = try {
                    dpm.getApplicationRestrictions(admin, pkg)
                } catch (e: Exception) {
                    null
                }
                if (restrictions?.getInt("IncognitoModeAvailability", 0) == 1) {
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not check restrictions for $pkg: ${e.message}")
            }
        }
        return false
    }

    override fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldResult {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return ShieldResult(
                shieldId = id,
                success = false,
                message = context.getString(R.string.toast_not_device_owner)
            )
        }

        return try {
            val applied = applyBrowserRestrictions(dpm, admin)
            ShieldResult(
                shieldId = id,
                success = applied,
                message = if (applied) {
                    context.getString(R.string.shield_browser_success)
                } else {
                    context.getString(R.string.card_browser_unblocked)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error applying browser restrictions", e)
            ShieldResult(
                shieldId = id,
                success = false,
                message = context.getString(R.string.toast_error_applying, e.localizedMessage ?: "Unknown")
            )
        }
    }

    override fun checkStatus(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldStatus {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return ShieldStatus.Disabled(context.getString(R.string.card_device_owner_inactive))
        }

        val isRestricted = checkBrowserRestrictions(context, dpm, admin)
        return if (isRestricted) {
            ShieldStatus.Active(context.getString(R.string.card_browser_active))
        } else {
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_browser_unblocked))
        }
    }
}
