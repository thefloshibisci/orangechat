package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationLifecycleTest {
    @Test fun `cold initialization loads once and keeps newer streaming messages`() = runBlocking {
        withSession { session ->
            val stored = session.state.value
            session.state.value = stored.copy(messageNodes = emptyList(), messageNodesLoaded = false)
            var reads = 0
            session.initialize({ reads++; stored }, { error("Existing chat must not be recreated") })
            val streamed = stored.copy(messageNodes = stored.messageNodes + MessageNode.of(UIMessage.assistant("new")))
            session.state.value = streamed
            session.initialize({ reads++; stored }, { error("Must not recreate") })
            assertEquals(1, reads)
            assertSame(streamed, session.state.value)
            assertSame(stored, session.persistedConversation)
            assertFalse(session.isInUse)
        }
    }

    @Test fun `new bot conversation retains the requested assistant and presets`() = runBlocking {
        withSession { session ->
            session.state.value = session.state.value.copy(messageNodesLoaded = false)
            val requested = Conversation.ofId(session.id, Uuid.random(), newConversation = true)
                .updateCurrentMessages(listOf(UIMessage.user("preset")))
            session.initialize({ null }, { requested })
            assertSame(requested, session.state.value)
            assertNull(session.persistedConversation)
            assertFalse(session.isInUse)
        }
    }

    @Test fun `failed load never publishes empty history and can be retried`() = runBlocking {
        withSession { session ->
            val stored = session.state.value
            val unloaded = stored.copy(messageNodes = emptyList(), messageNodesLoaded = false)
            session.state.value = unloaded
            try { session.initialize({ error("load failed") }, { error("must not create") }); fail() }
            catch (_: IllegalStateException) { }
            assertSame(unloaded, session.state.value)
            assertFalse(session.isInUse)
            session.initialize({ stored }, { error("must not create") })
            assertSame(stored, session.state.value)
        }
    }

    @Test fun `undo restores the same observed flow and rejects callbacks from the deleted lifecycle`() = runBlocking {
        withSession { session ->
            session.acquire() // A retained chat screen.
            val observed = session.state
            val stored = observed.value
            val oldEpoch = session.epoch
            session.deleteAndAwait { }
            assertTrue(observed.value.messageNodes.isEmpty())
            session.restore { stored }
            assertSame(observed, session.state)
            assertSame(stored, observed.value)
            assertSame(stored, session.persistedConversation)
            assertTrue(session.isInUse)
            assertFalse(session.isDeleted)
            session.saveGeneratedTitle("stale", oldEpoch) { error("Must not persist stale title") }
            session.saveGeneratedSuggestions(listOf("stale"), oldEpoch) { error("Must not persist stale suggestions") }
            assertSame(stored, observed.value)
            session.saveGeneratedTitle("fresh") { true }
            assertEquals("fresh", observed.value.title)
            session.release()
            assertFalse(session.isInUse)
        }
    }

    @Test fun `failed undo does not reopen deleted state`() = runBlocking {
        withSession { session ->
            session.deleteAndAwait { }
            val deleted = session.state.value
            try { session.restore { error("insert failed") }; fail() }
            catch (_: IllegalStateException) { }
            assertTrue(session.isDeleted)
            assertSame(deleted, session.state.value)
            assertFalse(session.isInUse)
        }
    }

    private suspend fun withSession(block: suspend (ConversationSession) -> Unit) = coroutineScope {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(UIMessage.user("keep"))))
        val session = ConversationSession(initial.id, initial, scope, debugLog = {}) {}
        try { block(session) } finally { session.cleanup(); scope.cancel() }
    }
}
