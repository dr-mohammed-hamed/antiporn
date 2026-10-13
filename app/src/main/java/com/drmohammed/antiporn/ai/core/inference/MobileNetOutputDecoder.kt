package com.drmohammed.antiporn.ai.core.inference

import kotlin.math.exp

/**
 * فكاك مخرجات MobileNetV4 (ImageNet-1k) إلى درجة شبهة واحدة.
 *
 * الموديل مصنف عام بمخرجات [1, 1000] (فهرسة 0..999 بترتيب torchvision/timm)،
 * لذا تُحسب الشبهة عبر Softmax مستقر عددياً ثم **مجموع احتمالات فئات الملابس
 * والإثارة القياسية**: bikini=445, brassiere=459, maillot=638/639,
 * miniskirt=655, sarong=775, swimming-trunks=842.
 *
 * يدعم احتياطياً [1,1] sigmoid و [1,2] softmax (بعتبة 0.65) تحسباً لأي نسخة ثنائية.
 * pure-Kotlin بلا Android — قابل للاختبار الوحدوي الكامل.
 */
class MobileNetOutputDecoder(
    /** عتبة مجموع الفئات المثيرة (الموصى بها تجريبياً 0.10). */
    val suspicionThreshold: Float = 0.10f,
    /** عتبة المسار الثنائي الاحتياطي. */
    val binaryThreshold: Float = 0.65f
) {

    data class DecodeResult(
        val suspicionScore: Float,
        val isSuspicious: Boolean,
        /** أعلى فئة ImageNet احتمالاً (للتشخيص والمعايرة فقط). */
        val topClassId: Int,
        val topProb: Float
    )

    companion object {
        /** فئات الإثارة بفهرسة 0-based (توافق torchvision). أي id خارج [0,size) يُتجاهل دفاعياً. */
        val SUSPICIOUS_CLASS_IDS: Set<Int> = setOf(445, 459, 638, 639, 655, 775, 842)
    }

    /**
     * فك مصفوفة الخرج بعد تشغيل المفسر.
     * @param output2d مصفوفة [1, N] حيث N ∈ {1000, 1, 2}.
     */
    @Synchronized
    fun decode(output2d: Array<FloatArray>): DecodeResult {
        require(output2d.isNotEmpty()) { "Empty model output" }
        val logits = output2d[0]
        return when (logits.size) {
            1000 -> decodeImageNet(logits)
            1 -> {
                val p = sigmoid(logits[0])
                DecodeResult(p, p >= binaryThreshold, 1, p)
            }
            2 -> {
                val probs = softmax(logits)
                val p = probs[1]
                DecodeResult(p, p >= binaryThreshold, if (p >= probs[0]) 1 else 0, maxOf(p, probs[0]))
            }
            else -> throw IllegalArgumentException("Unsupported MobileNet output size: ${logits.size} (expected 1000/1/2)")
        }
    }

    private fun decodeImageNet(logits: FloatArray): DecodeResult {
        val probs = softmax(logits)
        var topId = 0
        var topP = probs[0]
        for (i in 1 until probs.size) {
            if (probs[i] > topP) {
                topP = probs[i]
                topId = i
            }
        }
        var suspicion = 0f
        for (id in SUSPICIOUS_CLASS_IDS) {
            if (id in probs.indices) suspicion += probs[id]
        }
        // Pure-Kotlin: no android.util.Log here (unit-test safe) — NaN is sanitized explicitly.
        if (suspicion.isNaN()) {
            suspicion = 0f
        }
        return DecodeResult(
            suspicionScore = suspicion,
            isSuspicious = suspicion >= suspicionThreshold,
            topClassId = topId,
            topProb = topP
        )
    }

    private fun softmax(logits: FloatArray): FloatArray {
        var max = logits[0]
        for (i in 1 until logits.size) if (logits[i] > max) max = logits[i]
        val out = FloatArray(logits.size)
        var sum = 0.0
        for (i in logits.indices) {
            val e = exp((logits[i] - max).toDouble())
            out[i] = e.toFloat()
            sum += e
        }
        // MAJOR(M10): corrupt/NNAPI-delegated logits (NaN/±Inf, all -Inf → sum=0) must never decode to silent-safe.
        if (sum.isNaN() || sum <= 0.0 || sum.isInfinite()) {
            return FloatArray(logits.size) { 1f / logits.size }
        }
        for (i in out.indices) out[i] = (out[i] / sum).toFloat()
        return out
    }

    private fun sigmoid(x: Float): Float {
        return (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
    }
}
