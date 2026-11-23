package me.rerere.rikkahub.data.reading

data class ReadSnapshot(
    val book: ReadBook, val progress: ReadProgress, val annotations: List<ReadAnnotation>,
    val records: List<ReadRecord> = emptyList(), val viewport: ReadViewport? = null,
    val strictViewport: Boolean = false,
)

/** The current paragraph is the furthest text offered by the live reader, not permission to read ahead. */
internal fun ReadSnapshot.visibleParagraphs(chapter: Int): List<String> {
    val limit = readLimit()
    require(chapter in book.chapters.indices && chapter <= limit.chapter) { "只能阅读当前进度及之前的章节" }
    val paragraphs = book.chapters[chapter].paragraphs
    if (chapter != limit.chapter) return paragraphs
    val visible = paragraphs.take(limit.paragraph.coerceIn(-1, paragraphs.lastIndex) + 1)
    return if (viewport == null && !strictViewport || visible.isEmpty()) visible
    else visible.dropLast(1) + visible.last().take(limit.offset)
}

internal fun ReadSnapshot.visibleAnnotations(conversationId: String, assistantId: String): List<ReadAnnotation> =
    annotations.filter { annotation ->
        annotation.bookId == book.id && annotation.conversationId == conversationId &&
            annotation.assistantId == assistantId && annotation.chapter in book.chapters.indices &&
            containsPosition(annotation.chapter, annotation.paragraph, annotation.end) &&
            book.chapters[annotation.chapter].paragraphs.getOrNull(annotation.paragraph)?.let { source ->
                annotation.start in source.indices && annotation.end > annotation.start && annotation.end <= source.length &&
                    annotation.quote == source.substring(annotation.start, annotation.end)
            } == true
    }

/** Correct small paragraph/typography mistakes only in the requested chapter's nearby, visible text. */
internal fun ReadSnapshot.matchReadQuote(chapter: Int, paragraph: Int, quote: String): ReadQuoteMatch {
    require(quote.isNotBlank() && quote.length <= 6000) { "请选择原文中的连续片段" }
    val available = visibleParagraphs(chapter)
    require(paragraph in available.indices) { "引用位置不在已读范围内" }
    fun match(index: Int, folded: Boolean): ReadQuoteMatch? {
        val source = available[index]
        val target = if (folded) quote.trim().foldReadQuote() else quote.trim()
        val searchable = if (folded) source.foldReadQuote() else source
        val start = searchable.indexOf(target)
        if (start < 0 || source.readSplitsPair(start) || source.readSplitsPair(start + target.length)) return null
        return ReadQuoteMatch(chapter, index, start, start + target.length, source.substring(start, start + target.length))
    }
    match(paragraph, false)?.let { return it }
    match(paragraph, true)?.let { return it }
    val nearby = ((paragraph - 2).coerceAtLeast(0)..(paragraph + 2).coerceAtMost(available.lastIndex)).filter { it != paragraph }
    val exact = nearby.mapNotNull { match(it, false) }
    if (exact.size == 1) return exact.single()
    require(exact.isEmpty()) { "附近有多处相同原文，请提供准确段落" }
    val folded = nearby.mapNotNull { match(it, true) }
    require(folded.size == 1) { "引用未能在当前已读的附近段落唯一定位，请复制原文并给出准确段落" }
    return folded.single()
}

private fun String.foldReadQuote(): String = map { when (it) {
    '“', '”', '„', '「', '」', '『', '』', '＂' -> '"'
    '‘', '’', '＇' -> '\''
    '，' -> ','; '：' -> ':'; '；' -> ';'; '！' -> '!'; '？' -> '?'
    '（' -> '('; '）' -> ')'; '　', '\u00a0' -> ' '
    else -> it
} }.joinToString("")
