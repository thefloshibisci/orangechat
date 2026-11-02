package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.queuedReplyText
import me.rerere.rikkahub.service.queuedSpeechReplyText
import org.junit.Assert.*
import org.junit.Test

class SpeechOutputTransformerTest {
    @Test fun `chat and external transcript stay clean while call and replay keep sentence controls`() {
        val raw = "[[voice emotion=happy speed=0.9 pitch=-2]]你好(laughs)<#0.3#>呀。[[/voice]]"
        val message = speechTranscript(UIMessage.assistant(raw))
        assertEquals("你好呀。", message.toText())
        assertEquals(raw, message.speechText())
        assertEquals(message, speechTranscript(message))
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(message))
        assertEquals("你好呀。", restored.toText())
        assertEquals(raw, restored.speechText())
        val user = UIMessage.user("你好")
        val nextUser = UIMessage.user("下一条")
        val messages = listOf(user, restored, nextUser, UIMessage.assistant("别的回复"))
        assertEquals("你好呀。", queuedReplyText(messages, user.id))
        assertEquals(raw, queuedSpeechReplyText(messages, user.id))
    }

    @Test fun `editing displayed text invalidates hidden old speech`() {
        val message = speechTranscript(UIMessage.assistant("[[voice emotion=happy]]旧话。[[/voice]]"))
        val part = message.parts.filterIsInstance<UIMessagePart.Text>().single()
        val edited = message.copy(parts = listOf(part.copy(text = "新话。")))
        assertEquals("新话。", edited.speechText())
    }

    @Test fun `user literal control syntax is never transformed`() {
        val user = UIMessage.user("[[voice emotion=happy]]示例。[[/voice]]")
        assertEquals(user, speechTranscript(user))
    }
}
