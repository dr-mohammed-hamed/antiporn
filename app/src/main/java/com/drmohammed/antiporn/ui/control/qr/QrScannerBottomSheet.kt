package com.drmohammed.antiporn.ui.control.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Modern Material 3 BottomSheet dialog providing real-time camera scanning of
 * Self-Control barrier QR codes using CameraX and ML Kit Barcode Scanning.
 */
class QrScannerBottomSheet : BottomSheetDialogFragment() {

    var onVerified: (() -> Unit)? = null
    var onEmergencyBypassTriggered: (() -> Unit)? = null

    private var _previewViewQrScanner: PreviewView? = null
    private val previewViewQrScanner get() = _previewViewQrScanner!!

    private var _btnToggleTorch: MaterialButton? = null
    private val btnToggleTorch get() = _btnToggleTorch!!

    private var _tvScanStatus: TextView? = null
    private val tvScanStatus get() = _tvScanStatus!!

    private var _btnCloseScanner: MaterialButton? = null
    private val btnCloseScanner get() = _btnCloseScanner!!

    private var _layoutEmergencyBypass: MaterialCardView? = null
    private val layoutEmergencyBypass get() = _layoutEmergencyBypass!!

    private var _tvEmergencyBypassInfo: TextView? = null
    private val tvEmergencyBypassInfo get() = _tvEmergencyBypassInfo!!

    private var _btnEmergencyBypass: MaterialButton? = null
    private val btnEmergencyBypass get() = _btnEmergencyBypass!!

    private var _layoutPermissionFallback: View? = null
    private val layoutPermissionFallback get() = _layoutPermissionFallback!!

    private var _btnGrantPermission: Button? = null
    private val btnGrantPermission get() = _btnGrantPermission!!

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraExecutor: java.util.concurrent.Executor? = null
    private var barcodeScanner: BarcodeScanner? = null

    private val isCodeProcessing = AtomicBoolean(false)
    private var isTorchOn = false
    private var countdownJob: Job? = null
    private var defaultStatusColor: Int = Color.GRAY

    private val qrManager by lazy {
        SelfControlQrManager.getInstance(requireContext())
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            layoutPermissionFallback.isVisible = false
            startCamera()
        } else {
            layoutPermissionFallback.isVisible = true
            tvScanStatus.text = getString(R.string.qr_scan_camera_permission_required)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_qr_scanner, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _previewViewQrScanner = view.findViewById(R.id.previewViewQrScanner)
        _btnToggleTorch = view.findViewById(R.id.btnToggleTorch)
        _tvScanStatus = view.findViewById(R.id.tvScanStatus)
        _btnCloseScanner = view.findViewById(R.id.btnCloseScanner)
        _layoutEmergencyBypass = view.findViewById(R.id.layoutEmergencyBypass)
        _tvEmergencyBypassInfo = view.findViewById(R.id.tvEmergencyBypassInfo)
        _btnEmergencyBypass = view.findViewById(R.id.btnEmergencyBypass)
        _layoutPermissionFallback = view.findViewById(R.id.layoutPermissionFallback)
        _btnGrantPermission = view.findViewById(R.id.btnGrantPermission)

        defaultStatusColor = tvScanStatus.currentTextColor

        cameraExecutor = Dispatchers.Default.asExecutor()

        try {
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            barcodeScanner = BarcodeScanning.getClient(options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BarcodeScanner client", e)
            barcodeScanner = null
            tvScanStatus.text = getString(R.string.qr_scan_error_init)
            tvScanStatus.setTextColor(Color.parseColor("#EF4444"))
        }

        btnCloseScanner.setOnClickListener {
            dismiss()
        }

        btnToggleTorch.setOnClickListener {
            toggleTorch()
        }

        btnGrantPermission.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setupEmergencyBypassUi()

        if (barcodeScanner == null) {
            layoutPermissionFallback.isVisible = false
        } else if (hasCameraPermission()) {
            layoutPermissionFallback.isVisible = false
            startCamera()
        } else {
            layoutPermissionFallback.isVisible = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val context = context ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize CameraX provider", e)
                tvScanStatus.text = getString(R.string.qr_scan_camera_permission_required)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        if (!isAdded) {
            try {
                provider.unbindAll()
            } catch (e: Exception) {
                Log.w(TAG, "Unbind failed during detached bindCameraUseCases", e)
            }
            return
        }

        val preview = Preview.Builder()
            .build()
            .also {
                it.surfaceProvider = previewViewQrScanner.surfaceProvider
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val executor = cameraExecutor ?: return
        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            processImageProxy(imageProxy)
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            updateTorchAvailability()
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || isCodeProcessing.get()) {
            imageProxy.close()
            return
        }

        try {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val scanner = barcodeScanner ?: run {
                imageProxy.close()
                return
            }

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrBlank()) {
                            handleScannedToken(rawValue)
                            break
                        }
                    }
                }
                .addOnFailureListener { error ->
                    Log.d(TAG, "Barcode analysis failed", error)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during barcode image processing", e)
            imageProxy.close()
        }
    }

    private fun handleScannedToken(token: String) {
        if (!isCodeProcessing.compareAndSet(false, true)) {
            return
        }

        val currentActivity = activity
        if (currentActivity == null || !isAdded) {
            isCodeProcessing.set(false)
            return
        }

        val isValid = qrManager.verifyToken(token)

        currentActivity.runOnUiThread {
            if (!isAdded) {
                isCodeProcessing.set(false)
                return@runOnUiThread
            }

            if (isValid) {
                triggerHapticFeedback()
                tvScanStatus.text = getString(R.string.qr_scan_success)
                tvScanStatus.setTextColor(Color.parseColor("#1CD076")) // Emerald Green

                viewLifecycleOwner.lifecycleScope.launch {
                    delay(400)
                    cameraProvider?.unbindAll()
                    val reqKey = arguments?.getString(EXTRA_REQUEST_KEY)
                    if (reqKey != null) {
                        parentFragmentManager.setFragmentResult(reqKey, Bundle().apply { putBoolean(RESULT_KEY_VERIFIED, true) })
                    } else {
                        onVerified?.invoke()
                    }
                    dismissAllowingStateLoss()
                }
            } else {
                tvScanStatus.text = getString(R.string.qr_scan_invalid)
                tvScanStatus.setTextColor(Color.parseColor("#EF4444")) // Crimson Red

                viewLifecycleOwner.lifecycleScope.launch {
                    delay(1500)
                    if (isAdded) {
                        tvScanStatus.text = getString(R.string.qr_scan_aim_camera)
                        tvScanStatus.setTextColor(defaultStatusColor)
                        isCodeProcessing.set(false)
                    }
                }
            }
        }
    }

    private fun updateTorchAvailability() {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        btnToggleTorch.isVisible = hasFlash
        btnToggleTorch.setIconResource(if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            btnToggleTorch.setIconResource(if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
            btnToggleTorch.contentDescription = getString(if (isTorchOn) R.string.qr_torch_off else R.string.qr_torch_on)
        }
    }

    @Suppress("DEPRECATION")
    private fun triggerHapticFeedback() {
        try {
            val context = context ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator?.vibrate(80)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic vibration failed", e)
        }
    }

    // --- Emergency Delay Handling ---

    private fun setupEmergencyBypassUi() {
        if (!qrManager.isEmergencyDelayEnabled()) {
            layoutEmergencyBypass.isVisible = false
            return
        }

        layoutEmergencyBypass.isVisible = true
        refreshEmergencyDelayState()

        countdownJob?.cancel()
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(30_000L)
                if (isAdded) {
                    refreshEmergencyDelayState()
                }
            }
        }
    }

    private fun refreshEmergencyDelayState() {
        val isRequested = qrManager.isEmergencyDelayRequested()
        val delayHours = qrManager.getEmergencyDelayHours()

        if (!isRequested) {
            tvEmergencyBypassInfo.text = getString(R.string.qr_emergency_confirm_message, delayHours)
            btnEmergencyBypass.text = getString(R.string.qr_emergency_bypass_action, delayHours)
            btnEmergencyBypass.setOnClickListener {
                showEmergencyConfirmationDialog(delayHours)
            }
        } else {
            val isElapsed = qrManager.isEmergencyDelayActiveAndElapsed()
            if (isElapsed) {
                tvEmergencyBypassInfo.text = getString(R.string.qr_emergency_bypass_ready)
                btnEmergencyBypass.text = getString(R.string.qr_emergency_bypass_ready)
                btnEmergencyBypass.setOnClickListener {
                    val reqKey = arguments?.getString(EXTRA_REQUEST_KEY)
                    if (reqKey != null) {
                        parentFragmentManager.setFragmentResult(reqKey, Bundle().apply { putBoolean(RESULT_KEY_EMERGENCY_BYPASS, true) })
                    } else {
                        onEmergencyBypassTriggered?.invoke()
                    }
                    dismissAllowingStateLoss()
                }
            } else {
                val remainingMs = qrManager.getEmergencyDelayRemainingMs()
                val totalMinutes = (remainingMs / 1000L / 60L).coerceAtLeast(1)
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                val formattedTime = if (hours > 0) {
                    getString(R.string.qr_emergency_time_hours_minutes, hours.toInt(), minutes.toInt())
                } else {
                    getString(R.string.qr_emergency_time_minutes, minutes.toInt())
                }

                tvEmergencyBypassInfo.text = getString(R.string.qr_emergency_bypass_active_format, formattedTime)
                btnEmergencyBypass.text = getString(R.string.qr_emergency_cancel)
                btnEmergencyBypass.setOnClickListener {
                    qrManager.cancelEmergencyDelay()
                    refreshEmergencyDelayState()
                }
            }
        }
    }

    private fun showEmergencyConfirmationDialog(hours: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.qr_emergency_confirm_title)
            .setMessage(getString(R.string.qr_emergency_confirm_message, hours))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                qrManager.requestEmergencyDelay()
                refreshEmergencyDelayState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countdownJob?.cancel()
        countdownJob = null
        isCodeProcessing.set(false)

        try {
            cameraProvider?.unbindAll()
            camera = null
        } catch (e: Exception) {
            Log.w(TAG, "Camera unbind failed", e)
        }

        try {
            barcodeScanner?.close()
            barcodeScanner = null
        } catch (e: Exception) {
            Log.w(TAG, "Barcode scanner close failed", e)
        }

        cameraExecutor = null

        _previewViewQrScanner = null
        _btnToggleTorch = null
        _tvScanStatus = null
        _btnCloseScanner = null
        _layoutEmergencyBypass = null
        _tvEmergencyBypassInfo = null
        _btnEmergencyBypass = null
        _layoutPermissionFallback = null
        _btnGrantPermission = null
    }

    companion object {
        const val TAG = "QrScannerBottomSheet"
        const val EXTRA_REQUEST_KEY = "extra_request_key"
        const val RESULT_KEY_VERIFIED = "result_key_verified"
        const val RESULT_KEY_EMERGENCY_BYPASS = "result_key_emergency_bypass"

        fun newInstance(requestKey: String? = null): QrScannerBottomSheet {
            return QrScannerBottomSheet().apply {
                if (requestKey != null) {
                    arguments = Bundle().apply {
                        putString(EXTRA_REQUEST_KEY, requestKey)
                    }
                }
            }
        }
    }
}
