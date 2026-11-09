package com.drmohammed.antiporn.core.network

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class DnsFilteringValidatorTest {

    @Test
    fun testIsKnownFamilyPreset() {
        assertTrue(DnsFilteringValidator.isKnownFamilyPreset(DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST))
        assertTrue(DnsFilteringValidator.isKnownFamilyPreset(DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST))
        assertTrue(DnsFilteringValidator.isKnownFamilyPreset(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST))
        assertTrue(DnsFilteringValidator.isKnownFamilyPreset(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST))
        assertTrue(DnsFilteringValidator.isKnownFamilyPreset("FAMILY-FILTER-DNS.CLEANBROWSING.ORG"))
        assertTrue(DnsFilteringValidator.isKnownFamilyPreset("dns-family.adguard.com"))

        assertFalse(DnsFilteringValidator.isKnownFamilyPreset("p0.freedns.controld.com"))
        assertFalse(DnsFilteringValidator.isKnownFamilyPreset("custom.controld.com"))
        assertFalse(DnsFilteringValidator.isKnownFamilyPreset("adguard-dns.com"))
        assertFalse(DnsFilteringValidator.isKnownFamilyPreset("google.com"))
        assertFalse(DnsFilteringValidator.isKnownFamilyPreset("custom.dns.nextdns.io"))
        assertFalse(DnsFilteringValidator.isKnownFamilyPreset("1.1.1.1"))
    }

    @Test
    fun testBuildQuery_packetStructure() {
        val domain = "google.com"
        val txId = 0x1234
        val bytes = DnsFilteringValidator.DnsPacketHelper.buildQuery(domain, txId)

        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(0x1234.toShort(), buffer.short)       // ID
        assertEquals(0x0100.toShort(), buffer.short)       // Flags (RD=1)
        assertEquals(1.toShort(), buffer.short)            // QDCOUNT=1
        assertEquals(0.toShort(), buffer.short)            // ANCOUNT=0
        assertEquals(0.toShort(), buffer.short)            // NSCOUNT=0
        assertEquals(0.toShort(), buffer.short)            // ARCOUNT=0

        // QNAME: 6google3com0
        assertEquals(6.toByte(), buffer.get())
        val googleBytes = ByteArray(6)
        buffer.get(googleBytes)
        assertEquals("google", String(googleBytes))

        assertEquals(3.toByte(), buffer.get())
        val comBytes = ByteArray(3)
        buffer.get(comBytes)
        assertEquals("com", String(comBytes))

        assertEquals(0.toByte(), buffer.get())             // Terminator 0

        assertEquals(1.toShort(), buffer.short)            // QTYPE: A (1)
        assertEquals(1.toShort(), buffer.short)            // QCLASS: IN (1)
    }

    @Test
    fun testParseResponse_successWithARecord() {
        // Construct a synthetic DNS response packet for "google.com" -> 142.250.190.46
        val txId = 0x5678
        val queryBytes = DnsFilteringValidator.DnsPacketHelper.buildQuery("google.com", txId)

        // Modify header: Flags = 0x8180 (Response, Recursion Desired, Recursion Available, RCODE=0), ANCOUNT=1
        val qLen = queryBytes.size
        // Answer: Name pointer (0xC00C), Type A (1), Class IN (1), TTL (300), RDLength (4), IP (142.250.190.46)
        val answerRecord = byteArrayOf(
            0xC0.toByte(), 0x0C.toByte(),             // Name pointer to offset 12
            0x00, 0x01,                               // TYPE: A
            0x00, 0x01,                               // CLASS: IN
            0x00, 0x00, 0x01, 0x2C,                   // TTL: 300
            0x00, 0x04,                               // RDLENGTH: 4
            142.toByte(), 250.toByte(), 190.toByte(), 46.toByte() // IP
        )

        val responseBytes = ByteArray(qLen + answerRecord.size)
        System.arraycopy(queryBytes, 0, responseBytes, 0, qLen)
        System.arraycopy(answerRecord, 0, responseBytes, qLen, answerRecord.size)

        // Set response flags: 0x8180
        responseBytes[2] = 0x81.toByte()
        responseBytes[3] = 0x80.toByte()
        // Set ANCOUNT = 1
        responseBytes[6] = 0x00
        responseBytes[7] = 0x01

        val response = DnsFilteringValidator.DnsPacketHelper.parseResponse(responseBytes)
        assertEquals(0x5678, response.txId)
        assertEquals(0, response.rcode)
        assertEquals(1, response.ancount)
        assertEquals(1, response.ips.size)
        assertEquals("142.250.190.46", response.ips[0])
    }

    @Test
    fun testParseResponse_nxdomainBlocked() {
        // Construct synthetic NXDOMAIN response (Flags: RCODE=3)
        val responseBytes = ByteArray(12)
        responseBytes[0] = 0x12
        responseBytes[1] = 0x34
        responseBytes[2] = 0x81.toByte()
        responseBytes[3] = 0x83.toByte() // RCODE = 3 (NXDOMAIN)

        val response = DnsFilteringValidator.DnsPacketHelper.parseResponse(responseBytes)
        assertEquals(0x1234, response.txId)
        assertEquals(3, response.rcode)
        assertEquals(0, response.ancount)
        assertTrue(response.ips.isEmpty())
    }

    @Test
    fun testParseResponse_truncatedPacketReturnsErrorCode() {
        val responseBytes = ByteArray(12)
        responseBytes[0] = 0x12
        responseBytes[1] = 0x34
        // Flags: 0x8300 (QR=1, TC=1 -> 0x0200 bit set)
        responseBytes[2] = 0x83.toByte()
        responseBytes[3] = 0x00.toByte()

        val response = DnsFilteringValidator.DnsPacketHelper.parseResponse(responseBytes)
        assertEquals(0x1234, response.txId)
        assertEquals(-1, response.rcode)
        assertEquals(0, response.ancount)
        assertTrue(response.ips.isEmpty())
    }

    @Test
    fun testParseResponse_tooShortBufferReturnsErrorCode() {
        val shortBytes = ByteArray(8)
        val response = DnsFilteringValidator.DnsPacketHelper.parseResponse(shortBytes)
        assertEquals(0, response.txId)
        assertEquals(-1, response.rcode)
        assertEquals(0, response.ancount)
        assertTrue(response.ips.isEmpty())
    }
}
