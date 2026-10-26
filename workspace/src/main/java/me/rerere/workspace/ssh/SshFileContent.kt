package me.rerere.workspace.ssh

import me.rerere.workspace.WorkspaceTextPreview
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A growing remote file cannot bypass the stat-based memory limit. Interruption discards its SFTP channel. */
internal fun readRemoteBytes(limit: Long, read: (OutputStream) -> Unit): ByteArray {
    require(limit in 1..Int.MAX_VALUE.toLong()) { "Remote read limit is invalid" }
    val bytes = ByteArrayOutputStream()
    val bounded = object : OutputStream() {
        override fun write(value: Int) {
            if (bytes.size().toLong() >= limit) throw InterruptedIOException("Remote file exceeds read limit")
            bytes.write(value)
        }

        override fun write(value: ByteArray, offset: Int, length: Int) {
            if (length < 0 || bytes.size().toLong() + length > limit) {
                throw InterruptedIOException("Remote file exceeds read limit")
            }
            bytes.write(value, offset, length)
        }
    }
    read(bounded)
    return bytes.toByteArray()
}

internal fun remoteTextPreview(bytes: ByteArray, maxBytes: Int): WorkspaceTextPreview {
    require(maxBytes > 0) { "Preview byte limit must be positive" }
    val count = minOf(bytes.size, maxBytes)
    val truncated = bytes.size > count
    if ((0 until count).any { bytes[it] == 0.toByte() }) {
        return WorkspaceTextPreview("", isBinary = true, truncated = truncated)
    }
    val text = try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val output = CharBuffer.allocate(count)
        val result = decoder.decode(ByteBuffer.wrap(bytes, 0, count), output, !truncated)
        if (result.isError) result.throwException()
        if (!truncated) decoder.flush(output)
        output.flip().toString()
    } catch (_: CharacterCodingException) {
        return WorkspaceTextPreview("", isBinary = true, truncated = truncated)
    }
    return WorkspaceTextPreview(text, truncated = truncated)
}
