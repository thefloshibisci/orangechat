package me.rerere.ai.util

import java.io.IOException
import kotlinx.serialization.json.*
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class JsonRequestBodyTest {
    @Test fun unicodeEscapesAndNestedValuesMatchThePreviousWireFormat() {
        val payload = buildJsonObject {
            put("text", "橘瓣🍊\n\t\r\b\u0000\"\\ / \u2028")
            putJsonArray("messages") {
                add(JsonNull)
                add(true)
                add(123.25)
                add(buildJsonObject { put("tool", "lookup"); put("arguments", "{\"q\":\"中文\"}") })
            }
        }
        for (format in listOf(json, Json { prettyPrint = true })) {
            val expected = format.encodeToString(JsonElement.serializer(), payload).toByteArray(Charsets.UTF_8)
            val body = payload.toJsonRequestBody(format)
            repeat(3) {
                assertEquals(expected.size.toLong(), body.contentLength())
                val sink = Buffer()
                body.writeTo(sink)
                assertArrayEquals(expected, sink.readByteArray())
            }
            assertFalse(body.isOneShot())
            assertEquals("application/json; charset=utf-8", body.contentType().toString())
        }
    }

    @Test fun multiMegabyteHistoryIsWrittenInBoundedChunksAndDoesNotCloseTheSink() {
        // Reuse the text deliberately: the request body must not turn this tree into a giant copy.
        val text = "长对话🍊".repeat(4096)
        val payload = buildJsonObject {
            putJsonArray("messages") { repeat(128) { add(buildJsonObject { put("content", text) }) } }
        }
        val body = payload.toJsonRequestBody()
        var bytes = 0L
        var largestBufferedWrite = 0L
        var closed = false
        val destination = object : Sink {
            override fun write(source: Buffer, byteCount: Long) {
                largestBufferedWrite = maxOf(largestBufferedWrite, source.size)
                source.skip(byteCount)
                bytes += byteCount
            }
            override fun flush() = Unit
            override fun timeout() = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        body.writeTo(destination)
        destination.flush()
        assertTrue(bytes > 4 * 1024 * 1024)
        assertEquals(body.contentLength(), bytes)
        assertTrue("Buffered $largestBufferedWrite bytes at once", largestBufferedWrite <= 64 * 1024)
        assertFalse(closed)
    }

    @Test fun writeFailuresPropagateAndTheBodyCanBeRetried() {
        val payload = buildJsonObject { put("content", "retry".repeat(10000)) }
        val body = payload.toJsonRequestBody()
        val broken = object : Sink {
            override fun write(source: Buffer, byteCount: Long): Unit = throw IOException("disconnected")
            override fun flush() = Unit
            override fun close() = Unit
            override fun timeout() = Timeout.NONE
        }.buffer()
        assertThrows(IOException::class.java) { body.writeTo(broken); broken.flush() }
        val retried = Buffer()
        body.writeTo(retried)
        assertEquals(body.contentLength(), retried.size)
        assertEquals(payload, json.parseToJsonElement(retried.readUtf8()))
    }
}
