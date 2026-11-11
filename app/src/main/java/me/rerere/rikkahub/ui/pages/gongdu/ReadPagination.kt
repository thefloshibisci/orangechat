package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.data.reading.ReadChapter
import me.rerere.rikkahub.data.reading.ReadProgress

internal data class ReadPageFragment(val paragraph: Int, val start: Int, val end: Int, val image: Boolean = false)
internal data class ReadLayoutPage(val fragments: List<ReadPageFragment>) {
    fun position(chapter: Int) = fragments.first().let { ReadProgress(chapter, it.paragraph, it.start) }
}

/** Every fragment keeps original paragraph offsets. Pagination changes presentation, never source text. */
internal suspend fun paginateReadChapter(
    chapter: ReadChapter, measurer: TextMeasurer, style: TextStyle, width: Int, height: Int,
    paragraphSpacing: Int, imageHeight: Int,
): List<ReadLayoutPage> {
    val pageHeight = height.coerceAtLeast(1)
    val pages = mutableListOf<ReadLayoutPage>()
    var current = mutableListOf<ReadPageFragment>()
    var used = 0f
    fun flush() {
        if (current.isNotEmpty()) pages += ReadLayoutPage(current.toList())
        current = mutableListOf()
        used = 0f
    }
    chapter.paragraphs.forEachIndexed { paragraph, source ->
        currentCoroutineContext().ensureActive()
        if (chapter.images.containsKey(paragraph)) {
            val size = imageHeight.coerceAtMost(pageHeight)
            if (current.isNotEmpty() && used + paragraphSpacing + size > pageHeight) flush()
            if (current.isNotEmpty()) used += paragraphSpacing
            current += ReadPageFragment(paragraph, 0, source.length, true)
            used += size
        } else {
            val layout = measurer.measure(AnnotatedString(source), style, constraints = Constraints(maxWidth = width.coerceAtLeast(1)))
            var line = 0
            while (line < layout.lineCount) {
                currentCoroutineContext().ensureActive()
                val gap = if (current.isNotEmpty()) paragraphSpacing else 0
                val available = pageHeight - used - gap
                val top = layout.getLineTop(line)
                if (current.isNotEmpty() && layout.getLineBottom(line) - top > available) { flush(); continue }
                var last = line
                while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= available) last++
                val start = layout.getLineStart(line)
                if (start >= source.length) break
                var end = if (last == layout.lineCount - 1) source.length else layout.getLineStart(last + 1)
                // A final newline belongs to the source, but must not add a blank line to this page.
                var fragment = source.substring(start, end).trimEnd('\n', '\r')
                var measured = measurer.measure(AnnotatedString(fragment), style,
                    constraints = Constraints(maxWidth = width.coerceAtLeast(1)))
                while (last > line && measured.size.height > available) {
                    last--
                    end = layout.getLineStart(last + 1)
                    fragment = source.substring(start, end).trimEnd('\n', '\r')
                    measured = measurer.measure(AnnotatedString(fragment), style,
                        constraints = Constraints(maxWidth = width.coerceAtLeast(1)))
                }
                if (current.isNotEmpty() && measured.size.height > available) { flush(); continue }
                current += ReadPageFragment(paragraph, start, end)
                used += gap + measured.size.height
                line = last + 1
                if (line < layout.lineCount) flush()
            }
        }
    }
    flush()
    return pages
}

internal fun readPageForPosition(pages: List<ReadLayoutPage>, progress: ReadProgress): Int =
    pages.indexOfLast { page -> page.fragments.first().let {
        it.paragraph < progress.paragraph || it.paragraph == progress.paragraph && it.start <= progress.offset
    } }.coerceAtLeast(0)
