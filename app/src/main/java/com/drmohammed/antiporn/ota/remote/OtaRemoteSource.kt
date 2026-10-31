package com.drmohammed.antiporn.ota.remote

import android.util.Log
import com.drmohammed.antiporn.ota.model.OtaVersionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Remote data source for Over-The-Air (OTA) updates using Cloudflare R2 bucket distribution.
 *
 * Implements resilient network communication on [Dispatchers.IO] with timeout management,
 * streamed buffered downloads, debounced progress callbacks, and atomic file replacement.
 */
object OtaRemoteSource {

    private const val TAG = "OtaRemoteSource"

    /**
     * Default Cloudflare R2 public distribution endpoint for version.json.
     */
    const val DEFAULT_VERSION_URL = "https://example-releases.r2.dev/version.json"

    private const val CONNECT_TIMEOUT_MS = 15_000 // 15 seconds
    private const val READ_TIMEOUT_MS = 15_000    // 15 seconds
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000 // 30 seconds for binary streaming
    private const val BUFFER_SIZE = 64 * 1024     // 64 KB chunk size
    private const val PROGRESS_DEBOUNCE_INTERVAL_MS = 100L // Min interval between progress updates

    /**
     * Fetches the latest version metadata from the specified URL.
     * Uses [HttpURLConnection] with a 15-second timeout, verifies HTTP 200, and parses JSON.
     *
     * @param url Remote endpoint URL for version metadata. Defaults to [DEFAULT_VERSION_URL].
     * @return [Result] containing parsed [OtaVersionInfo] or failure exception.
     */
    suspend fun fetchLatestVersion(url: String = DEFAULT_VERSION_URL): Result<OtaVersionInfo> =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val parsedUri = URI.create(url)
                connection = (parsedUri.toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "AntiPorn-OTA-Client")
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorMsg = "HTTP $responseCode: ${connection.responseMessage}"
                    Log.e(TAG, "Version fetch failed: $errorMsg")
                    return@withContext Result.failure(IOException("Failed to fetch version metadata ($errorMsg)"))
                }

                val jsonResponse = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (jsonResponse.isBlank()) {
                    return@withContext Result.failure(IOException("Empty response received from $url"))
                }

                val versionInfo = OtaVersionInfo.fromJson(jsonResponse)
                Result.success(versionInfo)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Error fetching version info from $url", t)
                Result.failure(t)
            } finally {
                try {
                    connection?.disconnect()
                } catch (ignored: Exception) {
                    Log.w(TAG, "Error disconnecting HTTP connection: ${ignored.message}")
                }
            }
        }

    /**
     * Downloads an APK binary from [downloadUrl] to [destinationFile].
     *
     * Uses a temporary `.tmp` file during download, streams in 64KB chunks,
     * throttles/debounces progress reporting, and atomically replaces [destinationFile]
     * upon successful download. The temp file is automatically cleaned up on failure.
     *
     * @param downloadUrl Public HTTPS URL of the APK.
     * @param destinationFile Local destination file for the verified APK.
     * @param onProgress Callback invoked on [Dispatchers.IO] reporting:
     *                   `downloadedBytes`, `totalBytes` (-1 if unknown), and `progressPercent` (0-100, or -1 if unknown).
     * @return [Result] containing destination [File] on success, or failure exception.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        destinationFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long, percent: Int) -> Unit = { _, _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        val parentDir = destinationFile.parentFile ?: destinationFile.absoluteFile.parentFile ?: File(".")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        val tempFile = File(parentDir, "${destinationFile.name}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        var connection: HttpURLConnection? = null
        try {
            var currentUrl = downloadUrl
            var redirectCount = 0
            val maxRedirects = 5

            // Follow HTTP/HTTPS redirects safely if required
            while (redirectCount < maxRedirects) {
                currentCoroutineContext().ensureActive()

                val uri = URI.create(currentUrl)
                val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                    setRequestProperty("User-Agent", "AntiPorn-OTA-Client")
                }

                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 || code == 308
                ) {
                    val redirectLocation = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (redirectLocation != null) {
                        if (!redirectLocation.startsWith("https://", ignoreCase = true)) {
                            return@withContext Result.failure(
                                IOException("Insecure HTTP redirect rejected: $redirectLocation")
                            )
                        }
                        currentUrl = redirectLocation
                        redirectCount++
                        continue
                    }
                }

                connection = conn
                break
            }

            val finalConn = connection ?: return@withContext Result.failure(
                IOException("Failed to establish connection to $downloadUrl")
            )

            val responseCode = finalConn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorMsg = "HTTP $responseCode: ${finalConn.responseMessage}"
                Log.e(TAG, "Download failed: $errorMsg")
                if (tempFile.exists()) tempFile.delete()
                return@withContext Result.failure(IOException("APK download failed ($errorMsg)"))
            }

            val totalBytes = finalConn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

            var downloadedBytes = 0L
            var lastProgressTime = 0L
            var lastReportedPercent = -1

            // Notify initial 0% state
            onProgress(0L, totalBytes, 0)

            finalConn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        currentCoroutineContext().ensureActive()

                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val percent = if (totalBytes > 0L) {
                            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }

                        val now = System.currentTimeMillis()
                        val shouldReport = (percent != lastReportedPercent) &&
                            (percent == 100 || (now - lastProgressTime >= PROGRESS_DEBOUNCE_INTERVAL_MS))

                        if (shouldReport) {
                            lastReportedPercent = percent
                            lastProgressTime = now
                            onProgress(downloadedBytes, totalBytes, percent)
                        }
                    }
                    output.flush()
                }
            }

            // Ensure final 100% progress callback
            if (lastReportedPercent != 100 && totalBytes > 0L) {
                onProgress(downloadedBytes, totalBytes, 100)
            }

            // Atomic rename to destination
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            val renameSuccess = tempFile.renameTo(destinationFile)
            if (!renameSuccess) {
                // Fallback copy if renameTo fails across file systems
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
            }

            Log.i(TAG, "Downloaded APK successfully saved to ${destinationFile.absolutePath} ($downloadedBytes bytes)")
            Result.success(destinationFile)
        } catch (ce: CancellationException) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Error downloading APK from $downloadUrl", t)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            Result.failure(t)
        } finally {
            try {
                connection?.disconnect()
            } catch (ignored: Exception) {
                Log.w(TAG, "Error disconnecting HTTP connection: ${ignored.message}")
            }
        }
    }
}
