package me.rerere.rikkahub.data.reading

/** Ephemeral layout coordinates measured by the native reader, never restored from a backup. */
data class ReadVisibleFragment(val paragraph: Int, val start: Int, val end: Int)

data class ReadViewport(
    val chapter: Int,
    val pageIndex: Int,
    val pageCount: Int,
    val fragments: List<ReadVisibleFragment>,
)

internal data class ReadViewportBinding(
    val bookId: String,
    val conversationId: String,
    val progress: ReadProgress,
    val viewport: ReadViewport,
    val settings: ReadSettings,
)

internal fun ReadViewport.validate(book: ReadBook, anchor: ReadProgress) {
    require(chapter == anchor.chapter && chapter in book.chapters.indices && pageCount in 1..100_000 &&
        pageIndex in 0 until pageCount && fragments.size in 1..200) { "页面坐标已失效，请重新排版" }
    var previous: ReadVisibleFragment? = null
    for (part in fragments) {
        val text = book.chapters[chapter].paragraphs.getOrNull(part.paragraph) ?: error("页面段落已失效")
        require(part.start >= 0 && part.end > part.start && part.end <= text.length &&
            !text.readSplitsPair(part.start) && !text.readSplitsPair(part.end)) { "页面字符坐标已失效" }
        previous?.let {
            require((part.paragraph == it.paragraph && part.start == it.end) ||
                (part.paragraph == it.paragraph + 1 && part.start == 0 && it.end == book.chapters[chapter].paragraphs[it.paragraph].length)) {
                "页面片段不连续"
            }
        }
        previous = part
    }
    require(fragments.any { it.paragraph == anchor.paragraph && anchor.offset in it.start..it.end }) { "页面与当前书签不一致" }
}

internal fun ReadSnapshot.readLimit(): ReadProgress = viewport?.let { view ->
    view.fragments.lastOrNull()?.let { ReadProgress(view.chapter, it.paragraph, it.end) }
} ?: progress

internal fun ReadSnapshot.containsPosition(chapter: Int, paragraph: Int, end: Int): Boolean {
    val limit = readLimit()
    return chapter < limit.chapter || chapter == limit.chapter &&
        (paragraph < limit.paragraph || paragraph == limit.paragraph && (viewport == null && !strictViewport || end <= limit.offset))
}
