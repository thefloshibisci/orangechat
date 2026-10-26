package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class QueuedBotReplyTest {
    @Test fun instantReplyUsesItsEntryEvenIfAnotherTurnAlreadyAppears() = runTest {
        withSession { session ->
            val entry = entry().apply { reply.complete(" exact reply ") }
            val actual = session.awaitQueuedBotReply(1_000, {}, {
                session.state.value = session.state.value.copy(messageNodes = listOf(
                    MessageNode.of(UIMessage.assistant("somebody else's newer reply")),
                ))
                entry
            }, { error("Finished reply must not be cancelled") })
            assertEquals("exact reply", actual)
            assertFalse(session.isInUse)
        }
    }

    @Test fun unrelatedConversationUpdatesDoNotCompleteQueuedBotWait() = runTest {
        withSession { session ->
            val entry = entry()
            val waiter = async { session.awaitQueuedBotReply(1_000, {}, { entry }, {}) }
            runCurrent()
            session.state.value = session.state.value.copy(messageNodes = listOf(
                MessageNode.of(UIMessage.assistant("unrelated completed turn")),
            ))
            runCurrent()
            assertFalse(waiter.isCompleted)
            entry.reply.complete("my turn")
            assertEquals("my turn", waiter.await())
            assertFalse(session.isInUse)
        }
    }

    @Test fun timeoutCancelsOnlyItsRequestIdAndReleasesReference() = runTest {
        withSession { session ->
            val entry = entry()
            val unrelated = Job()
            val cancelled = mutableListOf<Uuid>()
            val actual = session.awaitQueuedBotReply(20, {}, { entry }, { cancelled += it })
            assertTrue(actual.contains("超时"))
            assertEquals(listOf(entry.id), cancelled)
            assertTrue(unrelated.isActive)
            unrelated.cancel()
            assertFalse(session.isInUse)
        }
    }

    @Test fun callerCancellationWithdrawsItsOwnPendingRequest() = runTest {
        withSession { session ->
            val entry = entry()
            val cancelled = mutableListOf<Uuid>()
            val waiter = launch { session.awaitQueuedBotReply(10_000, {}, { entry }, { cancelled += it }) }
            runCurrent()
            waiter.cancelAndJoin()
            assertEquals(listOf(entry.id), cancelled)
            assertFalse(session.isInUse)
        }
    }

    @Test fun withdrawnEntryCannotReturnEarlierAssistantText() = runTest {
        withSession { session ->
            val entry = entry().apply { reply.complete(null) }
            val actual = session.awaitQueuedBotReply(1_000, {}, { entry }, { error("Already withdrawn") })
            assertTrue(actual.contains("撤回"))
            assertFalse(actual.contains("old"))
            assertFalse(session.isInUse)
        }
    }

    @Test fun pausedReplyNotifiesBotWithoutDiscardingRetainedQueueEntry() = runTest {
        withSession { session ->
            val entry = entry().apply { reply.completeExceptionally(MessageQueuePausedException()) }
            val actual = session.awaitQueuedBotReply(1_000, {}, { entry }, { error("Paused entry must remain queued") })
            assertTrue(actual.contains("队列已暂停"))
            assertFalse(session.isInUse)
        }
    }

    @Test fun providerFailureIsReportedWithoutForwardingAnyOtherTurn() = runTest {
        withSession { session ->
            val entry = entry().apply { reply.completeExceptionally(IllegalStateException("provider failed")) }
            val actual = session.awaitQueuedBotReply(1_000, {}, { entry }, {})
            assertTrue(actual.contains("本次回复失败"))
            assertFalse(actual.contains("old"))
            assertFalse(session.isInUse)
        }
    }

    @Test fun cancelledDeferredReportsInterruptionWithoutCancellingCaller() = runTest {
        withSession { session ->
            val entry = entry().apply { reply.cancel() }
            val actual = session.awaitQueuedBotReply(1_000, {}, { entry }, {})
            assertTrue(actual.contains("中断"))
            assertTrue(currentCoroutineContext().isActive)
            assertFalse(session.isInUse)
        }
    }

    @Test fun initializationFailureDoesNotSubmitAndAlwaysReleasesReference() = runTest {
        withSession { session ->
            var submitted = false
            val failure = runCatching {
                session.awaitQueuedBotReply(1_000, { error("database unavailable") }, {
                    submitted = true; entry()
                }, {})
            }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(submitted)
            assertFalse(session.isInUse)
        }
    }

    @Test fun emptyReplyRemainsExplicitInsteadOfReturningLastAssistant() = runTest {
        withSession { session ->
            val entry = entry().apply { reply.complete(" \n ") }
            val actual = session.awaitQueuedBotReply(1_000, {}, { entry }, {})
            assertTrue(actual.contains("无回复"))
            assertFalse(actual.contains("old"))
            assertFalse(session.isInUse)
        }
    }

    @Test fun replyTextStopsAtNextUserAndIncludesOnlyOwnAssistantParts() {
        val own = UIMessage.user("own")
        val messages = listOf(
            UIMessage.assistant("earlier"), own,
            UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("system notice"))),
            UIMessage.assistant("first"), UIMessage.assistant("second"),
            UIMessage.user("next queued turn"), UIMessage.assistant("someone else"),
        )
        assertEquals("first\nsecond", queuedReplyText(messages, own.id))
        assertEquals("", queuedReplyText(messages, Uuid.random()))
        assertEquals("", queuedReplyText(messages, messages.first().id))
    }

    @Test fun queuedVoiceWithoutPersistedUserCannotReadPreviousStreamingReply() {
        val requested = entry()
        val older = listOf(UIMessage.user("previous"), UIMessage.assistant("still streaming"))
        assertEquals("", queuedReplyText(older, requested.id))
        val own = UIMessage(id = requested.id, role = MessageRole.USER, parts = requested.parts)
        assertEquals("voice only", queuedReplyText(older + own + UIMessage.assistant("voice only"), requested.id))
    }

    private fun entry() = QueuedMessage(parts = listOf(UIMessagePart.Text("request")))

    private suspend fun withSession(block: suspend (ConversationSession) -> Unit) = coroutineScope {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(UIMessage.assistant("old"))))
        val session = ConversationSession(initial.id, initial, scope, debugLog = {}) {}
        try { block(session) } finally { session.cleanup(); scope.cancel() }
    }
}
