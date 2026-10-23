package com.drmohammed.antiporn.ui.accountability

import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch

/**
 * View binder responsible for rendering pending application installation requests
 * and handling remote approve/reject actions on behalf of the guardian.
 */
class GuardianApprovalsViewBinder(
    private val activity: AppCompatActivity,
    private val layoutPendingApps: LinearLayout,
    private val tvNoPendingApps: TextView,
    private val wardIdProvider: () -> String,
    private val onReloadRequested: () -> Unit
) {
    private val processingPackages = mutableSetOf<String>()

    fun renderPendingApprovals(items: List<Pair<String, String>>) {
        layoutPendingApps.removeAllViews()
        if (items.isEmpty()) {
            layoutPendingApps.addView(tvNoPendingApps)
            return
        }

        val context = activity
        for ((pkg, name) in items) {
            val card = MaterialCardView(context).apply {
                radius = resources.displayMetrics.density * 8
                cardElevation = resources.displayMetrics.density * 1
                setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer))
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
                strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
                layoutParams = lp
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            val titleView = TextView(context).apply {
                text = name
                textSize = 14f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                typeface = Typeface.DEFAULT_BOLD
            }
            container.addView(titleView)

            val pkgView = TextView(context).apply {
                text = pkg
                textSize = 11f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
            }
            container.addView(pkgView)

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }

            var approveBtn: MaterialButton? = null
            var rejectBtn: MaterialButton? = null

            approveBtn = MaterialButton(context).apply {
                text = context.getString(R.string.btn_approve_app)
                textSize = 12f
                cornerRadius = (6 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    resolveApproval(pkg, approve = true, approveBtn = approveBtn, rejectBtn = rejectBtn)
                }
            }
            btnRow.addView(approveBtn)

            rejectBtn = MaterialButton(context).apply {
                text = context.getString(R.string.btn_reject_app)
                textSize = 12f
                setTextColor(context.getColor(R.color.status_orange))
                strokeWidth = 0
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    resolveApproval(pkg, approve = false, approveBtn = approveBtn, rejectBtn = rejectBtn)
                }
            }
            btnRow.addView(rejectBtn)

            container.addView(btnRow)
            card.addView(container)
            layoutPendingApps.addView(card)
        }
    }

    fun renderError() {
        layoutPendingApps.removeAllViews()
        val errorView = TextView(activity).apply {
            text = activity.getString(R.string.error_loading_pending_apps)
            setTextColor(activity.getColor(R.color.status_orange))
            textSize = 13f
            val pad = (12 * activity.resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
        }
        layoutPendingApps.addView(errorView)
    }

    private fun resolveApproval(
        packageName: String,
        approve: Boolean,
        approveBtn: MaterialButton? = null,
        rejectBtn: MaterialButton? = null
    ) {
        if (processingPackages.contains(packageName)) return
        val wardId = wardIdProvider()
        if (wardId.isBlank()) return

        processingPackages.add(packageName)
        approveBtn?.isEnabled = false
        rejectBtn?.isEnabled = false

        activity.lifecycleScope.launch {
            if (activity.isFinishing || activity.isDestroyed) return@launch
            try {
                val approvalRepo = ApprovalRepository.getInstance(activity.applicationContext)
                val guardianName = activity.getString(R.string.guardian_default_name)
                val success = approvalRepo.resolveWardApproval(wardId, packageName, approve, guardianName)
                if (activity.isFinishing || activity.isDestroyed) return@launch
                if (success) {
                    Toast.makeText(
                        activity,
                        if (approve) activity.getString(R.string.toast_approval_granted) else activity.getString(R.string.toast_approval_rejected),
                        Toast.LENGTH_SHORT
                    ).show()
                    onReloadRequested()
                } else {
                    approveBtn?.isEnabled = true
                    rejectBtn?.isEnabled = true
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_approval_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (activity.isFinishing || activity.isDestroyed) return@launch
                Log.e(TAG, "Error resolving approval for $packageName", e)
                approveBtn?.isEnabled = true
                rejectBtn?.isEnabled = true
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_approval_failed),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                processingPackages.remove(packageName)
            }
        }
    }

    companion object {
        private const val TAG = "GuardianApprovalsBinder"
    }
}
