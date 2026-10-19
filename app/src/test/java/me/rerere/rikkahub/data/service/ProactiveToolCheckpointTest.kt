package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.withExecutionCheckpoint
import org.junit.Assert.*
import org.junit.Test

class ProactiveToolCheckpointTest {
    private fun call(id: String) = UIMessagePart.Tool(id, "external-action", "{}")

    @Test
    fun `checkpoint projection saves assistant tools without hidden decision or reasoning`() {
        val tool = call("current").withExecutionCheckpoint("started")
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("[WAIT:10]\n[NOTE:private note]"),
            UIMessagePart.Reasoning("private thought"),
            tool,
        ))
        val projected = proactiveToolCheckpointMessage(assistant)
        assertEquals(assistant.id, projected.id)
        assertEquals(MessageRole.ASSISTANT, projected.role)
        assertEquals(listOf(tool), projected.parts)
        try {
            proactiveToolCheckpointMessage(beginProactiveRequest(emptyList()).last())
            fail("Hidden wake-up USER must never be persisted")
        } catch (_: IllegalArgumentException) {
            // The projection only accepts owned ASSISTANT records.
        }
    }

    @Test
    fun `late success only fills the exact owned started tool and preserves other parts`() {
        val started = call("current").withExecutionCheckpoint("started")
        val unstarted = call("next")
        val existing = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("preserve existing text"), started, unstarted,
        ))
        val completed = started.copy(output = listOf(UIMessagePart.Text("receipt")))
            .withExecutionCheckpoint("completed")
        val incoming = existing.copy(parts = listOf(
            UIMessagePart.Text("must not overwrite"), completed,
            unstarted.copy(output = listOf(UIMessagePart.Text("must not append"))).withExecutionCheckpoint("completed"),
            call("foreign").withExecutionCheckpoint("completed"),
        ))
        val merged = requireNotNull(mergeProactiveToolCompletion(existing, incoming))
        assertEquals(existing.id, merged.id)
        assertEquals(listOf(existing.parts.first(), completed, unstarted), merged.parts)
        assertNull(mergeProactiveToolCompletion(merged, incoming))
        assertNull(mergeProactiveToolCompletion(existing, incoming.copy(id = kotlin.uuid.Uuid.random())))
    }

    @Test
    fun `changed input name or unstarted state cannot gain completion permission after user return`() {
        val started = call("current").withExecutionCheckpoint("started")
        val existing = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(started))
        val completed = started.copy(output = listOf(UIMessagePart.Text("receipt")))
            .withExecutionCheckpoint("completed")
        for (invalid in listOf(
            completed.copy(input = "{\"different\":true}"),
            completed.copy(toolName = "different-action"),
            completed.copy(output = emptyList()),
            completed.copy(toolCallId = "different-id"),
        )) {
            assertNull(mergeProactiveToolCompletion(existing, existing.copy(parts = listOf(invalid))))
        }
        assertNull(mergeProactiveToolCompletion(existing.copy(parts = listOf(call("current"))),
            existing.copy(parts = listOf(completed))))
    }
}
