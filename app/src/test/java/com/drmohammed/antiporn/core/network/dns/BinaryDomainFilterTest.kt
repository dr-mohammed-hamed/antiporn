package com.drmohammed.antiporn.core.network.dns

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryDomainFilterTest {

    @Test
    fun testFnv1a64DeterministicHash() {
        val hash1 = BinaryDomainFilter.fnv1a64("pornhub.com")
        val hash2 = BinaryDomainFilter.fnv1a64("pornhub.com")
        assertEquals("FNV-1a must be strictly deterministic", hash1, hash2)

        val hashLower = BinaryDomainFilter.fnv1a64("PORNHUB.COM".lowercase())
        assertEquals("Normalized hash must match", hash1, hashLower)

        assertNotEquals(hash1, BinaryDomainFilter.fnv1a64("google.com"))
    }

    @Test
    fun testUnsignedComparisonBucketInvariants() {
        val hashA = 0x0011223344556677L
        val hashB = 0x8899AABBCCDDEEFFuL.toLong() // negative in signed Long, large in unsigned

        // Signed comparison would say hashB < hashA (-8.6e18 < 4.8e15)
        assertTrue("Signed Long puts negative first", hashB < hashA)

        // Unsigned comparison must say hashA < hashB
        assertTrue("Unsigned comparison must order 0x00... before 0x88...", java.lang.Long.compareUnsigned(hashA, hashB) < 0)

        // Bucket calculation verification
        val bucketA = ((hashA ushr 56) and 0xFF).toInt()
        val bucketB = ((hashB ushr 56) and 0xFF).toInt()
        assertEquals(0x00, bucketA)
        assertEquals(0x88, bucketB)
        assertTrue("Bucket A must precede Bucket B", bucketA < bucketB)
    }

    @Test
    fun testAssetBinaryFilterStructure() {
        val assetFile = File("src/main/assets/hisn_dns_filter.bin")
        if (!assetFile.exists()) {
            // Check relative to current working directory if running in module root
            val altFile = File("app/src/main/assets/hisn_dns_filter.bin")
            if (!altFile.exists()) return // Skip if file is not in local runner path
        }
        val targetFile = if (assetFile.exists()) assetFile else File("app/src/main/assets/hisn_dns_filter.bin")
        assertTrue("hisn_dns_filter.bin must exist on disk", targetFile.exists())
        assertTrue("File size must be at least header size (1040 bytes)", targetFile.length() >= 1040)

        FileInputStream(targetFile).use { fis ->
            val bytes = fis.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            // Magic: "HISN"
            val magic = ByteArray(4)
            buf.get(magic)
            assertArrayEquals("HISN".toByteArray(Charsets.US_ASCII), magic)

            // Version: 1
            val version = buf.int
            assertEquals(1, version)

            // Total entries > 0
            val totalEntries = buf.int
            assertTrue("Filter must contain compiled entries", totalEntries > 0)

            // Bucket table (257 entries)
            val bucketOffsets = IntArray(257)
            for (i in 0..256) {
                bucketOffsets[i] = buf.int
            }

            assertEquals(0, bucketOffsets[0])
            assertEquals(totalEntries, bucketOffsets[256])

            // Invariant: offsets must be monotonically non-decreasing
            for (i in 0 until 256) {
                assertTrue("Bucket offsets must be monotonic", bucketOffsets[i] <= bucketOffsets[i + 1])
            }
        }
    }
}
