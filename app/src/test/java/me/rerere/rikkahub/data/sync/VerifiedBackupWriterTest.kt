package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VerifiedBackupWriterTest {
    @Test
    fun `valid archive is copied and verified byte for byte`() {
        val source = archive()
        try {
            val stored = ByteArrayOutputStream()
            val checksum = VerifiedBackupWriter.copyAndVerify(
                source,
                output = { stored },
                readBack = { ByteArrayInputStream(stored.toByteArray()) },
            )

            assertEquals(source.length(), checksum.size)
            assertEquals(32, checksum.sha256.size)
            assertArrayEquals(source.readBytes(), stored.toByteArray())
        } finally {
            source.delete()
        }
    }

    @Test
    fun `same length readback corruption is rejected`() {
        val source = archive()
        try {
            val stored = ByteArrayOutputStream()
            VerifiedBackupWriter.copyAndVerify(
                source,
                output = { stored },
                readBack = {
                    ByteArrayInputStream(
                        source.readBytes().also { bytes ->
                            bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0xff).toByte()
                        },
                    )
                },
            )
            fail("Modified readback should not match the source digest")
        } catch (_: IllegalStateException) {
            // Expected: the provider changed bytes without changing the reported length.
        } finally {
            source.delete()
        }
    }

    @Test
    fun `write failure closes destination and skips readback`() {
        val source = archive()
        var closed = false
        var readBack = false
        try {
            expectFailure {
                VerifiedBackupWriter.copyAndVerify(
                    source,
                    output = {
                        object : OutputStream() {
                            override fun write(value: Int) = throw IOException("disk full")
                            override fun close() {
                                closed = true
                            }
                        }
                    },
                    readBack = {
                        readBack = true
                        ByteArrayInputStream(byteArrayOf())
                    },
                )
            }
            assertTrue(closed)
            assertFalse(readBack)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `cancellation cannot publish an unchecked archive`() {
        val source = archive()
        var readBack = false
        try {
            expectFailure {
                VerifiedBackupWriter.copyAndVerify(
                    source,
                    output = { ByteArrayOutputStream() },
                    readBack = {
                        readBack = true
                        ByteArrayInputStream(byteArrayOf())
                    },
                    checkActive = { throw CancellationException("cancelled") },
                )
            }
            assertFalse(readBack)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `incomplete archive is rejected before writing`() {
        val source = archive(includeDatabase = false)
        var openedOutput = false
        try {
            expectFailure {
                VerifiedBackupWriter.copyAndVerify(
                    source,
                    output = {
                        openedOutput = true
                        ByteArrayOutputStream()
                    },
                    readBack = { ByteArrayInputStream(byteArrayOf()) },
                )
            }
            assertFalse(openedOutput)
        } finally {
            source.delete()
        }
    }

    private fun archive(includeDatabase: Boolean = true): java.io.File {
        val file = Files.createTempFile("verified-backup", ".zip").toFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            val names = buildList {
                add("settings.json")
                if (includeDatabase) {
                    add("rikka_hub.db")
                    add("rikka_hub-wal")
                }
            }
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("content for $name".toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        fail("Expected verification failure")
    }
}
