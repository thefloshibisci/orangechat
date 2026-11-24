package me.rerere.rikkahub.data.listen

import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import org.junit.Assert.*
import org.junit.Test

class ListenShareTest {
    @Test fun exportedSongAndSelectedLinesRoundTrip() {
        val song = ListenSong("123", "一起听的歌", "歌手", "专辑", "https://example.com/cover.png", 123000)
        val restored = parseListenShare(listenShareText(song, listOf("选中的一句"), "这首给你"))!!
        assertEquals(song, restored.song)
        assertEquals(listOf("选中的一句"), restored.lines)
        assertEquals("这首给你", restored.message)
    }

    @Test fun ordinaryMessagesAndMalformedCardsKeepTheirTextRendering() {
        assertNull(parseListenShare("随手聊聊这首歌"))
        assertNull(parseListenShare("[共度·歌曲]\n{broken}"))
        assertNull(parseListenShare("[共度·歌曲]\n{\"id\":\"../../secret\",\"title\":\"test\"}"))
        assertNull(parseListenShare("[共度·歌曲]\n{\"id\":\"123\",\"title\":\"\"}"))
    }

    @Test fun sharedTextIsBoundedAndExternalSchemesAreIgnored() {
        val restored = parseListenShare(listenShareText(
            ListenSong("123", "歌", "人", cover = "file:///private/image"),
            List(30) { "一句" }, "话".repeat(9000),
        ))!!
        assertEquals(8, restored.lines.size)
        assertEquals(6000, restored.message.length)
        assertEquals("", restored.song.cover)
    }

    @Test fun headlessAndUnknownCallersHaveNoMusicTool() {
        assertTrue(listenTools(ToolInvocationContext.EMPTY).isEmpty())
        assertTrue(listenTools(ToolInvocationContext("assistant", "chat", isHeadless = true)).isEmpty())
    }
}
