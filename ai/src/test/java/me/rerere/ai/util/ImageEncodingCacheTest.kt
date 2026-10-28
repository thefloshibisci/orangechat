package me.rerere.ai.util

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageEncodingCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `identical content reuses disk result but policy and content changes invalidate it`() {
        val cache = ImageEncodingCache(temporary.newFolder())
        val source = temporary.newFile().apply { writeText("original") }
        val duplicate = temporary.newFile().apply { writeBytes(source.readBytes()) }
        var writes = 0
        fun encode(file: File, policy: String = "v1") = cache.useEncoded(file, policy,
            { writes++; it.write("encoded-$writes".toByteArray()) }, { it.readText() })
        assertEquals("encoded-1", encode(source))
        assertEquals("encoded-1", encode(duplicate))
        assertEquals("encoded-2", encode(source, "v2"))
        // Same size and timestamp must not hide edits.
        val timestamp = source.lastModified()
        source.writeText("modified")
        source.setLastModified(timestamp)
        assertEquals("encoded-3", encode(source))
        assertEquals(3, writes)
    }

    @Test fun `partial encodings never become cache hits and temporary files are removed`() {
        val directory = temporary.newFolder()
        val cache = ImageEncodingCache(directory)
        val source = temporary.newFile().apply { writeText("source") }
        try {
            cache.useEncoded(source, "v1", { it.write(1); error("interrupted") }, { fail("not committed") })
            fail("failure expected")
        } catch (_: IllegalStateException) { }
        assertTrue(directory.listFiles()!!.isEmpty())
        assertEquals("complete", cache.useEncoded(source, "v1",
            { it.write("complete".toByteArray()) }, { it.readText() }))
    }

    @Test fun `entry and byte limits apply including oversized results`() {
        val directory = temporary.newFolder()
        val cache = ImageEncodingCache(directory, maxBytes = 6, maxEntries = 2)
        for (i in 0..3) {
            val source = temporary.newFile().apply { writeText("source-$i") }
            cache.useEncoded(source, "v1", { it.write(ByteArray(3)) }, { assertEquals(3, it.length()) })
            assertTrue(directory.listFiles()!!.size <= 2)
            assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 6)
        }
        val source = temporary.newFile().apply { writeText("oversized") }
        cache.useEncoded(source, "v1", { it.write(ByteArray(10)) }, { assertEquals(10, it.length()) })
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `interrupted process temporary files are discarded on next use`() {
        val directory = temporary.newFolder()
        File(directory, "encoding-interrupted.tmp").writeBytes(ByteArray(32))
        val source = temporary.newFile().apply { writeText("source") }
        ImageEncodingCache(directory).useEncoded(source, "v1",
            { it.write("complete".toByteArray()) }, { assertEquals("complete", it.readText()) })
        assertTrue(directory.listFiles()!!.all { it.name.endsWith(".image") })
    }

    @Test fun `concurrent requests serialize encoding and reuse the completed file`() {
        val cache = ImageEncodingCache(temporary.newFolder())
        val source = temporary.newFile().apply { writeText("same image") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<String> {
                cache.useEncoded(source, "v1", {
                    writes.incrementAndGet(); entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    it.write("complete".toByteArray())
                }, { it.readText() })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<String> {
                cache.useEncoded(source, "v1", {
                    writes.incrementAndGet(); it.write("duplicate".toByteArray())
                }, { it.readText() })
            }
            release.countDown()
            assertEquals("complete", first.get(5, TimeUnit.SECONDS))
            assertEquals("complete", second.get(5, TimeUnit.SECONDS))
            assertEquals(1, writes.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
