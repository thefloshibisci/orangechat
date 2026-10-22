package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class StartupRecoveryTest {
    @Test fun `fresh task starts once after screen and never from Application startup`() = runBlocking {
        var calls = 0
        val recovery = StartupRecovery(this, restore = { calls++ }, clearPending = {}, initializeServices = {})
        recovery.start(true, automatically = true)
        kotlinx.coroutines.yield()
        assertEquals(0, calls)
        assertEquals(StartupRecoveryState.ReadyToRestore, recovery.state.value)
        recovery.onRecoveryScreenShown()
        recovery.onRecoveryScreenShown()
        withTimeout(5_000) { recovery.awaitReady() }
        recovery.onRecoveryScreenShown()
        assertEquals(1, calls)
    }

    @Test fun `cancel before first frame prevents automatic restore`() = runBlocking {
        val recovery = StartupRecovery(this, restore = { error("must not restore") },
            clearPending = {}, initializeServices = {})
        recovery.start(true, automatically = true)
        recovery.cancelAndContinue()
        recovery.onRecoveryScreenShown()
        withTimeout(5_000) { recovery.awaitReady() }
    }

    @Test fun `pending restore waits for user and can be cancelled without opening archive or database`() = runBlocking {
        val order = mutableListOf<String>()
        val recovery = StartupRecovery(this, restore = { error("must not open archive") },
            clearPending = { order.add("clear") }, initializeServices = { order.add("services") })
        recovery.start(true)
        kotlinx.coroutines.yield()
        assertEquals(StartupRecoveryState.AwaitingConfirmation, recovery.state.value)
        assertTrue(recovery.blocksChatAccess)
        assertTrue(order.isEmpty())
        recovery.cancelAndContinue()
        withTimeout(5_000) { recovery.awaitReady() }
        assertEquals(listOf("clear", "services"), order)
    }

    @Test fun `slow restore leaves caller event loop responsive and workers wait for service initialization`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val recovery = StartupRecovery(this, restore = { progress ->
            progress(ChatRestoreProgress("会话 1/10"))
            entered.complete(Unit)
            release.await()
        }, clearPending = {}, initializeServices = { order.add("services") })
        recovery.start(true)
        recovery.retry()
        val worker = async { recovery.awaitReady(); order.add("worker") }
        withTimeout(5_000) { entered.await() }
        val heartbeat = CompletableDeferred<Unit>()
        launch { heartbeat.complete(Unit) }
        withTimeout(5_000) { heartbeat.await() }
        assertFalse(recovery.isReady)
        assertTrue(recovery.blocksChatAccess)
        assertEquals(StartupRecoveryState.Restoring(ChatRestoreProgress("会话 1/10")), recovery.state.value)
        assertTrue(order.isEmpty())
        assertFalse(worker.isCompleted)
        release.complete(Unit)
        withTimeout(5_000) { worker.await() }
        assertEquals(listOf("services", "worker"), order)
        assertFalse(recovery.blocksChatAccess)
    }

    @Test fun `cancel waits for rollback and pending cleanup before releasing startup and workers`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val rollingBack = CompletableDeferred<Unit>()
        val finishRollback = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val recovery = StartupRecovery(this, restore = {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    rollingBack.complete(Unit)
                    finishRollback.await()
                    order.add("rollback")
                }
            }
        }, clearPending = { order.add("clear") }, initializeServices = { order.add("services") })
        recovery.start(true)
        recovery.retry()
        withTimeout(5_000) { entered.await() }
        val worker = async { recovery.awaitReady(); order.add("worker") }
        recovery.cancelAndContinue()
        withTimeout(5_000) { rollingBack.await() }
        assertFalse(recovery.isReady)
        assertTrue(recovery.blocksChatAccess)
        assertTrue(order.isEmpty())
        finishRollback.complete(Unit)
        withTimeout(5_000) { worker.await() }
        assertEquals(listOf("rollback", "clear", "services", "worker"), order)
        assertFalse(recovery.blocksChatAccess)
    }

    @Test fun `failed recovery keeps workers blocked and can be retried without duplicate service startup`() = runBlocking {
        var attempts = 0
        var services = 0
        val recovery = StartupRecovery(this, restore = {
            if (++attempts == 1) error("unreadable backup")
        }, clearPending = {}, initializeServices = { services++ })
        recovery.start(true)
        recovery.retry()
        withTimeout(5_000) { recovery.state.first { it is StartupRecoveryState.Failed } }
        assertFalse(recovery.isReady)
        assertEquals(0, services)
        recovery.retry()
        withTimeout(5_000) { recovery.awaitReady() }
        recovery.retry()
        recovery.cancelAndContinue()
        assertEquals(2, attempts)
        assertEquals(1, services)
    }

    @Test fun `failed pending cleanup never releases database consumers`() = runBlocking {
        var services = 0
        val entered = CompletableDeferred<Unit>()
        val recovery = StartupRecovery(this, restore = { entered.complete(Unit); awaitCancellation() },
            clearPending = { error("cannot delete pending pointer") }, initializeServices = { services++ })
        recovery.start(true)
        recovery.retry()
        withTimeout(5_000) { entered.await() }
        recovery.cancelAndContinue()
        val failed = withTimeout(5_000) { recovery.state.first { it is StartupRecoveryState.Failed } }
        assertTrue((failed as StartupRecoveryState.Failed).message.contains("pending pointer"))
        assertFalse(recovery.isReady)
        assertEquals(0, services)
        assertTrue(recovery.blocksChatAccess)
    }

    @Test fun `ordinary startup does not launch restore`() = runBlocking {
        var services = 0
        val recovery = StartupRecovery(this, restore = { error("must not run") },
            clearPending = { error("must not run") }, initializeServices = { services++ })
        recovery.start(false)
        assertTrue(recovery.isReady)
        assertEquals(1, services)
    }

    @Test fun `chat prewarming is permitted only after restore and before workers are released`() = runBlocking {
        lateinit var recovery: StartupRecovery
        var initialized = false
        recovery = StartupRecovery(this, restore = {
            assertTrue(recovery.blocksChatAccess)
            assertFalse(recovery.isReady)
        }, clearPending = {}, initializeServices = {
            assertFalse(recovery.blocksChatAccess)
            assertFalse(recovery.isReady)
            initialized = true
        })
        recovery.start(true)
        recovery.retry()
        withTimeout(5_000) { recovery.awaitReady() }
        assertTrue(initialized)
    }
}
