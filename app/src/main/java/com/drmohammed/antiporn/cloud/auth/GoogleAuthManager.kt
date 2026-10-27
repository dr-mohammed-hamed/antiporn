package com.drmohammed.antiporn.cloud.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Enterprise manager for Google Identity linking and sign-in via Android Credential Manager.
 * Seamlessly upgrades anonymous accounts to persistent Google-backed cloud accounts
 * while preserving the identical Firebase UID and all active supervision policies.
 */
class GoogleAuthManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val auth = FirebaseAuth.getInstance()

    /**
     * Checks if the currently active user is linked with Google.
     */
    fun isLinkedWithGoogle(): Boolean {
        val user = auth.currentUser ?: return false
        if (user.isAnonymous) return false
        return user.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }
    }

    /**
     * Gets the linked Google email, if available.
     */
    fun getLinkedEmail(): String? {
        val user = auth.currentUser ?: return null
        return user.email ?: user.providerData.firstOrNull { it.providerId == GoogleAuthProvider.PROVIDER_ID }?.email
    }

    /**
     * Launches the Google Credential Manager picker and links or signs into Firebase.
     * If user is currently anonymous, links account in-place (preserving current UID).
     * If user is restoring after factory reset, signs into the previous Google account.
     */
    suspend fun signInOrLinkWithGoogle(activity: Activity): Result<FirebaseUser> = withContext(Dispatchers.IO) {
        val serverClientId = try {
            appContext.getString(R.string.default_web_client_id).trim()
        } catch (_: Exception) {
            ""
        }

        if (serverClientId.isBlank()) {
            val msg = appContext.getString(R.string.error_google_client_id_missing)
            Log.w(TAG, msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credentialManager = CredentialManager.create(activity)
            val response = credentialManager.getCredential(
                context = activity,
                request = request
            )

            val credential = response.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken

                val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                val currentUser = auth.currentUser

                val resolvedUser = if (currentUser != null && currentUser.isAnonymous) {
                    try {
                        Log.i(TAG, "Upgrading anonymous user ${currentUser.uid} in-place with Google credential...")
                        val linkResult = currentUser.linkWithCredential(firebaseCredential).await()
                        linkResult.user ?: currentUser
                    } catch (collisionEx: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                        if (AccountabilityRepository.getInstance(appContext).isSupervised()) {
                            Log.w(TAG, "Cannot switch to existing Google account while device is supervised")
                            throw IllegalStateException(appContext.getString(R.string.error_switch_account_while_supervised))
                        }
                        Log.w(TAG, "Google account already exists in Firebase. Signing into existing account instead...")
                        val signInResult = auth.signInWithCredential(firebaseCredential).await()
                        signInResult.user ?: throw collisionEx
                    }
                } else if (currentUser != null && !currentUser.isAnonymous) {
                    currentUser
                } else {
                    Log.i(TAG, "No user present; signing in directly with Google credential...")
                    val signInResult = auth.signInWithCredential(firebaseCredential).await()
                    signInResult.user ?: throw IllegalStateException("Firebase sign-in returned null user")
                }

                val finalUid = resolvedUser.uid
                val accountabilityRepo = AccountabilityRepository.getInstance(appContext)
                accountabilityRepo.persistUid(finalUid)
                PolicyRepository.getInstance(appContext).startFirestoreSync()
                accountabilityRepo.syncPartnersFromCloud()

                Log.i(TAG, "Google authentication successful for UID $finalUid (email: ${resolvedUser.email})")
                Result.success(resolvedUser)
            } else {
                Result.failure(IllegalStateException("Unsupported credential type received from CredentialManager"))
            }
        } catch (e: GetCredentialCancellationException) {
            Log.i(TAG, "User dismissed Google credential picker")
            Result.failure(e)
        } catch (e: GetCredentialException) {
            Log.e(TAG, "CredentialManager failed: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error during Google Auth flow", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "AntiPorn_GoogleAuth"

        @Volatile
        private var instance: GoogleAuthManager? = null

        fun getInstance(context: Context): GoogleAuthManager {
            return instance ?: synchronized(this) {
                instance ?: GoogleAuthManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
