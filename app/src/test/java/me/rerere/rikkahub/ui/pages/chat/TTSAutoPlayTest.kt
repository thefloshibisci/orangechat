package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class TTSAutoPlayTest {
    @Test fun `voice tool messages are never automatically spoken again`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("语音已发送"), UIMessagePart.Tool(toolCallId = "voice1", toolName = "text_to_speech", input = "{}",
                output = listOf(UIMessagePart.Text("{\"success\":true}"))),
        ))
        assertFalse(shouldAutoPlayTts(message))
        assertTrue(shouldAutoPlayTts(message.copy(parts = listOf(UIMessagePart.Text("ordinary reply")))))
        assertFalse(shouldAutoPlayTts(message.copy(role = MessageRole.USER)))
    }
}
