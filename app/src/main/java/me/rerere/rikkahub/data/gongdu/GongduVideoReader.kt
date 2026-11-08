package me.rerere.rikkahub.data.gongdu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.provider.supportsDirectAudio
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.transcribeAudioFile
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

data class GongduVideoSource(
    val url: String,
    val subtitles: String,
    val subtitleUrl: String = "",
    val subtitleLanguage: String = "",
    val subtitleOriginal: Boolean = false,
)

/** Real sampled frames, not a claim that the model received a video URL's pixels or sound. */
class GongduVideoReader(private val context: Context, private val files: FilesManager, private val settingsStore: SettingsStore) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).followRedirects(false).build()

    suspend fun read(note: GongduNote, model: Model, provider: ProviderSetting?,
        request: GongduVideoRequest = GongduVideoRequest(),
    ): List<UIMessagePart> = withContext(Dispatchers.IO) {
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
                                currentCoroutineContext().ensureActive()
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
                    bytes.toString("UTF-8")
                }
            }.onFailure { if (it is CancellationException) throw it }.getOrDefault("")
            retriever.setDataSource(temp.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("无法读取视频时长")
            require(duration in 1..180_000) { "首版支持 3 分钟以内的视频，请换一条较短视频" }
            val plan = gongduVideoPlan(duration, request)
            val subtitleMessage = GongduSubtitles.message(
                GongduSubtitleTrack(
                    text = source.subtitles, url = source.subtitleUrl,
                    language = source.subtitleLanguage, original = source.subtitleOriginal,
                ),
                subtitles, plan,
            )
            if (Build.VERSION.SDK_INT < 27) {
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                require(width in 1..1920 && height in 1..1920) { "当前系统暂不支持这条视频的分辨率" }
            }
            val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text(
                "[共度·小红书视频抽样] ${note.feed.title}\n视频总长 ${duration / 1000.0} 秒，本次范围 ${plan.startMs / 1000.0}～${plan.endMs / 1000.0} 秒。" +
                    "画面按时间排列在带秒数的拼图中，最多 24 帧；长视频概览会调大间隔，短暂动作可能未被采样。" +
                    "授权并提供 gongdu_read_video 工具时，可传 start_seconds/end_seconds（每段最多30秒）和 frame_interval_seconds（0.5～5秒）补看。超过24帧时会放宽间隔，需要更密请缩短窗口。不要声称看过未采样动作。" +
                    subtitleMessage,
                metadata = gongduVideoReference(note.feed),
            ))
            val columns = when { plan.times.size > 8 -> 4; plan.times.size > 4 -> 3; plan.times.size > 2 -> 2; else -> 1 }
            val tileWidth = if (columns == 1) 720 else 360
            val tileHeight = if (columns == 1) 460 else 250
            val board = Bitmap.createBitmap(columns * tileWidth, ((plan.times.size + columns - 1) / columns) * tileHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(board)
            canvas.drawColor(Color.rgb(22, 22, 22))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 21f }
            var extracted = 0
            try {
            for ((index, time) in plan.times.withIndex()) {
                currentCoroutineContext().ensureActive()
                val bitmap = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(time * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST, 720, 720)
                else retriever.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bitmap != null) {
                    try {
                        val x = (index % columns) * tileWidth.toFloat()
                        val y = (index / columns) * tileHeight.toFloat()
                        val scale = minOf((tileWidth - 8f) / bitmap.width, (tileHeight - 36f) / bitmap.height)
                        val width = bitmap.width * scale
                        val height = bitmap.height * scale
                        canvas.drawBitmap(bitmap, null, RectF(x + (tileWidth - width) / 2, y + 4, x + (tileWidth + width) / 2, y + 4 + height), null)
                        canvas.drawText("${time / 1000.0} s", x + 8, y + tileHeight - 10, paint)
                        extracted++
                    } finally { bitmap.recycle() }
                }
            }
            check(extracted > 0) { "没有成功提取画面，请刷新笔记或换一个视频" }
            val bytes = ByteArrayOutputStream().use { output -> board.compress(Bitmap.CompressFormat.JPEG, 85, output); output.toByteArray() }
            val entity = files.saveUploadFromBytes(bytes, "gongdu-frames-${plan.startMs}.jpg", "image/jpeg")
            parts += UIMessagePart.Text("成功抽取 $extracted/${plan.times.size} 帧，空白格表示该时间未提取成功；时间点：${plan.times.joinToString { "${it / 1000.0}s" }}")
            parts += UIMessagePart.Image(Uri.fromFile(files.getFile(entity)).toString())
            } finally { board.recycle() }
            val audio = File.createTempFile("gongdu-sound-", ".wav", context.cacheDir)
            try {
                if (GongduAudioReader.extract(temp, audio, plan.startMs, plan.endMs)) {
                    val live = settingsStore.settingsFlowRaw.first()
                    val direct = model.supportsDirectAudio(provider)
                    if (direct) {
                        val saved = files.saveUploadFromBytes(audio.readBytes(), "gongdu-audio-${plan.startMs}.wav", "audio/wav")
                        parts += UIMessagePart.Text("[原声音轨] 以下 WAV 对应 ${plan.startMs / 1000.0}～${plan.endMs / 1000.0} 秒，可结合画面分析对白、语气、音乐和环境音；无法确定的声音请明确说不确定。")
                        parts += UIMessagePart.Audio(Uri.fromFile(files.getFile(saved)).toString())
                    } else {
                        val provider = live.getSelectedASRProvider()
                        if (provider == null) parts += UIMessagePart.Text("[声音未提供] 当前模型未启用原生音频输入，且未配置语音识别；仅能看画面和已有字幕，不能推测音轨。可在模型设置开启音频输入，或在语音设置配置文件识别。")
                        else {
                            val transcript = transcribeAudioFile(http, provider, audio).ifBlank { "未识别到可用对白；不代表没有音乐或其他声音。" }
                            parts += UIMessagePart.Text("[音轨对白转写，范围 ${plan.startMs / 1000.0}～${plan.endMs / 1000.0} 秒]\n$transcript\n这里只提供识别出的文字，没有向你提供原声，不能据此判断音乐、音效、说话语气或声纹。")
                        }
                    }
                } else parts += UIMessagePart.Text("[无可用音轨] 这条视频没有可解码的声音，仅根据画面和可用字幕分析。")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { parts += UIMessagePart.Text("[声音处理未完成] 音轨解码或语音服务未成功；画面仍可分析。请检查语音识别设置或改用支持原声输入的模型，不能声称已经听过。") }
            finally { audio.delete() }
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
        fun sampleTimes(durationMs: Long): List<Long> = gongduVideoPlan(durationMs).times
        fun subtitleText(srt: String): String = GongduSubtitles.plainText(srt).take(GongduSubtitles.OVERVIEW_LIMIT)
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
            val subtitles = GongduSubtitles.select(video)
            return GongduVideoSource(
                stream.str("masterUrl"), subtitles.text, subtitles.url, subtitles.language, subtitles.original,
            )
        }
    }
}
