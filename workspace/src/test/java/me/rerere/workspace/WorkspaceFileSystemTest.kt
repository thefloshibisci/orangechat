package me.rerere.workspace

import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceFileSystemTest {
    private lateinit var root: java.io.File
    private lateinit var fileSystem: WorkspaceFileSystem

    @Before
    fun setUp() {
        root = createTempDirectory("workspace-preview").toFile()
        fileSystem = WorkspaceFileSystem()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun previewIsBoundedAndReportsTruncation() {
        val file = root.toPath().resolve("large.txt")
        file.writeText("0123456789".repeat(100))

        val preview = fileSystem.readTextPreview(root, "large.txt", maxBytes = 32)

        assertEquals("0123456789".repeat(3) + "01", preview.text)
        assertTrue(preview.truncated)
        assertTrue(!preview.isBinary)
    }

    @Test
    fun previewRejectsBinaryAndInvalidUtf8() {
        root.toPath().resolve("binary.bin").writeBytes(byteArrayOf(1, 2, 0, 3))
        root.toPath().resolve("invalid.bin").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))

        assertTrue(fileSystem.readTextPreview(root, "binary.bin").isBinary)
        assertTrue(fileSystem.readTextPreview(root, "invalid.bin").isBinary)
    }
}
