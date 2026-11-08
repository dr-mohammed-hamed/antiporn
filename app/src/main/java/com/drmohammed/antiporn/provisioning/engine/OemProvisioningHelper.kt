package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import android.os.Build
import android.os.UserManager
import androidx.annotation.StringRes
import com.drmohammed.antiporn.R
import timber.log.Timber
import java.util.Locale

/**
 * OEM-specific detection and guidance helper.
 *
 * Inspects device manufacturer and OEM configurations to present
 * tailored instructions for Xiaomi, Samsung, and Realme/Oppo devices.
 * Adheres strictly to idiomatic Kotlin, < 250 lines, zero external libraries.
 */
object OemProvisioningHelper {

    private const val TAG = "AntiPorn_OemHelper"

    val manufacturer: String
        get() = Build.MANUFACTURER.lowercase(Locale.ROOT)

    fun isXiaomi(): Boolean =
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")

    fun isSamsung(): Boolean =
        manufacturer.contains("samsung")

    fun isRealmeOrOppo(): Boolean =
        manufacturer.contains("realme") || manufacturer.contains("oppo") || manufacturer.contains("oneplus")

    /**
     * Checks if secondary users, work profiles, or Samsung Knox Secure Folder are active.
     */
    fun checkSamsungMultiUser(context: Context): Boolean {
        if (!isSamsung()) return false
        return try {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            val profiles = userManager?.userProfiles ?: emptyList()
            profiles.size > 1
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed inspecting user profiles: ${e.message}")
            false
        }
    }

    /**
     * Returns the appropriate instruction resource for enabling developer options.
     */
    @StringRes
    fun getDevOptionsDialogDescription(): Int {
        return if (isXiaomi()) {
            R.string.dialog_enable_dev_desc_miui
        } else {
            R.string.dialog_enable_dev_desc
        }
    }

    data class OemWarning(
        @param:StringRes val titleRes: Int,
        @param:StringRes val descRes: Int
    )

    /**
     * Returns the OEM-specific pre-flight guidance warning, if applicable.
     */
    fun getOemWarning(context: Context): OemWarning? {
        return when {
            isXiaomi() -> OemWarning(
                titleRes = R.string.oem_xiaomi_title,
                descRes = R.string.oem_xiaomi_desc
            )
            isSamsung() && checkSamsungMultiUser(context) -> OemWarning(
                titleRes = R.string.oem_samsung_multiuser_title,
                descRes = R.string.oem_samsung_multiuser_desc
            )
            isRealmeOrOppo() -> OemWarning(
                titleRes = R.string.oem_realme_oppo_title,
                descRes = R.string.oem_realme_oppo_desc
            )
            else -> null
        }
    }
}
