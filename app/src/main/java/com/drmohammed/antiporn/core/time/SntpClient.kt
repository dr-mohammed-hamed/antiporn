package com.drmohammed.antiporn.core.time

import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Result data class for time queries executed via [SntpClient].
 *
 * @property timeMs The true epoch time in milliseconds at response arrival.
 * @property elapsedRealtimeMs The [SystemClock.elapsedRealtime] reference at arrival.
 * @property roundTripLatencyMs The measured network round-trip latency in milliseconds.
 * @property source The origin server or fallback endpoint providing the timestamp.
 */
data class SntpResult(
    val timeMs: Long,
    val elapsedRealtimeMs: Long,
    val roundTripLatencyMs: Long,
    val source: String
)

/**
 * Pure Kotlin RFC 4330 / RFC 5905 SNTP protocol client over UDP [DatagramSocket].
 *
 * Features:
 * - RFC 4330 standard 48-byte NTP client packet.
 * - NTP 1900-to-1970 Unix epoch offset handling (2,208,988,800 seconds).
 * - UDP server rotation across high-reliability NTP pools.
 * - Automatic HTTPS HEAD fallback ("Date" header parsing) if UDP port 123 is blocked
 *   or restricted by firewalls, cellular carriers, or local VPN tunnels.
 * - Zero third-party dependencies (pure Android framework and Kotlin stdlib).
 */
object SntpClient {

    private const val TAG = "AntiPorn_SntpClient"

    /** Standard NTP UDP port. */
    private const val NTP_PORT = 123

    /** NTP packet size defined by RFC 4330. */
    private const val NTP_PACKET_SIZE = 48

    /** Mode 3 (Client) with Version 3 (00 011 011 = 0x1B). */
    private const val NTP_MODE_CLIENT: Byte = 0x1B

    /** Transmit timestamp byte offset in NTP packet. */
    private const val TRANSMIT_TIME_OFFSET = 40

    /** Originate timestamp byte offset in NTP packet. */
    private const val ORIGINATE_TIME_OFFSET = 24

    /** Receive timestamp byte offset in NTP packet. */
    private const val RECEIVE_TIME_OFFSET = 32

    /**
     * Seconds between NTP epoch (1900-01-01 00:00:00 UTC) and Unix epoch (1970-01-01 00:00:00 UTC).
     * 70 years = 70 * 365 + 17 leap days = 25,567 days * 86,400 seconds = 2,208,988,800 seconds.
     */
    private const val OFFSET_1900_TO_1970: Long = 2208988800L

    /** Default UDP socket timeout in milliseconds. */
    const val TIMEOUT_UDP_MS: Int = 3500

    /** Default HTTP fallback timeout in milliseconds. */
    const val TIMEOUT_HTTP_MS: Int = 4000

    /** Maximum allowed future drift for HTTP date fallback (30 days). */
    const val MAX_HTTP_FUTURE_DRIFT_MS: Long = 30L * 24 * 3600 * 1000L

    /** Minimum plausible epoch boundary (2024-01-01 00:00:00 UTC) to reject corrupted packets. */
    const val MIN_PLAUSIBLE_EPOCH_MS: Long = 1704067200000L

    /** High-availability NTP servers for client rotation. */
    private val NTP_SERVERS = listOf(
        "time.google.com",
        "time.cloudflare.com",
        "pool.ntp.org"
    )

    /** Secure HTTPS endpoints providing standard RFC 1123 Date response headers. */
    private val HTTP_FALLBACK_URLS = listOf(
        "https://www.google.com",
        "https://www.cloudflare.com"
    )

    /** Thread-safe formatter for standard HTTP Date header (RFC 1123). */
    private val HTTP_DATE_FORMAT = ThreadLocal.withInitial {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
    }

    /**
     * Synchronously requests trusted time from network servers.
     * Tries rotating UDP NTP servers first; if all fail or are blocked,
     * falls back to HTTPS HEAD Date header parsing.
     *
     * @param timeoutMs Timeout for each UDP attempt.
     * @return [SntpResult] if successful, or null if all network sources fail.
     */
    fun requestTime(timeoutMs: Int = TIMEOUT_UDP_MS): SntpResult? {
        for (server in NTP_SERVERS) {
            val result = queryUdpServer(server, timeoutMs)
            if (result != null) {
                Log.i(TAG, "SNTP query succeeded from $server: timeMs=${result.timeMs}, latency=${result.roundTripLatencyMs}ms")
                return result
            }
        }

        Log.w(TAG, "All UDP NTP servers failed or blocked. Attempting HTTP Date fallback...")
        return queryHttpDateFallback(TIMEOUT_HTTP_MS)
    }

    /**
     * Queries a single SNTP server via UDP socket conforming to RFC 4330.
     */
    private fun queryUdpServer(host: String, timeoutMs: Int): SntpResult? {
        var socket: DatagramSocket? = null
        return try {
            val address = InetAddress.getByName(host)
            val buffer = ByteArray(NTP_PACKET_SIZE)

            // Setup client mode packet
            buffer[0] = NTP_MODE_CLIENT

            val requestTicks = SystemClock.elapsedRealtime()
            val requestTime = System.currentTimeMillis()
            writeTimeStamp(buffer, TRANSMIT_TIME_OFFSET, requestTime)

            val requestPacket = DatagramPacket(buffer, buffer.size, address, NTP_PORT)
            socket = DatagramSocket().apply {
                soTimeout = timeoutMs
                connect(address, NTP_PORT)
            }

            socket.send(requestPacket)

            val responseBuffer = ByteArray(NTP_PACKET_SIZE)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)
            val responseTicks = SystemClock.elapsedRealtime()

            val roundTripLatency = responseTicks - requestTicks
            if (responsePacket.length < NTP_PACKET_SIZE) {
                Log.w(TAG, "Malformed NTP response length: ${responsePacket.length} from $host")
                return null
            }

            val leapIndicator = (responseBuffer[0].toInt() shr 6) and 0x03
            val mode = responseBuffer[0].toInt() and 0x07
            val stratum = responseBuffer[1].toInt() and 0xFF

            // Mode 4 = Server, Mode 5 = Broadcast
            if (mode != 4 && mode != 5) {
                Log.w(TAG, "Invalid NTP response mode: $mode from $host")
                return null
            }

            // Stratum 0 is Kiss-o'-Death packet
            if (stratum == 0 || stratum > 15) {
                Log.w(TAG, "Unusable NTP stratum: $stratum (Kiss-o'-Death or unsynchronized) from $host")
                return null
            }

            // Leap indicator 3 indicates alarm condition (clock not synchronized)
            if (leapIndicator == 3) {
                Log.w(TAG, "NTP server reported unsynchronized leap indicator: $leapIndicator from $host")
                return null
            }

            val originateMatches = (0 until 8).all { i ->
                buffer[TRANSMIT_TIME_OFFSET + i] == responseBuffer[ORIGINATE_TIME_OFFSET + i]
            }
            if (!originateMatches) {
                Log.w(TAG, "Originate timestamp mismatch from $host (possible replay or spoofed packet)")
                return null
            }

            val originateTime = readTimeStamp(responseBuffer, ORIGINATE_TIME_OFFSET)
            val receiveTime = readTimeStamp(responseBuffer, RECEIVE_TIME_OFFSET)
            val transmitTime = readTimeStamp(responseBuffer, TRANSMIT_TIME_OFFSET)

            if (transmitTime <= 0L) {
                Log.w(TAG, "Invalid transmit timestamp: $transmitTime from $host")
                return null
            }

            // Standard RFC 4330 clock calculation:
            // Round trip delay = (responseTicks - requestTicks) - (transmitTime - receiveTime)
            // Clock true time at response arrival = transmitTime + (roundTripDelay / 2)
            val serverProcessingDelay = if (receiveTime > 0L && transmitTime >= receiveTime) {
                transmitTime - receiveTime
            } else {
                0L
            }
            val netDelay = maxOf(0L, roundTripLatency - serverProcessingDelay)
            val ntpTime = transmitTime + (netDelay / 2)

            if (ntpTime < MIN_PLAUSIBLE_EPOCH_MS) {
                Log.w(TAG, "Unreasonable NTP timestamp: $ntpTime from $host (before 2024 boundary)")
                return null
            }

            val lastKnown = TrustedTimeProvider.getLastKnownTrueTime()
            if (lastKnown > 0L) {
                if (ntpTime < lastKnown) {
                    Log.w(TAG, "Rejecting NTP time from $host: $ntpTime is earlier than lastKnown $lastKnown")
                    return null
                }
                if (ntpTime > lastKnown + MAX_HTTP_FUTURE_DRIFT_MS) {
                    Log.w(TAG, "Rejecting NTP time from $host: $ntpTime is too far in future (> 30 days beyond $lastKnown)")
                    return null
                }
            }

            SntpResult(
                timeMs = ntpTime,
                elapsedRealtimeMs = responseTicks,
                roundTripLatencyMs = roundTripLatency,
                source = "SNTP: $host"
            )
        } catch (e: Exception) {
            Log.w(TAG, "SNTP query failed for host: $host - ${e.message}")
            null
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing UDP socket", e)
            }
        }
    }

    /**
     * Fallback mechanism querying standard HTTP "Date" headers via lightweight HTTPS HEAD requests.
     * Used when UDP port 123 is blocked by network filters, local VPN, or firewall.
     */
    fun queryHttpDateFallback(timeoutMs: Int = TIMEOUT_HTTP_MS): SntpResult? {
        for (urlString in HTTP_FALLBACK_URLS) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                val requestTicks = SystemClock.elapsedRealtime()
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    useCaches = false
                }
                connection.connect()
                val responseTicks = SystemClock.elapsedRealtime()
                val latency = responseTicks - requestTicks

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    Log.w(TAG, "Rejecting HTTP Date from $urlString: invalid response code $responseCode (captive portal / redirect / error)")
                    continue
                }

                val dateHeader = connection.getHeaderField("Date")
                val parsedHeaderMs: Long? = if (!dateHeader.isNullOrBlank()) {
                    try {
                        HTTP_DATE_FORMAT.get()?.parse(dateHeader)?.time
                    } catch (pe: Exception) {
                        Log.w(TAG, "Failed parsing Date header: $dateHeader from $urlString", pe)
                        null
                    }
                } else {
                    null
                }

                // Fall back to connection.date if header string parse was unsuccessful
                val headerTimeMs = parsedHeaderMs ?: connection.date.takeIf { it > 0L }
                if (headerTimeMs != null && headerTimeMs > 0L) {
                    val estimatedTrueTime = headerTimeMs + (latency / 2)
                    if (estimatedTrueTime < MIN_PLAUSIBLE_EPOCH_MS) {
                        Log.w(TAG, "Rejecting HTTP Date from $urlString: estimated timestamp $estimatedTrueTime is before 2024 boundary")
                        continue
                    }
                    val lastKnown = TrustedTimeProvider.getLastKnownTrueTime()
                    if (lastKnown > 0L) {
                        if (estimatedTrueTime < lastKnown) {
                            Log.w(TAG, "Rejecting HTTP Date from $urlString: estimated timestamp $estimatedTrueTime is earlier than lastKnown $lastKnown")
                            continue
                        }
                        if (estimatedTrueTime > lastKnown + MAX_HTTP_FUTURE_DRIFT_MS) {
                            Log.w(TAG, "Rejecting HTTP Date from $urlString: estimated timestamp $estimatedTrueTime is too far in future (> 30 days beyond $lastKnown)")
                            continue
                        }
                    }
                    Log.i(TAG, "HTTP Date fallback succeeded from $urlString: estimatedTrueTime=$estimatedTrueTime, latency=${latency}ms")
                    return SntpResult(
                        timeMs = estimatedTrueTime,
                        elapsedRealtimeMs = responseTicks,
                        roundTripLatencyMs = latency,
                        source = "HTTP: ${url.host}"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "HTTP Date query failed for $urlString: ${e.message}")
            } finally {
                try {
                    connection?.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting HTTP connection", e)
                }
            }
        }
        return null
    }

    /**
     * Reads a 32-bit big-endian unsigned integer from [buffer] at [offset].
     */
    private fun read32(buffer: ByteArray, offset: Int): Long {
        val b0 = buffer[offset].toLong() and 0xFF
        val b1 = buffer[offset + 1].toLong() and 0xFF
        val b2 = buffer[offset + 2].toLong() and 0xFF
        val b3 = buffer[offset + 3].toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    /**
     * Reads an RFC 4330 64-bit NTP timestamp (32-bit seconds + 32-bit fraction)
     * and converts it to Unix epoch milliseconds.
     */
    private fun readTimeStamp(buffer: ByteArray, offset: Int): Long {
        val seconds = read32(buffer, offset)
        val fraction = read32(buffer, offset + 4)
        if (seconds == 0L && fraction == 0L) {
            return 0L
        }
        val epochSeconds = seconds - OFFSET_1900_TO_1970
        val fractionMillis = (fraction * 1000L) / 0x100000000L
        return (epochSeconds * 1000L) + fractionMillis
    }

    /**
     * Writes an RFC 4330 64-bit NTP timestamp from Unix epoch milliseconds into [buffer] at [offset].
     */
    private fun writeTimeStamp(buffer: ByteArray, offset: Int, timeMs: Long) {
        if (timeMs <= 0L) {
            for (i in 0 until 8) {
                buffer[offset + i] = 0
            }
            return
        }

        val seconds = (timeMs / 1000L) + OFFSET_1900_TO_1970
        val fraction = ((timeMs % 1000L) * 0x100000000L) / 1000L

        buffer[offset] = (seconds shr 24).toByte()
        buffer[offset + 1] = (seconds shr 16).toByte()
        buffer[offset + 2] = (seconds shr 8).toByte()
        buffer[offset + 3] = seconds.toByte()

        buffer[offset + 4] = (fraction shr 24).toByte()
        buffer[offset + 5] = (fraction shr 16).toByte()
        buffer[offset + 6] = (fraction shr 8).toByte()
        buffer[offset + 7] = fraction.toByte()
    }
}
