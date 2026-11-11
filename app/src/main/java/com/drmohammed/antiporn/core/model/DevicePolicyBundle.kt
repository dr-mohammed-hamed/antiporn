package com.drmohammed.antiporn.core.model

import android.util.Log
import com.drmohammed.antiporn.waqfa.model.WaqfaConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unified enterprise policy bundle controlling all protection parameters
 * configured by the user's accountability partner/guardian.
 */
data class DevicePolicyBundle(
    val curfewStartHour: Int = com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR, val curfewStartMinute: Int = com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE,
    val curfewEndHour: Int = com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR, val curfewEndMinute: Int = com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE,
    val isCurfewEnabled: Boolean = true,
    val isInstantInternetKillEnabled: Boolean = false,
    val isWeekendCurfewEnabled: Boolean = false,
    val weekendCurfewStartHour: Int = com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR, val weekendCurfewStartMinute: Int = com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE,
    val weekendCurfewEndHour: Int = com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR, val weekendCurfewEndMinute: Int = com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE,
    val isVpnBlackholeEnabled: Boolean = com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED,
    val isIncognitoBlocked: Boolean = true,
    val lockNewInstallsUntilApproved: Boolean = true,
    val customBlacklistPackages: Set<String> = emptySet(),
    val customCurfewWhitelistPackages: Set<String> = emptySet(),
    val customAiScanPackages: Set<String> = emptySet(),
    val curfewVpnBypassPackages: Set<String> = emptySet(),
    val protectedAppPackages: Set<String> = emptySet(),
    val browserFilterMode: Int = BROWSER_FILTER_OFF,
    val isFullSystemFilteringEnabled: Boolean = false,
    val customBlockedUrls: Set<String> = emptySet(),
    val customAllowedUrls: Set<String> = emptySet(),
    val dnsHost: String = CLOUDFLARE_FAMILY_DNS_HOST,
    val isYouTubeRestrictedModeEnabled: Boolean = false,
    val isTelegramWebBlocked: Boolean = true,
    val guardianUid: String? = null,
    val guardianName: String? = null,
    val isSupervised: Boolean = false,
    val isSelfControlAllowed: Boolean = true,
    val isDateTimeLocked: Boolean = false,
    val timeZoneId: String = com.drmohammed.antiporn.curfew.CurfewConfig.getDefaultFallbackTimeZone(),
    val lastHeartbeat: Long = 0L,
    val breakoutStatus: String? = null,
    val breakoutRequestedAt: Long? = null,
    val breakoutUnlockAt: Long? = null,
    val deactivationStatus: String? = null,
    val deactivationRequestedAt: Long? = null,
    val deactivationUnlockAt: Long? = null,
    val deactivationHasQr: Boolean = false,
    val guardianPolicySnapshot: String? = null,
    val lastSelfModifiedAt: Long? = null,
    val waqfaConfig: WaqfaConfig = WaqfaConfig(),
    val version: Long = 1L,
    val updatedAt: Long = System.currentTimeMillis()
) {

    fun toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "curfewStartHour" to curfewStartHour,
            "curfewStartMinute" to curfewStartMinute,
            "curfewEndHour" to curfewEndHour,
            "curfewEndMinute" to curfewEndMinute,
            "isCurfewEnabled" to isCurfewEnabled,
            "isInstantInternetKillEnabled" to isInstantInternetKillEnabled,
            "isWeekendCurfewEnabled" to isWeekendCurfewEnabled,
            "weekendCurfewStartHour" to weekendCurfewStartHour,
            "weekendCurfewStartMinute" to weekendCurfewStartMinute,
            "weekendCurfewEndHour" to weekendCurfewEndHour,
            "weekendCurfewEndMinute" to weekendCurfewEndMinute,
            "isVpnBlackholeEnabled" to isVpnBlackholeEnabled,
            "isIncognitoBlocked" to isIncognitoBlocked,
            "lockNewInstallsUntilApproved" to lockNewInstallsUntilApproved,
            "customBlacklistPackages" to customBlacklistPackages.toList(),
            "customCurfewWhitelistPackages" to customCurfewWhitelistPackages.toList(),
            "customAiScanPackages" to customAiScanPackages.toList(),
            "curfewVpnBypassPackages" to curfewVpnBypassPackages.toList(),
            "protectedAppPackages" to protectedAppPackages.toList(),
            "browserFilterMode" to browserFilterMode,
            "isFullSystemFilteringEnabled" to isFullSystemFilteringEnabled,
            "customBlockedUrls" to customBlockedUrls.toList(),
            "customAllowedUrls" to customAllowedUrls.toList(),
            "dnsHost" to dnsHost,
            "isYouTubeRestrictedModeEnabled" to isYouTubeRestrictedModeEnabled,
            "isTelegramWebBlocked" to isTelegramWebBlocked,
            "isSupervised" to isSupervised,
            "isSelfControlAllowed" to isSelfControlAllowed,
            "isDateTimeLocked" to isDateTimeLocked,
            "timeZoneId" to timeZoneId,
            "lastHeartbeat" to lastHeartbeat,
            "breakoutStatus" to breakoutStatus,
            "breakoutRequestedAt" to breakoutRequestedAt,
            "breakoutUnlockAt" to breakoutUnlockAt,
            "deactivationStatus" to deactivationStatus,
            "deactivationRequestedAt" to deactivationRequestedAt,
            "deactivationUnlockAt" to deactivationUnlockAt,
            "deactivationHasQr" to deactivationHasQr,
            "guardianPolicySnapshot" to guardianPolicySnapshot,
            "lastSelfModifiedAt" to lastSelfModifiedAt,
            "waqfaConfig" to waqfaConfig.toMap(),
            "version" to version,
            "updatedAt" to updatedAt
        )
        map["guardianUid"] = guardianUid
        map["guardianName"] = guardianName
        return map
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("curfewStartHour", curfewStartHour); json.put("curfewStartMinute", curfewStartMinute)
        json.put("curfewEndHour", curfewEndHour); json.put("curfewEndMinute", curfewEndMinute)
        json.put("isCurfewEnabled", isCurfewEnabled)
        json.put("isInstantInternetKillEnabled", isInstantInternetKillEnabled)
        json.put("isWeekendCurfewEnabled", isWeekendCurfewEnabled)
        json.put("weekendCurfewStartHour", weekendCurfewStartHour); json.put("weekendCurfewStartMinute", weekendCurfewStartMinute)
        json.put("weekendCurfewEndHour", weekendCurfewEndHour); json.put("weekendCurfewEndMinute", weekendCurfewEndMinute)
        json.put("isVpnBlackholeEnabled", isVpnBlackholeEnabled)
        json.put("isIncognitoBlocked", isIncognitoBlocked)
        json.put("lockNewInstallsUntilApproved", lockNewInstallsUntilApproved)

        val blacklistArr = JSONArray().apply { customBlacklistPackages.forEach { put(it) } }
        json.put("customBlacklistPackages", blacklistArr)

        val whitelistArr = JSONArray().apply { customCurfewWhitelistPackages.forEach { put(it) } }
        json.put("customCurfewWhitelistPackages", whitelistArr)

        val aiScanArr = JSONArray().apply { customAiScanPackages.forEach { put(it) } }
        json.put("customAiScanPackages", aiScanArr)

        val vpnBypassArr = JSONArray().apply { curfewVpnBypassPackages.forEach { put(it) } }
        json.put("curfewVpnBypassPackages", vpnBypassArr)

        val protectedArr = JSONArray().apply { protectedAppPackages.forEach { put(it) } }
        json.put("protectedAppPackages", protectedArr)

        json.put("browserFilterMode", browserFilterMode)
        json.put("isFullSystemFilteringEnabled", isFullSystemFilteringEnabled)

        val blockedUrlsArr = JSONArray().apply { customBlockedUrls.forEach { put(it) } }
        json.put("customBlockedUrls", blockedUrlsArr)

        val allowedUrlsArr = JSONArray().apply { customAllowedUrls.forEach { put(it) } }
        json.put("customAllowedUrls", allowedUrlsArr)

        json.put("dnsHost", dnsHost)
        json.put("isYouTubeRestrictedModeEnabled", isYouTubeRestrictedModeEnabled)
        json.put("isTelegramWebBlocked", isTelegramWebBlocked)
        if (guardianUid != null) json.put("guardianUid", guardianUid)
        if (guardianName != null) json.put("guardianName", guardianName)
        json.put("isSupervised", isSupervised)
        json.put("isSelfControlAllowed", isSelfControlAllowed)
        json.put("isDateTimeLocked", isDateTimeLocked)
        json.put("timeZoneId", timeZoneId)
        json.put("lastHeartbeat", lastHeartbeat)
        if (breakoutStatus != null) json.put("breakoutStatus", breakoutStatus)
        if (breakoutRequestedAt != null) json.put("breakoutRequestedAt", breakoutRequestedAt)
        if (breakoutUnlockAt != null) json.put("breakoutUnlockAt", breakoutUnlockAt)
        if (deactivationStatus != null) json.put("deactivationStatus", deactivationStatus)
        if (deactivationRequestedAt != null) json.put("deactivationRequestedAt", deactivationRequestedAt)
        if (deactivationUnlockAt != null) json.put("deactivationUnlockAt", deactivationUnlockAt)
        json.put("deactivationHasQr", deactivationHasQr)
        if (guardianPolicySnapshot != null) json.put("guardianPolicySnapshot", guardianPolicySnapshot)
        if (lastSelfModifiedAt != null) json.put("lastSelfModifiedAt", lastSelfModifiedAt)
        json.put("waqfaConfig", waqfaConfig.toJson())
        json.put("version", version)
        json.put("updatedAt", updatedAt)
        return json.toString()
    }

    companion object {
        private const val TAG = "AntiPorn_PolicyBundle"
        const val BROWSER_FILTER_OFF = 0
        const val BROWSER_FILTER_BLOCKLIST = 1
        const val BROWSER_FILTER_BLOCKLIST_ONLY = 1
        const val BROWSER_FILTER_WHITELIST_ONLY = 2

        const val CONTROLD_FAMILY_DNS_HOST = "no-ads-drugs-malware-porn-gambling-dating-typo.freedns.controld.com"
        const val CONTROLD_NO_ADS_PORN_DNS_HOST = "no-ads-porn.freedns.controld.com"
        const val ADGUARD_FAMILY_DNS_HOST = "family.adguard-dns.com"
        const val CLOUDFLARE_FAMILY_DNS_HOST = "family.cloudflare-dns.com"

        const val BREAKOUT_STATUS_PENDING = "PENDING"
        const val BREAKOUT_STATUS_REJECTED = "REJECTED"
        const val BREAKOUT_STATUS_CANCELLED = "CANCELLED"
        const val BREAKOUT_STATUS_COMPLETED = "COMPLETED"
        const val BREAKOUT_GRACE_PERIOD_MS = 24L * 60L * 60L * 1000L // 24 Hours

        const val DEACTIVATION_STATUS_PENDING = "PENDING"
        const val DEACTIVATION_STATUS_REJECTED = "REJECTED"
        const val DEACTIVATION_STATUS_CANCELLED = "CANCELLED"
        const val DEACTIVATION_STATUS_COMPLETED = "COMPLETED"
        const val DEACTIVATION_DEFAULT_DELAY_MS = 48L * 60L * 60L * 1000L // 48 Hours
        const val DEACTIVATION_QR_DELAY_MS = 24L * 60L * 60L * 1000L // 24 Hours

        fun defaultPolicy(): DevicePolicyBundle = DevicePolicyBundle(
            browserFilterMode = BROWSER_FILTER_OFF,
            isFullSystemFilteringEnabled = false,
            customBlockedUrls = emptySet(),
            customAllowedUrls = emptySet(),
            customCurfewWhitelistPackages = com.drmohammed.antiporn.curfew.CurfewConfig.STATIC_WHITELIST,
            dnsHost = CLOUDFLARE_FAMILY_DNS_HOST,
            isYouTubeRestrictedModeEnabled = false,
            isTelegramWebBlocked = true,
            guardianUid = null,
            guardianName = null,
            isSupervised = false,
            isSelfControlAllowed = true,
            isDateTimeLocked = false,
            timeZoneId = com.drmohammed.antiporn.curfew.CurfewConfig.getDefaultFallbackTimeZone()
        )

        fun reconcileLegacyV1(policy: DevicePolicyBundle): DevicePolicyBundle {
            return if (policy.timeZoneId.isBlank()) {
                policy.copy(timeZoneId = com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID)
            } else {
                policy
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): DevicePolicyBundle {
            val startHour = ((map["curfewStartHour"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR).coerceIn(0, 23)
            val startMin = ((map["curfewStartMinute"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE).coerceIn(0, 59)
            val endHour = ((map["curfewEndHour"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR).coerceIn(0, 23)
            val endMin = ((map["curfewEndMinute"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE).coerceIn(0, 59)
            val isCurfew = (map["isCurfewEnabled"] as? Boolean) ?: true
            val isInstantKill = (map["isInstantInternetKillEnabled"] as? Boolean) ?: false
            val isWeekendCurfew = (map["isWeekendCurfewEnabled"] as? Boolean) ?: false
            val wkStartHour = ((map["weekendCurfewStartHour"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR).coerceIn(0, 23)
            val wkStartMin = ((map["weekendCurfewStartMinute"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE).coerceIn(0, 59)
            val wkEndHour = ((map["weekendCurfewEndHour"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR).coerceIn(0, 23)
            val wkEndMin = ((map["weekendCurfewEndMinute"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE).coerceIn(0, 59)
            val vpn = (map["isVpnBlackholeEnabled"] as? Boolean) ?: com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED
            val incognito = (map["isIncognitoBlocked"] as? Boolean) ?: true
            val lockNew = (map["lockNewInstallsUntilApproved"] as? Boolean) ?: true

            val blacklist = (map["customBlacklistPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            val whitelist = if (!map.containsKey("customCurfewWhitelistPackages")) {
                com.drmohammed.antiporn.curfew.CurfewConfig.STATIC_WHITELIST
            } else {
                (map["customCurfewWhitelistPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            }
            val aiScan = (map["customAiScanPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            val vpnBypass = (map["curfewVpnBypassPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            val protectedApps = (map["protectedAppPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()

            val browserFilterMode = ((map["browserFilterMode"] as? Number)?.toInt()
                ?: (map["browserFilterMode"] as? String)?.toIntOrNull()
                ?: BROWSER_FILTER_OFF).coerceIn(0, 2)
            val isFullSystemFilteringEnabled = (map["isFullSystemFilteringEnabled"] as? Boolean) ?: false
            val customBlockedUrls = (map["customBlockedUrls"] as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            val customAllowedUrls = (map["customAllowedUrls"] as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

            val dns = (map["dnsHost"] as? String)?.takeIf { it.isNotBlank() } ?: CLOUDFLARE_FAMILY_DNS_HOST
            val ytRestricted = (map["isYouTubeRestrictedModeEnabled"] as? Boolean) ?: false
            val tgBlocked = (map["isTelegramWebBlocked"] as? Boolean) ?: true

            val gUid = (map["guardianUid"] as? String)?.takeIf { it.isNotBlank() }
            val gName = (map["guardianName"] as? String)?.takeIf { it.isNotBlank() }
            val supervised = (map["isSupervised"] as? Boolean) ?: (!gUid.isNullOrBlank())
            val isSelfControlAllowed = (map["isSelfControlAllowed"] as? Boolean) ?: true
            val isDateTimeLocked = (map["isDateTimeLocked"] as? Boolean) ?: false
            val timeZoneId = (map["timeZoneId"] as? String)?.takeIf { it.isNotBlank() } ?: com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID
            val lastHeartbeat = (map["lastHeartbeat"] as? Number)?.toLong() ?: 0L
            val breakoutStatus = (map["breakoutStatus"] as? String)?.takeIf { it.isNotBlank() }
            val breakoutRequestedAt = (map["breakoutRequestedAt"] as? Number)?.toLong()
            val breakoutUnlockAt = (map["breakoutUnlockAt"] as? Number)?.toLong()
            val deactivationStatus = (map["deactivationStatus"] as? String)?.takeIf { it.isNotBlank() }
            val deactivationRequestedAt = (map["deactivationRequestedAt"] as? Number)?.toLong()
            val deactivationUnlockAt = (map["deactivationUnlockAt"] as? Number)?.toLong()
            val deactivationHasQr = (map["deactivationHasQr"] as? Boolean) ?: false
            val guardianPolicySnapshot = (map["guardianPolicySnapshot"] as? String)?.takeIf { it.isNotBlank() }
            val lastSelfModifiedAt = (map["lastSelfModifiedAt"] as? Number)?.toLong()
            val waqfa = WaqfaConfig.fromMap(map["waqfaConfig"])

            val version = (map["version"] as? Number)?.toLong() ?: 1L
            val updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L

            return DevicePolicyBundle(
                curfewStartHour = startHour, curfewStartMinute = startMin,
                curfewEndHour = endHour, curfewEndMinute = endMin,
                isCurfewEnabled = isCurfew,
                isInstantInternetKillEnabled = isInstantKill,
                isWeekendCurfewEnabled = isWeekendCurfew,
                weekendCurfewStartHour = wkStartHour, weekendCurfewStartMinute = wkStartMin,
                weekendCurfewEndHour = wkEndHour, weekendCurfewEndMinute = wkEndMin,
                isVpnBlackholeEnabled = vpn, isIncognitoBlocked = incognito,
                lockNewInstallsUntilApproved = lockNew,
                customBlacklistPackages = blacklist,
                customCurfewWhitelistPackages = whitelist,
                customAiScanPackages = aiScan,
                curfewVpnBypassPackages = vpnBypass,
                protectedAppPackages = protectedApps,
                browserFilterMode = browserFilterMode,
                isFullSystemFilteringEnabled = isFullSystemFilteringEnabled,
                customBlockedUrls = customBlockedUrls,
                customAllowedUrls = customAllowedUrls,
                dnsHost = dns,
                isYouTubeRestrictedModeEnabled = ytRestricted,
                isTelegramWebBlocked = tgBlocked,
                guardianUid = gUid, guardianName = gName,
                isSupervised = supervised,
                isSelfControlAllowed = isSelfControlAllowed,
                isDateTimeLocked = isDateTimeLocked,
                timeZoneId = timeZoneId, lastHeartbeat = lastHeartbeat,
                breakoutStatus = breakoutStatus,
                breakoutRequestedAt = breakoutRequestedAt, breakoutUnlockAt = breakoutUnlockAt,
                deactivationStatus = deactivationStatus,
                deactivationRequestedAt = deactivationRequestedAt, deactivationUnlockAt = deactivationUnlockAt,
                deactivationHasQr = deactivationHasQr,
                guardianPolicySnapshot = guardianPolicySnapshot,
                lastSelfModifiedAt = lastSelfModifiedAt,
                waqfaConfig = waqfa,
                version = version, updatedAt = updatedAt
            )
        }

        private fun org.json.JSONArray?.toStringSet(): Set<String> {
            if (this == null) return emptySet()
            val set = mutableSetOf<String>()
            for (i in 0 until length()) {
                val s = optString(i)
                if (!s.isNullOrBlank() && s != "null") set.add(s)
            }
            return set
        }

        fun fromJson(jsonStr: String): DevicePolicyBundle {
            return try {
                val json = JSONObject(jsonStr)
                val blacklist = json.optJSONArray("customBlacklistPackages").toStringSet()
                val whitelist = if (json.has("customCurfewWhitelistPackages")) {
                    json.optJSONArray("customCurfewWhitelistPackages").toStringSet()
                } else {
                    com.drmohammed.antiporn.curfew.CurfewConfig.STATIC_WHITELIST
                }
                val aiScan = json.optJSONArray("customAiScanPackages").toStringSet()
                val vpnBypass = json.optJSONArray("curfewVpnBypassPackages").toStringSet()
                val protectedApps = json.optJSONArray("protectedAppPackages").toStringSet()
                val browserFilterMode = json.optInt("browserFilterMode", BROWSER_FILTER_OFF).coerceIn(0, 2)
                val isFullSystemFilteringEnabled = json.optBoolean("isFullSystemFilteringEnabled", false)
                val customBlockedUrls = json.optJSONArray("customBlockedUrls").toStringSet()
                val customAllowedUrls = json.optJSONArray("customAllowedUrls").toStringSet()

                val dns = json.optString("dnsHost", "").takeIf { it.isNotBlank() } ?: CLOUDFLARE_FAMILY_DNS_HOST
                val ytRestricted = json.optBoolean("isYouTubeRestrictedModeEnabled", false)
                val tgBlocked = json.optBoolean("isTelegramWebBlocked", true)
                val gUid = json.optString("guardianUid", "").takeIf { it.isNotBlank() }
                val gName = json.optString("guardianName", "").takeIf { it.isNotBlank() }
                val supervised = json.optBoolean("isSupervised", !gUid.isNullOrBlank())
                val isSelfControlAllowed = json.optBoolean("isSelfControlAllowed", true)
                val isDateTimeLocked = json.optBoolean("isDateTimeLocked", false)
                val timeZoneId = json.optString("timeZoneId", com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID).ifBlank { com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID }
                val lastHeartbeat = json.optLong("lastHeartbeat", 0L)
                val breakoutStatus = json.optString("breakoutStatus", "").takeIf { it.isNotBlank() }
                val breakoutRequestedAt = if (json.has("breakoutRequestedAt") && !json.isNull("breakoutRequestedAt")) json.optLong("breakoutRequestedAt") else null
                val breakoutUnlockAt = if (json.has("breakoutUnlockAt") && !json.isNull("breakoutUnlockAt")) json.optLong("breakoutUnlockAt") else null
                val deactivationStatus = json.optString("deactivationStatus", "").takeIf { it.isNotBlank() }
                val deactivationRequestedAt = if (json.has("deactivationRequestedAt") && !json.isNull("deactivationRequestedAt")) json.optLong("deactivationRequestedAt") else null
                val deactivationUnlockAt = if (json.has("deactivationUnlockAt") && !json.isNull("deactivationUnlockAt")) json.optLong("deactivationUnlockAt") else null
                val deactivationHasQr = json.optBoolean("deactivationHasQr", false)
                val guardianPolicySnapshot = json.optString("guardianPolicySnapshot", "").takeIf { it.isNotBlank() }
                val lastSelfModifiedAt = if (json.has("lastSelfModifiedAt") && !json.isNull("lastSelfModifiedAt")) json.optLong("lastSelfModifiedAt") else null
                val waqfa = WaqfaConfig.fromJson(json.optJSONObject("waqfaConfig"))

                DevicePolicyBundle(
                    curfewStartHour = json.optInt("curfewStartHour", com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR).coerceIn(0, 23),
                    curfewStartMinute = json.optInt("curfewStartMinute", com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE).coerceIn(0, 59),
                    curfewEndHour = json.optInt("curfewEndHour", com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR).coerceIn(0, 23),
                    curfewEndMinute = json.optInt("curfewEndMinute", com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE).coerceIn(0, 59),
                    isCurfewEnabled = json.optBoolean("isCurfewEnabled", true),
                    isInstantInternetKillEnabled = json.optBoolean("isInstantInternetKillEnabled", false),
                    isWeekendCurfewEnabled = json.optBoolean("isWeekendCurfewEnabled", false),
                    weekendCurfewStartHour = json.optInt("weekendCurfewStartHour", com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR).coerceIn(0, 23),
                    weekendCurfewStartMinute = json.optInt("weekendCurfewStartMinute", com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE).coerceIn(0, 59),
                    weekendCurfewEndHour = json.optInt("weekendCurfewEndHour", com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR).coerceIn(0, 23),
                    weekendCurfewEndMinute = json.optInt("weekendCurfewEndMinute", com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE).coerceIn(0, 59),
                    isVpnBlackholeEnabled = json.optBoolean("isVpnBlackholeEnabled", com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED),
                    isIncognitoBlocked = json.optBoolean("isIncognitoBlocked", true),
                    lockNewInstallsUntilApproved = json.optBoolean("lockNewInstallsUntilApproved", true),
                    customBlacklistPackages = blacklist,
                    customCurfewWhitelistPackages = whitelist,
                    customAiScanPackages = aiScan,
                    curfewVpnBypassPackages = vpnBypass,
                    protectedAppPackages = protectedApps,
                    browserFilterMode = browserFilterMode,
                    isFullSystemFilteringEnabled = isFullSystemFilteringEnabled,
                    customBlockedUrls = customBlockedUrls,
                    customAllowedUrls = customAllowedUrls,
                    dnsHost = dns,
                    isYouTubeRestrictedModeEnabled = ytRestricted,
                    isTelegramWebBlocked = tgBlocked,
                    guardianUid = gUid, guardianName = gName,
                    isSupervised = supervised,
                    isSelfControlAllowed = isSelfControlAllowed,
                    isDateTimeLocked = isDateTimeLocked,
                    timeZoneId = timeZoneId, lastHeartbeat = lastHeartbeat,
                    breakoutStatus = breakoutStatus,
                    breakoutRequestedAt = breakoutRequestedAt, breakoutUnlockAt = breakoutUnlockAt,
                    deactivationStatus = deactivationStatus,
                    deactivationRequestedAt = deactivationRequestedAt, deactivationUnlockAt = deactivationUnlockAt,
                    deactivationHasQr = deactivationHasQr,
                    guardianPolicySnapshot = guardianPolicySnapshot,
                    lastSelfModifiedAt = lastSelfModifiedAt,
                    waqfaConfig = waqfa,
                    version = json.optLong("version", 1L),
                    updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                try {
                    Log.w(TAG, "Error parsing DevicePolicyBundle from JSON: ${e.message}")
                } catch (_: Throwable) {
                    /* safe fallback for unit tests */
                }
                defaultPolicy()
            }
        }
    }
}
