package me.rerere.rikkahub.data.reading

import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import org.junit.Assert.*
import org.junit.Test

class ReadShareTest {
    private val book = ReadBook("a".repeat(64), "一本书", "作者", "txt", listOf(
        ReadChapter("第一章", listOf("原文里有“引号”、换行\n和小鱼🐟。", "第二段原文")),
    ))

    @Test fun sourceExcerptAndNoteSurviveExportAsOrdinaryText() {
        val text = readShareText(book, 0, 0, "“引号”、换行\n和小鱼🐟", "我的想法\n第二行")
        val restored = parseReadShare(text)!!
        assertEquals(book.id, restored.bookId)
        assertEquals(book.title, restored.title)
        assertEquals(book.author, restored.author)
        assertEquals("第一章", restored.chapterTitle)
        assertEquals("“引号”、换行\n和小鱼🐟", restored.quote)
        assertEquals("我的想法\n第二行", restored.note)
    }

    @Test fun generatorRejectsInventedQuotesAndInvalidAnchors() {
        assertTrue(runCatching { readShareText(book, 0, 0, "编造的原文") }.isFailure)
        assertTrue(runCatching { readShareText(book, 1, 0, "原文") }.isFailure)
        assertTrue(runCatching { readShareText(book, 0, -1, "原文") }.isFailure)
        assertTrue(runCatching { readShareText(book, 0, 0, " ") }.isFailure)
    }

    @Test fun malformedCardsRemainOrdinaryMessagesWithoutLocalNavigation() {
        val text = readShareText(book, 0, 1, "第二段原文")
        assertNull(parseReadShare("普通聊天"))
        assertNull(parseReadShare("[共度·书摘]\n{broken}"))
        assertNull(parseReadShare(text.replace(book.id, "../secret")))
        assertNull(parseReadShare(text.replace("\"chapter\":0", "\"chapter\":-1")))
        assertNull(parseReadShare(text.replace("\"quote\":\"第二段原文\"", "\"quote\":12")))
        assertNull(parseReadShare(text + " ".repeat(16000)))
    }

    @Test fun textBoundsRejectOversizedNotesInsteadOfSilentlyLosingThem() {
        assertTrue(runCatching { readShareText(book, 0, 1, "第二段原文", "想".repeat(4001)) }.isFailure)
        assertEquals("a", "a🐟b".readTake(2))
        assertEquals("a🐟", "a🐟b".readTake(3))
    }

    @Test fun unknownAndHeadlessCallersCannotDiscoverReadingTools() {
        assertTrue(readingTools(ToolInvocationContext.EMPTY).isEmpty())
        assertTrue(readingTools(ToolInvocationContext("assistant", "chat", isHeadless = true)).isEmpty())
    }
}
