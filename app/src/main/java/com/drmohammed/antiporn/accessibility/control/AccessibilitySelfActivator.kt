package com.drmohammed.antiporn.accessibility.control

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import timber.log.Timber

/**
 * مدير التفعيل والإفاقة والتخفي الذاتي لخدمة الوصول (HisnAccessibilityService).
 * يستفيد من صلاحيات Device Owner و WRITE_SECURE_SETTINGS للتحكم البرمجي الصامت.
 */
object AccessibilitySelfActivator {

    private const val TAG = "AntiPorn_A11yActivator"
    private const val COLON_DELIMITER = ":"

    @Volatile
    var isTemporarilyCloaked: Boolean = false
        private set

    fun getServiceComponent(context: Context): ComponentName {
        return ComponentName(context.applicationContext, HisnAccessibilityService::class.java)
    }

    /**
     * يتحقق مما إذا كان التطبيق يمتلك صلاحية WRITE_SECURE_SETTINGS رسمياً.
     */
    fun canWriteSecureSettings(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context.applicationContext,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * يتحقق مما إذا كانت الخدمة مفعلة ومسجلة في إعدادات نظام أندرويد.
     */
    fun isServiceEnabledInSettings(context: Context): Boolean {
        return try {
            val resolver = context.applicationContext.contentResolver
            val isGlobalEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (!isGlobalEnabled) return false

            val enabledServices = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()

            val component = getServiceComponent(context)
            val longName = component.flattenToString()
            val shortName = component.flattenToShortString()

            val servicesList = enabledServices.split(COLON_DELIMITER).map { it.trim() }
            servicesList.contains(longName) || servicesList.contains(shortName)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error checking isServiceEnabledInSettings")
            false
        }
    }

    /**
     * تفعيل خدمة الوصول برمجياً وبصمت تام دون مسح أي خدمات أخرى للمستخدم.
     *
     * @param forceUncloak عند تمرير true، يُلغى وضع التخفي المؤقت ويُفرض التفعيل مجدداً.
     */
    @Synchronized
    fun enableSilently(context: Context, forceUncloak: Boolean = false): Boolean {
        val appContext = context.applicationContext

        if (forceUncloak) {
            isTemporarilyCloaked = false
        } else if (isTemporarilyCloaked) {
            Timber.tag(TAG).d("enableSilently skipped: Service is temporarily cloaked (e.g. Banking protection)")
            return false
        }

        try {
            // 1. التأكد من تمكين المكون البرمجي في PackageManager
            val component = getServiceComponent(appContext)
            val pm = appContext.packageManager
            if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }

            // 2. السماح للخدمة على مستوى DevicePolicyManager إذا كان التطبيق Device Owner
            if (SafeDpmGateway.isDeviceOwner(appContext)) {
                try {
                    val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    val admin = SafeDpmGateway.getAdminComponent(appContext)
                    if (dpm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val permitted = dpm.getPermittedAccessibilityServices(admin)
                        if (permitted != null && !permitted.contains(appContext.packageName)) {
                            // إضافة حزمتنا للقائمة المسموحة دون إزالة القيود عن التطبيقات الأخرى
                            dpm.setPermittedAccessibilityServices(admin, permitted + appContext.packageName)
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed reconciling permitted accessibility services via DPM")
                }
            }

            // 3. قراءة الإعدادات الحالية من Settings.Secure
            val resolver = appContext.contentResolver
            val existingServices = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()

            val longName = component.flattenToString()
            val shortName = component.flattenToShortString()

            val currentTokens = existingServices.split(COLON_DELIMITER)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()

            val alreadyConfigured = currentTokens.contains(longName) || currentTokens.contains(shortName)

            if (!alreadyConfigured) {
                currentTokens.add(longName)
                val newConfig = currentTokens.joinToString(COLON_DELIMITER)
                val writeOk = Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newConfig
                )
                Timber.tag(TAG).i("Added $longName to ENABLED_ACCESSIBILITY_SERVICES (Result: $writeOk)")
            }

            // 4. تفعيل الراية العامة ACCESSIBILITY_ENABLED = 1
            if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }

            Timber.tag(TAG).i("HisnAccessibilityService successfully enabled silently")
            return true
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "SecurityException: Missing WRITE_SECURE_SETTINGS permission")
            return false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unexpected error in enableSilently")
            return false
        }
    }

    /**
     * إيقاف خدمة الوصول وإخفاؤها صامتاً.
     *
     * @param isTemporaryCloak إذا كان true، يُفعّل وضع التخفي المؤقت لتطبيقات البنوك لمنع الدروع من إيقاظها.
     */
    @Synchronized
    fun disableAndCloakSilently(context: Context, isTemporaryCloak: Boolean = false): Boolean {
        val appContext = context.applicationContext
        isTemporarilyCloaked = isTemporaryCloak

        return try {
            val component = getServiceComponent(appContext)
            val longName = component.flattenToString()
            val shortName = component.flattenToShortString()

            val resolver = appContext.contentResolver
            val existingServices = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()

            val remainingTokens = existingServices.split(COLON_DELIMITER)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != longName && it != shortName }

            val updatedConfig = remainingTokens.joinToString(COLON_DELIMITER)
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updatedConfig
            )

            if (remainingTokens.isEmpty()) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            }

            if (!isTemporaryCloak) {
                // في حالة التفكيك الكامل (DO Teardown)، نعطل المكون كلياً في PackageManager
                appContext.packageManager.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }

            Timber.tag(TAG).i("HisnAccessibilityService disabled/cloaked (isTemporaryCloak=$isTemporaryCloak)")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed disabling/cloaking HisnAccessibilityService")
            false
        }
    }
}
