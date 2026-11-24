package me.rerere.ai.util

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** These signed CDN URLs must be read by the client, not fetched again by a model provider. */
internal fun isXiaohongshuImageUrl(value: String): Boolean = value.toHttpUrlOrNull()?.host?.let {
    it == "xhscdn.com" || it.endsWith(".xhscdn.com")
} == true

internal class XiaohongshuImageCache(
    private val directory: File?,
    client: OkHttpClient = OkHttpClient(),
    private val maxImageBytes: Long = 8L * 1024 * 1024,
    private val maxCacheBytes: Long = 64L * 1024 * 1024,
    private val maxEntries: Int = 128,
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).build()
    private val failures = linkedMapOf<String, Long>()
    private val stateLock = Any()
    private val keyLocks = ConcurrentHashMap<String, Any>()

    /** Keep the original until encoding has finished; concurrent requests for the same URL share one download. */
    fun <T> useImage(value: String, consume: (File) -> T): T {
        val url = checkedUrl(value.toHttpUrlOrNull())
        val key = MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val lock = keyLocks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            if (directory != null) check(directory.isDirectory || directory.mkdirs()) { "Image cache unavailable" }
            val target = directory?.let { File(it, "$key.image") }
            try {
                if (target != null && target.isFile && target.length() in 1..maxImageBytes) {
                    try {
                        val result = consume(target)
                        target.setLastModified(System.currentTimeMillis())
                        return result
                    } catch (_: Exception) {
                        target.delete() // A corrupt disposable cache entry may be fetched again.
                    }
                }
                val now = System.nanoTime()
                synchronized(stateLock) {
                    if (failures[key]?.let { now - it < TimeUnit.SECONDS.toNanos(30) } == true) {
                        throw IOException("Xiaohongshu image unavailable; refresh the note to obtain a new image link")
                    }
                }
                val temporary = File.createTempFile("xhs-", ".tmp", directory)
                try {
                    download(url, temporary)
                    val result = consume(temporary) // Validate/decode before committing downloaded bytes.
                    if (target != null) check(temporary.renameTo(target)) { "Image cache commit failed" }
                    synchronized(stateLock) { failures.remove(key) }
                    return result
                } catch (error: Exception) {
                    synchronized(stateLock) {
                        failures[key] = System.nanoTime()
                        while (failures.size > maxEntries) failures.remove(failures.keys.first())
                    }
                    throw error
                } finally {
                    temporary.delete()
                }
            } finally {
                synchronized(stateLock) { trim() }
            }
        }
    }

    private fun checkedUrl(url: HttpUrl?): HttpUrl {
        require(url != null && isXiaohongshuImageUrl(url.toString()) &&
            url.toString().length <= 4096 && url.username.isEmpty() && url.password.isEmpty() &&
            url.port == (if (url.isHttps) 443 else 80)) { "Invalid Xiaohongshu image URL" }
        return url.newBuilder().scheme("https").port(443).fragment(null).build()
    }

    private fun download(initial: HttpUrl, target: File) {
        var url = initial
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        for (redirect in 0..3) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw IOException("Xiaohongshu image download timed out")
            val request = Request.Builder().url(url)
                .header("Referer", "https://www.xiaohongshu.com/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                .header("Accept", "image/webp,image/png,image/jpeg,image/gif").build()
            val call = http.newCall(request)
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            call.execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location") ?: throw IOException("Missing image redirect")
                    url = checkedUrl(url.resolve(location))
                } else {
                    if (!response.isSuccessful) throw IOException("Xiaohongshu image HTTP ${response.code}; refresh the note")
                    val body = response.body
                    if (body.contentLength() > maxImageBytes) throw IOException("Image exceeds download limit")
                    body.byteStream().use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > maxImageBytes) throw IOException("Image exceeds download limit")
                            output.write(buffer, 0, count)
                        }
                        if (total == 0L) throw IOException("Empty image response")
                    } }
                    return
                }
            }
        }
        throw IOException("Too many image redirects")
    }

    private fun trim() {
        val staleCutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5)
        directory?.listFiles { file ->
            file.name.startsWith("xhs-") && file.name.endsWith(".tmp") && file.lastModified() < staleCutoff
        }?.forEach { it.delete() }
        val files = directory?.listFiles { file -> file.name.endsWith(".image") }
            ?.sortedBy { it.lastModified() }.orEmpty()
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (bytes <= maxCacheBytes && count <= maxEntries) break
            val size = file.length()
            if (file.delete()) { bytes -= size; count-- }
        }
    }
}
