package me.rerere.rikkahub.data.gongdu

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.Locale

internal data class GongduSubtitleTrack(
    val text: String = "",
    val url: String = "",
    val language: String = "",
    val original: Boolean = false,
)

/** Platform captions are a separate source from the extracted soundtrack and ASR. */
internal object GongduSubtitles {
    const val OVERVIEW_LIMIT = 2400
    const val WINDOW_LIMIT = 1200
    private const val INPUT_LIMIT = 512 * 1024
    private val timeLine = Regex("^\\s*((?:\\d{1,3}:)?\\d{2}:\\d{2}[.,]\\d{3})\\s*-->\\s*((?:\\d{1,3}:)?\\d{2}:\\d{2}[.,]\\d{3})(?:\\s+.*)?$")
    private val tags = Regex("<[^>]+>")
    private data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** Select a single language group; never concatenate translated tracks together. */
    fun select(video: JsonObject): GongduSubtitleTrack {
        val originalLanguage = language(video.str("originalLanguage", "original_language", "language"))
        val choices = video.obj("subtitles")?.flatMap { (key, value) ->
            val rows = (value as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            rows.groupBy { row ->
                language(row.str("language", "lang", "languageCode", "language_code", "locale"))
                    .ifBlank { language(key) } to
                    (key.lowercase(Locale.ROOT) in setOf("original", "source") ||
                        listOf("original", "isOriginal", "is_original").any {
                            (row[it] as? JsonPrimitive)?.booleanOrNull == true
                        })
            }.map { (identity, group) ->
                GongduSubtitleTrack(
                    text = group.map { it.str("text", "content") }.filter(String::isNotBlank)
                        .joinToString("\n").take(INPUT_LIMIT),
                    url = group.firstNotNullOfOrNull { it.str("url").takeIf(String::isNotBlank) }.orEmpty(),
                    language = identity.first,
                    original = identity.second || (originalLanguage.isNotBlank() && identity.first == originalLanguage),
                )
            }
        }.orEmpty().filter { it.text.isNotBlank() || it.url.isNotBlank() }
        return choices.minByOrNull {
            when {
                it.language == "zh" -> 0
                it.original -> 1
                it.language.isBlank() -> 2
                else -> 3
            }
        } ?: GongduSubtitleTrack()
    }

    fun message(track: GongduSubtitleTrack, content: String, plan: GongduVideoPlan): String {
        if (content.isBlank()) return "\n[平台字幕] 未获取到可用字幕；声音是否提供请看后续音轨状态。"
        val languageLabel = when (track.language) {
            "zh" -> "中文"
            "en" -> "英文；平台未提供可用中文字幕"
            "ja" -> "日文；平台未提供可用中文字幕"
            "ko" -> "韩文；平台未提供可用中文字幕"
            "" -> "语言未标注"
            else -> "语言：${track.language}；平台未提供可用中文字幕"
        }
        val origin = if (track.original) "；平台标记为原语" else ""
        val label = "平台字幕（$languageLabel$origin；不代表已听过原声）"
        val cues = cues(content)
        if (!plan.overview && cues.isEmpty()) {
            return "\n[$label] 平台字幕没有可用时间戳，补看时不重复附全片字幕；请结合画面及本段音轨状态。"
        }
        val text = if (cues.isNotEmpty()) {
            cues.filter { plan.overview || (it.endMs > plan.startMs && it.startMs < plan.endMs) }
                .joinToString("\n") { it.text }
        } else plainText(content)
        val scope = if (plan.overview) "全片" else "${plan.startMs / 1000.0}～${plan.endMs / 1000.0}秒片段"
        if (text.isBlank()) return "\n[$scope$label] 该范围没有可用字幕条目；不代表没有声音。"
        val limit = if (plan.overview) OVERVIEW_LIMIT else WINDOW_LIMIT
        val note = if (text.length > limit) "\n[字幕已限长，未包含全部对白]" else ""
        return "\n$scope$label：\n${text.take(limit).trimEnd()}$note"
    }

    fun plainText(content: String): String {
        val timed = cues(content)
        return if (timed.isNotEmpty()) timed.joinToString("\n") { it.text } else
            content.take(INPUT_LIMIT).lineSequence().map { clean(it) }
                .filter { it.isNotBlank() && it != "WEBVTT" && !timeLine.matches(it) }
                .joinToString("\n")
    }

    private fun cues(content: String): List<Cue> {
        val lines = content.take(INPUT_LIMIT).replace("\r", "").removePrefix("\uFEFF").lines()
        val result = mutableListOf<Cue>()
        var index = 0
        while (index < lines.size) {
            val match = timeLine.matchEntire(lines[index].trim())
            if (match == null) { index++; continue }
            val start = timestamp(match.groupValues[1])
            val end = timestamp(match.groupValues[2])
            index++
            val body = mutableListOf<String>()
            while (index < lines.size && lines[index].isNotBlank() && !timeLine.matches(lines[index].trim())) {
                // A cue identifier immediately before the next timestamp is not dialogue.
                if (lines[index].trim().matches(Regex("\\d+")) &&
                    index + 1 < lines.size && timeLine.matches(lines[index + 1].trim())) break
                clean(lines[index]).takeIf(String::isNotBlank)?.let(body::add)
                index++
            }
            val text = body.joinToString("\n")
            if (start != null && end != null && end > start && text.isNotBlank()) {
                result += Cue(start, end, text)
            }
        }
        return result
    }

    private fun timestamp(value: String): Long? {
        val fields = value.replace(',', '.').split(':')
        val seconds = fields.last().split('.')
        val sec = seconds[0].toLongOrNull() ?: return null
        val millis = seconds[1].toLongOrNull() ?: return null
        val minutes = fields[fields.size - 2].toLongOrNull() ?: return null
        val hours = if (fields.size == 3) fields[0].toLongOrNull() ?: return null else 0L
        if (sec !in 0..59 || (fields.size == 3 && minutes !in 0..59)) return null
        return ((hours * 60 + minutes) * 60 + sec) * 1000 + millis
    }

    private fun clean(value: String): String = tags.replace(value.trim(), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    private fun language(value: String): String {
        val tag = value.trim().take(64).lowercase(Locale.ROOT).replace('_', '-')
        return when {
            tag in setOf("中文", "简体中文", "繁体中文", "chinese", "chi", "zho", "cmn", "cn", "chs", "cht") ||
                tag == "zh" || tag.startsWith("zh-") -> "zh"
            tag == "en" || tag.startsWith("en-") || tag in setOf("eng", "english", "英文", "英语") -> "en"
            tag in setOf("ja", "jpn", "japanese", "日语", "日文") || tag.startsWith("ja-") -> "ja"
            tag in setOf("ko", "kor", "korean", "韩语", "韩文") || tag.startsWith("ko-") -> "ko"
            tag.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")) -> tag
            else -> ""
        }
    }
}
