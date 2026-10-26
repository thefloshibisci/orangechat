package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class QueuedGenerationLifecycleTest {
    @Test fun replacementWaitsForAnEarlierStopsDelayedCheckpoint() = runTest {
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val session = ConversationSession(conversation.id, conversation, backgroundScope, debugLog = {}, onIdle = {})
        val checkpoint = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val old = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        session.setJob(old); old.start(); runCurrent()
        val stop = launch {
            session.stopQueuedGeneration(true) { checkpoint.await(); events += "stop checkpoint" }
        }
        runCurrent()
        val registrationBarrier = session.blockQueueDispatch()
        val replacement = launch(start = CoroutineStart.LAZY) {
            old.join()
            session.awaitQueueDispatchUnblocked()
            events += "replacement"
        }
        session.setJob(replacement)
        registrationBarrier.close()
        replacement.start(); runCurrent()
        assertTrue(events.isEmpty())
        checkpoint.complete(Unit); runCurrent(); stop.join()
        assertEquals(listOf("stop checkpoint", "replacement"), events)
        assertFalse(replacement.isCancelled)
        session.cleanup()
    }

    @Test fun oldRequestCancellationDoesNotOwnARegisteredReplacement() = runTest {
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val session = ConversationSession(conversation.id, conversation, backgroundScope, debugLog = {}, onIdle = {})
        val release = CompletableDeferred<Unit>()
        val old = launch(start = CoroutineStart.LAZY) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
        }
        session.setJob(old); old.start(); runCurrent()
        var replacementRan = false
        val replacement = launch(start = CoroutineStart.LAZY) { old.join(); replacementRan = true }
        session.setJob(replacement); replacement.start(); runCurrent()
        val stopped = session.stopQueuedGeneration(false, ownsRequest = { session.getJob() === old }) {
            error("old observer cannot finalize replacement")
        }
        assertFalse(stopped)
        assertTrue(replacement.isActive)
        release.complete(Unit); runCurrent()
        assertTrue(replacementRan)
        assertFalse(replacement.isCancelled)
        session.cleanup()
    }

    @Test fun stopThenResumeWaitsForOldCleanupAndPersistedInterruption() = runTest {
        val releaseCleanup = CompletableDeferred<Unit>()
        val releaseCheckpoint = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        lateinit var session: ConversationSession
        fun drain() {
            session.dispatchNextQueuedMessage { entry ->
                launch(start = CoroutineStart.LAZY) {
                    events += "new request"
                    session.messageQueue.complete(entry.id, "reply")
                }
            }?.start()
        }
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        session = ConversationSession(conversation.id, conversation, backgroundScope, debugLog = {},
            onGenerationIdle = { drain() }, onIdle = {})
        val old = launch(start = CoroutineStart.LAZY) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { releaseCleanup.await(); events += "old cleanup" } }
        }
        session.setJob(old)
        old.start()
        runCurrent()
        val stop = launch {
            session.stopQueuedGeneration(pauseQueue = true) {
                releaseCheckpoint.await()
                events += "checkpoint"
            }
        }
        runCurrent()
        assertTrue(session.messageQueue.state.value.paused)
        session.messageQueue.enqueue(listOf(UIMessagePart.Text("next")))
        session.messageQueue.resume()
        drain()
        runCurrent()
        assertTrue(events.isEmpty())
        releaseCleanup.complete(Unit)
        runCurrent()
        assertEquals(listOf("old cleanup"), events)
        releaseCheckpoint.complete(Unit)
        runCurrent()
        stop.join()
        assertEquals(listOf("old cleanup", "checkpoint", "new request"), events)
        assertFalse(session.messageQueue.state.value.paused)
        session.cleanup()
    }

    @Test fun targetedStopCannotCancelAnotherOwnersTurn() = runTest {
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val session = ConversationSession(conversation.id, conversation, backgroundScope, debugLog = {}, onIdle = {})
        val other = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        session.setJob(other); other.start(); runCurrent()
        var checkpointed = false
        val stopped = session.stopQueuedGeneration(false, ownsRequest = { false }) { checkpointed = true }
        assertFalse(stopped)
        assertTrue(other.isActive)
        assertFalse(checkpointed)
        assertFalse(session.messageQueue.state.value.paused)
        session.cleanup(); runCurrent()
    }

    @Test fun cancellationOfStopCallerStillFinishesCheckpointAndReleasesBarrier() = runTest {
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val session = ConversationSession(conversation.id, conversation, backgroundScope, debugLog = {}, onIdle = {})
        val release = CompletableDeferred<Unit>()
        var checkpointed = false
        val stop = launch {
            session.stopQueuedGeneration(false) { release.await(); checkpointed = true }
        }
        runCurrent(); stop.cancel()
        session.messageQueue.enqueue(listOf(UIMessagePart.Text("next")))
        assertNull(session.dispatchNextQueuedMessage { error("must remain blocked") })
        release.complete(Unit); runCurrent()
        assertTrue(checkpointed)
        val job = session.dispatchNextQueuedMessage { entry ->
            launch(start = CoroutineStart.LAZY) { session.messageQueue.complete(entry.id, "done") }
        }
        assertNotNull(job)
        job!!.start(); runCurrent(); session.cleanup()
    }

    @Test fun failedCheckpointPausesQueueAndDoesNotLeavePermanentDispatchBarrier() = runTest {
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val session = ConversationSession(conversation.id, conversation, backgroundScope, debugLog = {}, onIdle = {})
        session.messageQueue.enqueue(listOf(UIMessagePart.Text("next")))
        val error = runCatching {
            session.stopQueuedGeneration(false) { error("database write failed") }
        }.exceptionOrNull()
        assertEquals("database write failed", error?.message)
        assertTrue(session.messageQueue.state.value.paused)
        assertNull(session.dispatchNextQueuedMessage { error("must remain paused") })
        session.messageQueue.resume()
        val job = session.dispatchNextQueuedMessage { entry ->
            launch(start = CoroutineStart.LAZY) { session.messageQueue.complete(entry.id, "done") }
        }
        assertNotNull(job)
        job!!.start(); runCurrent(); session.cleanup()
    }

    @Test fun deletionDuringOldCleanupCannotPersistOrDispatchAnyNewTurn() = runTest {
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val session = ConversationSession(conversation.id, conversation, backgroundScope, debugLog = {}, onIdle = {})
        val release = CompletableDeferred<Unit>()
        val old = launch(start = CoroutineStart.LAZY) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
        }
        session.setJob(old); old.start(); runCurrent()
        var checkpointed = false
        val stop = launch { session.stopQueuedGeneration(true) { checkpointed = true } }
        runCurrent()
        val queued = session.messageQueue.enqueue(listOf(UIMessagePart.Text("must not send")))!!
        session.markDeleted()
        release.complete(Unit); runCurrent(); stop.join()
        assertFalse(checkpointed)
        // Admission while paused has already notified this observer; deletion cannot replace that outcome.
        assertTrue(runCatching { queued.reply.await() }.exceptionOrNull() is MessageQueuePausedException)
        assertTrue(queued.completion.isCancelled)
        assertTrue(session.messageQueue.state.value.messages.isEmpty())
        assertNull(session.dispatchNextQueuedMessage { error("deleted session cannot dispatch") })
        session.cleanup()
    }
}
