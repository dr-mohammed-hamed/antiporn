package com.drmohammed.antiporn.ota.model

import org.json.JSONObject

/**
 * Data model matching the schema of version.json hosted on Cloudflare R2.
 *
 * @property versionCode Numeric internal version code (e.g. 1)
 * @property versionName Human-readable semantic version name (e.g. "1.0")
 * @property minSupportedVersion Minimum version code supported without mandatory upgrade
 * @property apkFileName Target APK file name in release bucket (e.g. "antiporn-release.apk")
 * @property downloadUrl Full HTTPS download URL for the APK file
 * @property fileSizeBytes Expected file size in bytes
 * @property sha256CertificateChecksum Optional SHA-256 base64/hex fingerprint of signing certificate
 * @property sha256ApkHash Optional SHA-256 hex digest of the raw APK binary
 * @property releaseNotes Human-readable release notes/changelog
 * @property releaseDate Release date formatted as ISO string (YYYY-MM-DD)
 */
data class OtaVersionInfo(
    val versionCode: Long,
    val versionName: String,
    val minSupportedVersion: Long,
    val apkFileName: String,
    val downloadUrl: String,
    val fileSizeBytes: Long,
    val sha256CertificateChecksum: String? = null,
    val sha256ApkHash: String? = null,
    val releaseNotes: String? = null,
    val releaseDate: String? = null
) {
    /**
     * Checks if this version is strictly newer than the provided local version code.
     */
    fun isNewerThan(currentVersionCode: Long): Boolean = versionCode > currentVersionCode

    /**
     * Checks if the current version is lower than [minSupportedVersion], requiring mandatory update.
     */
    fun isMandatoryFor(currentVersionCode: Long): Boolean = currentVersionCode < minSupportedVersion

    /**
     * Serializes this [OtaVersionInfo] into an [org.json.JSONObject].
     */
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("versionCode", versionCode)
        json.put("versionName", versionName)
        json.put("minSupportedVersion", minSupportedVersion)
        json.put("apkFileName", apkFileName)
        json.put("downloadUrl", downloadUrl)
        json.put("fileSizeBytes", fileSizeBytes)
        sha256CertificateChecksum?.let { json.put("sha256CertificateChecksum", it) }
        sha256ApkHash?.let { json.put("sha256ApkHash", it) }
        releaseNotes?.let { json.put("releaseNotes", it) }
        releaseDate?.let { json.put("releaseDate", it) }
        return json
    }

    /**
     * Serializes this [OtaVersionInfo] to a JSON string representation.
     */
    fun toJson(indentSpaces: Int = 2): String = toJsonObject().toString(indentSpaces)

    companion object {
        /**
         * Parses [OtaVersionInfo] from raw JSON string.
         * Gracefully handles nulls and missing optional attributes.
         */
        fun fromJson(jsonStr: String): OtaVersionInfo {
            val json = JSONObject(jsonStr)
            return OtaVersionInfo(
                versionCode = json.optLong("versionCode", 0L),
                versionName = json.optString("versionName", ""),
                minSupportedVersion = json.optLong("minSupportedVersion", 0L),
                apkFileName = json.optString("apkFileName", ""),
                downloadUrl = json.optString("downloadUrl", ""),
                fileSizeBytes = json.optLong("fileSizeBytes", 0L),
                sha256CertificateChecksum = json.optNullableString("sha256CertificateChecksum"),
                sha256ApkHash = json.optNullableString("sha256ApkHash"),
                releaseNotes = json.optNullableString("releaseNotes"),
                releaseDate = json.optNullableString("releaseDate")
            )
        }

        private fun JSONObject.optNullableString(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val value = optString(key)
            return if (value.isBlank() || value.equals("null", ignoreCase = true)) null else value.trim()
        }
    }
}
