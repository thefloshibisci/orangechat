package me.rerere.rikkahub.data.listen

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ListenLyricsPageTest {
    @Test fun pageBoundaryIncludesEveryTimestampAndRepeatedLine() {
        val lines = (0..401).map { ListenLyric(it.toLong(), "相同", "译$it") }
        val pages = listOf(0,200,400).map { listenLyricsPage("123", lines, it) }
        assertEquals(listOf(200,400,null), pages.map { it["next_offset"]?.jsonPrimitive?.intOrNull })
        assertEquals((0..401).toList(), pages.flatMap { p -> p.getValue("lines").jsonArray.map { it.jsonObject.getValue("timeMs").jsonPrimitive.int } })
        assertEquals(JsonNull, listenLyricsPage("1", emptyList(), 0)["next_offset"])
        assertTrue(runCatching { listenLyricsPage("1", lines, -1) }.isFailure)
        assertTrue(runCatching { listenLyricsPage("1", lines, Int.MAX_VALUE) }.isFailure)
    }
    @Test fun oldAndNewEmptySharesAreNotClaimsAboutTheTrack() {
        val raw = "[共度·歌曲]\n{\"id\":\"17\",\"title\":\"一首歌\",\"lines\":[]}"
        val old = requireNotNull(parseListenShare(raw)); val new = listenShareText(old.song)
        assertEquals(old, parseListenShare(new)); assertTrue(new.contains("selected_excerpt_only"))
    }
    @Test fun emptyFailedUnrequestedAndPendingAreDistinctAndTransient() {
        val states = listOf(ListenLyricStatus.NOT_REQUESTED, ListenLyricStatus.LOADING, ListenLyricStatus.EMPTY, ListenLyricStatus.FAILED)
        assertEquals(4, states.map { it.emptyLabel }.toSet().size)
        assertEquals(ListenLyricStatus.NOT_REQUESTED, ListenState(lyricStatus=ListenLyricStatus.LOADING).afterPlaybackInterrupted().lyricStatus)
        assertEquals(ListenLyricStatus.NOT_REQUESTED, ListenState(lyricStatus=ListenLyricStatus.FAILED).forRestore().lyricStatus)
    }
}
