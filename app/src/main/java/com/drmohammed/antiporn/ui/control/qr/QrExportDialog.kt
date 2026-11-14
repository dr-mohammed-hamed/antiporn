package com.drmohammed.antiporn.ui.control.qr

import android.content.ContentValues
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.print.PrintHelper
import com.drmohammed.antiporn.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Modern Material Design 3 BottomSheet dialog displaying the generated Self-Control QR lock code.
 * Provides user actions to share (via FileProvider Intent), print (via PrintHelper),
 * and save as PNG to the device photo gallery (via MediaStore).
 */
class QrExportDialog : BottomSheetDialogFragment() {

    private lateinit var ivExportQrCode: ImageView
    private lateinit var btnShareQr: MaterialButton
    private lateinit var btnPrintQr: MaterialButton
    private lateinit var btnSaveQrImage: MaterialButton
    private lateinit var cbAcknowledgePossession: MaterialCheckBox
    private lateinit var btnProceedTestScan: MaterialButton
    private lateinit var btnCloseExportDialog: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentFragmentManager.setFragmentResultListener(
            QrScannerBottomSheet.REQUEST_KEY_TEST_SCAN,
            this
        ) { _, bundle ->
            val verified = bundle.getBoolean(QrScannerBottomSheet.RESULT_KEY_VERIFIED, false)
            if (verified) {
                val ctx = context?.applicationContext ?: return@setFragmentResultListener
                val qrManager = SelfControlQrManager.getInstance(ctx)
                qrManager.setQrLockEnabled(true)
                Toast.makeText(ctx, R.string.qr_wizard_test_success_toast, Toast.LENGTH_LONG).show()
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY_SETUP_COMPLETED,
                    Bundle().apply { putBoolean(RESULT_KEY_ENABLED, true) }
                )
                dismissAllowingStateLoss()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_qr_export, container, false)
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ivExportQrCode = view.findViewById(R.id.ivExportQrCode)
        btnShareQr = view.findViewById(R.id.btnShareQr)
        btnPrintQr = view.findViewById(R.id.btnPrintQr)
        btnSaveQrImage = view.findViewById(R.id.btnSaveQrImage)
        cbAcknowledgePossession = view.findViewById(R.id.cbAcknowledgePossession)
        btnProceedTestScan = view.findViewById(R.id.btnProceedTestScan)
        btnCloseExportDialog = view.findViewById(R.id.btnCloseExportDialog)

        // Resolve or generate QR bitmap
        val qrManager = SelfControlQrManager.getInstance(requireContext())
        val token = qrManager.getSavedToken() ?: qrManager.generateAndSaveNewToken()
        val resolvedBitmap = qrManager.generateQrBitmap(token, 512)
        ivExportQrCode.setImageBitmap(resolvedBitmap)

        btnShareQr.setOnClickListener {
            shareQrCode(resolvedBitmap)
        }

        btnPrintQr.setOnClickListener {
            printQrCode(resolvedBitmap)
        }

        btnSaveQrImage.setOnClickListener {
            saveBitmapToGallery(resolvedBitmap)
        }

        cbAcknowledgePossession.setOnCheckedChangeListener { _, isChecked ->
            btnProceedTestScan.isEnabled = isChecked
        }

        btnProceedTestScan.setOnClickListener {
            launchTestScan()
        }

        btnCloseExportDialog.setOnClickListener {
            dismiss()
        }
    }

    private fun launchTestScan() {
        val parentFm = parentFragmentManager
        if (parentFm.isStateSaved || parentFm.findFragmentByTag(QrScannerBottomSheet.TAG) != null) return

        QrScannerBottomSheet.newInstance(QrScannerBottomSheet.REQUEST_KEY_TEST_SCAN)
            .show(parentFm, QrScannerBottomSheet.TAG)
    }

    private fun shareQrCode(bitmap: Bitmap) {
        val context = context ?: return
        btnShareQr.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cachePath = File(context.cacheDir, "qr_exports").apply { mkdirs() }
                val imageFile = File(cachePath, "AntiPorn_SelfControl_QR.png")
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                val authority = "${context.packageName}.fileprovider"
                val contentUri: Uri = FileProvider.getUriForFile(context, authority, imageFile)

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_TEXT, getString(R.string.qr_export_share_text))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, getString(R.string.qr_export_share_chooser))
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        startActivity(chooser)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to share QR image", e)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Toast.makeText(context, getString(R.string.qr_export_share_error, e.localizedMessage ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        btnShareQr.isEnabled = true
                    }
                }
            }
        }
    }

    private fun printQrCode(bitmap: Bitmap) {
        val context = context ?: return
        try {
            if (PrintHelper.systemSupportsPrint()) {
                PrintHelper(context).apply {
                    scaleMode = PrintHelper.SCALE_MODE_FIT
                }.printBitmap("AntiPorn_SelfControl_QR", bitmap)
            } else {
                Toast.makeText(context, R.string.qr_export_print_not_supported, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Print helper failed", e)
            Toast.makeText(context, R.string.qr_export_print_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val context = context ?: return
        btnSaveQrImage.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val filename = "AntiPorn_SelfControl_QR_${System.currentTimeMillis()}.png"
            var outputStream: OutputStream? = null

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AntiPorn")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }

                    val uri = context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    )

                    if (uri != null) {
                        outputStream = context.contentResolver.openOutputStream(uri)
                        if (outputStream != null) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                            outputStream.flush()
                        }

                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        context.contentResolver.update(uri, contentValues, null, null)

                        withContext(Dispatchers.Main) {
                            val act = activity
                            if (isAdded && act != null && !act.isFinishing && !act.isDestroyed) {
                                MaterialAlertDialogBuilder(act)
                                    .setTitle(R.string.qr_export_gallery_warning_title)
                                    .setMessage(R.string.qr_export_gallery_warning_message)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                Toast.makeText(context, R.string.qr_export_save_error_gallery, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    // Pre-Android 10: avoid SecurityException due to missing WRITE_EXTERNAL_STORAGE
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            Toast.makeText(context, R.string.qr_export_save_error_gallery, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save QR to gallery", e)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Toast.makeText(context, getString(R.string.qr_export_save_error, e.localizedMessage ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                try {
                    outputStream?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing stream", e)
                }
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        btnSaveQrImage.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        val ctx = context?.applicationContext
        if (ctx != null) {
            val qrManager = SelfControlQrManager.getInstance(ctx)
            if (!qrManager.isQrLockEnabled()) {
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY_SETUP_COMPLETED,
                    Bundle().apply { putBoolean(RESULT_KEY_ENABLED, false) }
                )
            }
        }
    }

    companion object {
        const val TAG = "QrExportDialog"
        const val REQUEST_KEY_SETUP_COMPLETED = "qr_setup_completed"
        const val RESULT_KEY_ENABLED = "result_key_enabled"

        fun newInstance(): QrExportDialog {
            return QrExportDialog()
        }
    }
}
