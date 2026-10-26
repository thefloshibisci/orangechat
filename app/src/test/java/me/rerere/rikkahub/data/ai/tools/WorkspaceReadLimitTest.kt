package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class WorkspaceReadLimitTest {
    @Test fun growingFileCannotExceedCap() {
        val buffer = ByteArrayOutputStream()
        val bounded = boundedWorkspaceOutput(buffer, 4)
        bounded.write(byteArrayOf(1, 2, 3))
        assertTrue(runCatching { bounded.write(byteArrayOf(4, 5)) }.isFailure)
        assertArrayEquals(byteArrayOf(1, 2, 3), buffer.toByteArray())
        bounded.write(4)
        assertTrue(runCatching { bounded.write(5) }.isFailure)
        assertEquals(4, buffer.size())
    }
    @Test fun exactLimitRetainsUnicodeBytes() {
        val bytes = "中文 👩🏽‍💻".toByteArray()
        val buffer = ByteArrayOutputStream()
        boundedWorkspaceOutput(buffer, bytes.size.toLong()).use { it.write(bytes) }
        assertArrayEquals(bytes, buffer.toByteArray())
    }
}
