package me.rerere.ai.util

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/** Private, disposable compressed images. No bitmaps or Base64 strings are retained in RAM. */
internal class ImageEncodingCache(
    private val directory: File,
    private val maxBytes: Long = 64L * 1024 * 1024,
    private val maxEntries: Int = 128,
) {
    init { require(maxBytes > 0 && maxEntries > 0) }

    /** Serialize decoding and consumption so concurrent requests cannot multiply bitmap peaks. */
    @Synchronized
    fun <T> useEncoded(
        source: File,
        policy: String,
        write: (OutputStream) -> Unit,
        consume: (File) -> T,
    ): T {
        check(directory.isDirectory || directory.mkdirs()) { "Image cache directory unavailable" }
        // A process killed during compression can leave an uncommitted temporary file.
        directory.listFiles { file -> file.name.startsWith("encoding-") && file.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(policy.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        source.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val key = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val target = File(directory, "$key.image")
        try {
            if (!target.isFile || target.length() == 0L) {
                val temporary = File.createTempFile("encoding-", ".tmp", directory)
                try {
                    temporary.outputStream().buffered().use(write)
                    check(temporary.length() > 0) { "Empty encoded image" }
                    check(temporary.renameTo(target)) { "Image cache commit failed" }
                } finally {
                    temporary.delete()
                }
            }
            target.setLastModified(System.currentTimeMillis())
            return consume(target)
        } finally {
            // Enforce bounds on both hits and misses, including an oversized single result.
            val files = directory.listFiles { file -> file.name.endsWith(".image") }
                ?.sortedBy { it.lastModified() }.orEmpty()
            var bytes = files.sumOf { it.length() }
            var count = files.size
            for (file in files) {
                if (bytes <= maxBytes && count <= maxEntries) break
                val size = file.length()
                if (file.delete()) { bytes -= size; count-- }
            }
        }
    }
}

/** One decode gate across all providers. The app configures an Android-managed cache directory. */
object RequestImageEncoding {
    private var cache: ImageEncodingCache? = null

    @Synchronized
    fun configure(directory: File) {
        cache = ImageEncodingCache(directory)
    }

    @Synchronized
    internal fun <T> withCache(block: (ImageEncodingCache?) -> T): T = block(cache)
}
