package com.drmohammed.antiporn.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Unified enterprise policy bundle controlling all protection parameters
 * configured by the user's accountability partner/guardian.
 */
data class DevicePolicyBundle(
    val curfewStartHour: Int = com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR,
    val curfewStartMinute: Int = com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE,
    val curfewEndHour: Int = com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR,
    val curfewEndMinute: Int = com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE,
    val isCurfewEnabled: Boolean = true,
    val isInstantInternetKillEnabled: Boolean = false,
    val isWeekendCurfewEnabled: Boolean = false,
    val weekendCurfewStartHour: Int = com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR,
    val weekendCurfewStartMinute: Int = com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE,
    val weekendCurfewEndHour: Int = com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR,
    val weekendCurfewEndMinute: Int = com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE,
    val isVpnBlackholeEnabled: Boolean = com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED,
    val isIncognitoBlocked: Boolean = true,
    val lockNewInstallsUntilApproved: Boolean = true,
    val customBlacklistPackages: Set<String> = emptySet(),
    val customCurfewWhitelistPackages: Set<String> = emptySet(),
    val protectedAppPackages: Set<String> = emptySet(),
    val dnsHost: String = ADGUARD_FAMILY_DNS_HOST,
    val guardianUid: String? = null,
    val guardianName: String? = null,
    val isSupervised: Boolean = false,
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
            "protectedAppPackages" to protectedAppPackages.toList(),
            "dnsHost" to dnsHost,
            "isSupervised" to isSupervised,
            "version" to version,
            "updatedAt" to updatedAt
        )
        map["guardianUid"] = guardianUid
        map["guardianName"] = guardianName
        return map
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("curfewStartHour", curfewStartHour)
        json.put("curfewStartMinute", curfewStartMinute)
        json.put("curfewEndHour", curfewEndHour)
        json.put("curfewEndMinute", curfewEndMinute)
        json.put("isCurfewEnabled", isCurfewEnabled)
        json.put("isInstantInternetKillEnabled", isInstantInternetKillEnabled)
        json.put("isWeekendCurfewEnabled", isWeekendCurfewEnabled)
        json.put("weekendCurfewStartHour", weekendCurfewStartHour)
        json.put("weekendCurfewStartMinute", weekendCurfewStartMinute)
        json.put("weekendCurfewEndHour", weekendCurfewEndHour)
        json.put("weekendCurfewEndMinute", weekendCurfewEndMinute)
        json.put("isVpnBlackholeEnabled", isVpnBlackholeEnabled)
        json.put("isIncognitoBlocked", isIncognitoBlocked)
        json.put("lockNewInstallsUntilApproved", lockNewInstallsUntilApproved)

        val blacklistArr = JSONArray()
        customBlacklistPackages.forEach { blacklistArr.put(it) }
        json.put("customBlacklistPackages", blacklistArr)

        val whitelistArr = JSONArray()
        customCurfewWhitelistPackages.forEach { whitelistArr.put(it) }
        json.put("customCurfewWhitelistPackages", whitelistArr)

        val protectedArr = JSONArray()
        protectedAppPackages.forEach { protectedArr.put(it) }
        json.put("protectedAppPackages", protectedArr)

        json.put("dnsHost", dnsHost)
        if (guardianUid != null) json.put("guardianUid", guardianUid)
        if (guardianName != null) json.put("guardianName", guardianName)
        json.put("isSupervised", isSupervised)
        json.put("version", version)
        json.put("updatedAt", updatedAt)
        return json.toString()
    }

    companion object {
        const val ADGUARD_FAMILY_DNS_HOST = "family.adguard-dns.com"
        const val CLOUDFLARE_FAMILY_DNS_HOST = "family.cloudflare-dns.com"
        const val LEGACY_DEFAULT_NEXTDNS_HOST = "family.cloudflare-dns.com"

        fun defaultPolicy(): DevicePolicyBundle = DevicePolicyBundle(
            dnsHost = ADGUARD_FAMILY_DNS_HOST,
            guardianUid = null,
            guardianName = null,
            isSupervised = false
        )

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
            val whitelist = (map["customCurfewWhitelistPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            val protectedApps = (map["protectedAppPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()

            val dns = (map["dnsHost"] as? String)?.takeIf { it.isNotBlank() } ?: LEGACY_DEFAULT_NEXTDNS_HOST

            val gUid = (map["guardianUid"] as? String)?.takeIf { it.isNotBlank() }
            val gName = (map["guardianName"] as? String)?.takeIf { it.isNotBlank() }
            val supervised = (map["isSupervised"] as? Boolean) ?: (!gUid.isNullOrBlank())

            val version = (map["version"] as? Number)?.toLong() ?: 1L
            val updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L

            return DevicePolicyBundle(
                curfewStartHour = startHour,
                curfewStartMinute = startMin,
                curfewEndHour = endHour,
                curfewEndMinute = endMin,
                isCurfewEnabled = isCurfew,
                isInstantInternetKillEnabled = isInstantKill,
                isWeekendCurfewEnabled = isWeekendCurfew,
                weekendCurfewStartHour = wkStartHour,
                weekendCurfewStartMinute = wkStartMin,
                weekendCurfewEndHour = wkEndHour,
                weekendCurfewEndMinute = wkEndMin,
                isVpnBlackholeEnabled = vpn,
                isIncognitoBlocked = incognito,
                lockNewInstallsUntilApproved = lockNew,
                customBlacklistPackages = blacklist,
                customCurfewWhitelistPackages = whitelist,
                protectedAppPackages = protectedApps,
                dnsHost = dns,
                guardianUid = gUid,
                guardianName = gName,
                isSupervised = supervised,
                version = version,
                updatedAt = updatedAt
            )
        }

        fun fromJson(jsonStr: String): DevicePolicyBundle {
            return try {
                val json = JSONObject(jsonStr)
                val blacklist = mutableSetOf<String>()
                val blacklistArr = json.optJSONArray("customBlacklistPackages")
                if (blacklistArr != null) {
                    for (i in 0 until blacklistArr.length()) {
                        blacklist.add(blacklistArr.getString(i))
                    }
                }

                val whitelist = mutableSetOf<String>()
                val whitelistArr = json.optJSONArray("customCurfewWhitelistPackages")
                if (whitelistArr != null) {
                    for (i in 0 until whitelistArr.length()) {
                        whitelist.add(whitelistArr.getString(i))
                    }
                }

                val protectedApps = mutableSetOf<String>()
                val protectedArr = json.optJSONArray("protectedAppPackages")
                if (protectedArr != null) {
                    for (i in 0 until protectedArr.length()) {
                        protectedApps.add(protectedArr.getString(i))
                    }
                }

                val dns = json.optString("dnsHost", "").takeIf { it.isNotBlank() } ?: LEGACY_DEFAULT_NEXTDNS_HOST
                val gUid = json.optString("guardianUid", "").takeIf { it.isNotBlank() }
                val gName = json.optString("guardianName", "").takeIf { it.isNotBlank() }
                val supervised = json.optBoolean("isSupervised", !gUid.isNullOrBlank())

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
                    protectedAppPackages = protectedApps,
                    dnsHost = dns,
                    guardianUid = gUid,
                    guardianName = gName,
                    isSupervised = supervised,
                    version = json.optLong("version", 1L),
                    updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                try {
                    android.util.Log.e("AntiPorn_PolicyBundle", "Error parsing DevicePolicyBundle from JSON: ${e.message}")
                } catch (_: Throwable) {
                    System.err.println("Error parsing DevicePolicyBundle from JSON: ${e.message}")
                }
                defaultPolicy()
            }
        }
    }
}
