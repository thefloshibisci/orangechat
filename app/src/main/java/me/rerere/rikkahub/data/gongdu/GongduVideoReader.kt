package me.rerere.rikkahub.data.gongdu

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

data class GongduVideoSource(val url: String, val subtitles: String, val subtitleUrl: String = "")

/** Real sampled frames, not a claim that the model received a video URL's pixels or sound. */
class GongduVideoReader(private val context: Context, private val files: FilesManager) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).followRedirects(false).build()

    suspend fun read(note: GongduNote): List<UIMessagePart> = withContext(Dispatchers.IO) {
        val source = source(note.raw)
        val temp = File.createTempFile("gongdu-video-", ".mp4", context.cacheDir)
        val retriever = MediaMetadataRetriever()
        try {
            var url = checkedUrl(source.url)
            var downloaded = false
            repeat(4) {
                if (!downloaded) http.newCall(Request.Builder().url(url).header("Referer", "https://www.xiaohongshu.com/").build()).execute().use { response ->
                    if (response.code in 300..399) {
                        url = checkedUrl(url.resolve(response.header("Location").orEmpty())?.toString().orEmpty())
                    } else {
                        check(response.isSuccessful) { "视频获取失败，请刷新笔记后重试（HTTP ${response.code}）" }
                        val body = checkNotNull(response.body)
                        require(body.contentLength() <= MAX_BYTES) { "视频超过 40 MB，请选择较短视频" }
                        body.byteStream().use { input -> temp.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var count = 0L
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                count += size
                                require(count <= MAX_BYTES) { "视频超过 40 MB，请选择较短视频" }
                                output.write(buffer, 0, size)
                            }
                        } }
                        downloaded = true
                    }
                }
            }
            check(downloaded && temp.length() > 0) { "视频地址跳转异常，请刷新笔记" }
            val subtitles = if (source.subtitles.isNotBlank()) source.subtitles else runCatching {
                if (source.subtitleUrl.isBlank()) "" else http.newCall(Request.Builder().url(checkedUrl(source.subtitleUrl)).build()).execute().use { response ->
                    check(response.isSuccessful)
                    val body = checkNotNull(response.body)
                    require(body.contentLength() <= 512 * 1024)
                    val bytes = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(bytes.size() + count <= 512 * 1024)
                            bytes.write(buffer, 0, count)
                        }
                    }
                    subtitleText(bytes.toString("UTF-8"))
                }
            }.getOrDefault("")
            retriever.setDataSource(temp.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("无法读取视频时长")
            require(duration in 1..180_000) { "首版支持 3 分钟以内的视频，请换一条较短视频" }
            if (Build.VERSION.SDK_INT < 27) {
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                require(width in 1..1920 && height in 1..1920) { "当前系统暂不支持这条视频的分辨率" }
            }
            val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text(
                "[共度·小红书视频抽样] ${note.feed.title}\n以下是按时间顺序提取的关键画面；没有提供原始声音，不能据此声称听过完整音轨。短暂动作可能未被采样。" +
                    subtitles.takeIf(String::isNotBlank)?.let { "\n平台字幕：\n$it" }.orEmpty() +
                    if (subtitles.isBlank()) "\n未获取到可用字幕，请仅根据画面分析。" else "",
            ))
            for (time in sampleTimes(duration)) {
                val bitmap = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(time * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST, 720, 720)
                else retriever.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bitmap != null) {
                    try {
                        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output); output.toByteArray() }
                        val entity = files.saveUploadFromBytes(bytes, "gongdu-frame-${time}.jpg", "image/jpeg")
                        parts += UIMessagePart.Text("画面时间：${time / 1000.0} 秒")
                        parts += UIMessagePart.Image(files.getFile(entity).toURI().toString())
                    } finally { bitmap.recycle() }
                }
            }
            check(parts.any { it is UIMessagePart.Image }) { "没有成功提取画面，请刷新笔记或换一个视频" }
            parts
        } finally {
            runCatching { retriever.release() }
            temp.delete()
        }
    }

    companion object {
        const val MAX_BYTES = 40L * 1024 * 1024
        fun checkedUrl(value: String): okhttp3.HttpUrl {
            val url = value.toHttpUrlOrNull() ?: error("视频地址无效，请刷新笔记")
            require(url.username.isEmpty() && url.password.isEmpty() &&
                (url.host == "xhscdn.com" || url.host.endsWith(".xhscdn.com"))) { "仅支持小红书公开媒体地址" }
            return if (url.scheme == "http") url.newBuilder().scheme("https").build() else url
        }
        fun sampleTimes(durationMs: Long): List<Long> = (0..5).map { (durationMs - 1).coerceAtLeast(0) * it / 5 }.distinct()
        fun subtitleText(srt: String): String = srt.lineSequence().map(String::trim)
            .filter { it.isNotBlank() && !it.matches(Regex("\\d+")) && !it.contains("-->") && it != "WEBVTT" }
            .map { it.replace(Regex("<[^>]+>"), "") }.joinToString("\n").take(12000)
        fun source(raw: JsonObject): GongduVideoSource {
            val root = (GongduCodec.unwrap(raw) as? JsonObject) ?: raw
            val video = (root.obj("note") ?: root).obj("video") ?: error("这篇笔记没有可读取的视频")
            val media = video.obj("media")
            val streams = media?.obj("stream")
            val h264 = (streams?.get("h264") as? JsonArray).orEmpty()
            val choices = (if (h264.isNotEmpty()) h264 else streams?.values?.flatMap { (it as? JsonArray).orEmpty() }.orEmpty())
                .mapNotNull { it as? JsonObject }.filter { it.str("masterUrl").isNotBlank() }
                .sortedBy { (it["size"] as? JsonPrimitive)?.longOrNull?.takeIf { size -> size > 0 } ?: Long.MAX_VALUE }
            val stream = choices.firstOrNull() ?: error("服务未返回可下载的视频地址，请刷新笔记")
            val subtitles = video.obj("subtitles")?.values?.filterIsInstance<JsonArray>()?.flatten()
                ?.mapNotNull { it as? JsonObject }?.map { it.str("text", "content") }?.filter(String::isNotBlank)
                ?.distinct()?.joinToString("\n").orEmpty().take(12000)
            val subtitleUrl = video.obj("subtitles")?.values?.filterIsInstance<JsonArray>()?.flatten()
                ?.mapNotNull { it as? JsonObject }?.firstNotNullOfOrNull { it.str("url").takeIf(String::isNotBlank) }.orEmpty()
            return GongduVideoSource(stream.str("masterUrl"), subtitles, subtitleUrl)
        }
    }
}
