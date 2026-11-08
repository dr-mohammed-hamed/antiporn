package com.drmohammed.antiporn.waqfa.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.drmohammed.antiporn.waqfa.ui.gate.WaqfaGateActivity

/**
 * منسق اعتراض فتح التطبيقات المستهدفة وتوجيه المستخدم لنافذة «وَقْفَة».
 */
object WaqfaInterceptor {

    private const val TAG = "AntiPorn_WaqfaInterceptor"

    @Volatile
    private var lastInterceptionTime = 0L

    @Volatile
    private var lastInterceptedPackage = ""

    private const val INTERCEPTION_DEBOUNCE_MS = 600L

    fun resolveAppLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving app label for $packageName", e)
            packageName
        }
    }

    /**
     * يُفحص التطبيق النشط في الواجهة. إذا كان خاضعاً لوقفة ولا يملك جلسة نشطة، تُطلق نافذة الاعتراض فوراً.
     */
    fun evaluateForegroundPackage(context: Context, foregroundPackage: String, isPackageChanged: Boolean = true) {
        if (foregroundPackage.isBlank() || foregroundPackage == context.packageName) {
            return
        }

        val sessionMgr = WaqfaSessionManager.getInstance(context)

        // 1. إخطار مدير الجلسات بتغير التطبيق فقط إذا تغير التطبيق فعلياً لمنع حجز الـ Mutex غير المبرر
        if (isPackageChanged) {
            sessionMgr.onForegroundPackageChanged(foregroundPackage)
        }

        val config = sessionMgr.getConfig()
        if (!config.isEnabled || !config.targetPackages.contains(foregroundPackage)) {
            return
        }

        // 2. التحقق مما إذا كان التطبيق في فترة تهدئة وتجميد مؤقت
        if (WaqfaCooldownStore.isPackageInCooldown(context, foregroundPackage)) {
            Log.i(TAG, "Target $foregroundPackage is currently in cooldown. Forcing exit.")
            WaqfaReactionExecutor.kickToHomeScreen(context)
            return
        }

        // 3. التحقق مما إذا كان المستخدم يمتلك جلسة نشطة سارية المفعول
        if (sessionMgr.hasActiveValidSessionBlocking(foregroundPackage)) {
            // الجلسة سارية، يُسمح بالاستخدام
            return
        }

        // 4. منع إطلاق نوافذ مكررة متتابعة في أجزاء من الثانية (Debounce Guard)
        val now = android.os.SystemClock.elapsedRealtime()
        if (foregroundPackage == lastInterceptedPackage && (now - lastInterceptionTime < INTERCEPTION_DEBOUNCE_MS)) {
            return
        }

        lastInterceptedPackage = foregroundPackage
        lastInterceptionTime = now

        // 5. إطلاق شاشة الاعتراض الكاملة فوراً وبأعلى أولوية
        val appName = resolveAppLabel(context, foregroundPackage)
        launchGate(context, foregroundPackage, appName)
    }

    fun launchGate(context: Context, packageName: String, appName: String) {
        try {
            val intent = Intent(context, WaqfaGateActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(WaqfaGateActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(WaqfaGateActivity.EXTRA_APP_NAME, appName)
            }
            context.startActivity(intent)
            Log.i(TAG, "Launched WaqfaGateActivity for $packageName ($appName)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching WaqfaGateActivity for $packageName", e)
        }
    }
}
