package com.drmohammed.antiporn.ui.control

import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.ui.control.qr.QrScannerBottomSheet
import com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * View binder responsible for rendering pending application installation requests
 * and handling approve/reject actions for self-control mode.
 */
class SelfControlApprovalsViewBinder(
    private val activity: AppCompatActivity,
    private val layoutPendingApps: LinearLayout,
    private val tvNoPendingApps: TextView,
    private val cardPendingApps: View?,
    private val onReloadRequested: () -> Unit
) {
    fun renderPendingApprovals(items: List<Pair<String, String>>) {
        layoutPendingApps.removeAllViews()
        if (items.isEmpty()) {
            layoutPendingApps.addView(tvNoPendingApps)
            tvNoPendingApps.visibility = View.VISIBLE
            cardPendingApps?.visibility = View.GONE
            return
        }
        cardPendingApps?.visibility = View.VISIBLE
        tvNoPendingApps.visibility = View.GONE
        for ((pkg, name) in items) {
            val card = MaterialCardView(activity).apply {
                radius = resources.displayMetrics.density * 10
                cardElevation = resources.displayMetrics.density * 1
                setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh))
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
                strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
            }
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val titleView = TextView(activity).apply {
                text = name
                textSize = 14f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                typeface = Typeface.DEFAULT_BOLD
            }
            container.addView(titleView)
            val pkgView = TextView(activity).apply {
                text = pkg
                textSize = 11f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
            }
            container.addView(pkgView)
            val btnRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            lateinit var approveBtn: MaterialButton
            lateinit var rejectBtn: MaterialButton
            approveBtn = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                text = activity.getString(R.string.btn_approve_app)
                textSize = 12f
                cornerRadius = (8 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    resolveApproval(pkg, isApproved = true, approveBtn = approveBtn, rejectBtn = rejectBtn)
                }
            }
            btnRow.addView(approveBtn)
            rejectBtn = MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = activity.getString(R.string.btn_reject_app)
                textSize = 12f
                setTextColor(activity.getColor(R.color.status_orange))
                setOnClickListener {
                    resolveApproval(pkg, isApproved = false, approveBtn = approveBtn, rejectBtn = rejectBtn)
                }
            }
            btnRow.addView(rejectBtn)
            container.addView(btnRow)
            card.addView(container)
            layoutPendingApps.addView(card)
        }
    }

    private fun resolveApproval(
        pkg: String,
        isApproved: Boolean,
        approveBtn: MaterialButton?,
        rejectBtn: MaterialButton?
    ) {
        val executeAction = {
            approveBtn?.isEnabled = false
            rejectBtn?.isEnabled = false
            activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val repo = ApprovalRepository.getInstance(activity.applicationContext)
                    if (isApproved) {
                        repo.unsuspendAndApprove(pkg, "SELF")
                    } else {
                        repo.recordRejection(pkg, "SELF")
                    }
                    withContext(Dispatchers.Main) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            onReloadRequested()
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed resolving approval for package $pkg (isApproved=$isApproved)", e)
                    withContext(Dispatchers.Main) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            approveBtn?.isEnabled = true
                            rejectBtn?.isEnabled = true
                            Toast.makeText(activity, R.string.toast_self_policy_save_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        if (isApproved) {
            val qrManager = SelfControlQrManager.getInstance(activity.applicationContext)
            if (qrManager.isQrLockEnabled()) {
                val tag = "qr_scanner_approve_app"
                if (activity.supportFragmentManager.isStateSaved || activity.supportFragmentManager.findFragmentByTag(tag) != null) return
                QrScannerBottomSheet.newInstance().apply {
                    onVerified = { executeAction() }
                    onEmergencyBypassTriggered = { executeAction() }
                }.show(activity.supportFragmentManager, tag)
            } else {
                executeAction()
            }
        } else {
            executeAction()
        }
    }

    companion object {
        private const val TAG = "SelfControlApprovals"
    }
}
