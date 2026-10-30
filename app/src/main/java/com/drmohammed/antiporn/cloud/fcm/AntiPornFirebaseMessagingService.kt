package com.drmohammed.antiporn.cloud.fcm

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * High-Priority Silent FCM service for instant background wake-up and remote policy enforcement.
 * Handled with Device Owner privileges to bypass Doze mode and OEM battery restrictions.
 */
class AntiPornFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM Token received: $token")
        persistToken(applicationContext, token)
        uploadFcmToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        Log.i(TAG, "FCM message received with data: $data")

        val action = data["action"] ?: return

        when (action) {
            ACTION_POLICY_UPDATE -> {
                Log.i(TAG, "Processing high-priority silent POLICY_UPDATE")
                val policyJson = data["policy_json"]
                if (!policyJson.isNullOrBlank()) {
                    try {
                        val bundle = DevicePolicyBundle.fromJson(policyJson)
                        PolicyRepository.getInstance(applicationContext).updateLocalPolicyAndEnforce(bundle)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deserializing policy from FCM payload", e)
                        // Fallback: trigger fresh pull from Firestore
                        PolicyRepository.getInstance(applicationContext).startFirestoreSync()
                    }
                } else {
                    PolicyRepository.getInstance(applicationContext).startFirestoreSync()
                }
            }

            ACTION_APPROVAL_RESPONSE -> {
                val packageName = data["package_name"] ?: return
                val status = data["status"] ?: return
                val decidedBy = data["decided_by"]
                Log.i(TAG, "Processing APPROVAL_RESPONSE for $packageName with status $status")

                val isApproved = status.equals("APPROVED", ignoreCase = true)
                val isPermanentlyBlocked = com.drmohammed.antiporn.shields.AppSuspensionShield.isBlacklisted(packageName) ||
                    PolicyRepository.getInstance(applicationContext).getCurrentPolicy().customBlacklistPackages.contains(packageName)
                val actuallyApproved = isApproved && !isPermanentlyBlocked

                if (isApproved) {
                    ApprovalRepository.getInstance(applicationContext).unsuspendAndApprove(packageName, decidedBy)
                } else {
                    // Stays suspended and record rejection in local SQLite
                    ApprovalRepository.getInstance(applicationContext).recordRejection(packageName, decidedBy)
                    Log.i(TAG, "Application $packageName rejection recorded from $decidedBy")
                }
                ProtectionNotificationCoordinator.postAppApprovalDecisionNotification(
                    applicationContext,
                    packageName,
                    actuallyApproved,
                    decidedBy
                )
            }

            ACTION_OTA_CHECK_UPDATE, ACTION_TRIGGER_OTA_UPDATE -> {
                Log.i(TAG, "Processing OTA update trigger ($action) from FCM")
                serviceScope.launch {
                    try {
                        com.drmohammed.antiporn.ota.repository.OtaRepository.getInstance(applicationContext)
                            .checkForUpdatesAndAutoInstall(applicationContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling OTA trigger from FCM", e)
                    }
                }
            }

            else -> {
                Log.d(TAG, "Unhandled FCM action: $action")
            }
        }
    }

    private fun uploadFcmToken(token: String) {
        serviceScope.launch {
            try {
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                    ?: AccountabilityRepository.getInstance(applicationContext).getCachedUid()
                FirebaseFirestore.getInstance().collection("users").document(uid)
                    .set(
                        mapOf("fcmToken" to token, "tokenUpdatedAt" to System.currentTimeMillis()),
                        SetOptions.merge()
                    ).await()
                Log.i(TAG, "Updated FCM token in Firestore for UID $uid")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating FCM token in Firestore", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "AntiPorn_FCM"
        const val PREFS_NAME = "antiporn_fcm_prefs"
        const val KEY_FCM_TOKEN = "key_fcm_token"
        const val ACTION_POLICY_UPDATE = "POLICY_UPDATE"
        const val ACTION_APPROVAL_RESPONSE = "APPROVAL_RESPONSE"
        const val ACTION_OTA_CHECK_UPDATE = "OTA_CHECK_UPDATE"
        const val ACTION_TRIGGER_OTA_UPDATE = "TRIGGER_OTA_UPDATE"

        fun persistToken(context: Context, token: String) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
            Log.d(TAG, "Persisted new FCM token in SharedPreferences")
        }

        fun getPersistedToken(context: Context): String? {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_FCM_TOKEN, null)
        }

        fun syncPersistedToken(context: Context, uid: String) {
            val token = getPersistedToken(context) ?: return
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    FirebaseFirestore.getInstance().collection("users").document(uid)
                        .set(
                            mapOf("fcmToken" to token, "tokenUpdatedAt" to System.currentTimeMillis()),
                            SetOptions.merge()
                        ).await()
                    Log.i(TAG, "Uploaded persisted FCM token to Firestore for UID $uid")
                } catch (e: Exception) {
                    Log.e(TAG, "Error uploading persisted FCM token to Firestore", e)
                }
            }
        }
    }
}
