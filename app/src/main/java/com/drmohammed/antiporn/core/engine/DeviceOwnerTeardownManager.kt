package com.drmohammed.antiporn.core.engine

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * محرك التفكيك الآمن لصلاحيات مالك الجهاز (Device Owner Teardown Manager).
 * يضمن التفكيك الكامل والصارم لكافة القيود والتطبيقات المعلقة والمخفية قبل إسقاط الصلاحية
 * لتفادي وقوع هاتف المستخدم في فخ "القيود العالقة" (Orphaned Restrictions).
 */
object DeviceOwnerTeardownManager {

    private const val TAG = "AntiPorn_DOTeardown"

    /**
     * تنفيذ التفكيك الشامل والنهائي لصلاحية مالك الجهاز.
     * يُشترط أن يعمل على خيط خلفي (Dispatchers.IO).
     */
    suspend fun executeTeardown(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return@withContext Result.failure(IllegalStateException("DevicePolicyManager not found"))
        val admin = SafeDpmGateway.getAdminComponent(appContext)

        if (!dpm.isDeviceOwnerApp(appContext.packageName) && !dpm.isProfileOwnerApp(appContext.packageName)) {
            Timber.tag(TAG).w("Device is not Device Owner / Profile Owner. Skipping teardown.")
            return@withContext Result.success(Unit)
        }

        val policyRepo = PolicyRepository.getInstance(appContext)
        val currentPolicy = policyRepo.getCurrentPolicy()
        val now = TrustedTimeProvider.currentTimeMillis(appContext)
        val unlockAt = currentPolicy.deactivationUnlockAt ?: Long.MAX_VALUE

        if (currentPolicy.deactivationStatus != DevicePolicyBundle.DEACTIVATION_STATUS_PENDING) {
            Timber.tag(TAG).w("Teardown rejected: deactivation status is not PENDING (current: ${currentPolicy.deactivationStatus})")
            return@withContext Result.failure(IllegalStateException("Device owner deactivation is not pending"))
        }

        if (now < unlockAt) {
            Timber.tag(TAG).w("Teardown rejected: deactivation cooldown not elapsed (now: $now, unlockAt: $unlockAt)")
            return@withContext Result.failure(IllegalStateException("Deactivation delay has not elapsed yet"))
        }

        if (currentPolicy.isSupervised && !currentPolicy.guardianUid.isNullOrBlank()) {
            try {
                val myUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
                val snapshot = FirebaseFirestore.getInstance()
                    .collection("policies")
                    .document(myUid)
                    .get()
                    .await()
                val remoteStatus = snapshot.getString("deactivationStatus")
                val remoteUnlockAt = snapshot.getLong("deactivationUnlockAt") ?: Long.MAX_VALUE
                if (remoteStatus != DevicePolicyBundle.DEACTIVATION_STATUS_PENDING || now < remoteUnlockAt) {
                    Timber.tag(TAG).w("Teardown rejected by server policy check: remoteStatus=$remoteStatus, remoteUnlockAt=$remoteUnlockAt, now=$now")
                    return@withContext Result.failure(IllegalStateException("Server deactivation verification failed"))
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Teardown aborted: failed to verify server timestamp with Firestore")
                return@withContext Result.failure(IllegalStateException("Failed to verify deactivation with guardian server: ${e.message}", e))
            }
        }

        try {
            Timber.tag(TAG).w("[TEARDOWN] Starting complete safe unwinding of Device Owner restrictions...")

            // 1. رفع تجميد كافة التطبيقات المعلقة (Unsuspend all packages)
            try {
                val pm = appContext.packageManager
                val installedPackages = pm.getInstalledPackages(0) ?: emptyList()
                val packagesToUnsuspend = mutableListOf<String>()
                for (pkgInfo in installedPackages) {
                    val pkg = pkgInfo.packageName ?: continue
                    if (pkg == appContext.packageName) continue
                    try {
                        if (dpm.isPackageSuspended(admin, pkg)) {
                            packagesToUnsuspend.add(pkg)
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).w("Could not check suspension for $pkg: ${e.message}")
                    }
                }
                if (packagesToUnsuspend.isNotEmpty()) {
                    dpm.setPackagesSuspended(admin, packagesToUnsuspend.toTypedArray(), false)
                    Timber.tag(TAG).i("Un-suspended ${packagesToUnsuspend.size} packages successfully")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error un-suspending packages during teardown")
            }

            // 2. إلغاء إخفاء التطبيقات المخفية (Unhide all hidden packages)
            try {
                val pm = appContext.packageManager
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_UNINSTALLED_PACKAGES or PackageManager.GET_DISABLED_COMPONENTS
                }
                val installedPackages = pm.getInstalledPackages(flags) ?: emptyList()
                for (pkgInfo in installedPackages) {
                    val pkg = pkgInfo.packageName ?: continue
                    try {
                        if (dpm.isApplicationHidden(admin, pkg)) {
                            dpm.setApplicationHidden(admin, pkg, false)
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).w("Could not unhide $pkg: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error un-hiding packages during teardown")
            }

            // 3. مسح كافة قيود المستخدم (UserManager User Restrictions)
            @Suppress("DEPRECATION")
            val restrictionsToClear = mutableListOf(
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_ADD_MANAGED_PROFILE,
                UserManager.DISALLOW_APPS_CONTROL,
                UserManager.DISALLOW_INSTALL_APPS,
                UserManager.DISALLOW_UNINSTALL_APPS,
                UserManager.DISALLOW_MODIFY_ACCOUNTS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                restrictionsToClear.add(UserManager.DISALLOW_USER_SWITCH)
                restrictionsToClear.add(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            }
            restrictionsToClear.add(UserManager.DISALLOW_NETWORK_RESET)
            restrictionsToClear.add(UserManager.DISALLOW_CONFIG_VPN)
            restrictionsToClear.add(UserManager.DISALLOW_CONFIG_DATE_TIME)

            for (restriction in restrictionsToClear) {
                try {
                    dpm.clearUserRestriction(admin, restriction)
                    Timber.tag(TAG).d("Cleared user restriction: $restriction")
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Could not clear restriction $restriction: ${e.message}")
                }
            }

            // 3b. إعادة وضع خادم الـ DNS الخاص إلى الوضع التلقائي الطبيعي
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    dpm.setGlobalPrivateDnsModeOpportunistic(admin)
                    Timber.tag(TAG).i("Private DNS mode restored to Opportunistic (System Default)")
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Could not restore Private DNS mode: ${e.message}")
                }
            }

            // 4. إعادة تفعيل مستخدم الضيف وتبديل المستخدمين في Settings.Global
            try {
                Settings.Global.putInt(appContext.contentResolver, "user_switcher_enabled", 1)
                Settings.Global.putInt(appContext.contentResolver, "guest_user_enabled", 1)
            } catch (e: Exception) {
                Timber.tag(TAG).w("Could not restore user_switcher settings: ${e.message}")
            }

            // 5. إلغاء حظر مسح بيانات التطبيق لنفس الحزمة (setClearApplicationUserDataBlocked = false)
            try {
                val method = dpm.javaClass.getMethod(
                    "setClearApplicationUserDataBlocked",
                    android.content.ComponentName::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )
                method.invoke(dpm, admin, appContext.packageName, false)
                Timber.tag(TAG).i("Application data clearing unblocked for self package")
            } catch (e: Exception) {
                Timber.tag(TAG).w("setClearApplicationUserDataBlocked unblock fallback: ${e.message}")
            }

            // 6. إلغاء حظر حذف التطبيق والتطبيقات المحمية (setUninstallBlocked = false)
            try {
                dpm.setUninstallBlocked(admin, appContext.packageName, false)
                Timber.tag(TAG).i("setUninstallBlocked=false applied successfully for self package")
                val policy = policyRepo.getCurrentPolicy()
                for (protectedPkg in policy.protectedAppPackages) {
                    try {
                        dpm.setUninstallBlocked(admin, protectedPkg, false)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w("Could not unblock uninstall for protected app $protectedPkg: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error clearing setUninstallBlocked")
            }

            // 7. إلغاء Always-On VPN
            try {
                dpm.setAlwaysOnVpnPackage(admin, null, false)
                Timber.tag(TAG).i("AlwaysOnVpnPackage cleared successfully")
            } catch (e: Exception) {
                Timber.tag(TAG).w("Error clearing AlwaysOnVpnPackage: ${e.message}")
            }

            // 7b. إلغاء خدمة الوصول وتعطيلها بنظافة
            try {
                com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator.disableAndCloakSilently(appContext, isTemporaryCloak = false)
                Timber.tag(TAG).i("HisnAccessibilityService disabled cleanly during teardown")
            } catch (e: Exception) {
                Timber.tag(TAG).w("Error disabling accessibility service during teardown: ${e.message}")
            }

            // 8. دفع حالة اكتمال الإلغاء للسحابة وتحديث السياسة المحلية دون إعادة تفعيل القيود
            try {
                if (currentPolicy.isSupervised && !currentPolicy.guardianUid.isNullOrBlank()) {
                    val myUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
                    val updates = mapOf(
                        "deactivationStatus" to DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED,
                        "deactivationUnlockAt" to null,
                        "updatedAt" to now
                    )
                    FirebaseFirestore.getInstance()
                        .collection("policies")
                        .document(myUid)
                        .update(updates)
                        .await()
                    Timber.tag(TAG).i("Deactivation completed pushed to Firestore for uid: $myUid")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to push deactivation completed to Firestore: ${e.message}")
            }

            try {
                val updated = currentPolicy.copy(
                    deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED,
                    deactivationUnlockAt = null,
                    isSupervised = false,
                    guardianUid = null,
                    guardianName = null,
                    updatedAt = now
                )
                policyRepo.updateLocalPolicyWithoutEnforcing(updated)
                Timber.tag(TAG).i("Local policy updated to COMPLETED without DPM re-enforcement")
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed updating local policy to deactivation completed: ${e.message}")
            }

            // 9. إسقاط صلاحية مالك الجهاز أو ملف التعريف ذرياً وإلغاء مسؤولي الجهاز
            if (dpm.isDeviceOwnerApp(appContext.packageName)) {
                @Suppress("DEPRECATION")
                dpm.clearDeviceOwnerApp(appContext.packageName)
                Timber.tag(TAG).i("[TEARDOWN_SUCCESS] clearDeviceOwnerApp completed successfully")
            } else if (dpm.isProfileOwnerApp(appContext.packageName)) {
                @Suppress("DEPRECATION")
                dpm.clearProfileOwner(admin)
                Timber.tag(TAG).i("[TEARDOWN_SUCCESS] clearProfileOwner completed successfully")
            }

            try {
                dpm.removeActiveAdmin(admin)
                Timber.tag(TAG).i("removeActiveAdmin completed successfully")
            } catch (e: Exception) {
                Timber.tag(TAG).w("Could not removeActiveAdmin: ${e.message}")
            }

            BlackBoxRecorder.recordDpm(
                context = appContext,
                action = "DEVICE_OWNER_TEARDOWN_COMPLETED",
                latencyMs = 0L,
                isSuccess = true,
                details = "Device Owner successfully deactivated and all restrictions unwound"
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[TEARDOWN_FATAL] Failed to execute clearDeviceOwnerApp cleanly")
            BlackBoxRecorder.recordDpm(
                context = appContext,
                action = "DEVICE_OWNER_TEARDOWN_FAILED",
                latencyMs = 0L,
                isSuccess = false,
                details = "Error: ${e.message}",
                error = e.message
            )
            Result.failure(e)
        }
    }
}
