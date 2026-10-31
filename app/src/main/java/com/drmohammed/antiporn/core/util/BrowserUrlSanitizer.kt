package com.drmohammed.antiporn.core.util

import java.net.IDN

/**
 * Utility for sanitizing and validating URLs and hostnames for Chromium enterprise policy
 * restrictions (URLBlocklist and URLAllowlist).
 */
object BrowserUrlSanitizer {

    val CAPTIVE_PORTAL_EXEMPTIONS: List<String> = listOf(
        "connectivitycheck.gstatic.com",
        "clients3.google.com",
        "accounts.google.com",
        "accounts.youtube.com",
        "play.google.com",
        "play.googleapis.com",
        "ssl.gstatic.com"
    )

    val SOCIAL_MEDIA_PRESETS: List<String> = listOf(
        "facebook.com",
        "instagram.com",
        "tiktok.com",
        "twitter.com",
        "x.com",
        "reddit.com",
        "snapchat.com",
        "threads.net",
        "pinterest.com"
    )

    /**
     * Sanitizes and validates a URL or domain pattern for Chromium URLBlocklist / URLAllowlist.
     *
     * Rules:
     * - Trims whitespace; returns null if null or blank.
     * - Converts domain/host part to lowercase and encodes internationalized domain names (IDN) to Punycode (ASCII).
     * - Preserves case-sensitivity for paths and query parameters.
     * - Strips protocol schemes (http://, https://).
     * - Strips redundant www. prefix if followed by a domain with a dot.
     * - Strips trailing slashes from domains or paths.
     * - Preserves valid paths (e.g. example.com/path).
     * - Rejects strings containing spaces, control/invalid characters, credentials (@), or missing dots (unless wildcard '*').
     * - Supports wildcard patterns (e.g. '*' or '*.example.com').
     *
     * @return Sanitized string in Chromium enterprise policy format, or null if input is invalid.
     */
    fun sanitize(input: String?): String? {
        if (input == null) return null
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // Reject any string containing whitespace
        if (trimmed.any { it.isWhitespace() }) return null

        // Reject user credentials (@ in authority)
        if (trimmed.contains('@')) return null

        var cleaned = trimmed

        // Strip protocol schemes case-insensitively
        val lowerForScheme = cleaned.lowercase()
        if (lowerForScheme.startsWith("https://")) {
            cleaned = cleaned.substring(8)
        } else if (lowerForScheme.startsWith("http://")) {
            cleaned = cleaned.substring(7)
        }

        // Strip leading slashes if any were left (e.g. //example.com)
        while (cleaned.startsWith('/')) {
            cleaned = cleaned.substring(1)
        }

        if (cleaned.isEmpty()) return null

        // Remove redundant leading dot if user entered .example.com
        if (cleaned.startsWith(".") && !cleaned.startsWith("..") && cleaned.length > 1) {
            cleaned = cleaned.substring(1)
        }

        // Strip trailing slashes
        cleaned = cleaned.trimEnd('/')
        if (cleaned.isEmpty()) return null

        // Strip fragment identifier (#...) as Chromium URL filters do not support fragments
        if (cleaned.contains('#')) {
            cleaned = cleaned.substringBefore('#').trimEnd('/')
            if (cleaned.isEmpty()) return null
        }

        // Special case: Chromium wildcard all '*'
        if (cleaned == "*") {
            return "*"
        }

        // Require either a dot or a wildcard
        if (!cleaned.contains('.') && !cleaned.contains('*')) {
            return null
        }

        // Disallow consecutive dots or invalid punctuation
        if (cleaned.contains("..") || (cleaned.contains("*.") && cleaned.indexOf("*.") != 0)) {
            return null
        }

        // Split into host (with optional port) and path/query (preserving path/query casing)
        val pathIndex = cleaned.indexOfAny(charArrayOf('/', '?'))
        val hostAndPort = if (pathIndex != -1) cleaned.substring(0, pathIndex) else cleaned
        val pathAndQuery = if (pathIndex != -1) cleaned.substring(pathIndex) else ""

        if (hostAndPort.isEmpty()) return null

        // Separate host and port
        val colonIndex = hostAndPort.lastIndexOf(':')
        val rawHost: String
        val portSuffix: String
        if (colonIndex != -1) {
            rawHost = hostAndPort.substring(0, colonIndex)
            val portStr = hostAndPort.substring(colonIndex + 1)
            if (portStr.isEmpty() || !portStr.all { it.isDigit() }) return null
            val portNum = portStr.toIntOrNull() ?: return null
            if (portNum !in 1..65535) return null
            portSuffix = ":$portNum"
        } else {
            rawHost = hostAndPort
            portSuffix = ""
        }

        if (rawHost.isEmpty()) return null

        // Lowercase host part
        var host = rawHost.lowercase()

        // Strip redundant www. prefix if followed by a domain with a dot
        if (host.startsWith("www.") && host.removePrefix("www.").contains('.')) {
            host = host.removePrefix("www.")
        } else if (host.startsWith("*.www.") && host.removePrefix("*.www.").contains('.')) {
            host = "*." + host.removePrefix("*.www.")
        }

        // Handle wildcard prefix and convert internationalized domain names (IDN) to Punycode (ASCII)
        val hasWildcardPrefix = host.startsWith("*.")
        val domainToAscii = if (hasWildcardPrefix) host.removePrefix("*.") else host

        val asciiDomain = if (domainToAscii == "*") {
            "*"
        } else {
            try {
                IDN.toASCII(domainToAscii)
            } catch (_: Exception) {
                return null
            }
        }

        val normalizedHost = if (hasWildcardPrefix) "*.$asciiDomain" else asciiDomain

        // Validate normalized host format
        if (normalizedHost == "*") {
            // Wildcard host
        } else if (normalizedHost.startsWith("*.")) {
            val domainPart = normalizedHost.removePrefix("*.")
            if (domainPart.isEmpty() || domainPart.contains('*')) return null
            val labels = domainPart.split('.')
            if (labels.size < 2 || labels.any { !isValidHostLabel(it) }) return null
        } else {
            if (normalizedHost.contains('*')) return null
            val labels = normalizedHost.split('.')
            if (labels.size < 2) return null
            if (labels.any { !isValidHostLabel(it) }) return null
        }

        // Validate path and query characters
        if (pathAndQuery.isNotEmpty()) {
            for (ch in pathAndQuery) {
                if (ch in " <>\"\\^{}|`" || ch.isWhitespace() || ch.code < 32 || ch.code > 126) {
                    return null
                }
            }
        }

        return normalizedHost + portSuffix + pathAndQuery
    }

    /**
     * Checks whether an input URL or domain string is valid under sanitization rules.
     */
    fun isValid(input: String?): Boolean = sanitize(input) != null

    /**
     * Sanitizes a collection of URL strings, discarding any invalid inputs and returning unique values.
     */
    fun sanitizeList(inputs: Collection<String>?): List<String> {
        if (inputs == null) return emptyList()
        return inputs.mapNotNull { sanitize(it) }.distinct()
    }

    private fun isValidHostLabel(label: String): Boolean {
        if (label.isEmpty() || label.length > 63) return false
        if (label.startsWith('-') || label.endsWith('-')) return false
        if (label.startsWith('_') || label.endsWith('_')) return false
        return label.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
