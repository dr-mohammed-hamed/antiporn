package com.drmohammed.antiporn.notification.router

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.notification.model.InAppNotification
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.ui.accountability.GuardianControlActivity

/**
 * Smart Action Router responsible for executing appropriate deep-linking
 * and screen navigation when in-app notifications are tapped.
 */
object NotificationActionRouter {

    private const val TAG = "AntiPorn_NotifRouter"
    const val EXTRA_NAVIGATE_TAB = "extra_navigate_tab"
    const val TAB_HOME = 0
    const val TAB_SELF_CONTROL = 1
    const val TAB_ACCOUNTABILITY = 2
    const val TAB_SETTINGS = 3

    /**
     * Executes the navigation action associated with an in-app notification.
     */
    fun route(context: Context, notification: InAppNotification) {
        route(context, notification.actionType, notification.actionPayload)
    }

    /**
     * Dispatches navigation based on action type key and optional payload.
     */
    fun route(context: Context, actionTypeKey: String, payload: String?) {
        route(context, NotificationActionType.fromKey(actionTypeKey), payload)
    }

    /**
     * Dispatches navigation based on action type and optional payload.
     */
    fun route(context: Context, actionType: NotificationActionType, payload: String?) {
        Log.i(TAG, "Routing notification action: ${actionType.key} with payload: $payload")
        when (actionType) {
            NotificationActionType.OPEN_SELF_APPROVALS,
            NotificationActionType.OPEN_CURFEW -> {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_NAVIGATE_TAB, TAB_SELF_CONTROL)
                }
                context.startActivity(intent)
            }

            NotificationActionType.OPEN_GUARDIAN_PANEL -> {
                val intent = Intent(context, GuardianControlActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    if (!payload.isNullOrBlank()) {
                        putExtra(GuardianControlActivity.EXTRA_WARD_ID, payload)
                    }
                }
                context.startActivity(intent)
            }

            NotificationActionType.OPEN_OTA -> {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_NAVIGATE_TAB, TAB_SETTINGS)
                }
                context.startActivity(intent)
            }

            NotificationActionType.LAUNCH_APP -> {
                val packageName = payload
                if (!packageName.isNullOrBlank()) {
                    val isCurfewActive = CurfewEnforcer.isEffectiveBlockActive(context)
                    val policy = try {
                        PolicyRepository.getInstance(context).getCurrentPolicy()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed reading policy during package launch check", e)
                        null
                    }
                    val isWhitelisted = CurfewConfig.STATIC_WHITELIST.contains(packageName) ||
                            policy?.customCurfewWhitelistPackages?.contains(packageName) == true

                    if (isCurfewActive && !isWhitelisted) {
                        Toast.makeText(
                            context,
                            R.string.notif_apps_suspended_title,
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                        } else {
                            Toast.makeText(
                                context,
                                R.string.toast_open_app_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }

            NotificationActionType.NONE -> {
                // Informational notification, no navigation required
            }
        }
    }
}
