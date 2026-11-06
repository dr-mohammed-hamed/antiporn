package com.drmohammed.antiporn.ui.accountability

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.WeakHashMap
import java.util.regex.Pattern
import timber.log.Timber

/**
 * High-performance, memory-safe Smart Hybrid App Icon Resolver.
 *
 * Implements a 4-tier resolution pipeline:
 * 1. Memory LRU Cache (0ms, 60/120fps smooth scrolling)
 * 2. Disk Cache (persistent across app restarts)
 * 3. Local PackageManager (if app exists on current device)
 * 4. Google Play CDN Resolver (official high-res web scraping via og:image)
 * 5. Elegant Material Avatar fallback (deterministic color + initial letter)
 */
object AppIconResolver {

    private const val TAG = "AntiPorn_AppIconResolver"
    private const val TARGET_ICON_SIZE_PX = 128
    private const val CONNECT_TIMEOUT_MS = 3500
    private const val READ_TIMEOUT_MS = 4000

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeJobs = Collections.synchronizedMap(WeakHashMap<ImageView, Job>())
    private val playStoreSemaphore = Semaphore(3)
    private val avatarCache = LruCache<String, Bitmap>(64)

    // Allocate 1/8th of available runtime memory for icon caching (max 8MB)
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceIn(2048, 8192)

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val ogImagePattern = Pattern.compile("property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']")

    // Vibrant Material 3 harmonious palette for deterministic avatar backgrounds
    private val avatarColors = intArrayOf(
        0xFF1E88E5.toInt(), // Blue
        0xFF43A047.toInt(), // Green
        0xFFE53935.toInt(), // Red
        0xFF8E24AA.toInt(), // Purple
        0xFFFB8C00.toInt(), // Orange
        0xFF00ACC1.toInt(), // Cyan
        0xFF3949AB.toInt(), // Indigo
        0xFF00897B.toInt(), // Teal
        0xFFD81B60.toInt(), // Pink
        0xFF5E35B1.toInt(), // Deep Purple
        0xFFF4511E.toInt(), // Deep Orange
        0xFF546E7A.toInt()  // Blue Grey
    )

    /**
     * Asynchronously loads the icon for [packageName] into [imageView].
     * Synchronously sets an instant placeholder/avatar or cached bitmap to prevent RecyclerView flickering.
     */
    fun loadIcon(
        imageView: ImageView,
        packageName: String,
        appName: String,
        isLocalMode: Boolean
    ) {
        if (packageName.isBlank()) {
            cancelLoad(imageView)
            val fallback = generateAvatarBitmap(imageView.context, appName, packageName)
            imageView.setImageBitmap(fallback)
            return
        }

        imageView.tag = packageName

        // Tier 1: Instant Memory Cache hit (0ms)
        val cached = memoryCache.get(packageName)
        if (cached != null && !cached.isRecycled) {
            cancelLoad(imageView)
            imageView.setImageBitmap(cached)
            return
        }

        // Show instant deterministic avatar from avatarCache (do NOT poison memoryCache)
        val fallbackAvatar = generateAvatarBitmap(imageView.context, appName, packageName)
        imageView.setImageBitmap(fallbackAvatar)

        // Cancel previous ongoing job for this reused ImageView
        cancelLoad(imageView)

        val context = imageView.context.applicationContext
        val viewRef = WeakReference(imageView)
        val job = scope.launch {
            try {
                val resolvedBitmap = withContext(Dispatchers.IO) {
                    resolveIconBitmap(context, packageName, isLocalMode)
                }

                val targetView = viewRef.get()
                if (isActive && targetView != null && targetView.tag == packageName) {
                    if (resolvedBitmap != null && !resolvedBitmap.isRecycled) {
                        memoryCache.put(packageName, resolvedBitmap)
                        targetView.setImageBitmap(resolvedBitmap)
                    }
                }
            } finally {
                val currentJob = coroutineContext[Job]
                val targetView = viewRef.get()
                if (targetView != null) {
                    synchronized(activeJobs) {
                        if (activeJobs[targetView] === currentJob) {
                            activeJobs.remove(targetView)
                        }
                    }
                }
            }
        }

        synchronized(activeJobs) {
            activeJobs[imageView] = job
        }
    }

    /**
     * Cancels any pending background load job for the specified [ImageView].
     * Should be called in RecyclerView adapter's onViewRecycled.
     */
    fun cancelLoad(imageView: ImageView) {
        synchronized(activeJobs) {
            activeJobs.remove(imageView)?.cancel()
        }
    }

    /**
     * Cancels all pending background loads and cleans up job tracking.
     * Should be called when hosting Activity is destroyed.
     */
    fun clear() {
        synchronized(activeJobs) {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
        }
    }

    /**
     * Resolves the icon bitmap through Disk Cache -> Local PackageManager -> Google Play CDN.
     */
    private suspend fun resolveIconBitmap(
        context: Context,
        packageName: String,
        isLocalMode: Boolean
    ): Bitmap? {
        val diskFile = getDiskCacheFile(context, packageName)

        // Tier 2: Disk Cache
        if (diskFile.exists()) {
            if (diskFile.length() > 0) {
                try {
                    val bitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                    if (bitmap != null) {
                        return bitmap
                    } else {
                        diskFile.delete()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Corrupted disk icon cache for $packageName")
                    diskFile.delete()
                }
            } else {
                diskFile.delete()
            }
        }

        // Tier 3: Local PackageManager (if in local mode or app installed on supervisor's device)
        val localBitmap = tryExtractFromPackageManager(context, packageName)
        if (localBitmap != null) {
            saveBitmapToDisk(localBitmap, diskFile)
            return localBitmap
        }

        // Tier 4: Fetch from Google Play Store CDN (only in remote ward mode)
        if (!isLocalMode) {
            val playStoreBitmap = fetchGooglePlayIcon(packageName)
            if (playStoreBitmap != null) {
                saveBitmapToDisk(playStoreBitmap, diskFile)
                return playStoreBitmap
            }
        }

        return null
    }

    private fun tryExtractFromPackageManager(context: Context, packageName: String): Bitmap? {
        return try {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon(packageName)
            drawableToBitmap(drawable)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed extracting local icon for $packageName", e)
            null
        }
    }

    private suspend fun fetchGooglePlayIcon(packageName: String): Bitmap? = playStoreSemaphore.withPermit {
        var connection: HttpURLConnection? = null
        try {
            val currentJob = coroutineContext[Job]
            val storeUrl = "https://play.google.com/store/apps/details?id=$packageName&hl=en"
            connection = (URL(storeUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                instanceFollowRedirects = true
            }

            val completionHandler = currentJob?.invokeOnCompletion {
                try {
                    connection?.disconnect()
                } catch (e: Exception) {
                    Timber.w(e, "Error disconnecting HTTP connection")
                }
            }

            val iconUrl = if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().useLines { lines ->
                    var found: String? = null
                    for (line in lines) {
                        val matcher = ogImagePattern.matcher(line)
                        if (matcher.find()) {
                            found = matcher.group(1)?.replace("&amp;", "&")
                            break
                        }
                    }
                    found
                }
            } else {
                null
            }

            completionHandler?.dispose()

            if (iconUrl != null) {
                downloadBitmap(iconUrl, currentJob)
            } else {
                null
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.d("Could not resolve Play Store icon for $packageName: ${e.message}")
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                Timber.w(e, "Error disconnecting HTTP connection")
            }
        }
    }

    private fun downloadBitmap(urlStr: String, parentJob: Job? = null): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                instanceFollowRedirects = true
            }

            val completionHandler = parentJob?.invokeOnCompletion {
                try {
                    conn?.disconnect()
                } catch (e: Exception) {
                    Timber.w(e, "Error disconnecting download connection")
                }
            }

            val bitmap = if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                BufferedInputStream(conn.inputStream).use { stream ->
                    val raw = BitmapFactory.decodeStream(stream)
                    raw?.let { scaleBitmap(it, TARGET_ICON_SIZE_PX) }
                }
            } else {
                null
            }

            completionHandler?.dispose()
            bitmap
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w(e, "Failed downloading bitmap from $urlStr")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                Timber.w(e, "Error disconnecting download connection")
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val b = drawable.bitmap
            if (b.width == TARGET_ICON_SIZE_PX && b.height == TARGET_ICON_SIZE_PX) {
                return b
            }
            return Bitmap.createScaledBitmap(b, TARGET_ICON_SIZE_PX, TARGET_ICON_SIZE_PX, true)
        }

        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else TARGET_ICON_SIZE_PX
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else TARGET_ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return if (bitmap.width != TARGET_ICON_SIZE_PX || bitmap.height != TARGET_ICON_SIZE_PX) {
            Bitmap.createScaledBitmap(bitmap, TARGET_ICON_SIZE_PX, TARGET_ICON_SIZE_PX, true)
        } else {
            bitmap
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, targetSize: Int): Bitmap {
        if (bitmap.width == targetSize && bitmap.height == targetSize) {
            return bitmap
        }
        return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
    }

    private fun saveBitmapToDisk(bitmap: Bitmap, file: File) {
        try {
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (file.exists()) {
                file.delete()
            }
            if (!tempFile.renameTo(file)) {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving icon to disk for ${file.name}", e)
        }
    }

    private fun getDiskCacheFile(context: Context, packageName: String): File {
        val dir = File(context.cacheDir, "app_icons")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        // Sanitize package name for safe filesystem naming
        val safeName = packageName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        return File(dir, "${safeName}.png")
    }

    /**
     * Generates a deterministic, aesthetically pleasing Material avatar bitmap
     * with the first character of the app's real name.
     * Caches generated avatars by (letter + colorIndex) in [avatarCache] to prevent UI jank.
     */
    fun generateAvatarBitmap(context: Context, appName: String, packageName: String): Bitmap {
        val colorIndex = (packageName.hashCode() and 0x7FFFFFFF) % avatarColors.size
        val letter = appName.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
            ?: packageName.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
            ?: '?'
        val cacheKey = "${letter}_${colorIndex}"

        val cached = avatarCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return cached
        }

        val size = TARGET_ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgColor = avatarColors[colorIndex]
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }

        // Draw rounded rectangle background (12dp relative radius)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val cornerRadius = size * 0.26f // ~33px rounded corner
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.48f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        val bounds = Rect()
        textPaint.getTextBounds(letter.toString(), 0, 1, bounds)
        val x = size / 2f
        val y = (size / 2f) + (bounds.height() / 2f) - bounds.bottom

        canvas.drawText(letter.toString(), x, y, textPaint)

        avatarCache.put(cacheKey, bitmap)
        return bitmap
    }
}
