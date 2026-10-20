package me.rerere.rikkahub.service

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkahub.data.ai.executionCheckpointState
import me.rerere.rikkahub.data.ai.withExecutionCheckpoint
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationMessageSaveTest {
    private val tool = UIMessagePart.Tool("call", "write", "{}")

    @Test
    fun `history recovery preserves selected branch metadata and archived raw evidence`() {
        val started = UIMessage.assistant("").copy(parts = listOf(tool.withExecutionCheckpoint("started")))
        val archived = MessageNode.of(started.copy(id = Uuid.random()))
        val alternative = UIMessage.assistant("alternative")
        val selected = MessageNode(messages = listOf(alternative, started), selectIndex = 1)
        val before = Conversation(assistantId = Uuid.random(), title = "new title", isPinned = true,
            folderId = Uuid.random(), messageNodes = listOf(archived, selected),
            compressedMessageNodeIds = setOf(archived.id))
        val after = before.withRecoveredToolHistory(Json)
        assertSame(archived, after.messageNodes.first())
        assertEquals(selected.id, after.messageNodes.last().id)
        assertEquals(1, after.messageNodes.last().selectIndex)
        assertSame(alternative, after.messageNodes.last().messages.first())
        assertEquals("unknown", after.currentMessages.last().getTools().single().executionCheckpointState)
        assertEquals(before.copy(messageNodes = after.messageNodes), after)
        assertSame(after, after.withRecoveredToolHistory(Json))
    }

    @Test
    fun `legacy invalid selected branch is removed without touching its valid alternative`() {
        val valid = UIMessage.assistant("keep")
        val pending = UIMessage.assistant("").copy(parts = listOf(tool))
        val node = MessageNode(messages = listOf(valid, pending), selectIndex = 1)
        val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))
        val after = before.withRecoveredToolHistory(Json)
        assertEquals(listOf(valid), after.messageNodes.single().messages)
        assertEquals(node.id, after.messageNodes.single().id)
        assertEquals(0, after.messageNodes.single().selectIndex)
    }

    @Test
    fun `selected approval decision stays resumable after interrupted sibling recovery`() {
        val started = tool.withExecutionCheckpoint("started")
        val approved = tool.copy(toolCallId = "approved", approvalState = ToolApprovalState.Approved)
        val before = conversation(UIMessage.assistant("").copy(parts = listOf(started, approved)))
        val after = before.withRecoveredToolHistory(Json)
        assertEquals("unknown", after.currentMessages.single().getTools().first().executionCheckpointState)
        assertEquals(approved, after.currentMessages.single().getTools().last())
    }

    @Test
    fun `late uncheckpointed or started UI chunk cannot undo completed tool`() {
        val completed = tool.copy(output = listOf(UIMessagePart.Text("real success")))
            .withExecutionCheckpoint("completed")
        val message = UIMessage.assistant("").copy(parts = listOf(completed))
        val before = conversation(message)
        for (old in listOf(tool, tool.withExecutionCheckpoint("started"))) {
            val after = before.withGenerationMessages(listOf(message.copy(parts = listOf(old))))
            assertEquals(completed, after.currentMessages.single().getTools().single())
            assertFalse(after.currentMessages.single().parts.isEmptyUIMessage())
        }
    }

    @Test
    fun `started evidence survives old chunks and can accept its actual completed result`() {
        val started = tool.withExecutionCheckpoint("started")
        val message = UIMessage.assistant("").copy(parts = listOf(started))
        val before = conversation(message)
        val stale = before.withGenerationMessages(listOf(message.copy(parts = listOf(tool))))
        assertEquals(started, stale.currentMessages.single().getTools().single())
        val completed = tool.copy(output = listOf(UIMessagePart.Text("success")))
            .withExecutionCheckpoint("completed")
        val after = stale.withGenerationMessages(listOf(message.copy(parts = listOf(completed))))
        assertEquals(completed, after.currentMessages.single().getTools().single())
    }

    @Test
    fun `checkpoint merge preserves alternative message node identity and latest metadata`() {
        val alternative = UIMessage.assistant("alternative")
        val generated = UIMessage.assistant("").copy(parts = listOf(tool))
        val node = MessageNode(messages = listOf(alternative, generated), selectIndex = 1)
        val before = Conversation(assistantId = Uuid.random(), title = "latest title",
            messageNodes = listOf(node), isPinned = true, folderId = Uuid.random())
        val completed = tool.copy(output = listOf(UIMessagePart.Text("success")))
            .withExecutionCheckpoint("completed")
        val after = before.withGenerationMessages(listOf(generated.copy(parts = listOf(completed))))
        assertEquals(before.title, after.title)
        assertEquals(before.folderId, after.folderId)
        assertTrue(after.isPinned)
        assertEquals(node.id, after.messageNodes.single().id)
        assertEquals(alternative, after.messageNodes.single().messages.first())
        assertEquals(1, after.messageNodes.single().selectIndex)
        assertEquals(completed, after.currentMessages.single().getTools().single())
    }

    @Test
    fun `delayed text emission retains missing durable tools without duplicating them`() {
        val completed = tool.copy(output = listOf(UIMessagePart.Text("success")))
            .withExecutionCheckpoint("completed")
        val message = UIMessage.assistant("").copy(parts = listOf(completed))
        val before = conversation(message)
        val updated = message.copy(parts = listOf(UIMessagePart.Text("next text")))
        val after = before.withGenerationMessages(listOf(updated)).withGenerationMessages(listOf(updated))
        assertEquals("next text", after.currentMessages.single().parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(listOf(completed), after.currentMessages.single().getTools())
    }

    private fun conversation(message: UIMessage) = Conversation(
        assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(message)),
    )
}
