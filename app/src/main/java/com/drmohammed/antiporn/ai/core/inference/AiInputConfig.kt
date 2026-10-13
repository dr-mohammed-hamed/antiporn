package com.drmohammed.antiporn.ai.core.inference

/**
 * عقد دخل مصنف الصور (ImageNet classifier).
 * تُحسم القيم runtime من شكل تنسور الدخل — لا افتراضات صلبة.
 */
data class AiInputConfig(
    val width: Int,
    val height: Int,
    val isChannelsLast: Boolean,
    val outputKind: OutputKind
) {
    enum class OutputKind {
        /** مصنف ImageNet عام [1, 1000] — المسار الأساسي المعتمد. */
        IMAGENET_1000,

        /** مصنف ثنائي [1, 1] sigmoid احتياطي. */
        BINARY_SIGMOID,

        /** مصنف ثنائي [1, 2] softmax احتياطي. */
        BINARY_SOFTMAX
    }
}
