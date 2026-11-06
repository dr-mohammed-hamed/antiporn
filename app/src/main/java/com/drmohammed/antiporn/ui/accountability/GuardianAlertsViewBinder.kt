package com.drmohammed.antiporn.ui.accountability

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * View binder responsible for guardian alert banners (Self-Control modification notice
 * and Emergency Breakout request), including revert, dismiss, and reject actions.
 */
class GuardianAlertsViewBinder(
    private val activity: AppCompatActivity,
    private val cardSelfControlAlertBanner: MaterialCardView,
    private val tvSelfControlAlertMsg: TextView,
    private val btnRevertSelfControlChanges: MaterialButton,
    private val btnDismissSelfControlAlert: MaterialButton,
    private val cardBreakoutAlertBanner: MaterialCardView,
    private val tvBreakoutAlertMsg: TextView,
    private val btnRejectBreakout: MaterialButton,
    private val wardNameProvider: () -> String,
    private val wardIdProvider: () -> String,
    private val policyProvider: () -> DevicePolicyBundle,
    private val isSelfControlAllowedProvider: () -> Boolean,
    private val onPolicyReverted: (DevicePolicyBundle) -> Unit,
    private val onPolicyAcknowledged: (DevicePolicyBundle) -> Unit
) {
    private var lastRejectBreakoutClickTime = 0L

    fun setupListeners() {
        btnRevertSelfControlChanges.setOnClickListener {
            if (activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            val currentPolicy = policyProvider()
            val snapshotJson = currentPolicy.guardianPolicySnapshot
            if (snapshotJson.isNullOrBlank()) {
                cardSelfControlAlertBanner.visibility = View.GONE
                return@setOnClickListener
            }
            btnRevertSelfControlChanges.isEnabled = false
            activity.lifecycleScope.launch {
                try {
                    val snapshotPolicy = DevicePolicyBundle.fromJson(snapshotJson)
                    val accountRepo = AccountabilityRepository.getInstance(activity.applicationContext)
                    val myUid = accountRepo.getCachedUid()
                    val myName = accountRepo.getMyDisplayName()
                    val wardId = wardIdProvider()
                    val revertedPolicy = snapshotPolicy.copy(
                        guardianUid = currentPolicy.guardianUid?.takeIf { it.isNotBlank() } ?: myUid,
                        guardianName = currentPolicy.guardianName?.takeIf { it.isNotBlank() } ?: myName,
                        isSupervised = true,
                        isSelfControlAllowed = isSelfControlAllowedProvider(),
                        guardianPolicySnapshot = null,
                        lastSelfModifiedAt = null,
                        version = currentPolicy.version + 1,
                        updatedAt = TrustedTimeProvider.currentTimeMillis(activity.applicationContext)
                    )
                    val success = PolicyRepository.getInstance(activity.applicationContext).pushPolicyToWard(wardId, revertedPolicy)
                    if (activity.isFinishing || activity.isDestroyed) return@launch
                    if (success) {
                        cardSelfControlAlertBanner.visibility = View.GONE
                        onPolicyReverted(revertedPolicy)
                        Toast.makeText(activity, R.string.toast_self_control_reverted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, R.string.toast_policy_push_failed, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Error reverting self-control policy", e)
                } finally {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        btnRevertSelfControlChanges.isEnabled = true
                    }
                }
            }
        }

        btnDismissSelfControlAlert.setOnClickListener {
            if (activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            btnDismissSelfControlAlert.isEnabled = false
            activity.lifecycleScope.launch {
                try {
                    val currentPolicy = policyProvider()
                    val accountRepo = AccountabilityRepository.getInstance(activity.applicationContext)
                    val myUid = accountRepo.getCachedUid()
                    val myName = accountRepo.getMyDisplayName()
                    val wardId = wardIdProvider()
                    val acknowledgedPolicy = currentPolicy.copy(
                        guardianUid = currentPolicy.guardianUid?.takeIf { it.isNotBlank() } ?: myUid,
                        guardianName = currentPolicy.guardianName?.takeIf { it.isNotBlank() } ?: myName,
                        isSupervised = true,
                        guardianPolicySnapshot = null,
                        lastSelfModifiedAt = null,
                        version = currentPolicy.version + 1,
                        updatedAt = TrustedTimeProvider.currentTimeMillis(activity.applicationContext)
                    )
                    val success = PolicyRepository.getInstance(activity.applicationContext).pushPolicyToWard(wardId, acknowledgedPolicy)
                    if (activity.isFinishing || activity.isDestroyed) return@launch
                    if (success) {
                        cardSelfControlAlertBanner.visibility = View.GONE
                        onPolicyAcknowledged(acknowledgedPolicy)
                        Toast.makeText(activity, R.string.toast_self_control_dismissed, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, R.string.toast_policy_push_failed, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Error dismissing self-control alert", e)
                } finally {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        btnDismissSelfControlAlert.isEnabled = true
                    }
                }
            }
        }

        btnRejectBreakout.setOnClickListener {
            if (activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            val now = SystemClock.elapsedRealtime()
            if (now - lastRejectBreakoutClickTime < 1000L) return@setOnClickListener
            lastRejectBreakoutClickTime = now
            btnRejectBreakout.isEnabled = false
            activity.lifecycleScope.launch {
                try {
                    val wardId = wardIdProvider()
                    val result = AccountabilityRepository.getInstance(activity.applicationContext).rejectEmergencyBreakout(wardId)
                    if (activity.isFinishing || activity.isDestroyed) return@launch
                    result.fold(
                        onSuccess = {
                            Toast.makeText(activity, R.string.toast_breakout_rejected, Toast.LENGTH_SHORT).show()
                            cardBreakoutAlertBanner.visibility = View.GONE
                        },
                        onFailure = { err ->
                            Toast.makeText(activity, err.localizedMessage ?: activity.getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
                        }
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Error rejecting breakout", e)
                } finally {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        btnRejectBreakout.isEnabled = true
                    }
                }
            }
        }
    }

    fun renderPolicyAlerts(policy: DevicePolicyBundle) {
        if (activity.isFinishing || activity.isDestroyed) return
        val wardName = wardNameProvider()
        if (!policy.guardianPolicySnapshot.isNullOrBlank() || policy.lastSelfModifiedAt != null) {
            cardSelfControlAlertBanner.visibility = View.VISIBLE
            val formattedTime = if (policy.lastSelfModifiedAt != null) {
                val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                sdf.format(Date(policy.lastSelfModifiedAt))
            } else {
                ""
            }
            tvSelfControlAlertMsg.text = activity.getString(R.string.guardian_self_control_alert_msg, wardName, formattedTime)
        } else {
            cardSelfControlAlertBanner.visibility = View.GONE
        }

        if (policy.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING && policy.breakoutUnlockAt != null) {
            cardBreakoutAlertBanner.visibility = View.VISIBLE
            val nowTrusted = TrustedTimeProvider.currentTimeMillis(activity.applicationContext)
            val remainingMs = (policy.breakoutUnlockAt - nowTrusted).coerceAtLeast(0L)
            val hours = remainingMs / (1000 * 3600)
            val mins = (remainingMs % (1000 * 3600)) / (1000 * 60)
            val timeStr = String.format(Locale.US, "%02d:%02d", hours, mins)
            tvBreakoutAlertMsg.text = activity.getString(R.string.guardian_breakout_alert_msg, wardName, timeStr)

            val wardId = wardIdProvider()
            if (wardId.isNotBlank()) {
                try {
                    AppNotificationManager.getInstance(activity.applicationContext).postEventNotification(
                        category = NotificationCategory.GUARDIAN,
                        title = activity.getString(R.string.notif_breakout_alert_title),
                        message = activity.getString(R.string.notif_breakout_alert_desc, wardName, timeStr),
                        dedupKey = "breakout_alert_${wardId}_${policy.breakoutUnlockAt}",
                        cooldownMs = 60 * 60 * 1000L,
                        actionType = NotificationActionType.OPEN_GUARDIAN_PANEL,
                        actionPayload = wardId,
                        systemNotificationId = NotificationRegistry.EVENT_BREAKOUT_REQUEST_ID,
                        channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                        postSystemNotification = true,
                        importance = 3
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error logging breakout alert notification", e)
                }
            }
        } else {
            cardBreakoutAlertBanner.visibility = View.GONE
        }
    }

    fun hideSelfControlAlert() {
        cardSelfControlAlertBanner.visibility = View.GONE
    }

    fun setInputsEnabled(enabled: Boolean) {
        btnRevertSelfControlChanges.isEnabled = enabled
        btnDismissSelfControlAlert.isEnabled = enabled
        btnRejectBreakout.isEnabled = enabled
    }

    companion object {
        private const val TAG = "GuardianAlertsBinder"
    }
}
