package me.rerere.rikkahub.data.gongdu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

internal enum class GongduAudioResult(val message: String) {
    READY(""),
    NO_TRACK("[音轨未提供] 本次取得的视频文件未检测到音轨；可结合画面和可用的平台字幕理解，原视频的声音情况尚未确认。"),
    NO_SAMPLES("[音轨提取未完成] 已检测到音轨，但本次时间范围未提取到音频数据；可结合画面和可用的平台字幕理解。"),
    FAILED("[音轨处理失败] 视频备用版本获取或音轨解码未成功；可结合画面和可用的平台字幕理解，声音情况尚未确认。"),
}

/** Retry only media extraction. A caller may invoke paid ASR once, after READY. */
internal suspend fun recoverGongduVideoAudio(
    video: File,
    output: File,
    alternateUrls: List<String>,
    download: suspend (String, File) -> Unit,
    extract: suspend (File, File) -> GongduAudioResult,
): GongduAudioResult {
    suspend fun attempt(input: File): GongduAudioResult {
        currentCoroutineContext().ensureActive()
        output.writeBytes(byteArrayOf())
        return try {
            extract(input, output)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GongduAudioResult.FAILED
        }
    }

    val results = mutableListOf(attempt(video))
    if (results.last() == GongduAudioResult.READY) return GongduAudioResult.READY
    val candidates = alternateUrls.distinct().take(2)
    if (candidates.isNotEmpty()) {
        val alternate = File.createTempFile("gongdu-audio-source-", ".mp4", video.parentFile)
        try {
            for (url in candidates) {
                currentCoroutineContext().ensureActive()
                val result = try {
                    alternate.writeBytes(byteArrayOf())
                    download(url, alternate)
                    attempt(alternate)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    GongduAudioResult.FAILED
                }
                if (result == GongduAudioResult.READY) return result
                results += result
            }
        } finally {
            alternate.delete()
        }
    }
    output.writeBytes(byteArrayOf())
    return when {
        GongduAudioResult.FAILED in results -> GongduAudioResult.FAILED
        GongduAudioResult.NO_SAMPLES in results -> GongduAudioResult.NO_SAMPLES
        else -> GongduAudioResult.NO_TRACK
    }
}
