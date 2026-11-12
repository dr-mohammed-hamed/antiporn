package com.drmohammed.antiporn.shields

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry

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

    val IMMUNE_PACKAGES: Set<String> get() = AppPolicyRegistry.getImmunePackages()
    val TELEGRAM_PACKAGES: Set<String> get() = AppBlacklistRegistry.TELEGRAM_PACKAGES
    val OPEN_SOCIAL_NETWORKS: Set<String> get() = AppBlacklistRegistry.OPEN_SOCIAL_NETWORKS
    val PROXY_BROWSERS: Set<String> get() = AppBlacklistRegistry.PROXY_BROWSERS
    val RANDOM_CHAT_APPS: Set<String> get() = AppBlacklistRegistry.RANDOM_CHAT_APPS
    val TIKTOK_PACKAGES: Set<String> get() = AppBlacklistRegistry.TIKTOK_PACKAGES
    val LOCAL_ADB_TOOLS: Set<String> get() = AppBlacklistRegistry.LOCAL_ADB_TOOLS
    val BLACKLISTED_PACKAGES: Set<String> get() = AppPolicyRegistry.getBlacklistedPackages()

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
            LOCAL_ADB_TOOLS.contains(packageName) -> "Local ADB Exploit"
            else -> null
        }
    }

    fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean =
        AppSuspensionHandler.isPackageInstalled(pm, packageName)

    @SuppressLint("MissingPermission")
    fun uninstallSilently(context: Context, packageName: String) {
        Log.w(TAG, "Silent uninstallation is permanently disabled under Unified Zero-Trust architecture. Skipping uninstall for $packageName")
    }

    fun freezeAndUninstall(context: Context, dpm: DevicePolicyManager, admin: ComponentName, packageName: String) {
        AppSuspensionHandler.freezePackage(context, dpm, admin, packageName, id)
    }

    fun handlePackageAdded(context: Context, packageName: String) {
        AppSuspensionHandler.handlePackageAdded(context, packageName, id)
    }

    fun enforceBlacklistFast(context: Context) {
        AppSuspensionHandler.enforceBlacklistFast(context, id)
    }

    fun enforceBlacklistFastAsync(context: Context) {
        AppSuspensionHandler.enforceBlacklistFastAsync(context, id)
    }

    override fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName): ShieldResult {
        if (!ProtectionEngine.isDeviceOwner(context)) {
            return ShieldResult(id, false, context.getString(R.string.toast_not_device_owner))
        }

        return try {
            val installedTargets = AppSuspensionHandler.resolveTargetsToEnforce(context)
            var allSucceeded = true
            for (pkg in installedTargets) {
                if (!AppSuspensionHandler.freezePackage(context, dpm, admin, pkg, id)) {
                    allSucceeded = false
                }
            }
            val msg = when {
                !allSucceeded -> context.getString(R.string.card_app_suspension_inactive)
                installedTargets.isEmpty() -> context.getString(R.string.shield_app_suspension_none_found)
                else -> context.getString(R.string.shield_app_suspension_blocked_count, installedTargets.size)
            }
            ShieldResult(shieldId = id, success = allSucceeded, message = msg)
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
            val installedTargets = AppSuspensionHandler.resolveTargetsToEnforce(context)
            if (installedTargets.isEmpty()) {
                return ShieldStatus.Active(context.getString(R.string.card_app_suspension_active))
            }

            val unSuspended = installedTargets.filter { pkg ->
                try {
                    !dpm.isPackageSuspended(admin, pkg) && !dpm.isApplicationHidden(admin, pkg)
                } catch (e: Exception) {
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
