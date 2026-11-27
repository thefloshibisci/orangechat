package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationQueueSessionTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))
    private fun session(scope: CoroutineScope, wake: () -> Unit = {}, idle: () -> Unit = {}): ConversationSession {
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(UIMessage.user("old"))))
        return ConversationSession(
            id = initial.id,
            initial = initial,
            scope = scope, debugLog = {}, onGenerationIdle = { wake() }, onIdle = { idle() },
        )
    }

    @Test fun lazyRegistrationPreventsSecondDispatchAndLowPriorityClaim() = runTest {
        val session = session(backgroundScope)
        val proactive = Job()
        try {
            val entry = session.messageQueue.enqueue(text("queued"))!!
            val job = session.dispatchNextQueuedMessage {
                assertEquals(entry.id, it.id)
                backgroundScope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            }!!
            assertFalse(job.isActive)
            assertTrue(session.isGenerating)
            assertNull(session.dispatchNextQueuedMessage { error("Second turn cannot start") })
            assertFalse(session.tryClaimGeneration(proactive))
            job.start()
            runCurrent()
        } finally { proactive.cancel(); session.cleanup(); runCurrent() }
    }

    @Test fun cancellationTailBlocksDispatchUntilActuallyComplete() = runTest {
        var wakes = 0
        val session = session(backgroundScope, wake = { wakes++ })
        val release = CompletableDeferred<Unit>()
        val old = backgroundScope.launch(start = CoroutineStart.LAZY) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { release.await() } }
        }
        try {
            session.setJob(old)
            old.start()
            runCurrent()
            old.cancel()
            runCurrent()
            assertFalse(old.isActive)
            assertFalse(old.isCompleted)
            assertTrue(session.isGenerating)
            session.messageQueue.enqueue(text("new"))
            assertNull(session.dispatchNextQueuedMessage { error("Old checkpoint still writing") })
            release.complete(Unit)
            runCurrent()
            assertFalse(session.isGenerating)
            assertEquals(1, wakes)
            assertNotNull(session.dispatchNextQueuedMessage {
                backgroundScope.launch(start = CoroutineStart.LAZY) { }
            })
        } finally { release.complete(Unit); session.cleanup(); runCurrent() }
    }

    @Test fun stopBarrierProtectsFinalCheckpointAfterImmediateNewSend() = runTest {
        var wakes = 0
        val session = session(backgroundScope, wake = { wakes++ })
        try {
            val old = backgroundScope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            session.setJob(old)
            old.start()
            runCurrent()
            val stop = session.blockQueueDispatch()
            session.messageQueue.pause()
            session.cancelJobs()
            session.messageQueue.enqueue(text("immediate send"))
            session.messageQueue.resume()
            runCurrent()
            assertTrue(old.isCompleted)
            assertEquals(0, wakes)
            assertNull(session.dispatchNextQueuedMessage { error("Stop checkpoint not saved") })
            stop.close()
            stop.close()
            assertEquals(1, wakes)
            assertNotNull(session.dispatchNextQueuedMessage {
                backgroundScope.launch(start = CoroutineStart.LAZY) { }
            })
        } finally { session.cleanup(); runCurrent() }
    }

    @Test fun approvalWaitsUntilAllStopBarriersClose() = runTest {
        val session = session(backgroundScope)
        try {
            val first = session.blockQueueDispatch()
            val second = session.blockQueueDispatch()
            var continued = false
            val approval = backgroundScope.launch { session.awaitQueueDispatchUnblocked(); continued = true }
            runCurrent()
            assertFalse(continued)
            first.close()
            runCurrent()
            assertFalse(continued)
            second.close()
            runCurrent()
            assertTrue(continued)
            assertTrue(approval.isCompleted)
        } finally { session.cleanup(); runCurrent() }
    }

    @Test fun pausedQueueSurvivesIdleTimeoutAndPreventsProactiveClaim() = runTest {
        var removed = 0
        val session = session(backgroundScope, idle = { removed++ })
        val proactive = Job()
        try {
            session.acquire()
            session.release()
            session.messageQueue.enqueue(text("keep"))
            session.messageQueue.pause()
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(0, removed)
            assertTrue(session.isInUse)
            assertFalse(session.tryClaimGeneration(proactive))
        } finally { proactive.cancel(); session.cleanup(); runCurrent() }
    }

    @Test fun deletingSessionClearsObserversAndCannotDispatch() = runTest {
        var wakes = 0
        val session = session(backgroundScope, wake = { wakes++ })
        val entry = session.messageQueue.enqueue(text("pending"))!!
        session.markDeleted()
        runCurrent()
        assertTrue(entry.completion.isCancelled)
        assertNull(entry.reply.await())
        assertFalse(session.messageQueue.hasPendingOrInFlight)
        assertNull(session.dispatchNextQueuedMessage { error("Deleted session cannot dispatch") })
        val lateJob = Job()
        session.setJob(lateJob)
        assertTrue(lateJob.isCancelled)
        assertEquals(0, wakes)
        session.cleanup()
    }

    @Test fun oldCompletionDoesNotClearReplacementOrWakeTooEarly() = runTest {
        var wakes = 0
        val session = session(backgroundScope, wake = { wakes++ })
        val old = Job()
        val current = Job()
        try {
            session.setJob(old)
            session.setJob(current, cancelPrevious = false)
            old.complete()
            assertSame(current, session.getJob())
            assertTrue(session.isGenerating)
            assertEquals(0, wakes)
            current.complete()
            assertNull(session.getJob())
            assertFalse(session.isGenerating)
            assertEquals(1, wakes)
        } finally { session.cleanup(); runCurrent() }
    }

    @Test fun completedJobWakeRunsOutsideSessionMonitor() = runTest {
        var lockWasHeld = false
        var wakes = 0
        lateinit var session: ConversationSession
        session = session(backgroundScope, wake = { wakes++; lockWasHeld = Thread.holdsLock(session) })
        try {
            session.setJob(Job().apply { complete() })
            assertEquals(1, wakes)
            assertFalse(lockWasHeld)
            val barrier = session.blockQueueDispatch()
            barrier.close()
            assertEquals(2, wakes)
            assertFalse(lockWasHeld)
        } finally { session.cleanup(); runCurrent() }
    }

    @Test fun unloadedConversationAndPendingToolsBlockDispatch() = runTest {
        val session = session(backgroundScope)
        try {
            session.messageQueue.enqueue(text("pending"))
            session.state.value = session.state.value.copy(messageNodesLoaded = false)
            assertNull(session.dispatchNextQueuedMessage { error("History must load first") })
            val tool = UIMessagePart.Tool(toolCallId = "call", toolName = "test", input = "{}",
                approvalState = ToolApprovalState.Pending)
            session.state.value = session.state.value.copy(messageNodesLoaded = true,
                messageNodes = listOf(MessageNode.of(UIMessage.assistant("").copy(parts = listOf(tool)))))
            assertNull(session.dispatchNextQueuedMessage { error("Approval required") })
            session.state.value = session.state.value.copy(messageNodes = listOf(MessageNode.of(
                UIMessage.assistant("").copy(parts = listOf(tool.copy(approvalState = ToolApprovalState.Approved))))))
            assertNull(session.dispatchNextQueuedMessage { error("Approved tool still needs continuation") })
        } finally { session.cleanup(); runCurrent() }
    }

    @Test fun failingJobCreationResolvesReservedItemAndPausesFollowingQueue() = runTest {
        val session = session(backgroundScope)
        try {
            val first = session.messageQueue.enqueue(text("first"))!!
            session.messageQueue.enqueue(text("second"))
            val failure = IllegalStateException("cannot construct job")
            assertSame(failure, runCatching {
                session.dispatchNextQueuedMessage { throw failure }
            }.exceptionOrNull())
            val replyError = runCatching { first.reply.await() }.exceptionOrNull()
            assertTrue(replyError === failure || replyError?.cause === failure)
            assertTrue(first.completion.isCompleted)
            assertTrue(session.messageQueue.state.value.paused)
            assertEquals(1, session.messageQueue.state.value.messages.size)
            assertTrue(session.messageQueue.inFlightMessages.isEmpty())
        } finally { session.cleanup(); runCurrent() }
    }

    @Test fun submitMessageOnIdleLoadedSessionDispatchesWithoutFlashingPendingQueue() = runTest {
        val session = session(backgroundScope)
        val emittedPendingCounts = mutableListOf<Int>()
        val collector = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            session.messageQueue.state.collect { emittedPendingCounts += it.messages.size }
        }
        try {
            val result = session.submitMessage(
                enqueuePending = { session.messageQueue.enqueue(text("hello")) },
                enqueueInFlight = { session.messageQueue.enqueueInFlight(text("hello")) },
                createJob = { backgroundScope.launch(start = CoroutineStart.LAZY) { awaitCancellation() } },
            )
            assertNotNull(result)
            assertNotNull(result!!.immediateJob)
            assertTrue(session.isGenerating)
            assertEquals(1, session.messageQueue.inFlightMessages.size)
            assertEquals(result.message.id, session.messageQueue.inFlightMessages.single().id)
            assertTrue(session.messageQueue.state.value.messages.isEmpty())
            assertTrue(emittedPendingCounts.all { it == 0 })
        } finally {
            collector.cancel()
            session.cleanup()
            runCurrent()
        }
    }

    @Test fun submitMessageWhileBusyPausedOrUnloadedQueuesPendingItem() = runTest {
        val busySession = session(backgroundScope)
        val activeJob = backgroundScope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        try {
            busySession.setJob(activeJob)
            val queuedResult = busySession.submitMessage(
                enqueuePending = { busySession.messageQueue.enqueue(text("queued while busy")) },
                enqueueInFlight = { busySession.messageQueue.enqueueInFlight(text("queued while busy")) },
                createJob = { error("Must not create immediate job while generating") },
            )!!
            assertNull(queuedResult.immediateJob)
            assertEquals(1, busySession.messageQueue.state.value.messages.size)
        } finally {
            activeJob.cancel()
            busySession.cleanup()
            runCurrent()
        }

        val pausedSession = session(backgroundScope)
        try {
            pausedSession.messageQueue.enqueue(text("first pending"))
            pausedSession.messageQueue.pause()
            val queuedWhilePaused = pausedSession.submitMessage(
                enqueuePending = { pausedSession.messageQueue.enqueue(text("second pending")) },
                enqueueInFlight = { pausedSession.messageQueue.enqueueInFlight(text("second pending")) },
                createJob = { error("Must not dispatch into paused queue") },
            )!!
            assertNull(queuedWhilePaused.immediateJob)
            assertEquals(2, pausedSession.messageQueue.state.value.messages.size)
        } finally {
            pausedSession.cleanup()
            runCurrent()
        }

        val unloadedSession = session(backgroundScope)
        try {
            unloadedSession.state.value = unloadedSession.state.value.copy(messageNodesLoaded = false)
            val queuedWhileUnloaded = unloadedSession.submitMessage(
                enqueuePending = { unloadedSession.messageQueue.enqueue(text("waiting for history")) },
                enqueueInFlight = { unloadedSession.messageQueue.enqueueInFlight(text("waiting for history")) },
                createJob = { error("Must not dispatch before history loads") },
            )!!
            assertNull(queuedWhileUnloaded.immediateJob)
            assertEquals(1, unloadedSession.messageQueue.state.value.messages.size)
        } finally {
            unloadedSession.cleanup()
            runCurrent()
        }
    }
}
