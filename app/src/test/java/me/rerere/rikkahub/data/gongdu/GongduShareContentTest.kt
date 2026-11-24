package me.rerere.rikkahub.data.gongdu

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.listen.ListenSong
import me.rerere.rikkahub.data.listen.listenShareText
import me.rerere.rikkahub.data.reading.ReadBook
import me.rerere.rikkahub.data.reading.ReadChapter
import me.rerere.rikkahub.data.reading.readShareText
import org.junit.Assert.*
import org.junit.Test

class GongduShareContentTest {
    private val song = listenShareText(ListenSong("123", "测试歌曲", "歌手"))
    private val book = ReadBook("a".repeat(64), "测试书", "作者", "txt",
        listOf(ReadChapter("第一章", listOf("引号\"、花括号{}、反斜线\\和小鱼🐟。"))))
    private val excerpt = readShareText(book, 0, 0, book.chapters[0].paragraphs[0])

    @Test fun textBeforeAfterAndBetweenMultipleCardsIsPreservedExactly() {
        val text = "先听这首$song\n\n再看书摘：$excerpt\n最后一句"
        val parts = splitGongduShareText(text)
        assertEquals(listOf("先听这首", song, "\n\n再看书摘：", excerpt, "\n最后一句"), parts)
        assertEquals(text, parts.joinToString(""))
        assertEquals("歌曲 · 测试歌曲", gongduShareSummary(parts[1]))
        assertEquals("书摘 · 测试书", gongduShareSummary(parts[3]))
    }

    @Test fun adjacentAndRepeatedCardsKeepTheirOwnBoundaries() {
        assertEquals(listOf(song, song, excerpt), splitGongduShareText(song + song + excerpt))
        assertEquals(listOf(song), splitGongduShareText(song))
    }

    @Test fun jsonStringsCanContainEscapedQuotesBracesAndApparentCardHeaders() {
        val card = listenShareText(ListenSong("123", "歌", "人"),
            message = "引号\"\\和{}以及[共度·书摘]\n{\"quote\":\"这不是卡\"}")
        val text = "前\n$card\n后"
        assertEquals(listOf("前\n", card, "\n后"), splitGongduShareText(text))
        assertEquals(listOf(excerpt), splitGongduShareText(excerpt))
    }

    @Test fun malformedInvalidAndOversizedCardsRemainOrdinaryTextWithoutLoss() {
        val samples = listOf("[共度·歌曲]\n{broken}", "[共度·书摘]\n{\"quote\":12}",
            "[共度·歌曲]\n{\"id\":\"../secret\",\"title\":\"歌\"}",
            "[共度·歌曲]\n{\"id\":\"123\",\"title\":\"" + "x".repeat(20_000) + "\"}",
            "普通聊天", "", "[共度·歌曲]\n{\"title\":\"未闭合")
        for (text in samples) {
            assertEquals(listOf(text), splitGongduShareText(text))
            assertNull(gongduShareSummary(text))
        }
        val text = samples.first() + "\n" + song
        assertEquals(listOf(samples.first() + "\n", song), splitGongduShareText(text))
    }

    @Test fun existingTextPartsAndCardPayloadsSurviveMessageSerializationRoundTrip() {
        val image = UIMessagePart.Image("file:///upload/test.png")
        val message = UIMessage.user("").copy(parts = listOf(UIMessagePart.Text("附言"),
            UIMessagePart.Text(song), image, UIMessagePart.Text(excerpt)))
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(message))
        assertEquals(message.parts, restored.parts)
        assertEquals(listOf(song), splitGongduShareText((restored.parts[1] as UIMessagePart.Text).text))
        assertEquals(listOf(excerpt), splitGongduShareText((restored.parts[3] as UIMessagePart.Text).text))
    }

    @Test(timeout = 5000) fun manyRepeatedInvalidHeadersDoNotRescanTheRemainingMessage() {
        val text = "[共度·歌曲]\n{".repeat(30_000)
        assertEquals(text, splitGongduShareText(text).joinToString(""))
        val repeated = (song + "\n").repeat(1000)
        val segments = splitGongduShareText(repeated)
        assertEquals(repeated, segments.joinToString(""))
        assertEquals(1000, segments.count { gongduShareSummary(it) != null })
    }
}
