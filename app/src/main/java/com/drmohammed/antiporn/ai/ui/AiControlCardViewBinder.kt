package com.drmohammed.antiporn.ai.ui

import android.app.Activity
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.ai.service.ScreenAiProjectionService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * رابط عناصر واجهة المستخدم لبطاقة فحص الذكاء الاصطناعي (AiControlCardViewBinder)
 * يعزل منطق الواجهة عن MainActivity تنفيذاً لدستور كوتلين وقواعد Clean Architecture
 */
class AiControlCardViewBinder(
    private val activity: Activity,
    private val onToggleRequested: () -> Unit
) {
    // MAJOR(M17): fail loudly with context instead of bare platform-type NPE in onCreate.
    private val cardAiShield: MaterialCardView = activity.findViewById(R.id.cardAiShield)
        ?: throw IllegalStateException("activity_main missing R.id.cardAiShield")
    private val tvAiShieldStatus: TextView = activity.findViewById(R.id.tvAiShieldStatus)
        ?: throw IllegalStateException("activity_main missing R.id.tvAiShieldStatus")
    private val tvAiShieldMetrics: TextView = activity.findViewById(R.id.tvAiShieldMetrics)
        ?: throw IllegalStateException("activity_main missing R.id.tvAiShieldMetrics")
    private val btnAiShieldToggle: MaterialButton = activity.findViewById(R.id.btnAiShieldToggle)
        ?: throw IllegalStateException("activity_main missing R.id.btnAiShieldToggle")

    init {
        btnAiShieldToggle.setOnClickListener {
            onToggleRequested()
        }
    }

    /**
     * تحديث حالة البطاقة البصرية بناءً على حالة خدمة فحص الذكاء الاصطناعي الحية
     */
    fun updateUi() {
        val isRunning = ScreenAiProjectionService.isRunning
        val initError = ScreenAiProjectionService.initError
        val hasInitError = initError.isNotEmpty()

        if (isRunning) {
            // أثناء التشغيل يعكس الزر الحالة الحقيقية (إيقاف) حتى مع وجود خطأ تهيئة.
            // نص الخطأ يُعرض في سطر المقاييس بدل إخفاء حالة التشغيل.
            if (hasInitError) {
                tvAiShieldStatus.text = initError
                tvAiShieldStatus.setTextColor(ContextCompat.getColor(activity, R.color.status_orange))
            } else {
                tvAiShieldStatus.setText(R.string.card_ai_shield_active_desc)
                tvAiShieldStatus.setTextColor(ContextCompat.getColor(activity, R.color.status_green))
            }

            btnAiShieldToggle.setText(R.string.btn_ai_shield_stop)
            btnAiShieldToggle.strokeColor = ContextCompat.getColorStateList(activity, R.color.status_orange)
            btnAiShieldToggle.setTextColor(ContextCompat.getColor(activity, R.color.status_orange))

            val fps = ScreenAiProjectionService.currentFps
            // MAJOR(S3): atomic snapshot — never render torn label+confidence.
            val snapshot = ScreenAiProjectionService.lastDetection
            val lastLabel = snapshot.label
            val lastConf = snapshot.confidence

            if (lastLabel.isNotEmpty()) {
                val confPercent = (lastConf * 100).toInt()
                val fpsText = activity.getString(R.string.ai_fps_format, fps)
                val detText = activity.getString(R.string.ai_last_detection_format, lastLabel, confPercent)
                val modelText = activity.getString(R.string.ai_model_active_format, ScreenAiProjectionService.modelName)
                tvAiShieldMetrics.text = "$fpsText | $detText\n$modelText"
                tvAiShieldMetrics.setTextColor(ContextCompat.getColor(activity, R.color.status_orange))
            } else if (hasInitError) {
                tvAiShieldMetrics.text = activity.getString(R.string.ai_model_active_format, ScreenAiProjectionService.modelName)
                tvAiShieldMetrics.setTextColor(ContextCompat.getColor(activity, R.color.status_orange))
            } else {
                val fpsText = activity.getString(R.string.ai_fps_format, fps)
                val modelText = activity.getString(R.string.ai_model_active_format, ScreenAiProjectionService.modelName)
                tvAiShieldMetrics.text = "$fpsText | ${activity.getString(R.string.ai_no_detections_yet)}\n$modelText"
                tvAiShieldMetrics.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            }
        } else {
            // وضع التوقف: خطأ التهيئة لاصق بالأحمر (لا فشل صامت).
            if (hasInitError) {
                tvAiShieldStatus.text = initError
                tvAiShieldStatus.setTextColor(ContextCompat.getColor(activity, R.color.status_orange))
            } else {
                tvAiShieldStatus.setText(R.string.card_ai_shield_idle_desc)
                tvAiShieldStatus.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            }

            btnAiShieldToggle.setText(R.string.btn_ai_shield_start)
            btnAiShieldToggle.strokeColor = ContextCompat.getColorStateList(activity, R.color.status_green)
            btnAiShieldToggle.setTextColor(ContextCompat.getColor(activity, R.color.status_green))

            if (hasInitError) {
                tvAiShieldMetrics.text = activity.getString(R.string.ai_model_active_format, ScreenAiProjectionService.modelName)
                tvAiShieldMetrics.setTextColor(ContextCompat.getColor(activity, R.color.status_orange))
            } else {
                tvAiShieldMetrics.setText(R.string.ai_no_detections_yet)
                tvAiShieldMetrics.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            }
        }
    }
}
