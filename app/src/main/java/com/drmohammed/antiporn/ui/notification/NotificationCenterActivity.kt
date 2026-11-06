package com.drmohammed.antiporn.ui.notification

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.ActivityNotificationCenterBinding
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.router.NotificationActionRouter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Activity presenting the In-App Notification Center and Event History.
 * Supports category filtering, mark as read, batch clear, and deep-link routing.
 */
class NotificationCenterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationCenterBinding
    private val viewModel: NotificationCenterViewModel by viewModel()
    private lateinit var adapter: NotificationAdapter
    private var lastItemClickTime = 0L
    private var lastActionClickTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupFilters()
        setupRecyclerView()
        observeViewModel()
        handleIntentAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentAction(intent)
    }

    private fun handleIntentAction(intent: Intent?) {
        val actionKey = intent?.getStringExtra(AppNotificationManager.EXTRA_ACTION_TYPE) ?: return
        val payload = intent.getStringExtra(AppNotificationManager.EXTRA_ACTION_PAYLOAD)
        val actionType = NotificationActionType.fromKey(actionKey)
        if (actionType != NotificationActionType.NONE) {
            intent.removeExtra(AppNotificationManager.EXTRA_ACTION_TYPE)
            intent.removeExtra(AppNotificationManager.EXTRA_ACTION_PAYLOAD)
            NotificationActionRouter.route(this, actionType, payload)
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.btnMarkAllRead.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastActionClickTime < 500L) return@setOnClickListener
            lastActionClickTime = now
            viewModel.markAllAsRead()
        }

        binding.btnClearAll.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastActionClickTime < 500L) return@setOnClickListener
            lastActionClickTime = now
            showClearConfirmationDialog()
        }
    }

    private fun setupFilters() {
        binding.chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val selectedCategory = when (checkedIds.first()) {
                R.id.chipFilterApprovals -> NotificationCategory.APPROVALS
                R.id.chipFilterGuardian -> NotificationCategory.GUARDIAN
                R.id.chipFilterCurfew -> NotificationCategory.CURFEW
                R.id.chipFilterShields -> NotificationCategory.SHIELDS
                R.id.chipFilterAi -> NotificationCategory.AI
                R.id.chipFilterOta -> NotificationCategory.OTA
                R.id.chipFilterSystem -> NotificationCategory.SYSTEM
                else -> null
            }
            viewModel.setCategoryFilter(selectedCategory)
        }
    }

    private fun setupRecyclerView() {
        adapter = NotificationAdapter { notification ->
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastItemClickTime < 500L) return@NotificationAdapter
            lastItemClickTime = now

            if (!notification.isRead) {
                viewModel.markAsRead(notification.id)
            }
            NotificationActionRouter.route(this, notification)
        }

        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = adapter
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.notifications.collectLatest { list ->
                        adapter.submitList(list)
                        val isEmpty = list.isEmpty()
                        binding.layoutEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                        binding.rvNotifications.visibility = if (isEmpty) View.GONE else View.VISIBLE
                        binding.btnClearAll.isEnabled = !isEmpty
                    }
                }

                launch {
                    viewModel.unreadCount.collectLatest { count ->
                        binding.btnMarkAllRead.isEnabled = count > 0
                    }
                }
            }
        }
    }

    private fun showClearConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_clear_notifications_title)
            .setMessage(R.string.confirm_clear_notifications_msg)
            .setPositiveButton(R.string.btn_confirm_clear) { _, _ ->
                viewModel.clearAllNotifications()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
