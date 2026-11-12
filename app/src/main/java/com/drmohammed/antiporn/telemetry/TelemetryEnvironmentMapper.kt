package com.drmohammed.antiporn.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat

internal data class TelemetryEnvironmentInfo(
    val batteryPercent: Int,
    val availableMemoryMb: Long,
    val totalMemoryMb: Long,
    val isDeviceOwner: Boolean,
    val isAdminActive: Boolean,
    val appVersionName: String,
    val appVersionCode: Long,
    val manufacturer: String,
    val model: String,
    val device: String,
    val sdkInt: Int,
    val androidVersion: String
)

/**
 * Collects sanitized hardware, OS, battery, memory, and Enterprise ownership metadata
 * for diagnostic telemetry reporting.
 */
internal object TelemetryEnvironmentMapper {

    private const val TAG = "AntiPorn_AiReporter"

    fun collect(context: Context): TelemetryEnvironmentInfo {
        val batteryLevel = try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "Error querying battery level", e)
            -1
        }

        var availMemMb = -1L
        var totalMemMb = -1L
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            availMemMb = memInfo.availMem / (1024 * 1024)
            totalMemMb = memInfo.totalMem / (1024 * 1024)
        } catch (e: Exception) {
            Log.w(TAG, "Error querying memory info", e)
        }

        var verName = "unknown"
        var verCode = -1L
        try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            verName = pInfo.versionName ?: "unknown"
            verCode = PackageInfoCompat.getLongVersionCode(pInfo)
        } catch (e: Exception) {
            Log.w(TAG, "Error querying app version info", e)
        }

        return TelemetryEnvironmentInfo(
            batteryPercent = batteryLevel,
            availableMemoryMb = availMemMb,
            totalMemoryMb = totalMemMb,
            isDeviceOwner = SafeDpmGateway.isDeviceOwner(context),
            isAdminActive = SafeDpmGateway.isAdminActive(context),
            appVersionName = verName,
            appVersionCode = verCode,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            sdkInt = Build.VERSION.SDK_INT,
            androidVersion = Build.VERSION.RELEASE
        )
    }
}
