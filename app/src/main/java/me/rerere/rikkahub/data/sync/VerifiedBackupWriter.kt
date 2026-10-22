package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal object VerifiedBackupWriter {
    data class Checksum(
        val size: Long,
        val sha256: ByteArray,
    ) {
        fun matches(other: Checksum): Boolean =
            size == other.size && sha256.contentEquals(other.sha256)
    }

    fun validateArchive(file: File, checkActive: () -> Unit = {}) {
        check(file.isFile) { "备份文件不存在" }
        ZipFile(file).use { zip ->
            check(zip.getEntry("settings.json") != null && zip.getEntry("rikka_hub.db") != null) {
                "备份缺少设置或数据库"
            }
            val entries = zip.entries()
            val buffer = ByteArray(BUFFER_SIZE)
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val crc = CRC32()
                var entrySize = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        crc.update(buffer, 0, count)
                        entrySize += count
                    }
                }
                check(entry.size < 0 || entrySize == entry.size) { "备份压缩包大小校验失败" }
                check(entry.crc < 0 || crc.value == entry.crc) { "备份压缩包完整性校验失败" }
            }
        }
    }

    fun copyAndVerify(
        file: File,
        output: () -> OutputStream,
        readBack: () -> InputStream,
        checkActive: () -> Unit = {},
    ): Checksum {
        validateArchive(file, checkActive)
        val written = output().use { destination ->
            file.inputStream().use { source ->
                copyWithDigest(source, destination, checkActive)
            }
        }
        check(written.size == file.length()) { "备份写入长度不一致，保留原备份" }

        val stored = readBack().use { digest(it, checkActive) }
        check(written.matches(stored)) { "备份写入校验失败，保留原备份" }
        return written
    }

    private fun copyWithDigest(
        input: InputStream,
        output: OutputStream,
        checkActive: () -> Unit,
    ): Checksum {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            size += count
        }
        return Checksum(size, digest.digest())
    }

    private fun digest(input: InputStream, checkActive: () -> Unit): Checksum {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            size += count
        }
        return Checksum(size, digest.digest())
    }

    private const val BUFFER_SIZE = 64 * 1024
}
