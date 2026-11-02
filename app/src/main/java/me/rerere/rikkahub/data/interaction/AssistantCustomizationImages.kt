package me.rerere.rikkahub.data.interaction

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.isDecodableImage
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

private const val MAX_CUSTOMIZATION_IMAGE_BYTES = 12L * 1024 * 1024

/** Resolve real images, then let FilesManager import them into the normal backed-up upload store. */
internal class AssistantCustomizationImages(
    private val context: Context,
    private val saveImage: suspend (File, String) -> String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun importImage(source: String): String = withContext(Dispatchers.IO) {
        require(source.isNotBlank()) { "图片地址不能为空" }
        if (source.startsWith("https://") || source.startsWith("http://")) {
            val temp = File.createTempFile("assistant-style-", ".image", context.cacheDir)
            try {
                val request = Request.Builder().url(source).get().build()
                httpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "图片下载失败：HTTP ${response.code}" }
                    val body = checkNotNull(response.body) { "图片下载结果为空" }
                    require(body.contentLength() <= MAX_CUSTOMIZATION_IMAGE_BYTES) { "图片不能超过 12 MB" }
                    body.byteStream().use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_CUSTOMIZATION_IMAGE_BYTES) { "图片不能超过 12 MB" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                saveImage(temp, imageMime(temp))
            } finally {
                temp.delete()
            }
        } else {
            val file = when {
                source.startsWith("file:") -> runCatching { File(URI(source)) }.getOrNull()
                File(source).isAbsolute -> File(source)
                else -> null
            }?.canonicalFile ?: error("请使用图片链接或已生成、已上传图片的完整本地路径")
            val allowed = listOf(FileFolders.UPLOAD, "images").map { File(context.filesDir, it).canonicalFile }
            require(allowed.any { root -> file.path.startsWith(root.path + File.separator) }) {
                "本地图片必须来自聊天上传或绘图生成目录"
            }
            saveImage(file, imageMime(file))
        }
    }

    private fun imageMime(file: File): String {
        require(file.isFile && file.length() in 1..MAX_CUSTOMIZATION_IMAGE_BYTES) { "图片不存在、为空或超过 12 MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 && isDecodableImage(file)) {
            "文件不是可解码的图片，或图片尺寸超过 16384 像素"
        }
        return checkNotNull(bounds.outMimeType).also { require(it.startsWith("image/")) }
    }
}
