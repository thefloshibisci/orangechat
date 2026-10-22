package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.withExecutionCheckpoint
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationMessageJournalTest {
    private fun conversation(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(), messageNodes = messages.map { MessageNode.of(it) },
    )

    @Test
    fun `multiple uncommitted streams accumulate before checkpoint without rewriting untouched nodes`() {
        val stable = UIMessage.user("history")
        val first = UIMessage.assistant("first")
        val second = UIMessage.assistant("second")
        val committed = conversation(stable, first)
        val journal = ConversationMessageJournal()
        val updateFirst = committed.generationMessageMutation(listOf(first.copy(parts = UIMessage.assistant("finished first").parts)))
        journal.record(updateFirst, committed)
        val appendSecond = updateFirst.conversation.generationMessageMutation(listOf(second))
        journal.record(appendSecond, committed)
        val latest = appendSecond.conversation.copy(title = "latest", isPinned = true, folderId = Uuid.random())

        val delta = journal.deltaFor(committed.copy(chatSuggestions = listOf("new")), latest)!!
        assertEquals(listOf(1, 2), delta.changes.upserts.map { it.first })
        assertSame(committed.messageNodes[0], latest.messageNodes[0])
        assertTrue(delta.changes.deletions.isEmpty())
        assertEquals(latest, delta.current)
        assertTrue(delta.appliesTo(committed, latest))
    }

    @Test
    fun `failed persistence retains earlier changes when subsequent stream replaces same node`() {
        val message = UIMessage.assistant("initial")
        val committed = conversation(message)
        val journal = ConversationMessageJournal()
        val first = committed.generationMessageMutation(listOf(message.copy(parts = UIMessage.assistant("partial").parts)))
        journal.record(first, committed)
        try {
            checkNotNull(journal.deltaFor(committed, first.conversation))
            throw IllegalStateException("simulated transaction failure")
        } catch (_: IllegalStateException) { /* The caller must not acknowledge a failed commit. */ }
        val tool = UIMessagePart.Tool("call", "write", "{}").withExecutionCheckpoint("started")
        val second = first.conversation.generationMessageMutation(listOf(message.copy(parts = listOf(tool))))
        journal.record(second, committed)

        val retry = journal.deltaFor(committed, second.conversation)!!
        assertEquals(1, retry.changes.upserts.size)
        assertEquals(tool, retry.changes.upserts.single().second.currentMessage.getTools().single())
        assertSame(committed.messageNodes, retry.previous.messageNodes)
    }

    @Test
    fun `unknown full edit and structurally equal replacement lists cannot borrow a journal baseline`() {
        val message = UIMessage.assistant("before")
        val committed = conversation(message)
        val journal = ConversationMessageJournal()
        val mutation = committed.generationMessageMutation(listOf(message.copy(parts = UIMessage.assistant("after").parts)))
        journal.record(mutation, committed)
        val unknown = mutation.conversation.copy(messageNodes = mutation.conversation.messageNodes.toMutableList())
        assertEquals(mutation.conversation, unknown)
        assertNull(journal.deltaFor(committed, unknown))
        val equalBaseline = committed.copy(messageNodes = committed.messageNodes.toMutableList())
        assertNull(journal.deltaFor(equalBaseline, mutation.conversation))
        val afterUnknown = unknown.generationMessageMutation(listOf(UIMessage.assistant("appended after full edit")))
        journal.record(afterUnknown, committed)
        assertNull(journal.deltaFor(committed, afterUnknown.conversation))
    }

    @Test
    fun `acknowledgement resets journal while an unseen late snapshot still requires full comparison`() {
        val committed = conversation(UIMessage.user("initial"))
        val journal = ConversationMessageJournal()
        val first = committed.generationMessageMutation(listOf(UIMessage.assistant("first")))
        journal.record(first, committed)
        journal.committed()
        assertNull(journal.deltaFor(committed, first.conversation))
        val saved = first.conversation
        assertTrue(journal.deltaFor(saved, saved.copy(title = "title"))!!.changes.upserts.isEmpty())
        val second = saved.generationMessageMutation(listOf(UIMessage.assistant("second")))
        journal.record(second, saved)
        assertEquals(listOf(2), journal.deltaFor(saved, second.conversation)!!.changes.upserts.map { it.first })
    }

    @Test
    fun `new and incomplete conversations cannot authorize a destructive shortcut`() {
        val committed = conversation(UIMessage.user("initial"))
        val journal = ConversationMessageJournal()
        val mutation = committed.generationMessageMutation(listOf(UIMessage.assistant("new")))
        journal.record(mutation, null)
        assertNull(journal.deltaFor(null, mutation.conversation))
        assertThrows(IllegalStateException::class.java) {
            committed.copy(messageNodesLoaded = false).generationMessageMutation(listOf(UIMessage.assistant("bad")))
        }
    }

    @Test
    fun `full request windows yield only actual changed nodes and unchanged input keeps list identity`() {
        val messages = (0 until 2000).map { UIMessage.assistant("history $it") }
        val committed = conversation(*messages.toTypedArray())
        val unchanged = committed.generationMessageMutation(messages)
        assertSame(committed, unchanged.conversation)
        assertTrue(unchanged.changes.upserts.isEmpty())
        val last = messages.last().copy(parts = UIMessage.assistant("new streamed text").parts)
        val mutation = committed.generationMessageMutation(messages.dropLast(1) + last)
        assertEquals(listOf(1999), mutation.changes.upserts.map { it.first })
        assertSame(committed.messageNodes[1000], mutation.conversation.messageNodes[1000])
        assertEquals(last, mutation.conversation.currentMessages.last())
    }

    @Test
    fun `targeted regeneration records its branch and ignores archived raw nodes`() {
        val archivedMessage = UIMessage.assistant("archived")
        val alternative = UIMessage.assistant("alternative")
        val archived = MessageNode.of(archivedMessage)
        val node = MessageNode.of(alternative)
        val committed = Conversation(assistantId = Uuid.random(), messageNodes = listOf(archived, node),
            compressedMessageNodeIds = setOf(archived.id))
        val generated = UIMessage.assistant("regenerated")
        val mutation = committed.generationMessageMutation(listOf(
            archivedMessage.copy(parts = UIMessage.assistant("stale overwrite").parts), generated,
        ), mapOf(generated.id to node.id))
        assertSame(archived, mutation.conversation.messageNodes[0])
        assertEquals(listOf(1), mutation.changes.upserts.map { it.first })
        assertEquals(node.id, mutation.conversation.messageNodes[1].id)
        assertEquals(listOf(alternative, generated), mutation.conversation.messageNodes[1].messages)
        assertEquals(1, mutation.conversation.messageNodes[1].selectIndex)
    }
}
