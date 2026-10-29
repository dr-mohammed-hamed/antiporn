package com.drmohammed.antiporn.provisioning.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.net.toUri
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Enterprise Provisioning Activity (Android 12+ / API 31+).
 * Handles:
 * 1. [DevicePolicyManager.ACTION_GET_PROVISIONING_MODE]: Declares fully managed device mode.
 * 2. [DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE]: Enforces baseline policies,
 *    re-enables essential system apps via intent-based resolution, and guarantees
 *    returning [Activity.RESULT_OK] to the Setup Wizard on the UI thread under all circumstances.
 */
class EnterpriseProvisioningActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AntiPorn_Provisioning"
        private val bgExecutor = Executors.newSingleThreadExecutor()
    }

    private val isProvisioningCompleted = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> {
                handleGetProvisioningMode()
            }
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                enableEdgeToEdge()
                setContentView(R.layout.activity_enterprise_provisioning)

                findViewById<View>(R.id.layoutRootEnterpriseProvisioning)?.let { rootView ->
                    val basePadding = (24 * resources.displayMetrics.density).toInt()
                    ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                        val insets = windowInsets.getInsets(
                            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                        )
                        view.setPadding(
                            basePadding + insets.left,
                            basePadding + insets.top,
                            basePadding + insets.right,
                            basePadding + insets.bottom
                        )
                        windowInsets
                    }
                }

                // Protect provisioning flow against accidental back press dismissal
                onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        Log.w(TAG, "Back button press ignored during active provisioning compliance.")
                    }
                })
                handleAdminPolicyCompliance()
            }
            else -> {
                Log.w(TAG, "Unknown or unsupported provisioning action received: ${intent?.action}")
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
    }

    @SuppressLint("InlinedApi")
    private fun handleGetProvisioningMode() {
        Log.i(TAG, "Setup Wizard queried GET_PROVISIONING_MODE. Resolving target provisioning mode...")
        val allowedModes = intent?.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES)
        if (allowedModes != null && !allowedModes.contains(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)) {
            Log.e(TAG, "Device does not support FULLY_MANAGED_DEVICE mode (allowed: $allowedModes). Aborting.")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val resultIntent = Intent().apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun handleAdminPolicyCompliance() {
        Log.i(TAG, "Setup Wizard initiated ADMIN_POLICY_COMPLIANCE. Applying baseline shields in background...")
        val appContext = applicationContext

        bgExecutor.execute {
            try {
                // 1. Enforce baseline security shields (DNS, Anti-tamper, VPN lock, Curfew)
                try {
                    ProtectionEngine.applyAllSync(appContext)
                    Log.i(TAG, "Baseline protection successfully applied during compliance.")
                } catch (t: Throwable) {
                    Log.e(TAG, "Non-fatal error applying protection shields in compliance", t)
                }

                // 2. Dual-layer resilience: ensure core system applications remain active across all OEMs
                try {
                    val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    if (dpm != null && dpm.isDeviceOwnerApp(appContext.packageName)) {
                        val admin = ComponentName(appContext, AdminReceiver::class.java)
                        enableCoreSystemApps(appContext, dpm, admin)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Non-fatal error enabling core system apps in compliance", t)
                }
            } finally {
                // 3. Guarantees returning RESULT_OK to Setup Wizard on UI thread under ALL circumstances
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (isProvisioningCompleted.compareAndSet(false, true)) {
                            // ALWAYS signal RESULT_OK first so Setup Wizard transitions cleanly
                            try {
                                setResult(Activity.RESULT_OK)
                                Log.i(TAG, "Signaled Activity.RESULT_OK to Setup Wizard.")
                            } catch (t: Throwable) {
                                Log.e(TAG, "Error setting Activity.RESULT_OK", t)
                            }

                            try {
                                // Bootstrap MainActivity so it greets the user upon OOBE conclusion
                                val launchIntent = appContext.packageManager?.getLaunchIntentForPackage(appContext.packageName)?.apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                } ?: Intent(appContext, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                                appContext.startActivity(launchIntent)
                                Log.i(TAG, "Dispatched MainActivity bootstrap intent successfully.")
                            } catch (t: Throwable) {
                                Log.e(TAG, "Non-fatal error launching MainActivity from compliance activity", t)
                            } finally {
                                finish()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Ultra-defensive core system apps enabler:
     * Combines dynamic Intent-based package resolution with explicit known OEM fallbacks (Samsung, Xiaomi,
     * Realme/ColorOS, Pixel, Transsion). Every single resolution and enable call is isolated in its own
     * try-catch block to guarantee zero crashes on any device.
     */
    private fun enableCoreSystemApps(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        try {
            val pm = context.packageManager ?: return

            // 1. Intent definitions for essential smartphone functionalities
            val coreIntents = listOf(
                Intent(Intent.ACTION_DIAL),                                          // Phone / Dialer
                Intent(Intent.ACTION_SENDTO).apply { data = "smsto:".toUri() },      // SMS / Messages
                Intent(MediaStore.ACTION_IMAGE_CAPTURE),                             // Camera
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY) // Gallery / Photos
            )

            // Dynamic Intent-based resolution: queries OEM-installed system handlers
            for (intent in coreIntents) {
                // A) Direct intent enablement via DPM
                try {
                    dpm.enableSystemApp(admin, intent)
                } catch (t: Throwable) {
                    Log.d(TAG, "enableSystemApp(intent) skipped for $intent: ${t.message}")
                }

                // B) Resolve matching system package names and enable individually
                try {
                    val resolvedActivities = pm.queryIntentActivities(
                        intent,
                        PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_UNINSTALLED_PACKAGES
                    )
                    for (resolveInfo in resolvedActivities.orEmpty()) {
                        val pkgName = resolveInfo.activityInfo?.packageName
                        if (!pkgName.isNullOrBlank() && pkgName != context.packageName) {
                            try {
                                dpm.enableSystemApp(admin, pkgName)
                                Log.d(TAG, "Enabled resolved system app: $pkgName")
                            } catch (t: Throwable) {
                                Log.d(TAG, "enableSystemApp(pkg) skipped for $pkgName: ${t.message}")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "queryIntentActivities skipped for $intent: ${t.message}")
                }
            }

            // 2. Explicit OEM-specific packages fallback (Google, Samsung, Xiaomi, Realme/Oppo, Transsion)
            val oemKnownPackages = listOf(
                // Google Play Core & Framework
                "com.android.vending",
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.android.chrome",

                // Google Pixel Core
                "com.google.android.dialer",
                "com.google.android.apps.messaging",
                "com.google.android.GoogleCamera",
                "com.google.android.apps.photos",

                // Samsung OneUI Core
                "com.samsung.android.dialer",
                "com.samsung.android.messaging",
                "com.sec.android.app.camera",
                "com.sec.android.gallery3d",

                // Xiaomi / MIUI / HyperOS Core
                "com.android.contacts",
                "com.android.mms",
                "com.android.camera",
                "com.miui.gallery",

                // Realme / Oppo / OnePlus (ColorOS / OxygenOS)
                "com.coloros.phoneno",
                "com.oppo.camera",
                "com.coloros.gallery3d",

                // Transsion (Infinix / Tecno / itel)
                "com.transsion.camera"
            )

            for (pkg in oemKnownPackages) {
                try {
                    dpm.enableSystemApp(admin, pkg)
                } catch (t: Throwable) {
                    // Normal on devices from other manufacturers; silently proceed
                    Log.v(TAG, "OEM app $pkg not present or already active")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected outer exception in enableCoreSystemApps (safe guard)", t)
        }
    }
}
