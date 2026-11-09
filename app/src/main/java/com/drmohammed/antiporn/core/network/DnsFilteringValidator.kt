package com.drmohammed.antiporn.core.network

import android.util.Log
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Random
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Enterprise-grade DNS over TLS (DoT - RFC 7858) validator.
 * Tests custom supervisor-supplied DNS hostnames on port 853 to ensure they:
 * 1. Support DoT (TLS on port 853) without hanging or crashing.
 * 2. Successfully resolve standard internet traffic (benign canary).
 * 3. Strictly block pornographic content (adult canaries).
 */
object DnsFilteringValidator {

    private const val TAG = "AntiPorn_DnsValidator"
    private const val DOT_PORT = 853
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 4000

    const val CANARY_BENIGN_DOMAIN = "google.com"
    val CANARY_ADULT_DOMAINS = listOf("pornhub.com", "xvideos.com")

    private val SINKHOLE_IPS = setOf(
        "0.0.0.0",
        "127.0.0.1",
        "94.140.14.33",
        "94.140.14.34",
        "94.140.14.35",
        "185.228.168.10",
        "::",
        "::1"
    )

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class UnfilteredPorn(val domain: String, val resolvedIp: String) : ValidationResult()
        data class Unreachable(val reason: String) : ValidationResult()
        data class ResolverBroken(val reason: String) : ValidationResult()
    }

    /**
     * Checks if a given hostname is a known trusted family DNS preset that can be approved immediately.
     */
    fun isKnownFamilyPreset(host: String): Boolean {
        val lower = host.trim().lowercase()
        return lower == DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST.lowercase() ||
               lower == DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST.lowercase() ||
               lower == DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST.lowercase() ||
               lower == DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST.lowercase() ||
               lower == "family-filter-dns.cleanbrowsing.org" ||
               lower == "dns-family.adguard.com"
    }

    /**
     * Executes the comprehensive pre-flight DoT probe against the given DNS host.
     * Must be called from a background thread (e.g. Dispatchers.IO).
     */
    fun validateDnsHost(host: String): ValidationResult {
        val cleanHost = host.trim().lowercase()

        // 1. Instant approval for verified static family presets
        if (isKnownFamilyPreset(cleanHost)) {
            Log.i(TAG, "Host $cleanHost is a verified family preset, approved instantly.")
            return ValidationResult.Valid
        }

        var socket: SSLSocket? = null
        try {
            Log.d(TAG, "Connecting to DoT host $cleanHost:$DOT_PORT...")
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val rawSocket = factory.createSocket() as SSLSocket
            socket = rawSocket
            rawSocket.soTimeout = READ_TIMEOUT_MS

            // Attach TLS Server Name Indication (SNI) for NextDNS and strict DoT resolvers
            try {
                val sslParams = rawSocket.sslParameters
                sslParams.serverNames = listOf(SNIHostName(cleanHost))
                rawSocket.sslParameters = sslParams
            } catch (e: Exception) {
                Log.d(TAG, "Skipping SNI for host $cleanHost: ${e.message}")
            }

            rawSocket.connect(InetSocketAddress(cleanHost, DOT_PORT), CONNECT_TIMEOUT_MS)
            rawSocket.startHandshake()
            Log.d(TAG, "TLS Handshake successful with $cleanHost")

            val outStream = DataOutputStream(rawSocket.outputStream)
            val inStream = DataInputStream(rawSocket.inputStream)

            // 2. Benign Canary Test (Verify that the resolver is actually functional)
            val benignResponse = queryDomain(outStream, inStream, CANARY_BENIGN_DOMAIN)
            if (benignResponse == null || benignResponse.rcode != 0 || benignResponse.ips.isEmpty() ||
                benignResponse.ips.all { isSinkholeOrLocal(it) }
            ) {
                Log.w(TAG, "Resolver failed benign test for $CANARY_BENIGN_DOMAIN. Response: $benignResponse")
                return ValidationResult.ResolverBroken("Failed to resolve standard domain ($CANARY_BENIGN_DOMAIN)")
            }
            Log.d(TAG, "Benign test passed for $CANARY_BENIGN_DOMAIN -> ${benignResponse.ips}")

            // 3. Adult Canary Tests (Verify that explicit porn domains are strictly blocked)
            for (adultDomain in CANARY_ADULT_DOMAINS) {
                val adultResponse = queryDomain(outStream, inStream, adultDomain)
                if (adultResponse == null) {
                    Log.w(TAG, "Query timed out or connection dropped during verification for $adultDomain")
                    return ValidationResult.ResolverBroken("Query timed out or connection dropped during verification ($adultDomain)")
                }

                // If resolver returned NXDOMAIN (3) or REFUSED (5) or NOERROR with 0 answers -> BLOCKED!
                if (adultResponse.rcode == 3 || adultResponse.rcode == 5 || adultResponse.ips.isEmpty()) {
                    Log.d(TAG, "Adult domain $adultDomain is blocked by RCODE/empty (RCODE=${adultResponse.rcode})")
                    continue
                }

                // Check if any returned IP is a live routable public IP
                val unblockedPublicIp = adultResponse.ips.firstOrNull { ip -> !isSinkholeOrLocal(ip) }
                if (unblockedPublicIp != null) {
                    Log.w(TAG, "FAIL: Adult domain $adultDomain resolved to public IP: $unblockedPublicIp!")
                    return ValidationResult.UnfilteredPorn(
                        domain = adultDomain,
                        resolvedIp = unblockedPublicIp
                    )
                }

                Log.d(TAG, "Adult domain $adultDomain blocked via sinkhole IP: ${adultResponse.ips}")
            }

            Log.i(TAG, "DoT host $cleanHost passed all verification tests successfully.")
            return ValidationResult.Valid

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to or testing DoT host $cleanHost", e)
            val msg = e.localizedMessage ?: e.javaClass.simpleName
            return ValidationResult.Unreachable(msg)
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.d(TAG, "Error closing SSL socket: ${e.message}")
            }
        }
    }

    private fun isSinkholeOrLocal(ip: String): Boolean {
        if (ip in SINKHOLE_IPS) return true
        if (ip.startsWith("0.") || ip.startsWith("127.")) return true
        return false
    }

    private fun queryDomain(
        outStream: DataOutputStream,
        inStream: DataInputStream,
        domain: String
    ): DnsPacketHelper.DnsResponse? {
        return try {
            val txId = Random().nextInt(0x7FFF) + 1
            val queryBytes = DnsPacketHelper.buildQuery(domain, txId)

            // RFC 7858: 2-byte length prefix
            outStream.writeShort(queryBytes.size)
            outStream.write(queryBytes)
            outStream.flush()

            val responseLength = inStream.readUnsignedShort()
            if (responseLength <= 0 || responseLength > 4096) {
                return null
            }
            val responseBuffer = ByteArray(responseLength)
            inStream.readFully(responseBuffer)

            val parsed = DnsPacketHelper.parseResponse(responseBuffer)
            if (parsed.txId != (txId and 0xFFFF)) {
                Log.w(TAG, "DNS Transaction ID mismatch for $domain: expected ${txId and 0xFFFF}, got ${parsed.txId}")
                return null
            }
            if (parsed.rcode == -1) {
                Log.w(TAG, "DNS response invalid or truncated for $domain")
                return null
            }
            parsed
        } catch (e: IOException) {
            Log.w(TAG, "I/O exception while querying $domain", e)
            null
        }
    }

    /**
     * Compact, standard DNS packet builder and parser (RFC 1035).
     */
    internal object DnsPacketHelper {

        data class DnsResponse(
            val txId: Int,
            val rcode: Int,
            val ancount: Int,
            val ips: List<String>
        )

        fun buildQuery(domain: String, txId: Int): ByteArray {
            val qnameStream = ByteArrayOutputStream()
            for (label in domain.split('.')) {
                if (label.isEmpty()) continue
                val bytes = label.toByteArray(Charsets.UTF_8)
                qnameStream.write(bytes.size)
                qnameStream.write(bytes)
            }
            qnameStream.write(0)
            val qname = qnameStream.toByteArray()

            val buffer = ByteBuffer.allocate(12 + qname.size + 4)
            buffer.putShort(txId.toShort())       // ID
            buffer.putShort(0x0100.toShort())      // Flags: Recursion Desired = 1
            buffer.putShort(1.toShort())           // QDCOUNT = 1
            buffer.putShort(0.toShort())           // ANCOUNT = 0
            buffer.putShort(0.toShort())           // NSCOUNT = 0
            buffer.putShort(0.toShort())           // ARCOUNT = 0
            buffer.put(qname)
            buffer.putShort(1.toShort())           // QTYPE: A (IPv4)
            buffer.putShort(1.toShort())           // QCLASS: IN
            return buffer.array()
        }

        fun parseResponse(bytes: ByteArray): DnsResponse {
            if (bytes.size < 12) {
                return DnsResponse(txId = 0, rcode = -1, ancount = 0, ips = emptyList())
            }
            val buffer = ByteBuffer.wrap(bytes)
            val parsedTxId = buffer.short.toInt() and 0xFFFF
            val flags = buffer.short.toInt() and 0xFFFF
            val isTruncated = (flags and 0x0200) != 0
            if (isTruncated) {
                return DnsResponse(txId = parsedTxId, rcode = -1, ancount = 0, ips = emptyList())
            }
            val rcode = flags and 0x000F
            val qdcount = buffer.short.toInt() and 0xFFFF
            val ancount = buffer.short.toInt() and 0xFFFF

            var offset = 12
            // Skip Question records
            for (i in 0 until qdcount) {
                offset = skipDomainName(bytes, offset)
                offset += 4 // QTYPE (2) + QCLASS (2)
            }

            // Parse Answer records
            val ips = mutableListOf<String>()
            for (i in 0 until ancount) {
                if (offset >= bytes.size) break
                offset = skipDomainName(bytes, offset)
                if (offset + 10 > bytes.size) break

                val type = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
                val rdlength = ((bytes[offset + 8].toInt() and 0xFF) shl 8) or (bytes[offset + 9].toInt() and 0xFF)
                offset += 10

                if (type == 1 && rdlength == 4 && offset + 4 <= bytes.size) { // A record
                    val ip = "${bytes[offset].toInt() and 0xFF}.${bytes[offset + 1].toInt() and 0xFF}.${bytes[offset + 2].toInt() and 0xFF}.${bytes[offset + 3].toInt() and 0xFF}"
                    ips.add(ip)
                }
                offset += rdlength
            }

            return DnsResponse(txId = parsedTxId, rcode = rcode, ancount = ancount, ips = ips)
        }

        private fun skipDomainName(bytes: ByteArray, startOffset: Int): Int {
            var offset = startOffset
            while (offset < bytes.size) {
                val len = bytes[offset].toInt() and 0xFF
                if (len == 0) {
                    return offset + 1
                } else if ((len and 0xC0) == 0xC0) {
                    // Pointer (2 bytes)
                    return offset + 2
                } else {
                    offset += 1 + len
                }
            }
            return offset
        }
    }
}
