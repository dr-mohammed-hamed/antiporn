package com.drmohammed.antiporn.ui.control.qr

import android.content.ContentValues
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
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.print.PrintHelper
import com.drmohammed.antiporn.R
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Modern Material Design 3 Dialog displaying the generated Self-Control QR lock code.
 * Provides user actions to share (via FileProvider Intent), print (via PrintHelper),
 * and save as PNG to the device photo gallery (via MediaStore).
 */
class QrExportDialog : DialogFragment() {

    private lateinit var ivExportQrCode: ImageView
    private lateinit var btnShareQr: MaterialButton
    private lateinit var btnPrintQr: MaterialButton
    private lateinit var btnSaveQrImage: MaterialButton
    private lateinit var btnCloseExportDialog: MaterialButton

    private var currentBitmap: Bitmap? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_qr_export, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ivExportQrCode = view.findViewById(R.id.ivExportQrCode)
        btnShareQr = view.findViewById(R.id.btnShareQr)
        btnPrintQr = view.findViewById(R.id.btnPrintQr)
        btnSaveQrImage = view.findViewById(R.id.btnSaveQrImage)
        btnCloseExportDialog = view.findViewById(R.id.btnCloseExportDialog)

        // Resolve or generate QR bitmap
        val qrManager = SelfControlQrManager.getInstance(requireContext())
        val token = qrManager.getSavedToken() ?: qrManager.generateAndSaveNewToken()
        val resolvedBitmap = qrManager.generateQrBitmap(token, 512)
        currentBitmap = resolvedBitmap
        ivExportQrCode.setImageBitmap(resolvedBitmap)

        btnShareQr.setOnClickListener {
            resolvedBitmap.let { bmp -> shareQrCode(bmp) }
        }

        btnPrintQr.setOnClickListener {
            resolvedBitmap.let { bmp -> printQrCode(bmp) }
        }

        btnSaveQrImage.setOnClickListener {
            resolvedBitmap.let { bmp -> saveBitmapToGallery(bmp) }
        }

        btnCloseExportDialog.setOnClickListener {
            dismiss()
        }
    }

    private fun shareQrCode(bitmap: Bitmap) {
        val context = context ?: return
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
            startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share QR image", e)
            Toast.makeText(context, getString(R.string.qr_export_share_error, e.localizedMessage ?: ""), Toast.LENGTH_SHORT).show()
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
        val filename = "AntiPorn_SelfControl_QR_${System.currentTimeMillis()}.png"
        var outputStream: OutputStream? = null

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AntiPorn")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }

                Toast.makeText(context, R.string.qr_export_save_success, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, R.string.qr_export_save_error_gallery, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save QR to gallery", e)
            Toast.makeText(context, getString(R.string.qr_export_save_error, e.localizedMessage ?: ""), Toast.LENGTH_SHORT).show()
        } finally {
            try {
                outputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing stream", e)
            }
            if (isAdded) {
                btnSaveQrImage.isEnabled = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        currentBitmap = null
    }

    companion object {
        const val TAG = "QrExportDialog"

        fun newInstance(): QrExportDialog {
            return QrExportDialog()
        }
    }
}
