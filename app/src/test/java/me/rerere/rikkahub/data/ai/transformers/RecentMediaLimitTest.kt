package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentMediaLimitTest {
    private fun voice(i: Int, transcript: String = "转写${i}") =
        UIMessagePart.VoiceMessage(url = "/data/voice/$i.m4a", duration = 1000, transcript = transcript)

    private fun image(i: Int) = UIMessagePart.Image("file:///upload/$i.png")

    private fun user(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.USER, parts = parts.toList())

    private fun keptImages(result: List<UIMessage>) = result.sumOf { m -> m.parts.count(::isHistoryUploadImage) }

    @Test
    fun `zero limit means unlimited and returns the same list`() {
        val messages = (1..200).map { user(image(it)) }
        assertSame(messages, limitRecentUploadImages(messages, 0))
    }

    @Test
    fun `under the limit returns the same list untouched`() {
        val messages = (1..20).map { user(voice(it)) }
        assertSame(messages, limitRecentAudio(messages, 20))
    }

    @Test
    fun `chunk size is half the limit capped at ten`() {
        assertEquals(10, trimChunkSize(50))
        assertEquals(10, trimChunkSize(20))
        assertEquals(4, trimChunkSize(8))
        assertEquals(3, trimChunkSize(5))
        assertEquals(1, trimChunkSize(1))
    }

    @Test
    fun `trims in whole chunks so the kept count stays in a window and never hits zero`() {
        for (limit in listOf(1, 2, 3, 5, 8, 10, 20, 50, 120)) {
            val chunk = trimChunkSize(limit)
            for (total in 0..limit * 3 + 7) {
                val kept = total - mediaToDowngrade(total, limit)
                if (total <= limit) {
                    assertEquals("limit=$limit total=$total", total, kept)
                } else {
                    assertTrue("limit=$limit total=$total kept=$kept", kept in (limit - chunk + 1)..limit)
                }
            }
        }
    }

    @Test
    fun `request prefix only changes once a whole chunk of new media arrives`() {
        // 上限 50、每批 10：51..60 张都砍掉最旧的 10 张，前缀稳定
        val dropped = (51..60).map { mediaToDowngrade(it, 50) }.toSet()
        assertEquals(setOf(10), dropped)
        assertEquals(20, mediaToDowngrade(61, 50))
    }

    @Test
    fun `old voices become transcripts and newest stay audio`() {
        val messages = (1..8).map { user(UIMessagePart.Text("第${it}句"), voice(it)) }
        // 上限 5、每批 3：超出 3 条，砍最旧的 3 条
        val result = limitRecentAudio(messages, 5)

        (0 until 3).forEach { index ->
            assertEquals(UIMessagePart.Text("第${index + 1}句"), result[index].parts[0])
            assertEquals(UIMessagePart.Text("转写${index + 1}"), result[index].parts[1])
        }
        (3 until 8).forEach { index -> assertSame(messages[index], result[index]) }
    }

    @Test
    fun `old voice without transcript becomes an honest placeholder`() {
        val messages = listOf(user(voice(0, transcript = ""))) + (1..5).map { user(voice(it)) }
        val result = limitRecentAudio(messages, 5)
        assertEquals(UIMessagePart.Text(OLD_AUDIO_PLACEHOLDER), result[0].parts.single())
    }

    @Test
    fun `plain audio attachments share the same budget`() {
        val messages = listOf(user(UIMessagePart.Audio("file:///a/old.mp3"))) + (1..5).map { user(voice(it)) }
        val result = limitRecentAudio(messages, 5)
        assertEquals(UIMessagePart.Text(OLD_AUDIO_PLACEHOLDER), result[0].parts.single())
    }

    @Test
    fun `old uploaded images become a text note`() {
        val messages = (1..55).map { user(image(it)) }
        val result = limitRecentUploadImages(messages, 50)
        (0 until 10).forEach { assertEquals(UIMessagePart.Text(OLD_IMAGE_PLACEHOLDER), result[it].parts.single()) }
        (10 until 55).forEach { assertSame(messages[it], result[it]) }
        assertEquals(45, keptImages(result))
    }

    @Test
    fun `remote images and tool result images are not counted or replaced`() {
        val tool = UIMessagePart.Tool("t1", "take_screenshot", "{}", listOf(image(0)))
        val remote = UIMessagePart.Image("https://example.com/a.png")
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)),
            user(remote),
        ) + (1..5).map { user(image(it)) }

        assertSame(messages, limitRecentUploadImages(messages, 5))
    }

    @Test
    fun `stored history is not mutated`() {
        val messages = (1..30).map { user(voice(it)) }
        val snapshot = messages.map { it.copy() }
        limitRecentAudio(messages, 5)
        assertEquals(snapshot, messages)
    }

    private fun toolImage(i: Int) = UIMessagePart.Tool(
        toolCallId = "call$i", toolName = "nai_draw", input = "{}",
        output = listOf(UIMessagePart.Text("{\"image_attached\":true}"), image(i)),
    )

    @Test
    fun `tool images are counted separately and oldest become placeholders`() {
        val messages = (1..12).map { UIMessage(role = MessageRole.ASSISTANT, parts = listOf(toolImage(it))) } +
            user(image(100))
        val result = limitRecentUploadImages(messages, 5)
        val kept = result.sumOf { m -> m.parts.sumOf { p -> (p as? UIMessagePart.Tool)?.output?.count(::isHistoryUploadImage) ?: 0 } }
        assertTrue(kept in 3..5)
        val first = result.first().parts.single() as UIMessagePart.Tool
        assertEquals(OLD_TOOL_IMAGE_PLACEHOLDER, (first.output[1] as UIMessagePart.Text).text)
        assertEquals("{\"image_attached\":true}", (first.output[0] as UIMessagePart.Text).text)
        assertSame(messages.last(), result.last())
    }

    @Test
    fun `tool images untouched when limit is zero`() {
        val messages = (1..30).map { UIMessage(role = MessageRole.ASSISTANT, parts = listOf(toolImage(it))) }
        assertSame(messages, limitRecentUploadImages(messages, 0))
    }
}
