package me.rerere.rikkahub.data.gongdu

import me.rerere.rikkahub.data.listen.parseListenShare
import me.rerere.rikkahub.data.reading.parseReadShare

private val shareHeaders = listOf("[共度·歌曲]\n", "[共度·书摘]\n")
private const val MAX_CARD_LENGTH = 20_000

/** Only complete, validated cards become draft chips. Other text stays editable verbatim. */
fun gongduShareSummary(text: String): String? =
    parseListenShare(text)?.let { "歌曲 · ${it.song.title}" }
        ?: parseReadShare(text)?.let { "书摘 · ${it.title}" }

/**
 * Presentation only: retain every source character while separating valid embedded cards.
 * Match a bounded JSON object, respecting escaped quotes and braces inside strings. Parsing
 * still goes through the original strict validators; malformed or oversized cards stay text.
 */
fun splitGongduShareText(text: String): List<String> {
    val result = mutableListOf<String>()
    var plainStart = 0
    var searchStart = 0
    while (searchStart < text.length) {
        val start = text.indexOf('[', searchStart)
        if (start < 0) break
        val header = shareHeaders.firstOrNull { text.startsWith(it, start) }
        if (header == null) {
            searchStart = start + 1
            continue
        }
        val (end, nextSearch) = scanJsonObject(text, start + header.length, start)
        if (end != null && gongduShareSummary(text.substring(start, end)) != null) {
            if (start > plainStart) result.add(text.substring(plainStart, start))
            result.add(text.substring(start, end))
            plainStart = end
            searchStart = end
        } else {
            // Invalid candidates remain ordinary text. Never rescan the same JSON or a
            // long unterminated object for every apparent header inside it.
            searchStart = nextSearch
        }
    }
    if (plainStart < text.length) result.add(text.substring(plainStart))
    return result.ifEmpty { listOf(text) }
}

private fun scanJsonObject(text: String, jsonStart: Int, cardStart: Int): Pair<Int?, Int> {
    val limit = minOf(text.length, cardStart + MAX_CARD_LENGTH)
    var index = jsonStart
    while (index < limit && text[index] in " \t\r\n") index++
    if (index >= limit || text[index] != '{') return null to index
    var depth = 0
    var quoted = false
    var escaped = false
    while (index < limit) {
        val char = text[index++]
        if (quoted) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> quoted = false
            }
        } else {
            when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return index to index
            }
        }
    }
    return null to index
}
