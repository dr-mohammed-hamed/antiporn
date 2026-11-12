package com.drmohammed.antiporn.ui.shields

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Material 3 Bottom Sheet modal presenting currently active and verified security shields.
 */
object ActiveShieldsBottomSheet {

    private const val TAG = "ActiveShieldsSheet"
    private val sheetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentDialog: WeakReference<BottomSheetDialog>? = null
    private var activeEvalJob: Job? = null

    /**
     * Safely dismisses any currently active dialog instance.
     */
    fun dismiss() {
        activeEvalJob?.cancel()
        activeEvalJob = null
        try {
            currentDialog?.get()?.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "Failed dismissing ActiveShieldsBottomSheet", e)
        }
        currentDialog = null
    }

    fun show(context: Context) {
        val activity = findActivity(context)
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return
        }

        dismiss()

        val dialog = BottomSheetDialog(activity)
        currentDialog = WeakReference(dialog)
        var lifecycleObserver: LifecycleEventObserver? = null

        dialog.setOnDismissListener {
            activeEvalJob?.cancel()
            activeEvalJob = null
            if (activity is LifecycleOwner && lifecycleObserver != null) {
                activity.lifecycle.removeObserver(lifecycleObserver!!)
            }
            if (currentDialog?.get() === dialog) {
                currentDialog = null
            }
        }

        val inflater = LayoutInflater.from(activity)
        val sheetParent = FrameLayout(activity)
        val sheetView = inflater.inflate(R.layout.bottom_sheet_active_shields, sheetParent, false)
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

        container.visibility = View.GONE
        emptyState.visibility = View.GONE

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        if (activity.isFinishing || activity.isDestroyed) {
            currentDialog = null
            return
        }

        try {
            dialog.show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed showing ActiveShieldsBottomSheet", e)
            currentDialog = null
            return
        }

        if (activity is LifecycleOwner) {
            lifecycleObserver = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    dismiss()
                }
            }.also { observer ->
                activity.lifecycle.addObserver(observer)
            }
        }

        val appContext = activity.applicationContext ?: activity
        activeEvalJob = sheetScope.launch {
            val activeShields = withContext(Dispatchers.IO) {
                ActiveShieldsEvaluator.evaluateActiveShields(appContext)
            }
            if (!isActive || activity.isFinishing || activity.isDestroyed || currentDialog?.get() !== dialog) {
                return@launch
            }
            tvCount.text = activity.getString(R.string.bottom_sheet_shields_count, activeShields.size)

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
