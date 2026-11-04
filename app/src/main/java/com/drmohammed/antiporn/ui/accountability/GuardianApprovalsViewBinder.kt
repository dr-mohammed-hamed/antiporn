package com.drmohammed.antiporn.ui.accountability

import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isEmpty
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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
        val cardParent = activity.findViewById<android.view.View>(R.id.cardPendingApps)
            ?: (layoutPendingApps.parent?.parent as? android.view.View)
        if (items.isEmpty()) {
            (tvNoPendingApps.parent as? ViewGroup)?.removeView(tvNoPendingApps)
            layoutPendingApps.addView(tvNoPendingApps)
            cardParent?.visibility = android.view.View.GONE
            return
        }
        cardParent?.visibility = android.view.View.VISIBLE

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
                    resolveApproval(pkg, approve = true, card = card, approveBtn = approveBtn, rejectBtn = rejectBtn)
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
                    resolveApproval(pkg, approve = false, card = card, approveBtn = approveBtn, rejectBtn = rejectBtn)
                }
            }
            btnRow.addView(rejectBtn)

            container.addView(btnRow)
            card.addView(container)
            layoutPendingApps.addView(card)
        }
    }

    fun renderError() {
        val cardParent = activity.findViewById<android.view.View>(R.id.cardPendingApps)
            ?: (layoutPendingApps.parent?.parent as? android.view.View)
        cardParent?.visibility = android.view.View.VISIBLE
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
        card: MaterialCardView? = null,
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
            if (activity.isFinishing || activity.isDestroyed) {
                processingPackages.remove(packageName)
                return@launch
            }
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
                    card?.let {
                        layoutPendingApps.removeView(it)
                        if (layoutPendingApps.isEmpty()) {
                            val cardParent = activity.findViewById<android.view.View>(R.id.cardPendingApps)
                                ?: (layoutPendingApps.parent?.parent as? android.view.View)
                            cardParent?.visibility = android.view.View.GONE
                        }
                    }
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

    private var approvalsListener: com.google.firebase.firestore.ListenerRegistration? = null

    fun loadPendingApprovals() {
        val wardId = wardIdProvider()
        if (wardId.isBlank()) return
        activity.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (activity.isFinishing || activity.isDestroyed) return@launch
            try {
                val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val snapshot = firestore.collection("policies").document(wardId)
                    .collection("approvals")
                    .whereEqualTo("status", "PENDING")
                    .get()
                    .await()

                val pendingList = mutableListOf<Pair<String, String>>()
                for (doc in snapshot.documents) {
                    val pkg = doc.getString("packageName") ?: doc.id
                    val appName = doc.getString("appName") ?: pkg
                    if (pkg.isNotBlank()) {
                        pendingList.add(pkg to appName)
                    }
                }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    renderPendingApprovals(pendingList)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (activity.isFinishing || activity.isDestroyed) return@launch
                Log.e(TAG, "Error loading pending approvals for ward $wardId", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    renderError()
                    Toast.makeText(activity, R.string.error_loading_pending_apps, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun listenForPendingApprovals() {
        val wardId = wardIdProvider()
        if (wardId.isBlank()) return
        stopListening()
        try {
            val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            approvalsListener = firestore.collection("policies").document(wardId)
                .collection("approvals")
                .whereEqualTo("status", "PENDING")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Error in pending approvals listener for ward $wardId", error)
                        return@addSnapshotListener
                    }
                    if (activity.isFinishing || activity.isDestroyed || snapshot == null) return@addSnapshotListener
                    val pendingList = mutableListOf<Pair<String, String>>()
                    for (doc in snapshot.documents) {
                        val pkg = doc.getString("packageName") ?: doc.id
                        val appName = doc.getString("appName") ?: pkg
                        if (pkg.isNotBlank()) {
                            pendingList.add(pkg to appName)
                        }
                    }
                    renderPendingApprovals(pendingList)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach pending approvals listener for ward $wardId", e)
        }
    }

    fun stopListening() {
        approvalsListener?.remove()
        approvalsListener = null
    }

    companion object {
        private const val TAG = "GuardianApprovalsBinder"
    }
}
