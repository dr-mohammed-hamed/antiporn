package com.drmohammed.antiporn.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Unified enterprise policy bundle controlling all protection parameters
 * configured by the user's accountability partner/guardian.
 */
data class DevicePolicyBundle(
    val curfewStartHour: Int = 20,
    val curfewStartMinute: Int = 30,
    val curfewEndHour: Int = 7,
    val curfewEndMinute: Int = 0,
    val isVpnBlackholeEnabled: Boolean = false,
    val isIncognitoBlocked: Boolean = true,
    val lockNewInstallsUntilApproved: Boolean = true,
    val customBlacklistPackages: Set<String> = emptySet(),
    val customCurfewWhitelistPackages: Set<String> = emptySet(),
    val dnsHost: String = CLOUDFLARE_FAMILY_DNS_HOST,
    val version: Long = 1L,
    val updatedAt: Long = System.currentTimeMillis()
) {

    fun toMap(): Map<String, Any> {
        return mapOf(
            "curfewStartHour" to curfewStartHour,
            "curfewStartMinute" to curfewStartMinute,
            "curfewEndHour" to curfewEndHour,
            "curfewEndMinute" to curfewEndMinute,
            "isVpnBlackholeEnabled" to isVpnBlackholeEnabled,
            "isIncognitoBlocked" to isIncognitoBlocked,
            "lockNewInstallsUntilApproved" to lockNewInstallsUntilApproved,
            "customBlacklistPackages" to customBlacklistPackages.toList(),
            "customCurfewWhitelistPackages" to customCurfewWhitelistPackages.toList(),
            "dnsHost" to dnsHost,
            "version" to version,
            "updatedAt" to updatedAt
        )
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("curfewStartHour", curfewStartHour)
        json.put("curfewStartMinute", curfewStartMinute)
        json.put("curfewEndHour", curfewEndHour)
        json.put("curfewEndMinute", curfewEndMinute)
        json.put("isVpnBlackholeEnabled", isVpnBlackholeEnabled)
        json.put("isIncognitoBlocked", isIncognitoBlocked)
        json.put("lockNewInstallsUntilApproved", lockNewInstallsUntilApproved)

        val blacklistArr = JSONArray()
        customBlacklistPackages.forEach { blacklistArr.put(it) }
        json.put("customBlacklistPackages", blacklistArr)

        val whitelistArr = JSONArray()
        customCurfewWhitelistPackages.forEach { whitelistArr.put(it) }
        json.put("customCurfewWhitelistPackages", whitelistArr)

        json.put("dnsHost", dnsHost)
        json.put("version", version)
        json.put("updatedAt", updatedAt)
        return json.toString()
    }

    companion object {
        const val CLOUDFLARE_FAMILY_DNS_HOST = "family.cloudflare-dns.com"
        const val LEGACY_DEFAULT_NEXTDNS_HOST = "family.cloudflare-dns.com"

        fun defaultPolicy(): DevicePolicyBundle = DevicePolicyBundle(dnsHost = CLOUDFLARE_FAMILY_DNS_HOST)

        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): DevicePolicyBundle {
            val startHour = ((map["curfewStartHour"] as? Number)?.toInt() ?: 20).coerceIn(0, 23)
            val startMin = ((map["curfewStartMinute"] as? Number)?.toInt() ?: 30).coerceIn(0, 59)
            val endHour = ((map["curfewEndHour"] as? Number)?.toInt() ?: 7).coerceIn(0, 23)
            val endMin = ((map["curfewEndMinute"] as? Number)?.toInt() ?: 0).coerceIn(0, 59)
            val vpn = (map["isVpnBlackholeEnabled"] as? Boolean) ?: false
            val incognito = (map["isIncognitoBlocked"] as? Boolean) ?: true
            val lockNew = (map["lockNewInstallsUntilApproved"] as? Boolean) ?: true

            val blacklist = (map["customBlacklistPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            val whitelist = (map["customCurfewWhitelistPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()

            val dns = (map["dnsHost"] as? String)?.takeIf { it.isNotBlank() } ?: LEGACY_DEFAULT_NEXTDNS_HOST

            val version = (map["version"] as? Number)?.toLong() ?: 1L
            val updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L

            return DevicePolicyBundle(
                curfewStartHour = startHour,
                curfewStartMinute = startMin,
                curfewEndHour = endHour,
                curfewEndMinute = endMin,
                isVpnBlackholeEnabled = vpn,
                isIncognitoBlocked = incognito,
                lockNewInstallsUntilApproved = lockNew,
                customBlacklistPackages = blacklist,
                customCurfewWhitelistPackages = whitelist,
                dnsHost = dns,
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

                val dns = json.optString("dnsHost", "").takeIf { it.isNotBlank() } ?: LEGACY_DEFAULT_NEXTDNS_HOST

                DevicePolicyBundle(
                    curfewStartHour = json.optInt("curfewStartHour", 20),
                    curfewStartMinute = json.optInt("curfewStartMinute", 30),
                    curfewEndHour = json.optInt("curfewEndHour", 7),
                    curfewEndMinute = json.optInt("curfewEndMinute", 0),
                    isVpnBlackholeEnabled = json.optBoolean("isVpnBlackholeEnabled", false),
                    isIncognitoBlocked = json.optBoolean("isIncognitoBlocked", true),
                    lockNewInstallsUntilApproved = json.optBoolean("lockNewInstallsUntilApproved", true),
                    customBlacklistPackages = blacklist,
                    customCurfewWhitelistPackages = whitelist,
                    dnsHost = dns,
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
