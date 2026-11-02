package com.drmohammed.antiporn.core.network

import com.drmohammed.antiporn.core.network.dns.DnsPacketParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class DnsPacketParserTest {

    @Test
    fun testDomainBlockingWithSubdomainsAndSmartExpansion() {
        val blockedRules = setOf("youtube.com", "tiktok.com")

        // 1. Direct matches
        assertTrue(DnsPacketParser.isDomainBlocked("youtube.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("www.youtube.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("m.youtube.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("music.youtube.com", blockedRules))

        // 2. Smart service expansions for youtube.com
        assertTrue(DnsPacketParser.isDomainBlocked("youtu.be", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("googlevideo.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("rr1---sn-4g5ednss.googlevideo.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("ytimg.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("i.ytimg.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("youtube-nocookie.com", blockedRules))

        // 3. TikTok matches
        assertTrue(DnsPacketParser.isDomainBlocked("tiktok.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("v16-webapp-prime.tiktok.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("tiktokcdn.com", blockedRules))

        // 4. Allowed domains that share root or keywords
        assertFalse(DnsPacketParser.isDomainBlocked("google.com", blockedRules))
        assertFalse(DnsPacketParser.isDomainBlocked("play.google.com", blockedRules))
        assertFalse(DnsPacketParser.isDomainBlocked("wikipedia.org", blockedRules))
        assertFalse(DnsPacketParser.isDomainBlocked("notyoutube.com", blockedRules))
    }

    @Test
    fun testDomainBlockingWithWildcardsAndPaths() {
        val rulesWithWildcardsAndPaths = setOf(
            "*.youtube.com",
            "https://facebook.com/watch?v=123",
            "tiktok.com/",
            "http://instagram.com:8080/feed",
            "*.twitter.com/"
        )

        assertTrue(DnsPacketParser.isDomainBlocked("youtube.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("m.youtube.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("m.youtube.com.", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("facebook.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("m.facebook.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("tiktok.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("v16.tiktok.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("instagram.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("twitter.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("api.twitter.com", rulesWithWildcardsAndPaths))

        assertFalse(DnsPacketParser.isDomainBlocked("google.com", rulesWithWildcardsAndPaths))
    }

    @Test
    fun testParseDnsQueryAndBuildNxDomainResponse() {
        // Construct a synthetic IPv4/UDP/DNS query for "youtube.com"
        val queryPacket = buildSyntheticDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1), // 10.255.255.1
            srcPort = 54321,
            dstPort = 53,
            txId = 0x1234.toShort(),
            domain = "youtube.com"
        )

        val parsed = DnsPacketParser.parseDnsQuery(queryPacket, queryPacket.size)
        assertNotNull(parsed)
        assertEquals("youtube.com", parsed!!.qName)
        assertEquals(54321, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertEquals(0x1234.toShort(), parsed.transactionId)
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), parsed.srcIp)
        assertArrayEquals(byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1), parsed.dstIp)

        // Build NXDOMAIN response
        val response = DnsPacketParser.buildNxDomainResponse(parsed)
        assertNotNull(response)
        assertTrue(response.size > 28 + 12)

        val buf = ByteBuffer.wrap(response)
        // Check IPv4 header
        assertEquals(4, (buf.get(0).toInt() and 0xFF) ushr 4)
        val respSrcIp = ByteArray(4)
        val respDstIp = ByteArray(4)
        buf.position(12)
        buf.get(respSrcIp)
        buf.get(respDstIp)
        assertArrayEquals(byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1), respSrcIp) // Swapped
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), respDstIp)     // Swapped

        // Check UDP header
        buf.position(20)
        val respSrcPort = buf.short.toInt() and 0xFFFF
        val respDstPort = buf.short.toInt() and 0xFFFF
        assertEquals(53, respSrcPort)     // Swapped
        assertEquals(54321, respDstPort)  // Swapped

        // Check DNS header
        buf.position(28)
        val respTxId = buf.short
        val respFlags = buf.short.toInt() and 0xFFFF
        val respQdCount = buf.short.toInt() and 0xFFFF
        val respAnCount = buf.short.toInt() and 0xFFFF

        assertEquals(0x1234.toShort(), respTxId)
        assertEquals(0x8183, respFlags) // Response, Opcode=0, AA=0, RD=1, RA=1, RCODE=3 (NXDOMAIN)
        assertEquals(1, respQdCount)
        assertEquals(0, respAnCount)
    }

    @Test
    fun testMultiQuestionQueryRejected() {
        val queryPacket = buildSyntheticDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1),
            srcPort = 54321,
            dstPort = 53,
            txId = 0x1234.toShort(),
            domain = "youtube.com"
        )
        // Set QDCOUNT to 2 (offset 32 in packet: 20 IP + 8 UDP + 4 txId+flags = 32)
        queryPacket[32] = 0x00
        queryPacket[33] = 0x02

        val parsed = DnsPacketParser.parseDnsQuery(queryPacket, queryPacket.size)
        assertNull("Multi-question DNS queries must be rejected", parsed)
    }

    @Test
    fun testNonZeroOpcodeRejected() {
        val queryPacket = buildSyntheticDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1),
            srcPort = 54321,
            dstPort = 53,
            txId = 0x1234.toShort(),
            domain = "youtube.com"
        )
        // Set Opcode = 1 (IQUERY/Status): flags at offset 30: 0x0800 ((1 << 11) = 0x0800)
        queryPacket[30] = 0x08
        queryPacket[31] = 0x00

        val parsed = DnsPacketParser.parseDnsQuery(queryPacket, queryPacket.size)
        assertNull("DNS queries with non-zero Opcode must be rejected", parsed)
    }

    @Test
    fun testUnterminatedQnameRejected() {
        val queryPacket = buildSyntheticDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1),
            srcPort = 54321,
            dstPort = 53,
            txId = 0x1234.toShort(),
            domain = "youtube.com"
        )
        // Overwrite the terminating 0 byte of QNAME with non-zero length
        // "youtube" (7) + "com" (3) -> 1+7+1+3 = 12 bytes after header (offset 40 + 12 = 52 is the 0x00)
        // Find 0x00 right before QTYPE (1)
        val termIndex = queryPacket.size - 5 // 4 bytes of QTYPE/QCLASS + 1 byte of term 0
        assertEquals(0x00.toByte(), queryPacket[termIndex])
        queryPacket[termIndex] = 0x05 // Corrupt terminator

        val parsed = DnsPacketParser.parseDnsQuery(queryPacket, queryPacket.size)
        assertNull("Unterminated QNAME must be rejected", parsed)
    }

    private fun buildSyntheticDnsQueryPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        txId: Short,
        domain: String
    ): ByteArray {
        val labels = domain.split(".")
        var qNameLen = 1 // trailing 0
        for (label in labels) {
            qNameLen += 1 + label.length
        }
        val dnsLen = 12 + qNameLen + 4 // Header + QNAME + QTYPE + QCLASS
        val udpLen = 8 + dnsLen
        val ipLen = 20 + udpLen

        val packet = ByteArray(ipLen)
        val buf = ByteBuffer.wrap(packet)

        // IP Header
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(ipLen.toShort())
        buf.putShort(100.toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(17.toByte())
        buf.putShort(0.toShort()) // checksum placeholder
        buf.put(srcIp)
        buf.put(dstIp)

        // UDP Header
        buf.position(20)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0.toShort())

        // DNS Header
        buf.position(28)
        buf.putShort(txId)
        buf.putShort(0x0100.toShort()) // Standard query with RD=1
        buf.putShort(1.toShort())      // QDCOUNT = 1
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())

        // Question: QNAME
        for (label in labels) {
            buf.put(label.length.toByte())
            buf.put(label.toByteArray(Charsets.US_ASCII))
        }
        buf.put(0x00.toByte())
        buf.putShort(1.toShort()) // QTYPE: A (IPv4)
        buf.putShort(1.toShort()) // QCLASS: IN (Internet)

        return packet
    }
}
