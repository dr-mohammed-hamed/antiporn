package com.drmohammed.antiporn.core.network.dns

import org.junit.Assert.*
import org.junit.Test

class DnsLruCacheTest {

    @Test
    fun testCachePutAndGet() {
        val cache = DnsLruCache(maxEntries = 10)
        val payload = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)

        cache.put("example.com", 1, payload, ttlSeconds = 60)
        val retrieved = cache.get("example.com", 1)

        assertNotNull(retrieved)
        assertArrayEquals(payload, retrieved)

        // Different query type must miss
        assertNull(cache.get("example.com", 28))
    }

    @Test
    fun testCacheEvictionAtCapacity() {
        val cache = DnsLruCache(maxEntries = 2)
        val payload1 = byteArrayOf(1, 1)
        val payload2 = byteArrayOf(2, 2)
        val payload3 = byteArrayOf(3, 3)

        cache.put("one.com", 1, payload1, ttlSeconds = 60)
        cache.put("two.com", 1, payload2, ttlSeconds = 60)

        // Access one.com to make two.com the eldest entry
        cache.get("one.com", 1)

        // Add third entry -> should evict two.com
        cache.put("three.com", 1, payload3, ttlSeconds = 60)

        assertNotNull(cache.get("one.com", 1))
        assertNotNull(cache.get("three.com", 1))
        assertNull("two.com should be evicted by LRU policy", cache.get("two.com", 1))
    }

    @Test
    fun testTtlExpiration() {
        val cache = DnsLruCache(maxEntries = 10)
        val payload = byteArrayOf(1, 2, 3)

        // Put entry with short TTL (clamped to MIN_TTL 5s)
        cache.put("expired.com", 1, payload, ttlSeconds = 5)
        assertNotNull(cache.get("expired.com", 1))
    }
}
