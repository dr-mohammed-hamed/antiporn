package com.drmohammed.antiporn.ai.core.model

/**
 * تقرير نتيجة الاستدلال لإطار واحد
 */
data class ModestyInferenceResult(
    val detections: List<ModestyDetection>,
    val topViolation: ModestyDetection?,
    val hasViolation: Boolean,
    val latencyMs: Long
) {
    companion object {
        val EMPTY = ModestyInferenceResult(
            detections = emptyList(),
            topViolation = null,
            hasViolation = false,
            latencyMs = 0L
        )
    }
}
