package me.rerere.workspace.ssh

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException

class SshContentTest {
    @Test fun boundedReadRetainsExactBytesIncludingAnExactLimit() {
        val expected = byteArrayOf(0, 1, 127, -1)
        assertArrayEquals(expected, readRemoteBytes(4) { it.write(expected) })
        assertArrayEquals(byteArrayOf(65, 66), readRemoteBytes(2) { it.write(65); it.write(66) })
    }

    @Test fun growingRemoteFileCannotBypassTheReadLimit() {
        val failure = runCatching {
            readRemoteBytes(4) { it.write(byteArrayOf(1, 2, 3)); it.write(byteArrayOf(4, 5)) }
        }.exceptionOrNull()
        assertTrue(failure is InterruptedIOException)
        assertTrue(failure!!.isInterruption())
        assertTrue(runCatching { readRemoteBytes(0) {} }.isFailure)
        assertTrue(runCatching { readRemoteBytes(Long.MAX_VALUE) {} }.isFailure)
    }

    @Test fun textPreviewPreservesUnicodeAndClassifiesBinaryWithoutLossyDecoding() {
        val content = "中文 👩🏽‍💻\nsecond line"
        val preview = remoteTextPreview(content.toByteArray(), 1024)
        assertEquals(content, preview.text)
        assertFalse(preview.truncated)
        assertFalse(preview.isBinary)
        assertTrue(remoteTextPreview(byteArrayOf(65, 0, 66), 1024).isBinary)
        assertTrue(remoteTextPreview(byteArrayOf(65, -1, 66), 1024).isBinary)
    }

    @Test fun partialUtf8TailIsOmittedButInvalidMiddleBytesRemainBinary() {
        val bytes = "A中文B".toByteArray()
        val preview = remoteTextPreview(bytes, 5)
        assertEquals("A中", preview.text)
        assertTrue(preview.truncated)
        assertFalse(preview.isBinary)
        val partialOnly = remoteTextPreview("中A".toByteArray(), 2)
        assertEquals("", partialOnly.text)
        assertTrue(partialOnly.truncated)
        assertFalse(partialOnly.isBinary)
        assertTrue(remoteTextPreview(byteArrayOf(-1, 65, 66), 2).isBinary)
    }

    @Test fun incompleteFinalCodePointIsBinaryWhenTheFileWasNotTruncated() {
        val partial = remoteTextPreview(byteArrayOf(65, -28, -72), 1024)
        assertTrue(partial.isBinary)
        assertFalse(partial.truncated)
        assertTrue(runCatching { remoteTextPreview(byteArrayOf(), 0) }.isFailure)
    }

    @Test fun streamCollectorCapsMemoryWhileDrainingTheWholeRemoteOutput() {
        val stream = ByteArrayInputStream("0123456789".repeat(1000).toByteArray())
        val collector = StreamCollector(stream, maxChars = 17)
        collector.join(3000)
        assertEquals("01234567890123456", collector.text())
        assertTrue(collector.truncated)
        assertEquals(0, stream.available())
    }

    @Test fun collectorUsesUtf8AndWriterClosesInputToSendEof() {
        val text = "中文 👩🏽‍💻"
        val collector = StreamCollector(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        collector.join(3000)
        assertEquals(text, collector.text())
        assertFalse(collector.truncated)
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        }
        val writer = StreamWriter(output, text.toByteArray())
        writer.join(3000)
        assertEquals(text, output.toString(Charsets.UTF_8.name()))
        assertTrue(closed)
    }
}
