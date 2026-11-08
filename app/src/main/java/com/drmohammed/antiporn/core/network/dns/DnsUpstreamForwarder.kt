package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.util.Log
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Enterprise-grade DNS Upstream Forwarder implementing 3-tier failover,
 * micro LRU caching, bounded concurrency, zero-lookup socket creation,
 * and YouTube comments preservation.
 */
class DnsUpstreamForwarder(
    private val vpnService: VpnService,
    private val policyProvider: () -> DevicePolicyBundle = { DevicePolicyBundle.defaultPolicy() },
    private val lruCache: DnsLruCache = DnsLruCache(512)
) {

    companion object {
        private const val TAG = "AntiPorn_DnsForwarder"

        // Tier 1: Cloudflare Family (Strict adult filtering with YouTube comments preserved)
        const val PRIMARY_SAFE_DNS_IPV4 = "1.1.1.3"
        const val SECONDARY_SAFE_DNS_IPV4 = "1.0.0.3"

        // Tier 2: Safe Adult-Filtering Fallback (No unfiltered 8.8.8.8 / 1.1.1.1)
        const val OPENDNS_FAMILY_PRIMARY = "208.67.222.123" // OpenDNS FamilyShield Primary
        const val OPENDNS_FAMILY_SECONDARY = "208.67.220.123" // OpenDNS FamilyShield Secondary
        const val ADGUARD_FAMILY_PRIMARY = "94.140.14.15" // AdGuard Family
        const val CLEANBROWSING_FAMILY_PRIMARY = "185.228.168.168" // CleanBrowsing Family

        private val EXCLUDED_DNS_IPS = setOf(
            "10.255.255.1",
            "fd00::1",
            "127.0.0.1",
            "0.0.0.0"
        )

        /**
         * Parses a numeric IPv4 address into an InetAddress with 0ms overhead
         * and ZERO network/DNS resolution, completely eliminating recursive getByName loops.
         */
        fun parseIpv4ToInetAddress(rawText: String): InetAddress? {
            val clean = rawText.trim().substringBefore(':').trim()
            val parts = clean.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for (i in 0..3) {
                val intVal = parts[i].toIntOrNull() ?: return null
                if (intVal !in 0..255) return null
                bytes[i] = intVal.toByte()
            }
            return try {
                InetAddress.getByAddress(bytes)
            } catch (_: Exception) {
                null
            }
        }
    }

    private val queryPermits = Semaphore(32)

    @Volatile
    private var customDnsHost: String? = null

    fun setCustomDnsHost(host: String?) {
        customDnsHost = host?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Resolves a raw DNS query with Micro LRU cache and 3-tier failover.
     * Returns raw DNS response payload, or null if all tiers fail.
     */
    suspend fun resolve(
        qName: String,
        qType: Int,
        transactionId: Short,
        rawDnsPayload: ByteArray
    ): ByteArray? {
        // 1. Check LRU Cache (0ms hit)
        val cached = lruCache.get(qName, qType)
        if (cached != null) {
            // Rewrite transaction ID to match current client query
            cached[0] = (transactionId.toInt() ushr 8).toByte()
            cached[1] = (transactionId.toInt() and 0xFF).toByte()
            return cached
        }

        // 2. Query upstream via Bounded Protected Sockets
        return queryPermits.withPermit {
            var reply = queryTier1(rawDnsPayload)
            if (reply == null) {
                Log.d(TAG, "Tier 1 DNS timeout/fail for $qName, attempting Tier 2 (OpenDNS/AdGuard Family)...")
                reply = queryTier2(rawDnsPayload)
            }
            if (reply == null) {
                Log.w(TAG, "Tier 2 DNS failed for $qName, attempting Tier 3 (Physical Network DNS)...")
                reply = queryTier3(rawDnsPayload)
            }

            if (reply != null) {
                // Cache response with safe TTL
                val ttl = extractTtl(reply)
                lruCache.put(qName, qType, reply, ttl)
            }
            reply
        }
    }

    private fun queryTier1(rawPayload: ByteArray): ByteArray? {
        val policy = policyProvider()
        val host = customDnsHost

        // 1. If explicit CleanBrowsing preset is chosen
        if (host != null && (host == CLEANBROWSING_FAMILY_PRIMARY || host.contains("cleanbrowsing", ignoreCase = true))) {
            val reply = sendUdpQuery(CLEANBROWSING_FAMILY_PRIMARY, rawPayload, timeoutMs = 900)
            if (reply != null) return reply
        }

        // 2. If an explicit non-local numeric IPv4 address was provided
        if (host != null) {
            val parsedIp = parseIpv4ToInetAddress(host)
            if (parsedIp != null && !EXCLUDED_DNS_IPS.contains(host)) {
                val reply = sendUdpQuery(host, rawPayload, timeoutMs = 900)
                if (reply != null) return reply
            }
        }

        // 3. User configured AdGuard Family
        if (host != null && host.contains("adguard", ignoreCase = true)) {
            val reply = sendUdpQuery(ADGUARD_FAMILY_PRIMARY, rawPayload, timeoutMs = 900)
            if (reply != null) return reply
        }

        // 4. Default: Use Cloudflare Family (1.1.1.3 & 1.0.0.3)
        // Strictly blocks adult domains while keeping YouTube comments fully functional
        val primaryReply = sendUdpQuery(PRIMARY_SAFE_DNS_IPV4, rawPayload, timeoutMs = 900)
        if (primaryReply != null) return primaryReply

        return sendUdpQuery(SECONDARY_SAFE_DNS_IPV4, rawPayload, timeoutMs = 900)
    }

    private fun queryTier2(rawPayload: ByteArray): ByteArray? {
        // Safe adult-filtering failover: OpenDNS FamilyShield -> AdGuard Family
        var reply = sendUdpQuery(OPENDNS_FAMILY_PRIMARY, rawPayload, timeoutMs = 1200)
        if (reply != null) return reply

        reply = sendUdpQuery(OPENDNS_FAMILY_SECONDARY, rawPayload, timeoutMs = 1200)
        if (reply != null) return reply

        return sendUdpQuery(ADGUARD_FAMILY_PRIMARY, rawPayload, timeoutMs = 1200)
    }

    private fun queryTier3(rawPayload: ByteArray): ByteArray? {
        val physicalDnsServers = resolvePhysicalNetworkDns()
        for (dnsServer in physicalDnsServers) {
            val reply = sendUdpQuery(dnsServer, rawPayload, timeoutMs = 1500)
            if (reply != null) return reply
        }
        return null
    }

    private fun sendUdpQuery(ipAddress: String, rawPayload: ByteArray, timeoutMs: Int): ByteArray? {
        val target = parseIpv4ToInetAddress(ipAddress) ?: return null
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().apply {
                soTimeout = timeoutMs
            }
            if (!vpnService.protect(socket)) {
                Log.w(TAG, "VpnService.protect() failed for socket to $ipAddress")
                return null
            }

            val sendPacket = DatagramPacket(rawPayload, rawPayload.size, target, DnsPacketParser.DNS_PORT)
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(4096)
            val recvPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(recvPacket)

            val dnsReply = ByteArray(recvPacket.length)
            System.arraycopy(receiveBuffer, 0, dnsReply, 0, recvPacket.length)
            dnsReply
        } catch (e: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolvePhysicalNetworkDns(): List<String> {
        val result = mutableListOf<String>()
        try {
            val cm = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return result
            val networks = cm.allNetworks
            for (net in networks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    val linkProps = cm.getLinkProperties(net) ?: continue
                    for (inetAddress in linkProps.dnsServers) {
                        val hostAddress = inetAddress.hostAddress
                        // Exclude empty, excluded/local IPs, and IPv6 addresses (containing ':') since socket is IPv4 UDP
                        if (!hostAddress.isNullOrBlank() &&
                            !EXCLUDED_DNS_IPS.contains(hostAddress) &&
                            !hostAddress.contains(':') &&
                            parseIpv4ToInetAddress(hostAddress) != null &&
                            !result.contains(hostAddress)
                        ) {
                            result.add(hostAddress)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve physical network DNS", e)
        }
        return result
    }

    private fun extractTtl(dnsPayload: ByteArray): Long {
        if (dnsPayload.size < 12) return 60L
        val anCount = ((dnsPayload[6].toInt() and 0xFF) shl 8) or (dnsPayload[7].toInt() and 0xFF)
        if (anCount <= 0) return 30L
        return 60L // Clamped default TTL for clean balance
    }
}
