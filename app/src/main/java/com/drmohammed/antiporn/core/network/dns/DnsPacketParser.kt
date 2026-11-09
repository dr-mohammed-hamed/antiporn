package com.drmohammed.antiporn.core.network.dns

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Lightweight, zero-dependency DNS and IPv4/UDP packet processor.
 * Focuses strictly on extracting QNAME from incoming DNS queries, matching against
 * blocked domains, and generating immediate synthetic NXDOMAIN replies for blocked queries,
 * while allowing raw payload passthrough for allowed queries.
 */
object DnsPacketParser {

    const val DNS_PORT = 53
    const val IP_HEADER_MIN_LEN = 20
    const val UDP_HEADER_LEN = 8
    const val DNS_HEADER_LEN = 12

    // RFC 1035 RCODE 3: Non-Existent Domain (NXDOMAIN)
    // Flags 0x8183: QR=1 (Response), Opcode=0 (Query), AA=0, TC=0, RD=1, RA=1, RCODE=3 (NXDOMAIN)
    private const val FLAGS_NXDOMAIN: Short = 0x8183.toShort()
    private const val FLAGS_NOERROR: Short = 0x8180.toShort()

    val YOUTUBE_RESTRICTED_VIP_IPV4: ByteArray = byteArrayOf(
        216.toByte(), 239.toByte(), 38.toByte(), 120.toByte() // 216.239.38.120
    )

    // Smart mapping: If user blocks the primary service, automatically associate its core CDN/auxiliary domains
    private val SMART_SERVICE_EXPANSIONS = mapOf(
        "youtube.com" to setOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "youtube-nocookie.com", "youtubekids.com"),
        "tiktok.com" to setOf("tiktok.com", "tiktokcdn.com", "tiktokv.com", "musical.ly", "byteoversea.com", "ibytedtos.com"),
        "facebook.com" to setOf("facebook.com", "fbcdn.net", "facebook.net", "fb.com", "messenger.com"),
        "instagram.com" to setOf("instagram.com", "cdninstagram.com")
    )

    data class ParsedDnsQuery(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val ipId: Int,
        val transactionId: Short,
        val qName: String,
        val qType: Int = 1,
        val questionSection: ByteArray,
        val rawDnsPayload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedDnsQuery) return false
            return srcPort == other.srcPort && dstPort == other.dstPort && transactionId == other.transactionId &&
                    qName == other.qName && qType == other.qType && srcIp.contentEquals(other.srcIp) &&
                    dstIp.contentEquals(other.dstIp) && questionSection.contentEquals(other.questionSection) &&
                    rawDnsPayload.contentEquals(other.rawDnsPayload)
        }

        override fun hashCode(): Int {
            var result = srcPort
            result = 31 * result + dstPort
            result = 31 * result + transactionId
            result = 31 * result + qName.hashCode()
            result = 31 * result + qType
            result = 31 * result + srcIp.contentHashCode()
            result = 31 * result + dstIp.contentHashCode()
            result = 31 * result + questionSection.contentHashCode()
            result = 31 * result + rawDnsPayload.contentHashCode()
            return result
        }
    }

    /**
     * Parses an incoming raw IPv4/UDP packet from the TUN interface.
     * Returns [ParsedDnsQuery] if it is a valid DNS query on port 53, or null otherwise.
     */
    fun parseDnsQuery(packet: ByteArray, length: Int): ParsedDnsQuery? {
        if (length < IP_HEADER_MIN_LEN + UDP_HEADER_LEN + DNS_HEADER_LEN) return null

        val buffer = ByteBuffer.wrap(packet, 0, length)

        // 1. Validate IPv4 Header
        val versionAndIhl = buffer.get(0).toInt() and 0xFF
        val version = versionAndIhl ushr 4
        if (version != 4) return null

        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < IP_HEADER_MIN_LEN || length < ihl + UDP_HEADER_LEN + DNS_HEADER_LEN) return null

        val protocol = buffer.get(9).toInt() and 0xFF
        if (protocol != 17) return null // Must be UDP

        val ipId = buffer.getShort(4).toInt() and 0xFFFF

        val srcIp = ByteArray(4)
        val dstIp = ByteArray(4)
        buffer.position(12)
        buffer.get(srcIp)
        buffer.get(dstIp)

        // 2. Validate UDP Header
        buffer.position(ihl)
        val srcPort = buffer.short.toInt() and 0xFFFF
        val dstPort = buffer.short.toInt() and 0xFFFF
        val udpLength = buffer.short.toInt() and 0xFFFF
        buffer.short // skip checksum

        if (dstPort != DNS_PORT || udpLength < UDP_HEADER_LEN + DNS_HEADER_LEN) return null

        val dnsPayloadLength = minOf(udpLength - UDP_HEADER_LEN, length - ihl - UDP_HEADER_LEN)
        if (dnsPayloadLength < DNS_HEADER_LEN) return null

        val dnsOffset = ihl + UDP_HEADER_LEN
        val rawDnsPayload = ByteArray(dnsPayloadLength)
        System.arraycopy(packet, dnsOffset, rawDnsPayload, 0, dnsPayloadLength)

        // 3. Parse DNS Header
        val dnsBuffer = ByteBuffer.wrap(rawDnsPayload)
        val transactionId = dnsBuffer.short
        val flags = dnsBuffer.short.toInt() and 0xFFFF
        val isQuery = (flags and 0x8000) == 0
        if (!isQuery) return null

        val opcode = (flags and 0x7800) ushr 11
        if (opcode != 0) return null

        val qdCount = dnsBuffer.short.toInt() and 0xFFFF
        if (qdCount != 1) return null

        dnsBuffer.position(12) // Skip ANCOUNT, NSCOUNT, ARCOUNT to Question section

        // 4. Extract QNAME
        val questionStart = dnsBuffer.position()
        val labels = mutableListOf<String>()
        var isTerminated = false

        while (dnsBuffer.hasRemaining()) {
            val labelLen = dnsBuffer.get().toInt() and 0xFF
            if (labelLen == 0) {
                isTerminated = true
                break // End of QNAME
            }
            if (labelLen > 63 || dnsBuffer.remaining() < labelLen) return null // Malformed label

            val labelBytes = ByteArray(labelLen)
            dnsBuffer.get(labelBytes)
            labels.add(String(labelBytes, Charsets.US_ASCII))
        }

        if (!isTerminated || labels.isEmpty()) return null
        val qName = labels.joinToString(".").lowercase()

        // Read QTYPE and QCLASS (4 bytes)
        if (dnsBuffer.remaining() < 4) return null
        val qType = dnsBuffer.short.toInt() and 0xFFFF
        dnsBuffer.short // skip QCLASS
        val questionEnd = dnsBuffer.position()

        val questionSection = ByteArray(questionEnd - questionStart)
        System.arraycopy(rawDnsPayload, questionStart, questionSection, 0, questionSection.size)

        return ParsedDnsQuery(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            ipId = ipId,
            transactionId = transactionId,
            qName = qName,
            qType = qType,
            questionSection = questionSection,
            rawDnsPayload = rawDnsPayload
        )
    }

    /**
     * Sanitizes a domain rule by stripping protocol, path, port, wildcard (*.), and leading www.
     */
    fun sanitizeRule(rule: String): String {
        return rule.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .split('/', ':').first()
            .removePrefix("*.")
            .trimStart('.')
            .removePrefix("www.")
            .trim()
    }

    /**
     * Pre-compiles raw user rules into a flattened, sanitized set of target domains,
     * including automatic smart service expansions (e.g. youtube.com -> googlevideo.com, ytimg.com).
     */
    fun compileEffectiveBlockedDomains(rules: Set<String>): Set<String> {
        if (rules.isEmpty()) return emptySet()
        val result = HashSet<String>()
        for (rawRule in rules) {
            val rule = sanitizeRule(rawRule)
            if (rule.isBlank()) continue
            result.add(rule)
            SMART_SERVICE_EXPANSIONS[rule]?.let { expansions ->
                for (exp in expansions) {
                    val sanitizedExp = sanitizeRule(exp)
                    if (sanitizedExp.isNotBlank()) {
                        result.add(sanitizedExp)
                    }
                }
            }
        }
        return result
    }

    /**
     * High-speed O(1) domain match against precompiled blocked domains.
     * Checks exact match and parent subdomains without repetitive string allocations.
     */
    fun isDomainBlockedCompiled(qName: String, compiledBlockedDomains: Set<String>): Boolean {
        if (compiledBlockedDomains.isEmpty()) return false

        val normalizedQuery = qName.trim().lowercase().trimEnd('.').removePrefix("www.")

        // 1. Direct match O(1)
        if (compiledBlockedDomains.contains(normalizedQuery)) return true

        // 2. Subdomain match O(k) where k is the number of domain levels
        var dotIndex = normalizedQuery.indexOf('.')
        while (dotIndex != -1 && dotIndex < normalizedQuery.length - 1) {
            val parentDomain = normalizedQuery.substring(dotIndex + 1)
            if (compiledBlockedDomains.contains(parentDomain)) {
                return true
            }
            dotIndex = normalizedQuery.indexOf('.', dotIndex + 1)
        }

        return false
    }

    /**
     * Determines whether the requested [qName] matches any blocked domain rule,
     * including automatic subdomain matching (e.g., m.youtube.com matching youtube.com)
     * and smart service expansions.
     */
    fun isDomainBlocked(qName: String, customBlockedDomains: Set<String>): Boolean {
        if (customBlockedDomains.isEmpty()) return false
        val compiled = compileEffectiveBlockedDomains(customBlockedDomains)
        return isDomainBlockedCompiled(qName, compiled)
    }

    /**
     * Constructs a synthetic IPv4/UDP packet containing an immediate DNS NXDOMAIN response.
     * Swaps source/destination IPs and ports, keeping overhead to a few microseconds.
     */
    fun buildNxDomainResponse(query: ParsedDnsQuery): ByteArray {
        val dnsHeaderAndQuestionLen = DNS_HEADER_LEN + query.questionSection.size
        val udpTotalLen = UDP_HEADER_LEN + dnsHeaderAndQuestionLen
        val ipTotalLen = IP_HEADER_MIN_LEN + udpTotalLen

        val response = ByteArray(ipTotalLen)
        val buffer = ByteBuffer.wrap(response)

        writeHeaders(buffer, response, ipTotalLen, udpTotalLen, query.ipId, query.dstIp, query.srcIp, query.dstPort, query.srcPort)

        // 3. DNS Header (12 bytes)
        buffer.position(IP_HEADER_MIN_LEN + UDP_HEADER_LEN)
        buffer.putShort(query.transactionId)
        buffer.putShort(FLAGS_NXDOMAIN)
        buffer.putShort(1.toShort()) // QDCOUNT = 1
        buffer.putShort(0.toShort()) // ANCOUNT = 0
        buffer.putShort(0.toShort()) // NSCOUNT = 0
        buffer.putShort(0.toShort()) // ARCOUNT = 0
        buffer.put(query.questionSection)

        return response
    }

    /**
     * Constructs a synthetic IPv4/UDP packet containing a SafeSearch / Restricted Mode VIP response.
     * - For A queries (qType == 1): Returns NOERROR with ANCOUNT=1 pointing to 216.239.38.120 (TTL=300s).
     * - For AAAA (qType == 28), HTTPS (qType == 65), or others: Returns a standard NODATA response
     *   (NOERROR with ANCOUNT=0), preventing IPv6/SVCB bypass and forcing instant IPv4 fallback.
     */
    fun buildSafeSearchVipResponse(query: ParsedDnsQuery): ByteArray {
        val isTypeA = query.qType == 1
        val answerLen = if (isTypeA) 16 else 0
        val dnsHeaderAndQuestionLen = DNS_HEADER_LEN + query.questionSection.size + answerLen
        val udpTotalLen = UDP_HEADER_LEN + dnsHeaderAndQuestionLen
        val ipTotalLen = IP_HEADER_MIN_LEN + udpTotalLen

        val response = ByteArray(ipTotalLen)
        val buffer = ByteBuffer.wrap(response)

        writeHeaders(buffer, response, ipTotalLen, udpTotalLen, query.ipId, query.dstIp, query.srcIp, query.dstPort, query.srcPort)

        // 3. DNS Header (12 bytes)
        buffer.position(IP_HEADER_MIN_LEN + UDP_HEADER_LEN)
        buffer.putShort(query.transactionId)
        buffer.putShort(FLAGS_NOERROR)
        buffer.putShort(1.toShort()) // QDCOUNT = 1
        buffer.putShort(if (isTypeA) 1.toShort() else 0.toShort()) // ANCOUNT
        buffer.putShort(0.toShort()) // NSCOUNT = 0
        buffer.putShort(0.toShort()) // ARCOUNT = 0
        buffer.put(query.questionSection)

        if (isTypeA) {
            buffer.put(0xC0.toByte())
            buffer.put(0x0C.toByte()) // Compression pointer to QNAME at offset 12
            buffer.putShort(1.toShort()) // TYPE: A (1)
            buffer.putShort(1.toShort()) // CLASS: IN (1)
            buffer.putInt(300) // TTL: 300 seconds
            buffer.putShort(4.toShort()) // RDLENGTH: 4
            buffer.put(YOUTUBE_RESTRICTED_VIP_IPV4) // 216.239.38.120
        }

        return response
    }

    private fun writeHeaders(
        buffer: ByteBuffer,
        response: ByteArray,
        ipTotalLen: Int,
        udpTotalLen: Int,
        ipId: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int
    ) {
        // IPv4 Header (20 bytes)
        buffer.put(0x45.toByte())
        buffer.put(0x00.toByte())
        buffer.putShort(ipTotalLen.toShort())
        buffer.putShort(ipId.toShort())
        buffer.putShort(0x4000.toShort())
        buffer.put(64.toByte())
        buffer.put(17.toByte())
        buffer.putShort(0.toShort())
        buffer.put(srcIp)
        buffer.put(dstIp)
        val ipChecksum = calculateIpChecksum(response, 0, IP_HEADER_MIN_LEN)
        buffer.putShort(10, ipChecksum)

        // UDP Header (8 bytes)
        buffer.position(IP_HEADER_MIN_LEN)
        buffer.putShort(srcPort.toShort())
        buffer.putShort(dstPort.toShort())
        buffer.putShort(udpTotalLen.toShort())
        buffer.putShort(0.toShort())
    }

    /**
     * Wraps a raw upstream DNS response payload into an IPv4/UDP packet to send back to the client.
     */
    fun wrapUpstreamDnsResponse(
        clientIp: ByteArray,
        serverIp: ByteArray,
        clientPort: Int,
        serverPort: Int,
        dnsResponsePayload: ByteArray
    ): ByteArray {
        val udpTotalLen = UDP_HEADER_LEN + dnsResponsePayload.size
        val ipTotalLen = IP_HEADER_MIN_LEN + udpTotalLen

        val response = ByteArray(ipTotalLen)
        val buffer = ByteBuffer.wrap(response)

        writeHeaders(buffer, response, ipTotalLen, udpTotalLen, 0, serverIp, clientIp, serverPort, clientPort)

        // DNS Payload
        buffer.position(IP_HEADER_MIN_LEN + UDP_HEADER_LEN)
        buffer.put(dnsResponsePayload)

        return response
    }

    private fun calculateIpChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }

    /**
     * Inspects an incoming raw IPv6 packet and if it is a DNS query destined for UDP port 53,
     * immediately constructs an RFC-compliant IPv6 DNS REFUSED (RCODE 5) response packet.
     * This signals the client resolver to immediately fallback to IPv4 DNS without any timeout.
     */
    fun buildIpv6RefusedResponse(packet: ByteArray, length: Int): ByteArray? {
        if (length < 40 + UDP_HEADER_LEN + DNS_HEADER_LEN) return null

        val buffer = ByteBuffer.wrap(packet, 0, length)
        val firstByte = buffer.get(0).toInt() and 0xFF
        val version = firstByte ushr 4
        if (version != 6) return null

        val nextHeader = buffer.get(6).toInt() and 0xFF
        if (nextHeader != 17) return null // UDP

        val srcIp = ByteArray(16)
        val dstIp = ByteArray(16)
        buffer.position(8)
        buffer.get(srcIp)
        buffer.get(dstIp)

        // UDP header at offset 40
        buffer.position(40)
        val srcPort = buffer.short.toInt() and 0xFFFF
        val dstPort = buffer.short.toInt() and 0xFFFF
        val udpLength = buffer.short.toInt() and 0xFFFF

        if (dstPort != DNS_PORT || udpLength < UDP_HEADER_LEN + DNS_HEADER_LEN) return null

        val dnsOffset = 48
        val dnsLength = minOf(udpLength - UDP_HEADER_LEN, length - dnsOffset)
        if (dnsLength < DNS_HEADER_LEN) return null

        val txId = buffer.getShort(dnsOffset)
        val flags = buffer.getShort(dnsOffset + 2).toInt() and 0xFFFF
        val isQuery = (flags and 0x8000) == 0
        if (!isQuery) return null

        val qdCount = buffer.getShort(dnsOffset + 4)
        if ((qdCount.toInt() and 0xFFFF) != 1) return null

        // Parse exact QNAME to isolate question section without trailing EDNS0 / OPT RRs
        val questionStart = dnsOffset + DNS_HEADER_LEN
        val dnsEnd = dnsOffset + dnsLength
        buffer.position(questionStart)
        var isTerminated = false
        while (buffer.hasRemaining() && buffer.position() < dnsEnd) {
            val labelLen = buffer.get().toInt() and 0xFF
            if (labelLen == 0) {
                isTerminated = true
                break
            }
            if (labelLen > 63 || buffer.position() + labelLen > dnsEnd) return null
            buffer.position(buffer.position() + labelLen)
        }
        if (!isTerminated || buffer.position() + 4 > dnsEnd) return null
        buffer.position(buffer.position() + 4) // QTYPE (2 bytes) + QCLASS (2 bytes)
        val questionEnd = buffer.position()

        val questionLen = questionEnd - questionStart
        val questionSection = ByteArray(questionLen)
        System.arraycopy(packet, questionStart, questionSection, 0, questionLen)

        val dnsResponseLen = DNS_HEADER_LEN + questionLen
        val udpTotalLen = UDP_HEADER_LEN + dnsResponseLen
        val ipTotalLen = 40 + udpTotalLen

        val response = ByteArray(ipTotalLen)
        val respBuf = ByteBuffer.wrap(response)

        // 1. IPv6 Header (40 bytes)
        respBuf.put(0x60.toByte()) // Version 6, Traffic class 0
        respBuf.put(0x00.toByte())
        respBuf.putShort(0x0000.toShort()) // Flow label
        respBuf.putShort(udpTotalLen.toShort()) // Payload length
        respBuf.put(17.toByte()) // Next Header = UDP
        respBuf.put(64.toByte()) // Hop Limit
        respBuf.put(dstIp) // Swap src/dst
        respBuf.put(srcIp)

        // 2. UDP Header (8 bytes) at offset 40
        respBuf.putShort(dstPort.toShort()) // srcPort = 53
        respBuf.putShort(srcPort.toShort()) // dstPort
        respBuf.putShort(udpTotalLen.toShort())
        respBuf.putShort(0.toShort()) // Checksum placeholder

        // 3. DNS Header (12 bytes) + Question section
        respBuf.putShort(txId)
        respBuf.putShort(0x8185.toShort()) // QR=1, RD=1, RA=1, RCODE=5 (REFUSED)
        respBuf.putShort(qdCount)
        respBuf.putShort(0.toShort()) // ANCOUNT = 0
        respBuf.putShort(0.toShort()) // NSCOUNT = 0
        respBuf.putShort(0.toShort()) // ARCOUNT = 0
        respBuf.put(questionSection)

        // Calculate IPv6 UDP checksum
        val checksum = calculateIpv6UdpChecksum(dstIp, srcIp, udpTotalLen, response, 40, udpTotalLen)
        respBuf.putShort(46, checksum)

        return response
    }

    private fun calculateIpv6UdpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        udpLen: Int,
        udpPacket: ByteArray,
        udpOffset: Int,
        udpPacketLen: Int
    ): Short {
        var sum = 0L

        // Pseudo-header: Src IPv6 (16 bytes)
        for (i in 0 until 16 step 2) {
            sum += (((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF))
        }
        // Pseudo-header: Dst IPv6 (16 bytes)
        for (i in 0 until 16 step 2) {
            sum += (((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF))
        }
        // UDP length (32-bit in pseudo header)
        sum += (udpLen and 0xFFFF)
        // Next header = 17
        sum += 17

        // UDP packet data
        var i = udpOffset
        val end = udpOffset + udpPacketLen
        while (i < end - 1) {
            sum += (((udpPacket[i].toInt() and 0xFF) shl 8) or (udpPacket[i + 1].toInt() and 0xFF))
            i += 2
        }
        if (i < end) {
            sum += ((udpPacket[i].toInt() and 0xFF) shl 8)
        }

        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }

        var result = (sum.inv() and 0xFFFF).toInt()
        if (result == 0) result = 0xFFFF
        return result.toShort()
    }
}
