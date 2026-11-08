package me.rerere.rikkahub.data.gongdu

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class GongduNoteShareTest {
    private fun note(): GongduNote = GongduCodec.note(Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/gongdu/detail-with-images-comments.json")).readText()),
        GongduFeed("fixture-note", "标题", "a+b&?token", "原作者"))

    @Test fun readerKeepsAllImagesWhileShareSendsFirstSixInOrder() {
        val note = note()
        assertEquals(18, note.images.size)
        val parts = gongduNoteShareParts(note, true)
        assertEquals(note.images.take(6).map { it.url }, parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
        assertTrue((parts.first() as UIMessagePart.Text).text.contains("本次附带6张图片"))
        assertTrue((parts.first() as UIMessagePart.Text).text.contains("可读取18张"))
        assertEquals("a+b&?token", gongduNoteUrl(note).toHttpUrl().queryParameter("xsec_token"))
    }

    @Test fun textOnlyModelNeverReceivesImageAttachmentsOrClaimsToSeeThem() {
        val parts = gongduNoteShareParts(note(), false)
        assertEquals(1, parts.size)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("未提供图片"))
    }

    @Test fun alternateImageFieldsNormalizeCdnUrlsAndPreserveOriginalPositions() {
        val raw = Json.parseToJsonElement("""{"data":{"data":{"note":{"image_list":[
          {"url":"https://evil.test/a"},
          {"url_default":"http://sns-webpic-qc.xhscdn.com/a","width":800,"height":1000},
          {"info_list":[{"url":"//sns-webpic-qc.xhscdn.com/b"}]},
          "https://sns-webpic-qc.xhscdn.com/c"
        ]}}}}""")
        val result = GongduCodec.note(raw, GongduFeed("n", "原题", "t", "原作者"))
        assertEquals("原题", result.feed.title)
        assertEquals("原作者", result.feed.author)
        assertEquals(listOf(2, 3, 4), result.images.map { it.index })
        assertTrue(result.images.all { it.url.startsWith("https://") })
        assertEquals(800, result.images.first().width)
    }

    @Test fun absentImageListUsesSafeCoverButNeverFetchesArbitraryHosts() {
        val fallback = GongduFeed("n", "封面", "t", cover = "https://sns-webpic-qc.xhscdn.com/cover")
        val safe = GongduCodec.note(buildJsonObject { put("title", "封面") }, fallback)
        assertEquals(listOf(fallback.cover), safe.images.map { it.url })
        val unsafe = safe.copy(feed = fallback.copy(cover = "https://127.0.0.1/private"))
        assertTrue(unsafe.images.isEmpty())
    }
}
