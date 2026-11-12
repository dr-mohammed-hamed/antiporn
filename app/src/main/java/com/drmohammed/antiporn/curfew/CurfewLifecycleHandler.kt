package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.waqfa.engine.WaqfaCooldownStore

/**
 * Executes DevicePolicyManager package suspension, release, newly-installed interception,
 * and stuck-app recovery under strict Zero-Trust and partial-failure tracking guarantees.
 */
internal object CurfewLifecycleHandler {

    private const val TAG = "AntiPorn_CurfewLifecycle"

    fun isPackageInstalled(
        pm: PackageManager,
        packageName: String,
        includeHidden: Boolean = false
    ): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val flagBits = if (includeHidden) PackageManager.MATCH_UNINSTALLED_PACKAGES else 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flagBits.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flagBits)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error checking package installation for $packageName", e)
            false
        }
    }

    fun enforceActivation(context: Context): Boolean {
        var overallSuccess = true
        val instantKill = CurfewConfig.isInstantInternetKillEnabled(context)
        val vpnEnabled = instantKill || CurfewConfig.isVpnBlackholeEnabled(context)

        val alwaysOnPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(context).getOrNull()
        val isLockdown = SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(context).getOrNull() == true
        if (vpnEnabled) {
            val (_, vpnDpmOk) = CurfewVpnDnsBridge.ensureBlackholeVpnActive(context, alwaysOnPkg, isLockdown)
            if (!vpnDpmOk) overallSuccess = false
        } else {
            val requiredMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
            if (!CurfewVpnDnsBridge.reconcileNonBlackholeVpnAndDns(context, requiredMode, alwaysOnPkg, isLockdown)) {
                overallSuccess = false
            }
        }

        syncNotificationSafely(context, "enforceActivation")

        val targets = CurfewAppResolver.getCurfewTargetPackages(context)
        Log.i(TAG, "Enforcing Curfew ON for ${targets.size} targeted packages")

        val pm = context.packageManager
        val storesBatchToSuspend = (CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS).filter { pkg ->
            isPackageInstalled(pm, pkg) &&
                SafeDpmGateway.isPackageSuspendedBlocking(context, pkg).getOrNull() != true
        }
        val (storesOk, _) = suspendPackagesChecked(context, storesBatchToSuspend, suspend = true, label = "store/search")
        if (!storesOk) overallSuccess = false

        val installedTargets = targets.filter { isPackageInstalled(pm, it) }
        val toSuspend = installedTargets.filter { pkg ->
            SafeDpmGateway.isPackageSuspendedBlocking(context, pkg).getOrNull() != true
        }
        val (appsOk, failedApps) = suspendPackagesChecked(context, toSuspend, suspend = true, label = "curfew target")
        if (!appsOk) overallSuccess = false
        val newlySuspendedApps = toSuspend.filterNot { it in failedApps }
        val activeTrackedApps = installedTargets.filterNot { it in failedApps }

        if (!ensureSafeImmuneUnsuspended(context, pm)) {
            overallSuccess = false
        }

        clearLegacyInstallRestriction(context)

        CurfewConfig.addTrackedSuspendedPackages(context, activeTrackedApps)
        CurfewConfig.setLastBlockEnforced(context, true)

        BlackBoxRecorder.recordCurfew(
            context,
            "CURFEW_ACTIVATED",
            isSuccess = overallSuccess,
            details = "targets=${targets.size}, suspended=${newlySuspendedApps.size}, tracked=${activeTrackedApps.size}, failed=${failedApps.size}, vpnEnabled=$vpnEnabled, instantKill=$instantKill, nap=${NapController.isNapActive(context)}"
        )
        return overallSuccess
    }

    fun enforceRelease(context: Context): Boolean {
        var overallSuccess = true
        val instantKill = CurfewConfig.isInstantInternetKillEnabled(context)
        val requiredMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
        val alwaysOnPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(context).getOrNull()
        val isLockdown = SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(context).getOrNull() == true

        if (instantKill) {
            val (vpnChanged, vpnOk) = CurfewVpnDnsBridge.ensureBlackholeVpnActive(context, alwaysOnPkg, isLockdown)
            if (!vpnOk) overallSuccess = false
            if (vpnChanged) syncNotificationSafely(context, "enforceRelease InstantKill")
        }

        val isVpnTeardownNeeded = isVpnReconcileNeeded(instantKill, requiredMode)
        val isAlwaysOnConfigured = isAlwaysOnUpdateNeeded(context, instantKill, requiredMode, alwaysOnPkg, isLockdown)

        val wasBlockEnforced = CurfewConfig.isLastBlockEnforced(context)
        val recordedCurfewPackages = CurfewConfig.getTrackedSuspendedPackages(context)

        val policyState = loadPolicyAndApprovalsFailSecure(context, "curfew release")
        if (policyState.failed) {
            if (CurfewEnforcer.isVpnTeardownAllowedOnReleaseFailSecure(
                    fetchPolicyFailed = policyState.policyFailed,
                    instantKill = instantKill,
                    isVpnTeardownNeeded = isVpnTeardownNeeded,
                    isAlwaysOnConfigured = isAlwaysOnConfigured
                )
            ) {
                CurfewVpnDnsBridge.reconcileNonBlackholeVpnAndDns(context, requiredMode, alwaysOnPkg, isLockdown)
            }
            Log.w(TAG, "Fail-secure active: preserving suspended packages because approvals or policy could not be verified")
            return false
        }

        val pm = context.packageManager
        val immunePackages = CurfewAppResolver.resolveImmunePackages(context)
        val storesAndSearch = CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS
        val installedStoresAndSearch = filterEligibleStores(context, pm, storesAndSearch, policyState)
        val installedStoresSet = installedStoresAndSearch.toSet()

        val curfewTargets = if (wasBlockEnforced || recordedCurfewPackages.isNotEmpty()) {
            CurfewAppResolver.getCurfewTargetPackages(context)
        } else {
            emptySet()
        }
        val eligibleAppCandidates = (recordedCurfewPackages + immunePackages + policyState.approvedPackages + curfewTargets)
            .filterNot { installedStoresSet.contains(it) }
            .filter { isPackageEligibleForDaytimeRelease(context, pm, it, policyState, immunePackages) }

        val currentlySuspendedInstalled = (installedStoresAndSearch + eligibleAppCandidates).toSet()
            .filterTo(mutableSetOf()) { pkg ->
                SafeDpmGateway.isPackageSuspendedBlocking(context, pkg).getOrNull() == true
            }
        val storesBatchToUnsuspend = installedStoresAndSearch.filter { it in currentlySuspendedInstalled }
        val appsBatchToUnsuspend = eligibleAppCandidates.filter { it in currentlySuspendedInstalled }

        if (!CurfewEnforcer.shouldExecuteReleaseCleanup(
                isVpnTeardownNeeded = isVpnTeardownNeeded,
                isAlwaysOnConfigured = isAlwaysOnConfigured,
                wasBlockEnforced = wasBlockEnforced,
                hasTrackedSuspendedPackages = recordedCurfewPackages.isNotEmpty(),
                hasLingeringSuspendedPackages = currentlySuspendedInstalled.isNotEmpty()
            )
        ) {
            return true
        }

        Log.i(TAG, "Enforcing Curfew OFF: Releasing daytime apps and stores")
        if (!instantKill) {
            if (!CurfewVpnDnsBridge.reconcileNonBlackholeVpnAndDns(context, requiredMode, alwaysOnPkg, isLockdown)) {
                overallSuccess = false
            }
        }

        unhideLegacyStores(context, pm, storesAndSearch)
        clearLegacyInstallRestriction(context)

        val (storesOk, failedStores) = suspendPackagesChecked(context, storesBatchToUnsuspend, suspend = false, label = "store/search release")
        val (appsOk, failedApps) = suspendPackagesChecked(context, appsBatchToUnsuspend, suspend = false, label = "curfew app release")
        if (!storesOk || !appsOk) overallSuccess = false

        val packagesToKeepTracked = failedApps.filter { it in recordedCurfewPackages }.toSet()
        val packagesToUntrack = recordedCurfewPackages.filterNot { it in packagesToKeepTracked }
        if (packagesToUntrack.isNotEmpty()) {
            CurfewConfig.removeTrackedSuspendedPackages(context, packagesToUntrack)
        }
        val stillHasPendingRelease = failedStores.isNotEmpty() || packagesToKeepTracked.isNotEmpty()
        CurfewConfig.setLastBlockEnforced(context, stillHasPendingRelease)

        BlackBoxRecorder.recordCurfew(
            context,
            "CURFEW_RELEASED",
            isSuccess = overallSuccess,
            details = "released=${appsBatchToUnsuspend.size - failedApps.size}, failedApps=${failedApps.size}, failedStores=${failedStores.size}"
        )
        syncNotificationSafely(context, "enforceRelease")
        return overallSuccess
    }

    fun handlePackageAdded(context: Context, packageName: String) {
        val appContext = context.applicationContext ?: context
        if (!CurfewEnforcer.isEffectiveBlockActive(appContext)) {
            Log.d(TAG, "Package $packageName installed while curfew is inactive; will be evaluated at next curfew cycle")
            return
        }

        val immune = CurfewAppResolver.resolveImmunePackages(appContext)
        if (immune.contains(packageName) ||
            packageName == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE ||
            packageName == CurfewConfig.GOOGLE_SEARCH_PACKAGE
        ) {
            Log.i(TAG, "Newly installed package $packageName is immune, Play Store, or Google Search IPC; skipping suspension")
            return
        }

        if (!SafeDpmGateway.isDeviceOwner(appContext)) return

        try {
            val res = SafeDpmGateway.setPackagesSuspendedBlocking(appContext, arrayOf(packageName), true)
            val failed = res.getOrNull()?.toSet() ?: emptySet()
            if (res.isSuccess && !failed.contains(packageName)) {
                CurfewConfig.addTrackedSuspendedPackages(appContext, listOf(packageName))
                Log.i(TAG, "Newly installed package $packageName immediately suspended and recorded via SafeDpmGateway")
            } else {
                Log.w(TAG, "Failed suspending newly installed package $packageName during curfew (isSuccess=${res.isSuccess})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error suspending newly installed package $packageName", e)
        }
    }

    fun repairStuckSuspendedApps(context: Context): Int {
        val appContext = context.applicationContext ?: context
        if (!SafeDpmGateway.isDeviceOwner(appContext)) return 0

        val pm = appContext.packageManager ?: return 0
        val isCurfewRunning = CurfewEnforcer.isEffectiveBlockActive(appContext)
        val policyState = loadPolicyAndApprovalsFailSecure(appContext, "stuck app repair")
        if (policyState.failed) {
            Log.w(TAG, "Fail-secure active: Aborting repairStuckSuspendedApps to protect device")
            return 0
        }

        val immunePackages = CurfewAppResolver.resolveImmunePackages(appContext)
        val installed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed querying installed applications during stuck app repair", e)
            emptyList()
        }

        val stuckToUnsuspend = mutableListOf<String>()
        for (app in installed) {
            val pkg = app.packageName
            if (pkg == appContext.packageName) continue
            if (isCurfewRunning && !immunePackages.contains(pkg)) continue
            if (!isAppSafeToUnsuspend(appContext, app, policyState, immunePackages)) continue

            val isSuspended = SafeDpmGateway.isPackageSuspendedBlocking(appContext, pkg).getOrNull() == true
            if (isSuspended) {
                stuckToUnsuspend.add(pkg)
            }
        }

        if (stuckToUnsuspend.isEmpty()) return 0

        val (ok, failed) = suspendPackagesChecked(appContext, stuckToUnsuspend, suspend = false, label = "stuck app repair")
        val repairedCount = stuckToUnsuspend.count { it !in failed }
        if (!ok) {
            Log.w(TAG, "[REPAIR] Stuck app repair completed with failures: repaired=$repairedCount, failed=$failed")
        } else {
            Log.i(TAG, "[REPAIR] Successfully unsuspended $repairedCount stuck apps")
        }
        return repairedCount
    }

    private fun ensureSafeImmuneUnsuspended(context: Context, pm: PackageManager): Boolean {
        val policyState = loadPolicyAndApprovalsFailSecure(context, "curfew activation")
        val immunePackages = CurfewAppResolver.resolveImmunePackages(context)
        val immuneToUnsuspend = if (policyState.failed) {
            Log.w(TAG, "Fail-secure active: preserving suspended immune packages except core critical packages")
            CurfewAppResolver.resolveCoreSystemCriticalPackages(context).filter { pkg ->
                isPackageInstalled(pm, pkg) &&
                    !AppSuspensionShield.isBlacklisted(pkg) &&
                    !AppPolicyRegistry.isHighRisk(pkg) &&
                    SafeDpmGateway.isPackageSuspendedBlocking(context, pkg).getOrNull() == true
            }
        } else {
            immunePackages.filter { pkg ->
                isPackageInstalled(pm, pkg) &&
                    !AppSuspensionShield.isBlacklisted(pkg) &&
                    !AppPolicyRegistry.isHighRisk(pkg) &&
                    !policyState.customBlacklist.contains(pkg) &&
                    !policyState.unapprovedPackages.contains(pkg) &&
                    SafeDpmGateway.isPackageSuspendedBlocking(context, pkg).getOrNull() == true
            }
        }
        if (immuneToUnsuspend.isEmpty()) return true
        val (ok, _) = suspendPackagesChecked(context, immuneToUnsuspend, suspend = false, label = "immune activation restore")
        return ok
    }

    private fun suspendPackagesChecked(
        context: Context,
        packages: List<String>,
        suspend: Boolean,
        label: String
    ): Pair<Boolean, Set<String>> {
        if (packages.isEmpty()) return true to emptySet()
        return try {
            val res = SafeDpmGateway.setPackagesSuspendedBlocking(context, packages.toTypedArray(), suspend)
            if (!res.isSuccess) {
                Log.e(TAG, "DPM batch ($label, suspend=$suspend) failed to execute for ${packages.size} packages")
                false to packages.toSet()
            } else {
                val failed = res.getOrNull()?.toSet() ?: emptySet()
                val ok = failed.isEmpty()
                if (!ok) {
                    Log.w(TAG, "DPM batch ($label, suspend=$suspend) partial failure: failed=$failed")
                } else {
                    Log.i(TAG, "DPM batch ($label, suspend=$suspend) succeeded for ${packages.size} packages")
                }
                ok to failed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing DPM batch ($label, suspend=$suspend)", e)
            false to packages.toSet()
        }
    }

    private fun clearLegacyInstallRestriction(context: Context) {
        try {
            if (SafeDpmGateway.hasUserRestriction(context, UserManager.DISALLOW_INSTALL_APPS)) {
                SafeDpmGateway.clearUserRestrictionBlocking(context, UserManager.DISALLOW_INSTALL_APPS)
                Log.d(TAG, "Cleared UserManager.DISALLOW_INSTALL_APPS to preserve ADB workflow")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not ensure DISALLOW_INSTALL_APPS restriction is cleared", e)
        }
    }

    private fun unhideLegacyStores(context: Context, pm: PackageManager, storesAndSearch: Set<String>) {
        for (pkg in storesAndSearch) {
            try {
                if (isPackageInstalled(pm, pkg, includeHidden = true) &&
                    SafeDpmGateway.isApplicationHiddenBlocking(context, pkg).getOrNull() == true
                ) {
                    SafeDpmGateway.setApplicationHiddenBlocking(context, pkg, false)
                    Log.i(TAG, "Legacy cleanup: Unhid store/search package: $pkg")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not unhide package $pkg", e)
            }
        }
    }

    private fun filterEligibleStores(
        context: Context,
        pm: PackageManager,
        storesAndSearch: Set<String>,
        policyState: PolicyApprovalSnapshot
    ): List<String> = storesAndSearch.filter { pkg ->
        when {
            !isPackageInstalled(pm, pkg) -> false
            isHighRiskOrBlacklistedForRelease(pkg, policyState.approvedPackages, policyState.customBlacklist) -> {
                Log.i(TAG, "Preserving suspension of $pkg on curfew release as it is blacklisted/unapproved high-risk")
                false
            }
            else -> !policyState.unapprovedPackages.contains(pkg) && !WaqfaCooldownStore.isPackageInCooldown(context, pkg)
        }
    }

    private fun isPackageEligibleForDaytimeRelease(
        context: Context,
        pm: PackageManager,
        pkg: String,
        policyState: PolicyApprovalSnapshot,
        immunePackages: Set<String>
    ): Boolean {
        if (!isPackageInstalled(pm, pkg)) return false
        if (isHighRiskOrBlacklistedForRelease(pkg, policyState.approvedPackages, policyState.customBlacklist)) return false
        if (policyState.unapprovedPackages.contains(pkg)) return false
        if (WaqfaCooldownStore.isPackageInCooldown(context, pkg)) return false
        if (!policyState.isZeroTrustActive) return true

        val isApproved = policyState.approvedPackages.contains(pkg)
        val isImmuneOrStore = CurfewAppResolver.isSystemCriticalOrImmune(context, pkg) ||
            immunePackages.contains(pkg) ||
            pkg == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE ||
            pkg == CurfewConfig.GOOGLE_SEARCH_PACKAGE ||
            CurfewConfig.KNOWN_APP_STORES.contains(pkg)
        val isSystemOrUpdatedApp = try {
            val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
            (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading ApplicationInfo for $pkg during daytime release", e)
            false
        }
        return isApproved || isImmuneOrStore || isSystemOrUpdatedApp
    }

    private fun isAppSafeToUnsuspend(
        context: Context,
        app: ApplicationInfo,
        policyState: PolicyApprovalSnapshot,
        immunePackages: Set<String>
    ): Boolean {
        val pkg = app.packageName
        if (isHighRiskOrBlacklistedForRelease(pkg, policyState.approvedPackages, policyState.customBlacklist)) return false
        if (policyState.unapprovedPackages.contains(pkg)) return false
        if (WaqfaCooldownStore.isPackageInCooldown(context, pkg)) return false
        if (!policyState.isZeroTrustActive) return true

        val isImmune = immunePackages.contains(pkg) ||
            pkg == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE ||
            pkg == CurfewConfig.GOOGLE_SEARCH_PACKAGE ||
            CurfewConfig.KNOWN_APP_STORES.contains(pkg)
        val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
            (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
        return policyState.approvedPackages.contains(pkg) || isImmune || isSystem
    }

    internal fun isApprovalEligibleForRelease(
        packageName: String,
        isApproved: Boolean,
        decidedBy: String?
    ): Boolean {
        if (!isApproved) return false
        if (!AppPolicyRegistry.isHighRisk(packageName)) return true
        return decidedBy != null &&
            decidedBy != "INITIAL_DEVICE_BASELINE" &&
            decidedBy != "AUTO_APPROVED_UNLOCKED"
    }

    internal fun isHighRiskOrBlacklistedForRelease(
        pkg: String,
        approvedPackages: Set<String>,
        customBlacklist: Set<String>
    ): Boolean = AppSuspensionShield.isBlacklisted(pkg) ||
        (AppPolicyRegistry.isHighRisk(pkg) && !approvedPackages.contains(pkg)) ||
        customBlacklist.contains(pkg)

    private fun isVpnReconcileNeeded(
        instantKill: Boolean,
        requiredMode: CurfewBlackholeVpnService.VpnMode
    ): Boolean = when {
        instantKill -> false
        requiredMode == CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY ->
            !CurfewBlackholeVpnService.isServiceOrTunnelActive() ||
                CurfewBlackholeVpnService.currentMode != CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY
        else -> CurfewBlackholeVpnService.isServiceOrTunnelActive()
    }

    private fun isAlwaysOnUpdateNeeded(
        context: Context,
        instantKill: Boolean,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        alwaysOnPkg: String?,
        isLockdown: Boolean
    ): Boolean = when {
        instantKill -> false
        requiredMode == CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY ->
            alwaysOnPkg != context.packageName || isLockdown
        else -> alwaysOnPkg == context.packageName || (alwaysOnPkg == null && isLockdown)
    }

    private fun syncNotificationSafely(context: Context, caller: String) {
        try {
            ProtectionNotificationCoordinator.syncProtectionNotification(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error synchronizing protection notification in $caller", e)
        }
    }

    private data class PolicyApprovalSnapshot(
        val policyFailed: Boolean,
        val approvalsFailed: Boolean,
        val customBlacklist: Set<String>,
        val isZeroTrustActive: Boolean,
        val approvedPackages: Set<String>,
        val unapprovedPackages: Set<String>
    ) {
        val failed: Boolean get() = policyFailed || approvalsFailed
    }

    private fun loadPolicyAndApprovalsFailSecure(context: Context, caller: String): PolicyApprovalSnapshot {
        var policyFailed = false
        val currentPolicy = try {
            PolicyRepository.getInstance(context).getCurrentPolicy()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching policy during $caller - failing secure", e)
            policyFailed = true
            null
        }
        var approvalsFailed = false
        val localApprovals = try {
            ApprovalRepository.getInstance(context).getLocalPendingApprovals()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching pending approvals during $caller - failing secure", e)
            approvalsFailed = true
            emptyList()
        }
        val approvedPackages = localApprovals.filter {
            isApprovalEligibleForRelease(it.packageName, it.isApproved, it.decidedBy)
        }.map { it.packageName }.toSet()
        val unapprovedPackages = localApprovals.filter { !it.isApproved }.map { it.packageName }.toSet()
        return PolicyApprovalSnapshot(
            policyFailed = policyFailed,
            approvalsFailed = approvalsFailed,
            customBlacklist = currentPolicy?.customBlacklistPackages ?: emptySet(),
            isZeroTrustActive = currentPolicy?.lockNewInstallsUntilApproved ?: true,
            approvedPackages = approvedPackages,
            unapprovedPackages = unapprovedPackages
        )
    }
}
