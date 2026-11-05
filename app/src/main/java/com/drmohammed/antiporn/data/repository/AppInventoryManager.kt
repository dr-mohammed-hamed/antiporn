package com.drmohammed.antiporn.data.repository

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.data.model.InstalledAppDto
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Singleton repository responsible for scanning installed applications,
 * tracking their policy states (suspended, protected, curfew whitelist),
 * and synchronizing the app inventory with Firestore for guardian oversight.
 */
class AppInventoryManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var lastSyncElapsedRealtime: Long = 0L

    /**
     * Scans installed applications on the device, filtering out core system applications,
     * and compiles their current policy enforcement states.
     */
    fun getInstalledApps(context: Context = appContext): List<InstalledAppDto> {
        val pm = context.packageManager
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(context, AdminReceiver::class.java)
        val isDeviceOwner = com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(context)

        val policy = try {
            PolicyRepository.getInstance(context).getCurrentPolicy()
        } catch (e: Exception) {
            Log.w(TAG, "Could not obtain current policy bundle, using defaults", e)
            null
        }

        val customCurfewWhitelist = policy?.customCurfewWhitelistPackages ?: emptySet()
        val curfewVpnBypass = policy?.curfewVpnBypassPackages ?: emptySet()
        val customBlacklist = policy?.customBlacklistPackages ?: emptySet()
        val protectedPackages = policy?.protectedAppPackages ?: emptySet()

        val installedApplications = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed retrieving installed applications", e)
            emptyList<ApplicationInfo>()
        }

        val launchablePackages = try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }.map { it.activityInfo.packageName }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving launchable packages for inventory", e)
            emptySet()
        }

        val systemCritical = AppPolicyRegistry.getSystemCriticalPackages()

        return installedApplications
            .filter { appInfo ->
                val pkg = appInfo.packageName
                if (pkg == context.packageName || systemCritical.contains(pkg)) {
                    return@filter false
                }
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                // Include user-installed apps, updated system apps, launchable apps (e.g. Chrome, YouTube), or currently managed apps
                !isSystem || isUpdatedSystem || launchablePackages.contains(pkg) ||
                        customBlacklist.contains(pkg) || customCurfewWhitelist.contains(pkg) || protectedPackages.contains(pkg)
            }
            .map { appInfo ->
                val pkg = appInfo.packageName
                val appName = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    pkg
                }

                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val isSystem = isSystemApp && !isUpdatedSystem

                val isDpmSuspended = if (isDeviceOwner) {
                    try {
                        dpm?.isPackageSuspended(admin, pkg) == true
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed querying package suspension state for $pkg", e)
                        false
                    }
                } else {
                    false
                }

                val isSuspended = isDpmSuspended || customBlacklist.contains(pkg)
                val isCurfewWhitelisted = customCurfewWhitelist.contains(pkg)
                val isCurfewVpnBypassed = curfewVpnBypass.contains(pkg)
                val isProtected = protectedPackages.contains(pkg)

                InstalledAppDto(
                    packageName = pkg,
                    appName = appName,
                    isSystem = isSystem,
                    isSuspended = isSuspended,
                    isCurfewWhitelisted = isCurfewWhitelisted,
                    isCurfewVpnBypassed = isCurfewVpnBypassed,
                    isProtected = isProtected,
                    updatedAt = System.currentTimeMillis()
                )
            }
    }

    /**
     * Uploads the full installed applications inventory to Firestore under `/inventories/{wardUid}`.
     * Enforces a 15-minute throttle between calls unless [force] is true.
     */
    suspend fun syncInventoryToCloud(force: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val elapsedNow = SystemClock.elapsedRealtime()
            if (!force && (elapsedNow - lastSyncElapsedRealtime < MIN_SYNC_INTERVAL_MS)) {
                Log.d(TAG, "Skipping inventory sync: throttled (last sync ${(elapsedNow - lastSyncElapsedRealtime) / 1000}s ago)")
                return@withContext
            }

            val uid = AccountabilityRepository.getInstance(appContext).getCachedUid()
            if (uid.isBlank()) {
                Log.w(TAG, "Cannot sync inventory: Cached ward UID is blank")
                return@withContext
            }

            val apps = getInstalledApps(appContext)
            val data = mapOf(
                "wardUid" to uid,
                "updatedAt" to now,
                "apps" to apps.map { it.toMap() }
            )

            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("inventories").document(uid).set(data).await()
            lastSyncElapsedRealtime = elapsedNow
            Log.i(TAG, "Successfully synced ${apps.size} installed apps to cloud inventory for ward $uid")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing app inventory to Firestore", e)
        }
    }

    /**
     * Triggers asynchronous cloud synchronization of the device application inventory.
     */
    fun syncInventoryToCloudAsync(force: Boolean = false) {
        scope.launch {
            try {
                syncInventoryToCloud(force)
            } catch (e: Exception) {
                Log.e(TAG, "Background sync inventory execution failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_AppInventory"
        private const val MIN_SYNC_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes throttle

        @Volatile
        private var instance: AppInventoryManager? = null

        fun getInstance(context: Context): AppInventoryManager {
            return try {
                org.koin.core.context.GlobalContext.getOrNull()?.get<AppInventoryManager>()
            } catch (e: Exception) {
                null
            } ?: (instance ?: synchronized(this) {
                instance ?: AppInventoryManager(context.applicationContext).also {
                    instance = it
                }
            })
        }
    }
}
