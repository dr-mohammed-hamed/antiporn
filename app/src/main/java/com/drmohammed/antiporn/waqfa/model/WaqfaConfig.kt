package com.drmohammed.antiporn.waqfa.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * كائن إعدادات سياسة نظام «وَقْفَة» المدمج ضمن [com.drmohammed.antiporn.core.model.DevicePolicyBundle].
 * يدعم المزامنة السلسة أوفلاين وسحابياً عبر Firestore دون كسر التوافق العكسي.
 */
data class WaqfaConfig(
    val isEnabled: Boolean = false,
    val targetPackages: Set<String> = emptySet(),
    val quickDurationsMinutes: List<Int> = DEFAULT_DURATIONS,
    val isReasonRequired: Boolean = false,
    val timeoutReactionType: WaqfaTimeoutReactionType = WaqfaTimeoutReactionType.EXIT_ONLY,
    val cooldownMinutes: Int = DEFAULT_COOLDOWN_MINUTES
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "isEnabled" to isEnabled,
        "targetPackages" to targetPackages.toList(),
        "quickDurationsMinutes" to quickDurationsMinutes,
        "isReasonRequired" to isReasonRequired,
        "timeoutReactionType" to timeoutReactionType.name,
        "cooldownMinutes" to cooldownMinutes
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("isEnabled", isEnabled)
        put("targetPackages", JSONArray(targetPackages))
        put("quickDurationsMinutes", JSONArray(quickDurationsMinutes))
        put("isReasonRequired", isReasonRequired)
        put("timeoutReactionType", timeoutReactionType.name)
        put("cooldownMinutes", cooldownMinutes)
    }

    companion object {
        val DEFAULT_DURATIONS: List<Int> = listOf(10, 15, 30)
        const val DEFAULT_COOLDOWN_MINUTES: Int = 3

        fun fromMap(map: Any?): WaqfaConfig {
            val typedMap = map as? Map<*, *> ?: return WaqfaConfig()
            val pkgs = (typedMap["targetPackages"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.filter { it.isNotBlank() }
                ?.toSet() ?: emptySet()

            val durations = (typedMap["quickDurationsMinutes"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toInt() }
                ?.filter { it > 0 }
                ?: DEFAULT_DURATIONS

            val reactionStr = typedMap["timeoutReactionType"] as? String ?: WaqfaTimeoutReactionType.EXIT_ONLY.name
            val reaction = runCatching { WaqfaTimeoutReactionType.valueOf(reactionStr) }
                .getOrDefault(WaqfaTimeoutReactionType.EXIT_ONLY)

            val cooldown = ((typedMap["cooldownMinutes"] as? Number)?.toInt() ?: DEFAULT_COOLDOWN_MINUTES).coerceAtLeast(1)

            return WaqfaConfig(
                isEnabled = typedMap["isEnabled"] as? Boolean ?: false,
                targetPackages = pkgs,
                quickDurationsMinutes = if (durations.isEmpty()) DEFAULT_DURATIONS else durations,
                isReasonRequired = typedMap["isReasonRequired"] as? Boolean ?: false,
                timeoutReactionType = reaction,
                cooldownMinutes = cooldown
            )
        }

        fun fromJson(json: JSONObject?): WaqfaConfig {
            if (json == null) return WaqfaConfig()
            val pkgs = mutableSetOf<String>()
            json.optJSONArray("targetPackages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val pkg = arr.optString(i)
                    if (!pkg.isNullOrBlank()) pkgs.add(pkg)
                }
            }

            val durations = mutableListOf<Int>()
            json.optJSONArray("quickDurationsMinutes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val d = arr.optInt(i)
                    if (d > 0) durations.add(d)
                }
            }

            val reactionStr = json.optString("timeoutReactionType", WaqfaTimeoutReactionType.EXIT_ONLY.name)
            val reaction = runCatching { WaqfaTimeoutReactionType.valueOf(reactionStr) }
                .getOrDefault(WaqfaTimeoutReactionType.EXIT_ONLY)

            val cooldown = json.optInt("cooldownMinutes", DEFAULT_COOLDOWN_MINUTES).coerceAtLeast(1)

            return WaqfaConfig(
                isEnabled = json.optBoolean("isEnabled", false),
                targetPackages = pkgs,
                quickDurationsMinutes = if (durations.isEmpty()) DEFAULT_DURATIONS else durations,
                isReasonRequired = json.optBoolean("isReasonRequired", false),
                timeoutReactionType = reaction,
                cooldownMinutes = cooldown
            )
        }
    }
}
