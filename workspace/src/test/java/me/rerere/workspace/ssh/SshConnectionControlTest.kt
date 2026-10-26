package me.rerere.workspace.ssh

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SshConnectionControlTest {
    @Test fun cancellationReturnsBeforeBlockingConnectThenClosesItsLateResource() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                awaitInterruptibly(
                    step = { entered.countDown(); release.await(); "resource" },
                    onAbandoned = { assertEquals("resource", it); closed.countDown() },
                )
            } catch (failure: Throwable) { error.set(failure) }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            worker.interrupt(); worker.join(3000)
            assertFalse(worker.isAlive)
            assertTrue(error.get() is InterruptedException)
            release.countDown()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
        } finally { release.countDown(); worker.interrupt(); worker.join(3000) }
    }

    @Test fun cancelledConnectFailureRunsAbandonedFailureCleanupExactlyOnce() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val calls = AtomicInteger()
        val worker = thread(isDaemon = true) {
            try {
                awaitInterruptibly<String>(
                    step = { entered.countDown(); release.await(); throw IOException("late failure") },
                    onAbandoned = { error("No resource was opened") },
                    onAbandonedFailure = { calls.incrementAndGet(); cleanup.countDown() },
                )
            } catch (_: InterruptedException) { }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            worker.interrupt(); worker.join(3000)
            assertFalse(worker.isAlive)
            release.countDown()
            assertTrue(cleanup.await(3, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { release.countDown(); worker.interrupt(); worker.join(3000) }
    }

    @Test fun alreadyInterruptedCallerNeverStartsAConnectionAttempt() {
        var calls = 0
        try {
            Thread.currentThread().interrupt()
            val result = runCatching { awaitInterruptibly({ calls++; 1 }, {}) }
            assertTrue(result.exceptionOrNull() is InterruptedException)
            assertEquals(0, calls)
        } finally { Thread.interrupted() }
    }

    @Test fun unwrappedConnectFailureKeepsItsOriginalCause() {
        val expected = IOException("connection rejected")
        val actual = runCatching { awaitInterruptibly<String>({ throw expected }, {}) }.exceptionOrNull()
        assertSame(expected, actual)
    }

    @Test fun nestedInterruptedIoIsCancellationButNetworkTimeoutIsNot() {
        assertTrue(IOException("sftp", InterruptedIOException("cancelled")).isInterruption())
        assertTrue(IOException("connection", InterruptedException()).isInterruption())
        assertFalse(IOException("network", SocketTimeoutException("timeout")).isInterruption())
        assertFalse(IOException("server rejected input").isInterruption())
    }

    @Test fun closedPoolCannotOpenOrProbeAnyConnection() {
        val pool = SshConnectionPool()
        try {
            pool.close(); pool.close()
            val config = SshConnectionConfig("example.test", username = "user", authType = SshAuthType.PASSWORD,
                password = "secret", remoteDir = "/work")
            assertTrue(runCatching { pool.use("work", config, retry = true) { "never" } }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { pool.probe(config) }.exceptionOrNull() is IllegalStateException)
            assertFalse(pool.isConnected("work"))
        } finally { pool.close() }
    }

    @Test fun invalidTimeoutsFailBeforeSchedulingOrOpeningConnections() {
        assertTrue(runCatching { SshConnectionPool(idleTimeoutMillis = 0) }.isFailure)
        assertTrue(runCatching { SshConnectionPool(sweepIntervalMillis = 0) }.isFailure)
        assertTrue(runCatching { SshConnectionPool(channelTimeoutMillis = 0) }.isFailure)
    }
}
