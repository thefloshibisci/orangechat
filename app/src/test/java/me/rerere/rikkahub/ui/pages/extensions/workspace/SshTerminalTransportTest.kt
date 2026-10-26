package me.rerere.rikkahub.ui.pages.extensions.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SshTerminalTransportTest {
    private class Handle(val exit: Int? = 0, override val connectionLost: Boolean = false) : SshTerminalHandle {
        override val inputStream = ByteArrayInputStream(byteArrayOf(1, 2))
        override val outputStream = ByteArrayOutputStream()
        val closed = AtomicBoolean(false)
        val closeSignal = CountDownLatch(1)
        val resizeSignal = CountDownLatch(1)
        var resized: Pair<Int, Int>? = null
        var controlThread: Thread? = null
        override fun resize(columns: Int, rows: Int) {
            controlThread = Thread.currentThread(); resized = columns to rows; resizeSignal.countDown()
        }
        override fun waitFor() = exit
        override fun close() { closed.set(true); closeSignal.countDown() }
    }

    @Test fun streamsWindowControlAndExitStatusComeFromOwnRemoteShell() {
        val handle = Handle(7)
        val transport = SshTerminalTransport("user@host") { columns, rows ->
            assertEquals(80, columns); assertEquals(24, rows); handle
        }
        try {
            transport.start(80, 24)
            assertSame(handle.inputStream, transport.getInputStream())
            assertSame(handle.outputStream, transport.getOutputStream())
            val caller = Thread.currentThread()
            transport.setWindowSize(100, 30)
            assertTrue(handle.resizeSignal.await(5, TimeUnit.SECONDS))
            assertEquals(100 to 30, handle.resized)
            assertNotSame(caller, handle.controlThread)
            assertEquals(7, transport.waitFor())
            assertTrue(transport.describeExit(7).contains("code 7"))
        } finally { transport.close() }
        assertTrue(handle.closeSignal.await(5, TimeUnit.SECONDS))
    }

    @Test fun closeBeforeStartNeverOpensConnection() {
        var opens = 0
        val transport = SshTerminalTransport("host") { _, _ -> opens++; Handle() }
        transport.close()
        assertNotNull(runCatching { transport.start(80, 24) }.exceptionOrNull())
        assertEquals(0, opens)
        transport.close()
    }

    @Test fun killDuringConnectInterruptsOpeningThreadAndRejectsLateShell() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handle = Handle()
        val failure = AtomicReference<Throwable?>()
        val transport = SshTerminalTransport("host") { _, _ ->
            entered.countDown()
            try { release.await() } catch (_: InterruptedException) {
                interrupted.countDown()
                // Model a connect implementation that returns a channel after handling interruption.
                release.await()
            }
            handle
        }
        val starter = Thread {
            try { transport.start(80, 24) } catch (error: Throwable) { failure.set(error) }
        }
        starter.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            transport.kill()
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            release.countDown(); starter.join(5_000)
            assertFalse(starter.isAlive)
            assertTrue(handle.closed.get())
            assertNotNull(failure.get())
            assertNotNull(runCatching { transport.getInputStream() }.exceptionOrNull())
        } finally { release.countDown(); transport.close(); starter.join(5_000) }
    }

    @Test fun droppedConnectionIsDifferentFromReportedRemoteExit() {
        val transport = SshTerminalTransport("host") { _, _ -> Handle(null, true) }
        try {
            transport.start(80, 24)
            assertEquals(-1, transport.waitFor())
            assertEquals("Connection lost", transport.describeExit(-1))
        } finally { transport.close() }
    }

    @Test fun closeAndKillNeverRequireAStartedShell() {
        val transport = SshTerminalTransport("host") { _, _ -> error("must not open") }
        transport.kill(); transport.close(); transport.kill(); transport.close()
        assertEquals("Session closed", transport.describeExit(-1))
    }
}
