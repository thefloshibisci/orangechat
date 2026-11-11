package me.rerere.rikkahub.data.listen

import kotlinx.serialization.json.*

private const val SHARE_HEADER = "[共度·歌曲]\n"

data class ListenShare(val song: ListenSong, val message: String, val lines: List<String>)

/** Kept in ordinary message text so exports, backups and model input retain the song. */
fun listenShareText(song: ListenSong, lines: List<String> = emptyList(), message: String = ""): String =
    SHARE_HEADER + buildJsonObject {
        put("id", song.id)
        put("title", song.title.take(160))
        put("artist", song.artist.take(160))
        put("album", song.album.take(160))
        put("cover", song.cover.take(2048))
        put("durationMs", song.durationMs)
        put("message", message.take(6000))
        put("lines", buildJsonArray { lines.take(8).forEach { add(it.take(300)) } })
    }.toString()

fun parseListenShare(text: String): ListenShare? {
    if (!text.startsWith(SHARE_HEADER) || text.length > 20000) return null
    return runCatching {
        val value = Json.parseToJsonElement(text.removePrefix(SHARE_HEADER)).jsonObject
        fun string(key: String, max: Int) = value[key]?.jsonPrimitive?.contentOrNull.orEmpty().take(max)
        val id = string("id", 32)
        require(id.matches(Regex("[0-9]{1,32}")))
        val title = string("title", 160)
        require(title.isNotBlank())
        ListenShare(
            song = ListenSong(id, title, string("artist", 160), string("album", 160),
                string("cover", 2048).takeIf { it.startsWith("https://") }.orEmpty(),
                value["durationMs"]?.jsonPrimitive?.longOrNull?.coerceIn(0, 86_400_000) ?: 0),
            message = string("message", 6000),
            lines = value["lines"]?.jsonArray.orEmpty().take(8).map { it.jsonPrimitive.content.take(300) },
        )
    }.getOrNull()
}
