package com.drmohammed.antiporn.ui.accountability

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

data class WardApprovalModel(
    val packageName: String,
    val appName: String,
    val wardId: String,
    val wardName: String,
    val status: String, // "PENDING", "APPROVED", "REJECTED"
    val requestedAt: Long,
    val decidedBy: String? = null,
    val decidedAt: Long? = null,
    val isHighlighted: Boolean = false
)

class GuardianApprovalsAdapter(
    private val onApproveClick: (WardApprovalModel, (Boolean) -> Unit) -> Unit,
    private val onRejectClick: (WardApprovalModel, (Boolean) -> Unit) -> Unit,
    private val onRevokeClick: (WardApprovalModel, (Boolean) -> Unit) -> Unit,
    private val onReApproveClick: (WardApprovalModel, (Boolean) -> Unit) -> Unit
) : ListAdapter<WardApprovalModel, GuardianApprovalsAdapter.ApprovalViewHolder>(DiffCallback) {

    private val inFlightKeys = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ApprovalViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ward_approval, parent, false)
        return ApprovalViewHolder(view)
    }

    override fun onBindViewHolder(holder: ApprovalViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ApprovalViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardApproval: MaterialCardView = itemView.findViewById(R.id.cardApproval)
        private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPackageName: TextView = itemView.findViewById(R.id.tvPackageName)
        private val tvWardName: TextView = itemView.findViewById(R.id.tvWardName)
        private val tvRiskBadge: TextView = itemView.findViewById(R.id.tvRiskBadge)
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tvStatusBadge)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)

        private val layoutPendingActions: LinearLayout = itemView.findViewById(R.id.layoutPendingActions)
        private val btnApprove: MaterialButton = itemView.findViewById(R.id.btnApprove)
        private val btnReject: MaterialButton = itemView.findViewById(R.id.btnReject)

        private val layoutResolvedActions: LinearLayout = itemView.findViewById(R.id.layoutResolvedActions)
        private val btnChangeDecision: MaterialButton = itemView.findViewById(R.id.btnChangeDecision)

        fun bind(item: WardApprovalModel) {
            val ctx = itemView.context
            val itemKey = "${item.wardId}_${item.packageName}"
            val isOperating = inFlightKeys.contains(itemKey)

            tvAppName.text = item.appName
            tvPackageName.text = item.packageName
            tvWardName.text = ctx.getString(R.string.label_ward_prefix, item.wardName)

            val isHighRisk = com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isHighRisk(item.packageName)
            tvRiskBadge.visibility = if (isHighRisk) View.VISIBLE else View.GONE

            // Highlighting if directed from notification
            val primaryColor = com.google.android.material.color.MaterialColors.getColor(
                cardApproval,
                androidx.appcompat.R.attr.colorPrimary,
                Color.BLUE
            )
            if (item.isHighlighted) {
                cardApproval.strokeColor = primaryColor
                cardApproval.strokeWidth = (2 * ctx.resources.displayMetrics.density).toInt()
            } else {
                cardApproval.strokeColor = com.google.android.material.color.MaterialColors.getColor(
                    cardApproval,
                    com.google.android.material.R.attr.colorOutlineVariant,
                    Color.LTGRAY
                )
                cardApproval.strokeWidth = (1 * ctx.resources.displayMetrics.density).toInt()
            }

            // Time formatting
            val timeAgo = DateUtils.getRelativeTimeSpanString(
                item.requestedAt,
                TrustedTimeProvider.currentTimeMillis(ctx),
                DateUtils.MINUTE_IN_MILLIS
            )
            tvTimestamp.text = ctx.getString(R.string.label_requested_time, timeAgo)

            when (item.status.uppercase()) {
                "PENDING" -> {
                    updateStatusBadge(
                        badgeView = tvStatusBadge,
                        text = ctx.getString(R.string.tab_approvals_pending),
                        textColorRes = R.color.status_orange,
                        bgColorRes = R.color.status_orange_bg
                    )

                    layoutPendingActions.visibility = View.VISIBLE
                    layoutResolvedActions.visibility = View.GONE

                    btnApprove.isEnabled = !isOperating
                    btnReject.isEnabled = !isOperating

                    btnApprove.setOnClickListener {
                        inFlightKeys.add(itemKey)
                        btnApprove.isEnabled = false
                        btnReject.isEnabled = false
                        onApproveClick(item) { success ->
                            inFlightKeys.remove(itemKey)
                            if (!success) {
                                val pos = bindingAdapterPosition
                                if (pos != RecyclerView.NO_POSITION) {
                                    notifyItemChanged(pos)
                                }
                            }
                        }
                    }
                    btnReject.setOnClickListener {
                        inFlightKeys.add(itemKey)
                        btnApprove.isEnabled = false
                        btnReject.isEnabled = false
                        onRejectClick(item) { success ->
                            inFlightKeys.remove(itemKey)
                            if (!success) {
                                val pos = bindingAdapterPosition
                                if (pos != RecyclerView.NO_POSITION) {
                                    notifyItemChanged(pos)
                                }
                            }
                        }
                    }
                }
                "APPROVED" -> {
                    updateStatusBadge(
                        badgeView = tvStatusBadge,
                        text = ctx.getString(R.string.tab_approvals_approved),
                        textColorRes = R.color.status_green,
                        bgColorRes = R.color.status_green_bg
                    )

                    layoutPendingActions.visibility = View.GONE
                    layoutResolvedActions.visibility = View.VISIBLE

                    btnChangeDecision.text = ctx.getString(R.string.btn_revoke_approval)
                    btnChangeDecision.setTextColor(ContextCompat.getColor(ctx, R.color.status_orange))
                    btnChangeDecision.isEnabled = !isOperating
                    btnChangeDecision.setOnClickListener {
                        inFlightKeys.add(itemKey)
                        btnChangeDecision.isEnabled = false
                        onRevokeClick(item) { success ->
                            inFlightKeys.remove(itemKey)
                            if (!success) {
                                val pos = bindingAdapterPosition
                                if (pos != RecyclerView.NO_POSITION) {
                                    notifyItemChanged(pos)
                                }
                            }
                        }
                    }
                }
                "REJECTED" -> {
                    updateStatusBadge(
                        badgeView = tvStatusBadge,
                        text = ctx.getString(R.string.tab_approvals_rejected),
                        textColorRes = R.color.status_red,
                        bgColorRes = R.color.status_red_bg
                    )

                    layoutPendingActions.visibility = View.GONE
                    layoutResolvedActions.visibility = View.VISIBLE

                    btnChangeDecision.text = ctx.getString(R.string.btn_reapprove)
                    btnChangeDecision.setTextColor(primaryColor)
                    btnChangeDecision.isEnabled = !isOperating
                    btnChangeDecision.setOnClickListener {
                        inFlightKeys.add(itemKey)
                        btnChangeDecision.isEnabled = false
                        onReApproveClick(item) { success ->
                            inFlightKeys.remove(itemKey)
                            if (!success) {
                                val pos = bindingAdapterPosition
                                if (pos != RecyclerView.NO_POSITION) {
                                    notifyItemChanged(pos)
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun updateStatusBadge(badgeView: TextView, text: String, textColorRes: Int, bgColorRes: Int) {
            val ctx = badgeView.context
            badgeView.text = text
            badgeView.setTextColor(ContextCompat.getColor(ctx, textColorRes))
            val radius = 6f * ctx.resources.displayMetrics.density
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(ContextCompat.getColor(ctx, bgColorRes))
            }
            badgeView.background = shape
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<WardApprovalModel>() {
        override fun areItemsTheSame(oldItem: WardApprovalModel, newItem: WardApprovalModel): Boolean {
            return oldItem.wardId == newItem.wardId && oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: WardApprovalModel, newItem: WardApprovalModel): Boolean {
            return oldItem == newItem
        }
    }
}
