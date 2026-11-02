package me.rerere.tts.model

import kotlinx.serialization.Serializable

/** Per-utterance hints. Providers must opt in to each hint; these are never arbitrary API parameters. */
@Serializable
data class SpeechStyle(
    val emotion: String? = null,
    val speed: Float? = null,
    val pitch: Int? = null,
    val volume: Float? = null,
)

data class SpeechSegment(val text: String, val style: SpeechStyle = SpeechStyle())

/** Shared by the tts tool, call streaming, transcripts and the synthesis queue. */
object SpeechMarkup {
    const val SOURCE_METADATA = "orangechat_speech_source"
    private val block = Regex("\\[\\[voice\\b([^\\]]*)]]([\\s\\S]*?)(?:\\[\\[/voice]]|$)")
    private val wrapper = Regex("\\[\\[(?:/?voice)\\b[^\\]]*]]")
    private val attribute = Regex("(emotion|speed|pitch|volume)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s]+))")
    private val pause = Regex("<#([^>]*?)#>")
    val interjections = setOf(
        "laughs", "chuckle", "coughs", "clear-throat", "groans", "breath", "pant", "inhale", "exhale",
        "gasps", "sniffs", "sighs", "snorts", "burps", "lip-smacking", "humming", "hissing", "emm", "sneezes",
    )
    private val interjection = Regex("\\((?:${interjections.joinToString("|")})\\)", RegexOption.IGNORE_CASE)

    private fun removeUnfinishedControls(text: String): String {
        var result = text
        val start = result.lastIndexOf("[[")
        if (start >= 0 && result.indexOf("]]", start) < 0) {
            val tail = result.substring(start)
            if (tail.startsWith("[[voice") || tail.startsWith("[[/voice") ||
                "[[voice".startsWith(tail) || "[[/voice".startsWith(tail)) result = result.take(start)
        }
        val pauseStart = result.lastIndexOf("<#")
        if (pauseStart >= 0 && result.indexOf("#>", pauseStart) < 0) result = result.take(pauseStart)
        return result
    }

    fun parse(text: String): List<SpeechSegment> = buildList {
        var position = 0
        block.findAll(text).forEach { match ->
            text.substring(position, match.range.first).takeIf(String::isNotBlank)?.let { add(SpeechSegment(it)) }
            val attrs = attribute.findAll(match.groupValues[1]).associate {
                it.groupValues[1] to it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
            }
            val style = SpeechStyle(
                emotion = attrs["emotion"]?.lowercase(),
                speed = attrs["speed"]?.toFloatOrNull()?.takeIf(Float::isFinite)?.coerceIn(0.5f, 2f),
                pitch = attrs["pitch"]?.toIntOrNull()?.coerceIn(-12, 12),
                volume = attrs["volume"]?.toFloatOrNull()?.takeIf(Float::isFinite)?.coerceIn(0.01f, 10f),
            )
            match.groupValues[2].takeIf(String::isNotBlank)?.let { add(SpeechSegment(it, style)) }
            position = match.range.last + 1
        }
        text.substring(position).takeIf(String::isNotBlank)?.let { add(SpeechSegment(it)) }
    }

    /** Keep the words, hide synthesis controls (including an unfinished streaming header). */
    fun transcript(text: String): String = removeUnfinishedControls(text).replace(wrapper, "")
        .replace(pause, "")
        .replace(interjection, "")

    fun hasControls(text: String): Boolean = text.contains("[[voice") || text.contains("[[/voice]]") ||
        pause.containsMatchIn(text) || interjection.containsMatchIn(text)

    /** Preserve control tokens across Markdown/HTML stripping without exposing them to that parser. */
    fun prepare(text: String, plainTextTransform: (String) -> String): String = parse(text).joinToString("\n") { segment ->
        val tokens = mutableListOf<String>()
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val protected = segment.text.replace(Regex("<#.*?#>|${interjection.pattern}")) {
            tokens.add(it.value)
            "SPEECH${nonce}TOKEN${tokens.lastIndex}END"
        }
        var words = plainTextTransform(protected)
        tokens.forEachIndexed { index, token -> words = words.replace("SPEECH${nonce}TOKEN${index}END", token) }
        if (segment.style == SpeechStyle()) words else buildString {
            append("[[voice")
            segment.style.emotion?.let { append(" emotion=$it") }
            segment.style.speed?.let { append(" speed=$it") }
            segment.style.pitch?.let { append(" pitch=$it") }
            segment.style.volume?.let { append(" volume=$it") }
            append("]]$words[[/voice]]")
        }
    }

    /** Strip MiniMax-only text syntax before switching to an unverified model/provider. */
    fun plainText(text: String): String = transcript(text)

    fun miniMaxText(text: String, allowInterjections: Boolean): String {
        val withoutWrappers = removeUnfinishedControls(text).replace(wrapper, "")
        val sounds = withoutWrappers.replace(interjection) {
            if (allowInterjections) it.value.lowercase() else ""
        }
        // Pause markers must be between speakable text and cannot be consecutive.
        var previousEnd = 0
        var haveSpeech = false
        return buildString {
            pause.findAll(sounds).forEach { match ->
                val before = sounds.substring(previousEnd, match.range.first)
                append(before)
                if (before.replace(interjection, "").any { it.isLetterOrDigit() }) haveSpeech = true
                val value = match.groupValues[1].toDoubleOrNull()
                val after = sounds.substring(match.range.last + 1).replace(pause, "").replace(interjection, "")
                val valid = match.groupValues[1].matches(Regex("\\d{1,2}(?:\\.\\d{1,2})?")) &&
                    value != null && value in 0.01..99.99
                if (valid && haveSpeech && after.any { it.isLetterOrDigit() }) {
                    append(match.value)
                    haveSpeech = false
                }
                previousEnd = match.range.last + 1
            }
            append(sounds.substring(previousEnd))
        }
    }

    /** Raw prefix ready to enqueue. Never split headers, decimal pause tokens or voice blocks. */
    fun readyPrefixLength(text: String): Int {
        var i = 0
        var ready = 0
        while (i < text.length) {
            when {
                text.startsWith("[[voice", i) -> {
                    val end = text.indexOf("[[/voice]]", i)
                    if (end < 0) return ready
                    i = end + "[[/voice]]".length
                    ready = i
                }
                text.startsWith("<#", i) -> {
                    val end = text.indexOf("#>", i)
                    if (end < 0) return ready
                    i = end + 2
                }
                // A partial voice header is retained until another delta arrives.
                text.startsWith("[[", i) && "[[voice".startsWith(text.substring(i)) -> return ready
                else -> {
                    val char = text[i]
                    val decimal = char == '.' && i > 0 && text[i - 1].isDigit() &&
                        (i + 1 == text.length || text[i + 1].isDigit())
                    if (char in "。？！?!\n" || char == '.' && !decimal) ready = i + 1
                    i++
                }
            }
        }
        return ready
    }
}
