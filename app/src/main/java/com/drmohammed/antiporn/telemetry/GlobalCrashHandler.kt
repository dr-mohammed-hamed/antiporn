package com.drmohammed.antiporn.telemetry

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.data.local.db.AntiPornDatabase
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central Global Crash Handler for AntiPorn.
 * Intercepts uncaught runtime exceptions across all threads, extracts critical system diagnostics
 * (battery, memory, Device Owner status, device specs, stacktrace), writes an atomic emergency dump
 * file to internal storage, logs the crash to SQLite WAL database, and delegates cleanly to the OS default handler.
 */
class GlobalCrashHandler private constructor(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val crashReport = generateCrashReport(thread, throwable)

            // Step 1: Emergency raw disk persistence (Highest resilience, no external dependencies)
            writeCrashDumpToDisk(crashReport)

            // Step 2: Attempt synchronous persistence into SQLite WAL table
            writeCrashToDatabase(thread, throwable, crashReport)

        } catch (t: Throwable) {
            // Safety net: Never allow exceptions in the crash handler to block app termination
            Log.e(TAG, "Secondary error in GlobalCrashHandler while capturing crash", t)
        } finally {
            // Step 3: Clean delegation to Android's default uncaught exception handler
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Constructs a structured CrashReport model with hardware, OS, and enterprise metrics.
     */
    private fun generateCrashReport(thread: Thread, throwable: Throwable): CrashReport {
        val now = System.currentTimeMillis()
        val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(now))
        val stackTrace = Log.getStackTraceString(throwable)

        // Query Battery
        val batteryLevel = try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "Error querying battery level in crash handler", e)
            -1
        }

        // Query Memory
        var availMemMb = -1L
        var totalMemMb = -1L
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            availMemMb = memInfo.availMem / (1024 * 1024)
            totalMemMb = memInfo.totalMem / (1024 * 1024)
        } catch (e: Exception) {
            Log.w(TAG, "Error querying memory info in crash handler", e)
        }

        // Query App Version
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
            Log.w(TAG, "Error querying package info in crash handler", e)
        }

        // Query Device Owner & Admin Status
        val isDeviceOwner = SafeDpmGateway.isDeviceOwner(context)
        val isAdminActive = SafeDpmGateway.isAdminActive(context)

        return CrashReport(
            timestampMs = now,
            timestampIso = isoDate,
            threadName = thread.name,
            threadId = if (Build.VERSION.SDK_INT >= 36) {
                thread.threadId()
            } else {
                @Suppress("DEPRECATION")
                thread.id
            },
            exceptionClass = throwable.javaClass.name,
            exceptionMessage = throwable.message ?: "No error message provided",
            stackTrace = stackTrace,
            batteryPercent = batteryLevel,
            availableMemoryMb = availMemMb,
            totalMemoryMb = totalMemMb,
            isDeviceOwner = isDeviceOwner,
            isAdminActive = isAdminActive,
            appVersionName = verName,
            appVersionCode = verCode,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            androidVersion = Build.VERSION.RELEASE
        )
    }

    /**
     * Atomically writes plain text and JSON crash dumps to internal filesDir.
     */
    private fun writeCrashDumpToDisk(report: CrashReport) {
        try {
            val txtFile = File(context.filesDir, DUMP_FILE_TXT)
            FileOutputStream(txtFile).use { fos ->
                fos.write(report.toReadableText().toByteArray(Charsets.UTF_8))
                fos.flush()
            }
            Log.i(TAG, "Successfully flushed emergency crash dump to ${txtFile.absolutePath}")

            val jsonFile = File(context.filesDir, DUMP_FILE_JSON)
            FileOutputStream(jsonFile).use { fos ->
                fos.write(report.toJsonString().toByteArray(Charsets.UTF_8))
                fos.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing emergency crash dump to disk", e)
        }
    }

    /**
     * Inserts the crash event into SQLite WAL diagnostic table.
     */
    private fun writeCrashToDatabase(thread: Thread, throwable: Throwable, report: CrashReport) {
        try {
            val db = AntiPornDatabase.getInstance(context)
            val metadata = JSONObject().apply {
                put("thread", thread.name)
                put("battery", report.batteryPercent)
                put("isDO", report.isDeviceOwner)
                put("isAdmin", report.isAdminActive)
                put("sdk", report.sdkInt)
                put("memAvailMb", report.availableMemoryMb)
            }.toString()

            val errorDetails = "${report.exceptionClass}: ${report.exceptionMessage}\n" +
                    report.stackTrace.take(1500)

            db.insertDiagnosticEvent(
                tag = "CRASH",
                actionName = "UNCAUGHT_EXCEPTION",
                executionTimeMs = 0L,
                isSuccess = false,
                errorDetails = errorDetails,
                contextMetadata = metadata,
                timestamp = report.timestampMs
            )
            Log.i(TAG, "Recorded fatal crash event in AntiPornDatabase diagnostic_events table")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record fatal crash event in database", e)
        }
    }

    companion object {
        private const val TAG = "AntiPorn_CrashHandler"
        const val DUMP_FILE_TXT = "latest_crash_dump.txt"
        const val DUMP_FILE_JSON = "latest_crash_dump.json"

        @Volatile
        private var isInstalled = false

        /**
         * Installs the GlobalCrashHandler as the default uncaught exception handler.
         */
        @Synchronized
        fun install(application: Application) {
            if (isInstalled) {
                Log.d(TAG, "GlobalCrashHandler is already installed; skipping redundant install")
                return
            }
            val existingHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (existingHandler is GlobalCrashHandler) {
                isInstalled = true
                return
            }
            val handler = GlobalCrashHandler(application.applicationContext, existingHandler)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            isInstalled = true
            Log.i(TAG, "GlobalCrashHandler installed successfully as primary UncaughtExceptionHandler")
        }
    }
}

/**
 * Internal CrashReport model representing snapshot of device state upon unexpected termination.
 */
data class CrashReport(
    val timestampMs: Long,
    val timestampIso: String,
    val threadName: String,
    val threadId: Long,
    val exceptionClass: String,
    val exceptionMessage: String,
    val stackTrace: String,
    val batteryPercent: Int,
    val availableMemoryMb: Long,
    val totalMemoryMb: Long,
    val isDeviceOwner: Boolean,
    val isAdminActive: Boolean,
    val appVersionName: String,
    val appVersionCode: Long,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val androidVersion: String
) {
    fun toReadableText(): String {
        return """
================================================================================
💥 ANTIPORN UNCAUGHT CRASH REPORT
================================================================================
Date: $timestampIso ($timestampMs)
App Version: $appVersionName ($appVersionCode)
Device: $manufacturer $model (Android $androidVersion, API $sdkInt)
Battery Level: $batteryPercent%
Memory Available: ${availableMemoryMb}MB / ${totalMemoryMb}MB
Device Owner Active: $isDeviceOwner
Device Admin Active: $isAdminActive
Thread: $threadName (ID: $threadId)

Exception: $exceptionClass
Message: $exceptionMessage

--------------------------------------------------------------------------------
STACKTRACE:
--------------------------------------------------------------------------------
$stackTrace
================================================================================
""".trimIndent()
    }

    fun toJsonString(): String {
        return JSONObject().apply {
            put("timestampMs", timestampMs)
            put("timestampIso", timestampIso)
            put("threadName", threadName)
            put("threadId", threadId)
            put("exceptionClass", exceptionClass)
            put("exceptionMessage", exceptionMessage)
            put("batteryPercent", batteryPercent)
            put("availableMemoryMb", availableMemoryMb)
            put("totalMemoryMb", totalMemoryMb)
            put("isDeviceOwner", isDeviceOwner)
            put("isAdminActive", isAdminActive)
            put("appVersionName", appVersionName)
            put("appVersionCode", appVersionCode)
            put("manufacturer", manufacturer)
            put("model", model)
            put("sdkInt", sdkInt)
            put("androidVersion", androidVersion)
            put("stackTrace", stackTrace)
        }.toString(2)
    }
}
