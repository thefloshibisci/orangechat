package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationConfigPublicationTest {
    @Test fun `late message snapshot retains edited request settings and publishes new reply`() = withSession { session ->
        val old = session.state.value
        val edited = ConversationConfig(temperature = 0.8f, maxTokens = 4096)
        session.state.value = old.copy(config = edited)
        val reply = old.copy(messageNodes = old.messageNodes + MessageNode.of(UIMessage.assistant("reply")))
        session.publishMessageSnapshot(reply)
        assertEquals(edited, session.state.value.config)
        assertSame(reply.messageNodes, session.state.value.messageNodes)
    }

    @Test fun `late bound snapshot cannot undo explicit reset to inherited controls`() = withSession { session ->
        val old = session.state.value.copy(config = ConversationConfig(temperature = 0.8f))
        session.state.value = old.copy(config = null)
        session.publishMessageSnapshot(old.copy(title = "late title"))
        assertNull(session.state.value.config)
        assertEquals("late title", session.state.value.title)
    }

    @Test fun `unloaded session accepts fork or restored snapshot configuration`() = withSession { session ->
        session.state.value = session.state.value.copy(messageNodesLoaded = false)
        val restored = session.state.value.copy(messageNodesLoaded = true, config = ConversationConfig(maxTokens = 1024))
        session.publishMessageSnapshot(restored)
        assertEquals(restored, session.state.value)
    }

    private fun withSession(block: (ConversationSession) -> Unit) {
        val scope = CoroutineScope(SupervisorJob())
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(UIMessage.user("hello"))))
        val session = ConversationSession(initial.id, initial, scope, debugLog = {}) {}
        try { block(session) } finally { session.cleanup(); scope.cancel() }
    }
}
