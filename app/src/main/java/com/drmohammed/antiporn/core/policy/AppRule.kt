package com.drmohammed.antiporn.core.policy

/**
 * Declarative rule mapping a package name or wildcard pattern to an [AppClassification].
 * Supports exact package matching ("org.schabi.newpipe") and wildcard globs ("org.schabi.newpipe.*", "*.newpipe.*").
 */
data class AppRule(
    val pattern: String,
    val classification: AppClassification,
    val description: String = "",
    val highRiskCategory: String? = null
) {
    val isWildcard: Boolean = pattern.contains("*") || pattern.contains("?")

    private val regex: Regex? by lazy {
        if (isWildcard) {
            val escaped = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
            Regex("^$escaped$", RegexOption.IGNORE_CASE)
        } else null
    }

    fun matches(packageName: String): Boolean {
        return if (isWildcard) {
            regex?.matches(packageName) ?: false
        } else {
            pattern.equals(packageName, ignoreCase = true)
        }
    }
}
