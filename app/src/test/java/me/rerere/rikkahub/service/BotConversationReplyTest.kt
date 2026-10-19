package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class BotConversationReplyTest {
    @Test fun `instant reply is not lost before subscription and releases its reference`() = runBlocking {
        withSession { session ->
            val stored = session.state.value
            session.state.value = stored.copy(messageNodesLoaded = false)
            val reply = session.awaitBotReply(1_000, initialize = {
                session.initialize({ stored }, { error("Must load") })
            }, send = {
                assertTrue(session.state.value.messageNodesLoaded)
                assertTrue(session.isInUse)
                session.state.value = stored.copy(messageNodes = stored.messageNodes + MessageNode.of(UIMessage.assistant("new")))
                Job().also { it.complete() }
            })
            assertEquals("new", reply)
            assertFalse(session.isInUse)
        }
    }

    @Test fun `failure with no new assistant never forwards an old reply`() = runBlocking {
        withSession { session ->
            val reply = session.awaitBotReply(1_000, {}, { Job().also { it.complete() } })
            assertTrue(reply.contains("无回复"))
            assertFalse(session.isInUse)
        }
    }

    @Test fun `timeout cancels only its own send job and releases reference`() = runBlocking {
        withSession { session ->
            val pending = Job()
            val reply = session.awaitBotReply(20, {}, { pending })
            assertTrue(reply.contains("超时"))
            assertTrue(pending.isCancelled)
            assertFalse(session.isInUse)
        }
    }

    @Test fun `superseded job reports interruption instead of returning another reply`() = runBlocking {
        withSession { session ->
            val reply = session.awaitBotReply(1_000, {}, { Job().also { it.cancel() } })
            assertTrue(reply.contains("中断"))
            assertFalse(session.isInUse)
        }
    }

    @Test fun `initialization failure releases reference without sending`() = runBlocking {
        withSession { session ->
            try {
                session.awaitBotReply(1_000, { error("database unavailable") }, { error("must not send") })
                fail()
            } catch (_: IllegalStateException) { }
            assertFalse(session.isInUse)
        }
    }

    @Test fun `caller cancellation cancels sending and releases reference`() = runBlocking {
        withSession { session ->
            val pending = Job()
            val call = launch(start = CoroutineStart.UNDISPATCHED) {
                session.awaitBotReply(10_000, {}, { pending })
            }
            call.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertFalse(session.isInUse)
        }
    }

    private suspend fun withSession(block: suspend (ConversationSession) -> Unit) = coroutineScope {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(UIMessage.assistant("old"))))
        val session = ConversationSession(initial.id, initial, scope, debugLog = {}) {}
        try { block(session) } finally { session.cleanup(); scope.cancel() }
    }
}
