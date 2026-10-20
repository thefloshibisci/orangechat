package me.rerere.rikkahub.data.repository

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationNodeChangesTest {
    @Test
    fun `metadata and favorite changes do not serialize untouched message nodes`() {
        val first = node("first")
        val previous = conversation(first, node("second"))
        val current = previous.copy(
            title = "new title", chatSuggestions = listOf("next"), isPinned = true,
            compressionRevision = 9,
            messageNodes = listOf(first.copy(isFavorite = true), previous.messageNodes[1].copy()),
        )

        val changes = conversationNodeChanges(previous, current)

        assertTrue(changes.upserts.isEmpty())
        assertTrue(changes.deletions.isEmpty())
        assertTrue(changes.changedIds.isEmpty())
    }

    @Test
    fun `appended reply upserts only the newly appended node`() {
        val previous = conversation(node("old"))
        val appended = node("new")

        val changes = conversationNodeChanges(previous, previous.copy(messageNodes = previous.messageNodes + appended))

        assertEquals(listOf(1 to appended), changes.upserts)
        assertTrue(changes.deletions.isEmpty())
        assertEquals(setOf(appended.id.toString()), changes.changedIds)
    }

    @Test
    fun `stream text branch append and selected alternative updates touch their own nodes`() {
        val stable = node("untouched")
        val stream = node("in progress")
        val branch = node("first choice").copy(messages = listOf(UIMessage.assistant("one"), UIMessage.assistant("two")))
        val previous = conversation(stable, stream, branch)
        val updatedStream = stream.copy(messages = listOf(stream.messages.single().copy(parts = UIMessage.assistant("finished").parts)))
        val selected = branch.copy(selectIndex = 1)
        val current = previous.copy(messageNodes = listOf(stable, updatedStream, selected))

        assertEquals(listOf(1 to updatedStream, 2 to selected), conversationNodeChanges(previous, current).upserts)
        val withAlternative = stream.copy(messages = stream.messages + UIMessage.assistant("alternative"))
        assertEquals(listOf(1 to withAlternative), conversationNodeChanges(
            previous, previous.copy(messageNodes = listOf(stable, withAlternative, branch)),
        ).upserts)
    }

    @Test
    fun `reordering records only moved positions with no deletions`() {
        val first = node("first")
        val middle = node("middle")
        val last = node("last")
        val previous = conversation(first, middle, last)

        val changes = conversationNodeChanges(previous, previous.copy(messageNodes = listOf(last, middle, first)))

        assertEquals(listOf(0 to last, 2 to first), changes.upserts)
        assertTrue(changes.deletions.isEmpty())
    }

    @Test
    fun `deleting middle node explicitly deletes it and updates the shifted survivor`() {
        val first = node("first")
        val deleted = node("deleted")
        val survivor = node("survivor")
        val previous = conversation(first, deleted, survivor)

        val changes = conversationNodeChanges(previous, previous.copy(messageNodes = listOf(first, survivor)))

        assertEquals(listOf(1 to survivor), changes.upserts)
        assertEquals(setOf(deleted.id.toString()), changes.deletions)
        assertEquals(setOf(deleted.id.toString(), survivor.id.toString()), changes.changedIds)
    }

    @Test
    fun `truncation and explicit full clear have deletion sets without unnecessary upserts`() {
        val previous = conversation(node("first"), node("second"), node("third"))
        val truncated = conversationNodeChanges(previous, previous.copy(messageNodes = previous.messageNodes.take(1)))
        assertTrue(truncated.upserts.isEmpty())
        assertEquals(previous.messageNodes.drop(1).map { it.id.toString() }.toSet(), truncated.deletions)
        val cleared = conversationNodeChanges(previous, previous.copy(messageNodes = emptyList()))
        assertTrue(cleared.upserts.isEmpty())
        assertEquals(previous.messageNodes.map { it.id.toString() }.toSet(), cleared.deletions)
    }

    @Test
    fun `an incomplete previous or current snapshot cannot produce destructive changes`() {
        val previous = conversation(node("stored"))
        for ((before, after) in listOf(
            previous.copy(messageNodesLoaded = false) to previous.copy(messageNodes = emptyList()),
            previous to previous.copy(messageNodes = emptyList(), messageNodesLoaded = false),
        )) {
            try {
                conversationNodeChanges(before, after)
                fail("A projection must not become a destructive save")
            } catch (_: IllegalStateException) {
                // Completeness must be checked before calculating deletions.
            }
        }
    }

    @Test
    fun `different conversations and duplicate current node IDs are rejected`() {
        val first = node("stored")
        val previous = conversation(first)
        for (current in listOf(
            previous.copy(id = Uuid.random()),
            previous.copy(messageNodes = listOf(first, first.copy(messages = listOf(UIMessage.assistant("different"))))),
        )) {
            try {
                conversationNodeChanges(previous, current)
                fail("An ambiguous snapshot must be rejected")
            } catch (_: IllegalArgumentException) {
                // Neither cross-conversation IDs nor duplicate node IDs are a valid baseline.
            }
        }
    }

    private fun conversation(vararg nodes: MessageNode) = Conversation(
        assistantId = Uuid.random(), messageNodes = nodes.toList(),
    )

    private fun node(text: String) = MessageNode.of(UIMessage.assistant(text))
}
