package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationStreamUpdateTest {
    @Test fun `a long request window only replaces changed nodes`() {
        val history = (1..5000).map { UIMessage.user("message $it").toMessageNode() }
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = history)
        val reply = UIMessage.assistant("first")
        val updated = conversation.updateCurrentMessages(conversation.currentMessages + reply)
        history.forEachIndexed { index, node -> assertSame(node, updated.messageNodes[index]) }
        assertEquals(5001, updated.messageNodes.size)
        val next = reply.copy(parts = UIMessage.assistant("finished").parts)
        val final = updated.updateCurrentMessages(conversation.currentMessages + next)
        assertEquals(5001, final.messageNodes.size)
        assertEquals(next, final.currentMessages.last())
    }

    @Test fun `multiple updates of a new reply in one batch append only once`() {
        val reply = UIMessage.assistant("first")
        val last = reply.copy(parts = UIMessage.assistant("last").parts)
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val updated = conversation.updateCurrentMessages(listOf(reply, last))
        assertEquals(1, updated.messageNodes.size)
        assertEquals(last, updated.currentMessages.single())
    }

    @Test fun `regeneration preserves previous alternative and updates the selected new branch`() {
        val original = UIMessage.assistant("original").toMessageNode()
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(original))
        val regenerated = UIMessage.assistant("new")
        val updated = conversation.updateCurrentMessages(listOf(regenerated), mapOf(regenerated.id to original.id))
        assertSame(original.currentMessage, updated.messageNodes.single().messages.first())
        assertEquals(1, updated.messageNodes.single().selectIndex)
        val final = updated.updateCurrentMessages(listOf(regenerated.copy(parts = UIMessage.assistant("done").parts)))
        assertEquals(2, final.messageNodes.single().messages.size)
        assertEquals("done", final.currentMessages.single().toText())
    }
}
