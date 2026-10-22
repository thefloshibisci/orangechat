package me.rerere.rikkahub.data.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class InteractionTextTest {
    @Test
    fun `action and suffix remain independent`() {
        assertEquals(
            "哥哥揉了揉你的脑袋，并递来牛奶",
            interactionText("哥哥", "你", "揉了揉", "的脑袋，并递来牛奶"),
        )
        assertEquals("你戳了戳哥哥", interactionText("你", "哥哥", "戳了戳", ""))
    }

    @Test
    fun `invalid notices cannot be persisted`() {
        for ((action, suffix) in listOf("" to "", "x".repeat(21) to "", "拍了拍" to "x".repeat(81))) {
            runCatching { interactionText("你", "AI", action, suffix) }
                .onSuccess { fail("Expected validation") }
        }
    }

    @Test
    fun `reaction identity isolates every interaction dimension`() {
        val base = reactionKey("chat", "message", "user", "❤️", "喜欢牛奶")
        assertEquals(base, reactionKey("chat", "message", "user", "❤️", "喜欢牛奶"))
        assertNotEquals(base, reactionKey("other", "message", "user", "❤️", "喜欢牛奶"))
        assertNotEquals(base, reactionKey("chat", "message", "assistant", "❤️", "喜欢牛奶"))
        assertNotEquals(base, reactionKey("chat", "message", "user", "👎", "喜欢牛奶"))
        assertNotEquals(base, reactionKey("chat", "message", "user", "❤️", "不喜欢牛奶"))
    }
}
