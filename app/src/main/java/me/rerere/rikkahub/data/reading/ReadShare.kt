package me.rerere.rikkahub.data.reading

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val READ_SHARE_HEADER = "[共度·书摘]\n"
private const val READ_SHARE_LIMIT = 16000
private val readBookIdPattern = Regex("[a-f0-9]{64}")

data class ReadShare(
    val bookId: String,
    val title: String,
    val author: String,
    val chapter: Int,
    val chapterTitle: String,
    val paragraph: Int,
    val quote: String,
    val note: String,
)

/** The source text is retained in ordinary messages, exports and backups. No book access is granted by a card. */
fun readShareText(book: ReadBook, chapter: Int, paragraph: Int, quote: String, note: String = ""): String {
    require(readBookIdPattern.matches(book.id)) { "书籍编号无效" }
    val sourceChapter = book.chapters.getOrNull(chapter) ?: error("章节已不存在")
    val source = sourceChapter.paragraphs.getOrNull(paragraph) ?: error("段落已不存在")
    require(quote.isNotBlank() && quote.length <= 6000 && source.contains(quote)) { "书摘须来自当前段落，最多 6000 字" }
    require(note.length <= 4000) { "分享想法最多 4000 字" }
    return (READ_SHARE_HEADER + buildJsonObject {
        put("bookId", book.id)
        put("title", book.title.readTake(160))
        put("author", book.author.readTake(160))
        put("chapter", chapter)
        put("chapterTitle", sourceChapter.title.readTake(160))
        put("paragraph", paragraph)
        put("quote", quote)
        put("note", note)
    }).also { require(it.length <= READ_SHARE_LIMIT) { "分享内容过长，请少选一些文字" } }
}

fun parseReadShare(text: String): ReadShare? {
    if (!text.startsWith(READ_SHARE_HEADER) || text.length > READ_SHARE_LIMIT) return null
    return runCatching {
        val value = Json.parseToJsonElement(text.removePrefix(READ_SHARE_HEADER)).jsonObject
        fun string(key: String, max: Int): String {
            val field = value[key] as? JsonPrimitive ?: error("缺少书摘字段")
            require(field.isString)
            return checkNotNull(field.contentOrNull).also { require(it.length <= max) }
        }
        fun index(key: String): Int = (value[key] as? JsonPrimitive)?.intOrNull
            ?.also { require(it in 0..1_000_000) } ?: error("无效书摘位置")
        ReadShare(
            bookId = string("bookId", 64).also { require(readBookIdPattern.matches(it)) },
            title = string("title", 160).also { require(it.isNotBlank()) },
            author = string("author", 160),
            chapter = index("chapter"),
            chapterTitle = string("chapterTitle", 160),
            paragraph = index("paragraph"),
            quote = string("quote", 6000).also { require(it.isNotBlank()) },
            note = string("note", 4000),
        )
    }.getOrNull()
}

internal fun String.readTake(limit: Int): String {
    if (length <= limit) return this
    val end = if (limit > 0 && this[limit - 1].isHighSurrogate() && this[limit].isLowSurrogate()) limit - 1 else limit
    return substring(0, end.coerceAtLeast(0))
}
