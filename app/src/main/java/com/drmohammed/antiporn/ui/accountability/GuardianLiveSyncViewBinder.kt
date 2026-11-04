package com.drmohammed.antiporn.ui.accountability

import android.content.res.ColorStateList
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.google.android.material.color.MaterialColors
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * View binder responsible for rendering the live sync & delivery badge
 * indicating whether the guardian's policies have been acknowledged and applied
 * on the ward's device, or whether the device is pending/offline.
 */
class GuardianLiveSyncViewBinder(
    private val activity: AppCompatActivity,
    private val layoutLiveSyncBadge: LinearLayout,
    private val ivLiveSyncIcon: ImageView,
    private val tvLiveSyncText: TextView,
    private val wardIdProvider: () -> String
) {
    private var policyListener: ListenerRegistration? = null

    fun updateLiveSyncBadge(version: Long, acknowledgedVersion: Long, lastAcknowledgedAt: Long, lastHeartbeat: Long) {
        if (activity.isFinishing || activity.isDestroyed) return
        layoutLiveSyncBadge.visibility = View.VISIBLE
        if (acknowledgedVersion >= version && version > 0) {
            ivLiveSyncIcon.setImageResource(R.drawable.ic_verified)
            val primaryColor = MaterialColors.getColor(
                activity,
                androidx.appcompat.R.attr.colorPrimary,
                ContextCompat.getColor(activity, R.color.md_theme_light_primary)
            )
            ivLiveSyncIcon.imageTintList = ColorStateList.valueOf(primaryColor)
            tvLiveSyncText.text = activity.getString(R.string.guardian_sync_applied)
        } else {
            ivLiveSyncIcon.setImageResource(R.drawable.ic_warning_amber)
            val tertiaryColor = MaterialColors.getColor(
                activity,
                com.google.android.material.R.attr.colorTertiary,
                ContextCompat.getColor(activity, R.color.md_theme_light_tertiary)
            )
            ivLiveSyncIcon.imageTintList = ColorStateList.valueOf(tertiaryColor)
            val now = TrustedTimeProvider.currentTimeMillis(activity)
            val offlineMinutes = if (lastHeartbeat > 0L && now > lastHeartbeat) (now - lastHeartbeat) / 60000L else 0L
            if (offlineMinutes >= 15L) {
                tvLiveSyncText.text = activity.getString(R.string.guardian_sync_offline_minutes, offlineMinutes)
            } else {
                tvLiveSyncText.text = activity.getString(R.string.guardian_sync_pending)
            }
        }
    }

    fun listenForPolicyUpdates() {
        val wardId = wardIdProvider()
        if (wardId.isBlank()) return
        stopListening()
        try {
            val firestore = FirebaseFirestore.getInstance()
            policyListener = firestore.collection("policies").document(wardId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Error in real-time policy listener for ward $wardId", error)
                        return@addSnapshotListener
                    }
                    if (activity.isFinishing || activity.isDestroyed || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    val version = snapshot.getLong("version") ?: 1L
                    val acknowledgedVersion = snapshot.getLong("acknowledgedVersion") ?: 0L
                    val lastAcknowledgedAt = snapshot.getLong("lastAcknowledgedAt") ?: 0L
                    val lastHeartbeat = snapshot.getLong("lastHeartbeat") ?: 0L
                    updateLiveSyncBadge(version, acknowledgedVersion, lastAcknowledgedAt, lastHeartbeat)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach real-time policy listener for ward $wardId", e)
        }
    }

    fun stopListening() {
        policyListener?.remove()
        policyListener = null
    }

    companion object {
        private const val TAG = "GuardianLiveSyncBinder"
    }
}
