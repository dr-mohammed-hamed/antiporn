package com.drmohammed.antiporn.ui.notification

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.ItemInAppNotificationBinding
import com.drmohammed.antiporn.notification.model.InAppNotification
import com.drmohammed.antiporn.notification.model.NotificationCategory

class NotificationAdapter(
    private val onNotificationClick: (InAppNotification) -> Unit
) : ListAdapter<InAppNotification, NotificationAdapter.NotificationViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val binding = ItemInAppNotificationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return NotificationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NotificationViewHolder(
        private val binding: ItemInAppNotificationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: InAppNotification) {
            binding.tvNotificationTitle.text = item.title
            binding.tvNotificationMessage.text = item.message
            binding.viewUnreadDot.visibility = if (item.isRead) View.GONE else View.VISIBLE

            val now = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(binding.root.context)
            val relativeTime = DateUtils.getRelativeTimeSpanString(
                item.timestamp,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            binding.tvNotificationTime.text = relativeTime

            val iconRes = when (item.category) {
                NotificationCategory.APPROVALS -> R.drawable.ic_shield_freeze
                NotificationCategory.GUARDIAN -> R.drawable.ic_shield_device_owner
                NotificationCategory.CURFEW -> R.drawable.ic_shield_curfew
                NotificationCategory.SHIELDS -> R.drawable.ic_shield_dns
                NotificationCategory.AI -> R.drawable.ic_ai_shield
                NotificationCategory.OTA -> R.drawable.ic_system_update
                else -> R.drawable.ic_warning_amber
            }
            binding.ivCategoryIcon.setImageResource(iconRes)

            binding.cardNotification.setOnClickListener {
                onNotificationClick(item)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<InAppNotification>() {
            override fun areItemsTheSame(oldItem: InAppNotification, newItem: InAppNotification): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: InAppNotification, newItem: InAppNotification): Boolean {
                return oldItem == newItem
            }
        }
    }
}
