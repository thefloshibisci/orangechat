package me.rerere.rikkahub.data.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

class AtomicAttachmentCopyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `complete copy is published with no temporary files`() {
        val target = File(temporary.root, "attachment.txt")
        copyAttachmentAtomically(target) { "hello".byteInputStream() }
        assertEquals("hello", target.readText())
        assertEquals(listOf(target), temporary.root.listFiles()!!.toList())
    }

    @Test fun `missing source does not leave a zero byte target`() {
        val target = File(temporary.root, "missing.png")
        assertThrows(IllegalArgumentException::class.java) { copyAttachmentAtomically(target) { null } }
        assertFalse(target.exists())
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `empty source does not leave a zero byte target`() {
        val target = File(temporary.root, "empty.png")
        assertThrows(IllegalArgumentException::class.java) {
            copyAttachmentAtomically(target) { byteArrayOf().inputStream() }
        }
        assertFalse(target.exists())
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `interrupted read removes partial data and preserves an existing target`() {
        val target = temporary.newFile("existing.txt").apply { writeText("original") }
        var closed = false
        assertThrows(IOException::class.java) {
            copyAttachmentAtomically(target) {
                object : InputStream() {
                    override fun read(): Int = throw IOException("source disappeared")
                    override fun close() { closed = true }
                }
            }
        }
        assertTrue(closed)
        assertEquals("original", target.readText())
        assertEquals(listOf(target), temporary.root.listFiles()!!.toList())
    }
}
