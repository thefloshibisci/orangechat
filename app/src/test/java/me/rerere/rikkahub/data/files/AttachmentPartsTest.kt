package me.rerere.rikkahub.data.files

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class AttachmentPartsTest {
    @Test fun `filter preserves text identity and nested tool completion`() {
        val nested = UIMessagePart.Tool("outer", "outer", "{}", listOf(
            UIMessagePart.Text("keep"),
            UIMessagePart.Tool("inner", "camera", "{}", listOf(UIMessagePart.Image("file:///missing.png"))),
        ))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(nested))
        val filtered = filterAttachments(listOf(message)) { false }.single()
        assertEquals(message.id, filtered.id)
        val outer = filtered.parts.single() as UIMessagePart.Tool
        assertSame(nested.output.first(), outer.output.first())
        val inner = outer.output.last() as UIMessagePart.Tool
        assertTrue(inner.isExecuted)
        assertFalse(inner.canResumeExecution)
        assertEquals(emptySet<String>(), attachmentUrls(listOf(filtered)))
        assertEquals(setOf("file:///missing.png"), attachmentUrls(listOf(message)))
    }

    @Test fun `valid messages retain reference identity and pending tools remain pending`() {
        val pending = UIMessagePart.Tool("id", "tool", "{}")
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending)))
        assertSame(messages, filterAttachments(messages) { true })
        assertFalse(pending.isExecuted)
    }

    @Test fun `removal preserves an image only message and all alternative text`() {
        val imageOnly = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("bad")))
        val alternate = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("original text"),
            UIMessagePart.Document("bad", "file.txt")))
        val cleaned = filterAttachments(listOf(imageOnly, alternate)) { it.attachmentUrl() != "bad" }
        assertEquals(listOf(imageOnly.id, alternate.id), cleaned.map { it.id })
        assertTrue(cleaned.first().parts.isEmpty())
        assertEquals("original text", cleaned.last().toText())
    }
}
