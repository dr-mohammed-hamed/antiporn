package com.drmohammed.antiporn.ui.control.curfew

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Sub-Screen for Night Curfew Settings.
 * Supports both Local (Self-Control) and Remote (Guardian) modes.
 */
class CurfewSettingsActivity : AppCompatActivity() {

    private var isRemote = false
    private var wardId: String = ""
    private lateinit var initialPolicy: DevicePolicyBundle
    private lateinit var viewBinder: CurfewSettingsViewBinder

    private var isSaving = false
    private var immediateConfirmDialog: AlertDialog? = null
    private var discardConfirmDialog: AlertDialog? = null

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (isSaving) return
            if (hasUnsavedChanges()) {
                showDiscardConfirmationDialog()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_curfew_settings)

        applyWindowInsets()

        isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        wardId = intent.getStringExtra(EXTRA_WARD_ID).orEmpty()
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)

        if (!isRemote && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (isRemote) {
            if (wardId.isBlank() || policyJson.isNullOrBlank()) {
                Log.e(TAG, "Refusing remote curfew settings launch: missing EXTRA_WARD_ID or EXTRA_POLICY_JSON")
                Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val parsed = DevicePolicyBundle.fromJsonOrNull(policyJson)
            if (parsed == null) {
                Log.e(TAG, "Refusing remote curfew settings launch: invalid EXTRA_POLICY_JSON")
                Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            initialPolicy = parsed
        } else {
            initialPolicy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        viewBinder = CurfewSettingsViewBinder(this)

        supportFragmentManager.setFragmentResultListener(
            CurfewAppPickerBottomSheet.REQUEST_KEY,
            this
        ) { _, bundle ->
            val whitelist = bundle.getStringArrayList(CurfewAppPickerBottomSheet.RESULT_WHITELIST)?.toSet() ?: emptySet()
            val vpnBypass = bundle.getStringArrayList(CurfewAppPickerBottomSheet.RESULT_VPN_BYPASS)?.toSet() ?: emptySet()
            viewBinder.selectedCurfewWhitelist = whitelist
            viewBinder.selectedCurfewVpnBypass = vpnBypass
            viewBinder.updateAllowedAppsLabel()
            updateSaveButtonState()
        }

        viewBinder.bindViews(canGoBack = { !isSaving })
        viewBinder.applyInitialPolicy(initialPolicy)
        if (savedInstanceState != null) {
            viewBinder.restoreInstanceState(savedInstanceState)
        }
        viewBinder.setupListeners(
            isRemote = isRemote,
            wardId = wardId,
            initialBlacklist = initialPolicy.customBlacklistPackages,
            onDirtyStateChanged = ::updateSaveButtonState,
            onSaveClicked = ::saveChanges
        )
        viewBinder.updateTimingLabels()
        viewBinder.updateAllowedAppsLabel()
        updateSaveButtonState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::viewBinder.isInitialized) {
            viewBinder.saveInstanceState(outState)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isRemote && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        immediateConfirmDialog?.dismiss()
        immediateConfirmDialog = null
        discardConfirmDialog?.dismiss()
        discardConfirmDialog = null
        super.onDestroy()
    }

    private fun applyWindowInsets() {
        findViewById<View>(R.id.layoutRootCurfewSettings)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }
    }

    private fun hasUnsavedChanges(): Boolean = viewBinder.hasUnsavedChanges(initialPolicy)

    private fun updateSaveButtonState() {
        val dirty = hasUnsavedChanges()
        viewBinder.updateSaveButton(dirty = dirty, isSaving = isSaving)
        backPressedCallback.isEnabled = dirty || isSaving
    }

    private fun saveChanges() {
        if (isSaving || !hasUnsavedChanges()) return

        if (viewBinder.isCurfewEnabled &&
            viewBinder.selectedStartHour == viewBinder.selectedEndHour &&
            viewBinder.selectedStartMinute == viewBinder.selectedEndMinute
        ) {
            Toast.makeText(applicationContext, R.string.toast_curfew_invalid_same_start_end, Toast.LENGTH_LONG).show()
            return
        }
        if (viewBinder.isCurfewEnabled &&
            viewBinder.isWeekendCurfewEnabled &&
            viewBinder.selectedWeekendStartHour == viewBinder.selectedWeekendEndHour &&
            viewBinder.selectedWeekendStartMinute == viewBinder.selectedWeekendEndMinute
        ) {
            Toast.makeText(applicationContext, R.string.toast_curfew_invalid_same_start_end, Toast.LENGTH_LONG).show()
            return
        }

        if (shouldConfirmImmediateCurfewActivation()) {
            if (isFinishing || isDestroyed || immediateConfirmDialog?.isShowing == true) return
            showImmediateCurfewConfirmationDialog()
            return
        }

        performSaveChanges()
    }

    /**
     * Returns true when saving local curfew settings will immediately engage restrictions
     * because the current time is inside the selected schedule window and curfew was not active.
     */
    private fun shouldConfirmImmediateCurfewActivation(): Boolean {
        if (isRemote || !viewBinder.isCurfewEnabled || !ProtectionEngine.isDeviceOwner(this)) return false
        val targetWindowActive = CurfewEnforcer.isScheduleWindowActive(
            calendar = CurfewConfig.getCurfewCalendar(this),
            startHour = viewBinder.selectedStartHour,
            startMinute = viewBinder.selectedStartMinute,
            endHour = viewBinder.selectedEndHour,
            endMinute = viewBinder.selectedEndMinute,
            isWeekendCurfewEnabled = viewBinder.isWeekendCurfewEnabled,
            weekendStartHour = viewBinder.selectedWeekendStartHour,
            weekendStartMinute = viewBinder.selectedWeekendStartMinute,
            weekendEndHour = viewBinder.selectedWeekendEndHour,
            weekendEndMinute = viewBinder.selectedWeekendEndMinute
        )
        return shouldPromptImmediateCurfewConfirmation(
            isRemote = isRemote,
            targetCurfewEnabled = viewBinder.isCurfewEnabled,
            initialCurfewEnabled = initialPolicy.isCurfewEnabled,
            isCurrentlyCurfewActive = CurfewEnforcer.isCurfewActive(this),
            isDeviceOwner = true,
            isTargetScheduleActiveNow = targetWindowActive
        )
    }

    private fun showImmediateCurfewConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        immediateConfirmDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_curfew_immediate_title)
            .setMessage(R.string.dialog_curfew_immediate_msg)
            .setPositiveButton(R.string.btn_curfew_enable_now) { dialog, _ ->
                dialog.dismiss()
                performSaveChanges()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .setOnDismissListener {
                immediateConfirmDialog = null
            }
            .show()
    }

    private fun performSaveChanges() {
        if (isSaving || !hasUnsavedChanges()) return
        if (isRemote && wardId.isBlank()) {
            Log.e(TAG, "Refusing remote policy push: wardId is blank")
            Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
            return
        }

        isSaving = true
        updateSaveButtonState()

        val updatedPolicy = viewBinder.buildUpdatedPolicy(initialPolicy)
        val app = applicationContext
        val policyRepo = PolicyRepository.getInstance(app)

        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO + NonCancellable) {
                    if (isRemote) {
                        policyRepo.pushPolicyToWard(wardId, updatedPolicy)
                    } else {
                        val saved = policyRepo.saveAndEnforceSelfPolicy(updatedPolicy)
                        if (saved) {
                            CurfewScheduler.scheduleNextAlarms(app)
                            CurfewEnforcer.syncBlockState(app)
                            withContext(Dispatchers.Main + NonCancellable) {
                                ProtectionEngine.applyAllAsync(app)
                            }
                        }
                        saved
                    }
                }

                if (isFinishing || isDestroyed) return@launch
                isSaving = false
                if (success) {
                    val resultPolicy = if (isRemote) updatedPolicy else policyRepo.getCurrentPolicy()
                    val successRes = if (isRemote) R.string.toast_guardian_policy_saved else R.string.toast_self_policy_saved
                    Toast.makeText(applicationContext, successRes, Toast.LENGTH_SHORT).show()
                    val resultIntent = Intent().apply {
                        putExtra(EXTRA_UPDATED_POLICY_JSON, resultPolicy.toJson())
                    }
                    setResult(Activity.RESULT_OK, resultIntent)
                    backPressedCallback.isEnabled = false
                    finish()
                } else {
                    updateSaveButtonState()
                    val failRes = if (isRemote) R.string.toast_guardian_policy_save_failed else R.string.toast_self_policy_save_failed
                    Toast.makeText(applicationContext, failRes, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error saving curfew settings", e)
                if (isFinishing || isDestroyed) return@launch
                isSaving = false
                updateSaveButtonState()
                val failRes = if (isRemote) R.string.toast_guardian_policy_save_failed else R.string.toast_self_policy_save_failed
                Toast.makeText(applicationContext, failRes, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDiscardConfirmationDialog() {
        if (isFinishing || isDestroyed || discardConfirmDialog?.isShowing == true) return
        discardConfirmDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_unsaved_changes_title)
            .setMessage(R.string.dialog_unsaved_changes_msg)
            .setNegativeButton(R.string.btn_keep_editing) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton(R.string.btn_discard_changes) { dialog, _ ->
                dialog.dismiss()
                backPressedCallback.isEnabled = false
                finish()
            }
            .setOnDismissListener {
                discardConfirmDialog = null
            }
            .show()
    }

    companion object {
        private const val TAG = "CurfewSettings"
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        const val EXTRA_UPDATED_POLICY_JSON = "extra_updated_policy_json"

        /**
         * Returns true when saving local curfew settings will immediately engage restrictions
         * inside the active window (either newly enabled or newly covering the current hour).
         */
        internal fun shouldPromptImmediateCurfewConfirmation(
            isRemote: Boolean,
            targetCurfewEnabled: Boolean,
            initialCurfewEnabled: Boolean,
            isCurrentlyCurfewActive: Boolean,
            isDeviceOwner: Boolean,
            isTargetScheduleActiveNow: Boolean
        ): Boolean =
            !isRemote &&
                targetCurfewEnabled &&
                (!initialCurfewEnabled || !isCurrentlyCurfewActive) &&
                isDeviceOwner &&
                isTargetScheduleActiveNow
    }
}
