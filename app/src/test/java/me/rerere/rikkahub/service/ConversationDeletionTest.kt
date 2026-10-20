package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationDeletionTest {
    @Test
    fun `delete waits for cancellation cleanup before deleting and refuses late metadata`() = runBlocking {
        withSession { session ->
            val running = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val finishCleanup = CompletableDeferred<Unit>()
            val job = launch(start = CoroutineStart.LAZY) {
                try { running.complete(Unit); awaitCancellation() }
                finally {
                    withContext(NonCancellable) {
                        // Proves deletion does not hold saveMutex while joining the job.
                        session.saveMutex.withLock { cleanup.complete(Unit); finishCleanup.await() }
                    }
                }
            }
            session.setJob(job)
            job.start()
            running.await()
            var deleted = false
            val deleting = launch(start = CoroutineStart.UNDISPATCHED) {
                session.deleteAndAwait { deleted = true }
            }
            cleanup.await()
            assertTrue(session.isDeleted)
            assertFalse(deleted)
            finishCleanup.complete(Unit)
            deleting.join()
            assertTrue(deleted)
            assertFalse(session.isGenerating)
            assertTrue(session.state.value.messageNodes.isEmpty())
            var persisted = false
            try {
                session.saveGeneratedTitle("late") { persisted = true; true }
                fail("Deleted session must reject auxiliary writes")
            } catch (_: CancellationException) { }
            assertFalse(persisted)
        }
    }

    @Test
    fun `delete serializes with an already started save and prevents queued saves`() = runBlocking {
        withSession { session ->
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val save = launch(start = CoroutineStart.UNDISPATCHED) {
                session.withStateLock { started.complete(Unit); finish.await(); order += "save" }
            }
            started.await()
            val deleting = launch(start = CoroutineStart.UNDISPATCHED) {
                session.deleteAndAwait { order += "delete" }
            }
            var queuedRan = false
            val queued = launch(start = CoroutineStart.UNDISPATCHED) {
                session.withStateLock { queuedRan = true }
            }
            finish.complete(Unit)
            save.join(); deleting.join(); queued.join()
            assertEquals(listOf("save", "delete"), order)
            assertFalse(queuedRan)
            assertTrue(queued.isCancelled)
        }
    }

    @Test
    fun `deleted session rejects both forced and low priority generation claims`() = runBlocking {
        withSession { session ->
            session.deleteAndAwait { }
            val forced = Job()
            session.setJob(forced)
            assertTrue(forced.isCancelled)
            val passive = Job()
            try { assertFalse(session.tryClaimGeneration(passive)) }
            finally { passive.cancel() }
            assertFalse(session.isGenerating)
            assertNull(session.getJob())
        }
    }

    @Test
    fun `failed delete preserves loaded history and keeps late writers blocked`() = runBlocking {
        withSession { session ->
            val before = session.state.value
            val error = IllegalStateException("database failure")
            try { session.deleteAndAwait { throw error }; fail("Expected error") }
            catch (actual: IllegalStateException) { assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === error }) }
            assertSame(before, session.state.value)
            assertTrue(session.isDeleted)
        }
    }

    @Test
    fun `confirmed failed deletion can reopen without losing original history`() = runBlocking {
        withSession { session ->
            val before = session.state.value
            try { session.deleteAndAwait { error("delete failed") } }
            catch (_: IllegalStateException) { }
            session.reopenAfterFailedDelete()
            assertFalse(session.isDeleted)
            session.saveGeneratedTitle("retryable") { true }
            assertEquals(before.copy(title = "retryable"), session.state.value)
        }
    }

    private suspend fun withSession(block: suspend (ConversationSession) -> Unit) = coroutineScope {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(UIMessage.user("keep"))))
        val session = ConversationSession(initial.id, initial, scope, debugLog = {}) {}
        try { block(session) } finally { session.cleanup(); scope.cancel() }
    }
}
