package com.drmohammed.antiporn.provisioning.engine

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Pre-flight auditor for active system accounts.
 *
 * Android strictly forbids setting a Device Owner if any user accounts
 * (Google, Samsung, WhatsApp, Telegram, etc.) are currently configured on User 0.
 *
 * This auditor provides real-time state inspection and intent shortcuts to guide
 * non-technical users in temporarily removing accounts before provisioning.
 */
object AccountAuditor {

    private const val TAG = "AntiPorn_AccountAuditor"

    /**
     * Retrieves all active system accounts on the device.
     */
    fun getActiveAccounts(context: Context): List<Account> {
        return try {
            val accountManager = AccountManager.get(context.applicationContext)
            accountManager.accounts?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve accounts from AccountManager", e)
            emptyList()
        }
    }

    /**
     * Returns the number of accounts currently registered.
     * Must be 0 for Device Owner provisioning to succeed.
     */
    fun getAccountCount(context: Context): Int {
        return getActiveAccounts(context).size
    }

    /**
     * Checks if the device has zero accounts and is ready for Device Owner provisioning.
     */
    fun isReadyForProvisioning(context: Context): Boolean {
        return getAccountCount(context) == 0
    }

    /**
     * Returns a user-friendly human-readable list of account descriptions or names.
     */
    fun getAccountSummaryList(context: Context): List<String> {
        return getActiveAccounts(context).map { account ->
            val typeLabel = when {
                account.type.contains("google", ignoreCase = true) -> "Google"
                account.type.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
                account.type.contains("telegram", ignoreCase = true) -> "Telegram"
                account.type.contains("samsung", ignoreCase = true) -> "Samsung Account"
                account.type.contains("xiaomi", ignoreCase = true) -> "Mi Account"
                else -> account.type.substringAfterLast('.')
            }
            "$typeLabel (${account.name})"
        }
    }

    /**
     * Launches the system's Accounts & Sync settings screen.
     */
    fun openAccountSettings(context: Context) {
        val appContext = context.applicationContext
        try {
            val intent = Intent(Settings.ACTION_SYNC_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open ACTION_SYNC_SETTINGS, falling back to general settings", e)
            try {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                appContext.startActivity(fallbackIntent)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to open settings screen", ex)
            }
        }
    }
}
