package com.drmohammed.antiporn.ui.splash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.drmohammed.antiporn.R

/**
 * Orchestrates a fast, elegant, and hardware-accelerated animated splash screen
 * for the Hisn (حِصْن) brand identity.
 *
 * Runs exclusively on cold application launch (~1.1s total duration) and
 * smoothly removes itself from the view hierarchy upon completion with zero residue.
 */
class SplashAnimationController(private val activity: Activity) {

    companion object {
        /**
         * Cold-boot flag: true only once per process lifecycle.
         */
        @Volatile
        private var hasShownColdSplash = false

        /**
         * Reset method for testing or explicit cold relaunch.
         */
        fun resetColdBootFlag() {
            hasShownColdSplash = false
        }
    }

    private var activeAnimatorSet: AnimatorSet? = null
    private var splashOverlayView: View? = null
    @Volatile
    private var isDismissing = false

    /**
     * Checks if cold launch animation should execute.
     */
    fun shouldShowSplash(): Boolean = !hasShownColdSplash

    /**
     * Attaches the animated splash overlay and starts the choreographed sequence.
     *
     * @param onComplete Callback invoked when the splash animation completes or is dismissed.
     */
    fun showIfColdBoot(onComplete: (() -> Unit)? = null) {
        if (hasShownColdSplash || activity.isFinishing || activity.isDestroyed) {
            onComplete?.invoke()
            return
        }

        hasShownColdSplash = true
        isDismissing = false

        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
            onComplete?.invoke()
            return
        }

        val inflater = LayoutInflater.from(activity)
        val overlay = inflater.inflate(R.layout.view_splash_overlay, root, false)
        splashOverlayView = overlay
        root.addView(overlay)

        val ivGlow = overlay.findViewById<ImageView>(R.id.ivSplashGlow)
        val ivShield = overlay.findViewById<ImageView>(R.id.ivSplashShield)
        val tvTitle = overlay.findViewById<TextView>(R.id.tvSplashTitle)
        val tvSubtitle = overlay.findViewById<TextView>(R.id.tvSplashSubtitle)
        val contentGroup = overlay.findViewById<View>(R.id.splashContentGroup)

        // Initial invisible states before starting animation choreography
        ivShield.alpha = 0f
        ivShield.scaleX = 0.65f
        ivShield.scaleY = 0.65f

        ivGlow.alpha = 0f
        ivGlow.scaleX = 0.35f
        ivGlow.scaleY = 0.35f

        tvTitle.alpha = 0f
        tvTitle.translationY = 40f

        tvSubtitle.alpha = 0f
        tvSubtitle.translationY = 30f

        // Allow user to tap anywhere to quickly dismiss/skip
        overlay.setOnClickListener {
            dismissQuickly(root, onComplete)
        }

        // ==========================================
        // Phase 1: Shield Spring & Emerald Glow Aura
        // ==========================================
        val shieldScaleX = ObjectAnimator.ofFloat(ivShield, View.SCALE_X, 0.65f, 1.04f).apply {
            duration = 450
            interpolator = OvershootInterpolator(1.3f)
        }
        val shieldScaleY = ObjectAnimator.ofFloat(ivShield, View.SCALE_Y, 0.65f, 1.04f).apply {
            duration = 450
            interpolator = OvershootInterpolator(1.3f)
        }
        val shieldAlpha = ObjectAnimator.ofFloat(ivShield, View.ALPHA, 0f, 1f).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
        }

        val glowScaleX = ObjectAnimator.ofFloat(ivGlow, View.SCALE_X, 0.35f, 1.15f).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
        }
        val glowScaleY = ObjectAnimator.ofFloat(ivGlow, View.SCALE_Y, 0.35f, 1.15f).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
        }
        val glowAlpha = ObjectAnimator.ofFloat(ivGlow, View.ALPHA, 0f, 0.85f).apply {
            duration = 400
            interpolator = AccelerateDecelerateInterpolator()
        }

        // ==========================================
        // Phase 2: Shield Settle & Typography Reveal
        // ==========================================
        val shieldSettleX = ObjectAnimator.ofFloat(ivShield, View.SCALE_X, 1.04f, 1.0f).apply {
            startDelay = 450
            duration = 350
            interpolator = AccelerateDecelerateInterpolator()
        }
        val shieldSettleY = ObjectAnimator.ofFloat(ivShield, View.SCALE_Y, 1.04f, 1.0f).apply {
            startDelay = 450
            duration = 350
            interpolator = AccelerateDecelerateInterpolator()
        }

        val glowPulseX = ObjectAnimator.ofFloat(ivGlow, View.SCALE_X, 1.15f, 1.0f).apply {
            startDelay = 500
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
        }
        val glowPulseY = ObjectAnimator.ofFloat(ivGlow, View.SCALE_Y, 1.15f, 1.0f).apply {
            startDelay = 500
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
        }
        val glowPulseAlpha = ObjectAnimator.ofFloat(ivGlow, View.ALPHA, 0.85f, 0.65f).apply {
            startDelay = 500
            duration = 300
        }

        val titleAlpha = ObjectAnimator.ofFloat(tvTitle, View.ALPHA, 0f, 1f).apply {
            startDelay = 350
            duration = 350
            interpolator = DecelerateInterpolator()
        }
        val titleTranslate = ObjectAnimator.ofFloat(tvTitle, View.TRANSLATION_Y, 40f, 0f).apply {
            startDelay = 350
            duration = 350
            interpolator = DecelerateInterpolator()
        }

        val subtitleAlpha = ObjectAnimator.ofFloat(tvSubtitle, View.ALPHA, 0f, 1f).apply {
            startDelay = 450
            duration = 350
            interpolator = DecelerateInterpolator()
        }
        val subtitleTranslate = ObjectAnimator.ofFloat(tvSubtitle, View.TRANSLATION_Y, 30f, 0f).apply {
            startDelay = 450
            duration = 350
            interpolator = DecelerateInterpolator()
        }

        // ==========================================
        // Phase 3: Seamless Zoom & Fade-Out Exit
        // ==========================================
        val exitAlpha = ObjectAnimator.ofFloat(overlay, View.ALPHA, 1f, 0f).apply {
            startDelay = 950
            duration = 250
            interpolator = AccelerateInterpolator()
        }
        val exitScaleX = ObjectAnimator.ofFloat(contentGroup, View.SCALE_X, 1.0f, 1.06f).apply {
            startDelay = 950
            duration = 250
            interpolator = AccelerateInterpolator()
        }
        val exitScaleY = ObjectAnimator.ofFloat(contentGroup, View.SCALE_Y, 1.0f, 1.06f).apply {
            startDelay = 950
            duration = 250
            interpolator = AccelerateInterpolator()
        }

        val animatorSet = AnimatorSet().apply {
            playTogether(
                shieldScaleX, shieldScaleY, shieldAlpha,
                glowScaleX, glowScaleY, glowAlpha,
                shieldSettleX, shieldSettleY,
                glowPulseX, glowPulseY, glowPulseAlpha,
                titleAlpha, titleTranslate,
                subtitleAlpha, subtitleTranslate,
                exitAlpha, exitScaleX, exitScaleY
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cleanup(root, onComplete)
                }

                override fun onAnimationCancel(animation: Animator) {
                    cleanup(root, onComplete)
                }
            })
        }

        activeAnimatorSet = animatorSet
        animatorSet.start()
    }

    /**
     * Fast-forward dismissal in case of tap or immediate skip.
     */
    private fun dismissQuickly(root: ViewGroup, onComplete: (() -> Unit)?) {
        if (isDismissing) return
        isDismissing = true

        val overlay = splashOverlayView ?: run {
            onComplete?.invoke()
            return
        }

        overlay.isClickable = false
        overlay.setOnClickListener(null)

        activeAnimatorSet?.cancel()
        activeAnimatorSet = null

        overlay.animate().cancel()
        overlay.animate()
            .alpha(0f)
            .setDuration(150)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                cleanup(root, onComplete)
            }
            .start()
    }

    /**
     * Safely cleans up the overlay view and cancels any running animations.
     */
    fun cancel() {
        isDismissing = true
        activeAnimatorSet?.cancel()
        activeAnimatorSet = null
        splashOverlayView?.let { view ->
            view.animate().cancel()
            val parent = view.parent as? ViewGroup
            parent?.removeView(view)
        }
        splashOverlayView = null
    }

    private fun cleanup(root: ViewGroup, onComplete: (() -> Unit)?) {
        splashOverlayView?.let { overlay ->
            overlay.animate().cancel()
            root.removeView(overlay)
        }
        splashOverlayView = null
        activeAnimatorSet = null
        if (!activity.isFinishing && !activity.isDestroyed) {
            onComplete?.invoke()
        }
    }
}

