package com.drmohammed.antiporn.ui.shields

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.drmohammed.antiporn.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * Material 3 Bottom Sheet modal presenting currently active and verified security shields.
 */
object ActiveShieldsBottomSheet {

    private var currentDialog: BottomSheetDialog? = null

    /**
     * Safely dismisses any currently active dialog instance.
     */
    fun dismiss() {
        try {
            currentDialog?.dismiss()
        } catch (_: Exception) {}
        currentDialog = null
    }

    fun show(context: Context) {
        val activity = findActivity(context)
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
            return
        }

        dismiss()

        val dialog = BottomSheetDialog(context)
        currentDialog = dialog

        val lifecycleObserver = if (activity is LifecycleOwner) {
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    dismiss()
                }
            }.also { observer ->
                activity.lifecycle.addObserver(observer)
            }
        } else {
            null
        }

        dialog.setOnDismissListener {
            if (activity is LifecycleOwner && lifecycleObserver != null) {
                activity.lifecycle.removeObserver(lifecycleObserver)
            }
            if (currentDialog === dialog) {
                currentDialog = null
            }
        }

        val inflater = LayoutInflater.from(context)
        val sheetView = inflater.inflate(R.layout.bottom_sheet_active_shields, null)
        dialog.setContentView(sheetView)

        val baseBottomPadding = sheetView.paddingBottom
        val baseLeftPadding = sheetView.paddingLeft
        val baseRightPadding = sheetView.paddingRight
        val baseTopPadding = sheetView.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(sheetView) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                baseLeftPadding + insets.left,
                baseTopPadding,
                baseRightPadding + insets.right,
                baseBottomPadding + insets.bottom
            )
            windowInsets
        }

        val container = sheetView.findViewById<LinearLayout>(R.id.layoutShieldsContainer)
        val emptyState = sheetView.findViewById<LinearLayout>(R.id.layoutEmptyState)
        val tvCount = sheetView.findViewById<TextView>(R.id.tvBottomSheetCount)
        val btnClose = sheetView.findViewById<MaterialButton>(R.id.btnCloseBottomSheet)

        val activeShields = ActiveShieldsEvaluator.evaluateActiveShields(context)

        tvCount.text = context.getString(R.string.bottom_sheet_shields_count, activeShields.size)

        if (activeShields.isEmpty()) {
            container.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            container.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            container.removeAllViews()

            for (shield in activeShields) {
                val itemView = inflater.inflate(R.layout.item_active_shield, container, false)
                val ivIcon = itemView.findViewById<ImageView>(R.id.ivShieldIcon)
                val tvTitle = itemView.findViewById<TextView>(R.id.tvShieldTitle)
                val tvDesc = itemView.findViewById<TextView>(R.id.tvShieldDescription)
                val tvBadge = itemView.findViewById<TextView>(R.id.tvShieldBadge)

                ivIcon.setImageResource(shield.iconRes)
                tvTitle.text = shield.title
                tvDesc.text = shield.description
                tvBadge.text = shield.badgeText

                container.addView(itemView)
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
            currentDialog = null
            return
        }

        try {
            dialog.show()
        } catch (_: Exception) {
            currentDialog = null
        }
    }

    private fun findActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
