package com.drmohammed.antiporn.waqfa.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import org.json.JSONObject

/**
 * مخزن حالة التهدئة المؤقتة (Cooldown Store).
 * يسجل التطبيقات المجمدة مؤقتاً بتوقيت موثوق لاستعادتها وفك تجميدها بدقة حتى لو أعيد تشغيل الهاتف.
 */
object WaqfaCooldownStore {

    private const val TAG = "AntiPorn_WaqfaCooldown"
    private const val PREFS_NAME = "waqfa_cooldown_prefs"
    private const val KEY_PREFIX_FROZEN = "frozen_"

    data class FrozenPackage(
        val packageName: String,
        val appName: String,
        val freezeStartWallMs: Long,
        val freezeEndWallMs: Long,
        val cooldownMinutes: Int
    ) {
        fun isExpired(nowMs: Long): Boolean = nowMs >= freezeEndWallMs

        fun remainingMs(nowMs: Long): Long = maxOf(0L, freezeEndWallMs - nowMs)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun recordCooldown(
        context: Context,
        packageName: String,
        appName: String,
        cooldownMinutes: Int
    ): FrozenPackage {
        val nowTrusted = TrustedTimeProvider.currentTimeMillis(context)
        val endWall = nowTrusted + (cooldownMinutes * 60_000L)
        val frozen = FrozenPackage(
            packageName = packageName,
            appName = appName,
            freezeStartWallMs = nowTrusted,
            freezeEndWallMs = endWall,
            cooldownMinutes = cooldownMinutes
        )

        val json = JSONObject().apply {
            put("packageName", frozen.packageName)
            put("appName", frozen.appName)
            put("freezeStartWallMs", frozen.freezeStartWallMs)
            put("freezeEndWallMs", frozen.freezeEndWallMs)
            put("cooldownMinutes", frozen.cooldownMinutes)
        }

        getPrefs(context).edit()
            .putString(KEY_PREFIX_FROZEN + packageName, json.toString())
            .apply()

        Log.i(TAG, "Recorded cooldown for $packageName until wallMs=$endWall ($cooldownMinutes min)")
        return frozen
    }

    @Synchronized
    fun clearCooldown(context: Context, packageName: String) {
        getPrefs(context).edit()
            .remove(KEY_PREFIX_FROZEN + packageName)
            .apply()
        Log.i(TAG, "Cleared cooldown record for $packageName")
    }

    @Synchronized
    fun getFrozenPackage(context: Context, packageName: String): FrozenPackage? {
        val jsonStr = getPrefs(context).getString(KEY_PREFIX_FROZEN + packageName, null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            FrozenPackage(
                packageName = json.getString("packageName"),
                appName = json.optString("appName", json.getString("packageName")),
                freezeStartWallMs = json.getLong("freezeStartWallMs"),
                freezeEndWallMs = json.getLong("freezeEndWallMs"),
                cooldownMinutes = json.getInt("cooldownMinutes")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing frozen package json for $packageName", e)
            null
        }
    }

    @Synchronized
    fun getAllFrozenPackages(context: Context): List<FrozenPackage> {
        val prefs = getPrefs(context)
        val list = mutableListOf<FrozenPackage>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_PREFIX_FROZEN) && value is String) {
                try {
                    val json = JSONObject(value)
                    list.add(
                        FrozenPackage(
                            packageName = json.getString("packageName"),
                            appName = json.optString("appName", json.getString("packageName")),
                            freezeStartWallMs = json.getLong("freezeStartWallMs"),
                            freezeEndWallMs = json.getLong("freezeEndWallMs"),
                            cooldownMinutes = json.getInt("cooldownMinutes")
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing frozen package in getAll", e)
                }
            }
        }
        return list
    }

    @Synchronized
    fun isPackageInCooldown(context: Context, packageName: String): Boolean {
        val frozen = getFrozenPackage(context, packageName) ?: return false
        val nowTrusted = TrustedTimeProvider.currentTimeMillis(context)
        return !frozen.isExpired(nowTrusted)
    }
}
