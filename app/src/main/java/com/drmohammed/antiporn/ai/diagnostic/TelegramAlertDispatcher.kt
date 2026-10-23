package com.drmohammed.antiporn.ai.diagnostic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import com.drmohammed.antiporn.ai.core.model.ModestyClass
import com.drmohammed.antiporn.ai.core.model.ModestyDetection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * محرك الإرسال التشخيصي التلقائي عبر Telegram Bot API
 * يقوم بإرسال تقارير الرصد الميداني اللحظي مع الصور المشروحة والأصلية
 */
object TelegramAlertDispatcher {

    private const val TAG = "TelegramAlertDispatcher"

    @Volatile
    var botToken: String = ""

    @Volatile
    var chatId: String = ""

    @Volatile
    var isEnabled: Boolean = false

    private fun getBaseUrl(): String = "https://api.telegram.org/bot$botToken"
    private const val DEBOUNCE_INTERVAL_MS = 4000L

    private val lastDispatchTime = AtomicLong(0L)

    // نطاق كوروتين مخصص لشبكة التليجرام وضغط الصور لمنع تجميد معالجة الإطارات إطلاقاً
    private val dispatcherScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * إرسال تنبيه فوري مصحوباً بالصورة الأصلية والمشروحة إلى Telegram
     *
     * @param rawBitmap الإطار الأصلي الملتقط من الشاشة
     * @param detections قائمة كافة العناصر المرصودة في الإطار
     * @param topViolation المخالفة الأشد رصداً
     * @param latencyMs زمن استدلال النموذج بالمللي ثانية
     */
    fun dispatchAlert(
        rawBitmap: Bitmap,
        detections: List<ModestyDetection>,
        topViolation: ModestyDetection,
        latencyMs: Long
    ) {
        if (!isEnabled || botToken.isBlank() || chatId.isBlank()) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val last = lastDispatchTime.get()

        // 1. صمام الأمان والتحكم بالتكرار (Debounce minimum 4000ms)
        if (now - last < DEBOUNCE_INTERVAL_MS) {
            Log.d(TAG, "Telegram alert throttled (${now - last}ms < ${DEBOUNCE_INTERVAL_MS}ms)")
            return
        }

        if (rawBitmap.isRecycled || rawBitmap.width <= 0 || rawBitmap.height <= 0) {
            Log.w(TAG, "Cannot dispatch alert: invalid or recycled bitmap")
            return
        }

        if (!lastDispatchTime.compareAndSet(last, now)) {
            return
        }

        // 2. نسخ الصورة بأمان قبل تمريرها للخيط الخلفي لتفادي recycle متزامن من الخدمة الأصلية
        val bitmapCopy = try {
            rawBitmap.copy(rawBitmap.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to copy rawBitmap for Telegram dispatch", e)
            return
        } ?: run {
            Log.e(TAG, "rawBitmap.copy returned null")
            return
        }

        // 3. الإطلاق غير المتزامن على خيوط الخلفية المخصصة للشبكة
        dispatcherScope.launch {
            var annotatedBitmap: Bitmap? = null
            try {
                // رسم التحديدات على نسخة مطابقة
                annotatedBitmap = createAnnotatedBitmap(bitmapCopy, detections)

                // ضغط الصورتين إلى صيغة JPEG بجودة 90
                val rawJpegBytes = compressToJpeg(bitmapCopy, quality = 90)
                val annotatedJpegBytes = compressToJpeg(annotatedBitmap, quality = 90)

                // تحرير كائنات الـ Bitmap فور اكتمال الضغط لتحرير الذاكرة مبكراً
                try { bitmapCopy.recycle() } catch (e: Throwable) { Log.w(TAG, "Failed recycling bitmap: ${e.message}") }
                try { annotatedBitmap.recycle() } catch (e: Throwable) { Log.w(TAG, "Failed recycling bitmap: ${e.message}") }
                annotatedBitmap = null

                // بناء نص التقرير
                val caption = buildAlertCaption(topViolation, latencyMs)

                // إرسال الصورتين عبر Telegram Bot API
                dispatchNetworkRequest(annotatedJpegBytes, rawJpegBytes, caption)
            } catch (e: Throwable) {
                Log.e(TAG, "Error during Telegram alert dispatch", e)
            } finally {
                try { if (!bitmapCopy.isRecycled) bitmapCopy.recycle() } catch (e: Throwable) { Log.w(TAG, "Failed recycling bitmap: ${e.message}") }
                try { if (annotatedBitmap != null && !annotatedBitmap.isRecycled) annotatedBitmap.recycle() } catch (e: Throwable) { Log.w(TAG, "Failed recycling bitmap: ${e.message}") }
            }
        }
    }

    /**
     * رسم صناديق الإحاطة والتسميات التوضيحية على نسخة من الصورة
     */
    private fun createAnnotatedBitmap(source: Bitmap, detections: List<ModestyDetection>): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            textSize = (source.width / 24f).coerceIn(12f, 26f)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }

        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(190, 20, 20, 20)
            isAntiAlias = true
        }

        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val padding = 4f

        for (detection in detections) {
            val rect = mapBoundingBox(detection.boundingBox, source.width, source.height)

            val (rectColor, labelText) = when (detection.classType) {
                ModestyClass.PERSON -> {
                    val conf = (detection.confidence * 100).toInt()
                    Color.GREEN to "Person $conf%"
                }
                ModestyClass.EXPOSED_SKIN, ModestyClass.TIGHT_AND_CLEAVAGE -> {
                    val conf = (detection.confidence * 100).toInt()
                    Color.RED to "${detection.classType.labelAr} $conf%"
                }
                ModestyClass.SAFE_MODEST_ANCHOR -> {
                    Color.BLUE to null
                }
            }

            // رسم المستطيل باللون المحدد وبسماكة 3f
            strokePaint.color = rectColor
            canvas.drawRect(rect, strokePaint)

            // رسم النص بخلفية مقروءة وظل
            if (labelText != null) {
                val textWidth = textPaint.measureText(labelText)
                val labelLeft = rect.left.coerceIn(0f, (source.width - textWidth - padding * 2).coerceAtLeast(0f))
                val labelTop = if (rect.top - textHeight - padding * 2 >= 0) {
                    rect.top - textHeight - padding * 2
                } else {
                    rect.top
                }

                val labelRect = RectF(
                    labelLeft,
                    labelTop,
                    labelLeft + textWidth + padding * 2,
                    labelTop + textHeight + padding * 2
                )

                canvas.drawRect(labelRect, bgPaint)
                canvas.drawText(
                    labelText,
                    labelLeft + padding,
                    labelTop + padding - fontMetrics.ascent,
                    textPaint
                )
            }
        }

        return result
    }

    /**
     * تحويل إحداثيات الصندوق إلى أبعاد الصورة الحالية بدقة سواء كانت قياسية أو مصفوفية
     */
    internal fun mapBoundingBox(box: RectF, bitmapWidth: Int, bitmapHeight: Int): RectF {
        val isNormalized = box.left in 0f..1.05f && box.right in 0f..1.05f &&
                box.top in 0f..1.05f && box.bottom in 0f..1.05f

        val result = RectF()
        when {
            isNormalized -> {
                result.left = box.left * bitmapWidth
                result.top = box.top * bitmapHeight
                result.right = box.right * bitmapWidth
                result.bottom = box.bottom * bitmapHeight
            }
            bitmapWidth != 320 && box.right <= 325f && box.bottom <= 325f -> {
                val scaleX = bitmapWidth / 320f
                val scaleY = bitmapHeight / 320f
                result.left = box.left * scaleX
                result.top = box.top * scaleY
                result.right = box.right * scaleX
                result.bottom = box.bottom * scaleY
            }
            else -> {
                result.left = box.left
                result.top = box.top
                result.right = box.right
                result.bottom = box.bottom
            }
        }
        return result
    }

    /**
     * ضغط الصورة إلى JPEG بجودة محددة
     */
    private fun compressToJpeg(bitmap: Bitmap, quality: Int = 90): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return outputStream.toByteArray()
    }

    /**
     * بناء نص الرسالة الرسمي للتقرير الميداني
     */
    internal fun buildAlertCaption(topViolation: ModestyDetection, latencyMs: Long): String {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val confPercent = (topViolation.confidence * 100).toInt()
        return "🛡️ تقرير رصد ميداني - AntiPorn AI Sentinel\n" +
                "━━━━━━━━━━━━━━━━━━\n" +
                "🚨 نوع المخالفة: ${topViolation.classType.labelAr} (${topViolation.classType.labelEn})\n" +
                "📊 نسبة الثقة: $confPercent%\n" +
                "⚡ زمن الفحص: ${latencyMs} ms\n" +
                "🕒 التوقيت: $timeStr\n" +
                "━━━━━━━━━━━━━━━━━━\n" +
                "🟩 أخضر = جسد بشري | 🟥 أحمر = العورة المشتبه بها"
    }

    /**
     * إرسال الوسائط عبر Telegram Bot API مع دعم إعادة المحاولة التلقائية عبر sendPhoto
     */
    private fun dispatchNetworkRequest(
        annotatedBytes: ByteArray,
        rawBytes: ByteArray,
        caption: String
    ) {
        try {
            val success = sendMediaGroup(annotatedBytes, rawBytes, caption)
            if (!success) {
                Log.w(TAG, "sendMediaGroup returned non-OK; attempting fallback sequential sendPhoto...")
                sendPhotoFallback(annotatedBytes, rawBytes, caption)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network exception in sendMediaGroup: ${e.message}", e)
            try {
                sendPhotoFallback(annotatedBytes, rawBytes, caption)
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Fallback sendPhoto failed: ${fallbackEx.message}", fallbackEx)
            }
        }
    }

    /**
     * إرسال الصورتين معاً كألبوم موحد عبر sendMediaGroup
     */
    private fun sendMediaGroup(
        annotatedBytes: ByteArray,
        rawBytes: ByteArray,
        caption: String
    ): Boolean {
        val boundary = "===AntiPornAlertBoundary${System.currentTimeMillis()}==="
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val mediaArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "photo")
                put("media", "attach://annotated_photo")
                put("caption", caption)
            })
            put(JSONObject().apply {
                put("type", "photo")
                put("media", "attach://raw_photo")
            })
        }

        val url = URL("${getBaseUrl()}/sendMediaGroup")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            useCaches = false
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Connection", "Keep-Alive")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        return try {
            connection.outputStream.use { output ->
                writeFormField(output, boundary, "chat_id", chatId)
                writeFormField(output, boundary, "media", mediaArray.toString())
                writeFileField(output, boundary, "annotated_photo", "annotated.jpg", annotatedBytes)
                writeFileField(output, boundary, "raw_photo", "raw.jpg", rawBytes)
                output.write("$twoHyphens$boundary$twoHyphens$lineEnd".toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "Telegram sendMediaGroup dispatched successfully.")
                true
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.w(TAG, "Telegram sendMediaGroup HTTP $responseCode: $errorStream")
                false
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * خطة بديلة: إرسال الصورتين تباعاً عبر sendPhoto في حال تعذر إرسال الألبوم
     */
    private fun sendPhotoFallback(
        annotatedBytes: ByteArray,
        rawBytes: ByteArray,
        caption: String
    ) {
        sendSinglePhoto(annotatedBytes, "annotated.jpg", caption)
        sendSinglePhoto(rawBytes, "raw.jpg", null)
    }

    private fun sendSinglePhoto(photoBytes: ByteArray, fileName: String, caption: String?) {
        val boundary = "===AntiPornPhotoBoundary${System.currentTimeMillis()}==="
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val url = URL("${getBaseUrl()}/sendPhoto")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            useCaches = false
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Connection", "Keep-Alive")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            connection.outputStream.use { output ->
                writeFormField(output, boundary, "chat_id", chatId)
                if (!caption.isNullOrBlank()) {
                    writeFormField(output, boundary, "caption", caption)
                }
                writeFileField(output, boundary, "photo", fileName, photoBytes)
                output.write("$twoHyphens$boundary$twoHyphens$lineEnd".toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "Telegram fallback sendPhoto ($fileName) succeeded.")
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.w(TAG, "Telegram fallback sendPhoto ($fileName) HTTP $responseCode: $errorStream")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeFormField(output: OutputStream, boundary: String, name: String, value: String) {
        val lineEnd = "\r\n"
        val twoHyphens = "--"
        output.write("$twoHyphens$boundary$lineEnd".toByteArray(Charsets.UTF_8))
        output.write("Content-Disposition: form-data; name=\"$name\"$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
        output.write(value.toByteArray(Charsets.UTF_8))
        output.write(lineEnd.toByteArray(Charsets.UTF_8))
    }

    private fun writeFileField(
        output: OutputStream,
        boundary: String,
        fieldName: String,
        fileName: String,
        bytes: ByteArray
    ) {
        val lineEnd = "\r\n"
        val twoHyphens = "--"
        output.write("$twoHyphens$boundary$lineEnd".toByteArray(Charsets.UTF_8))
        output.write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"$lineEnd".toByteArray(Charsets.UTF_8))
        output.write("Content-Type: image/jpeg$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.write(lineEnd.toByteArray(Charsets.UTF_8))
    }
}
