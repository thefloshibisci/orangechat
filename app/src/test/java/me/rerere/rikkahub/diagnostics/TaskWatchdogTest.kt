package me.rerere.rikkahub.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TaskWatchdogTest {
    @Test fun independentTimerCapturesQueuedLocalWorkEvenWhenItsExecutorIsBlocked() {
        val work = Executors.newSingleThreadExecutor()
        val timer = ScheduledThreadPoolExecutor(1)
        val release = CountDownLatch(1)
        val occupied = CountDownLatch(1)
        val reported = CountDownLatch(1)
        val report = AtomicReference<List<TaskWatchdog.Snapshot>>()
        val watchdog = TaskWatchdog(timer) { report.set(it); reported.countDown() }
        try {
            work.submit { occupied.countDown(); release.await() }
            assertTrue(occupied.await(3, TimeUnit.SECONDS))
            val chat = watchdog.begin("chat.send", 0).apply { stage("waiting for database IO") }
            val local = watchdog.begin("storage.count", 30)
            val pending = work.submit { local.stage("reading directory"); local.close() }
            assertTrue("Diagnostic must not wait for the blocked work executor", reported.await(3, TimeUnit.SECONDS))
            assertEquals("queued", report.get().single { it.task == "storage.count" }.stage)
            assertEquals("waiting for database IO", report.get().single { it.task == "chat.send" }.stage)
            release.countDown()
            pending.get(3, TimeUnit.SECONDS)
            chat.close()
            assertTrue(watchdog.snapshots().isEmpty())
        } finally { release.countDown(); work.shutdownNow(); timer.shutdownNow() }
    }

    @Test fun completedOrCancelledWorkDoesNotGenerateLateStallReports() {
        val timer = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val reported = CountDownLatch(1)
        val watchdog = TaskWatchdog(timer) { reported.countDown() }
        try {
            watchdog.begin("files.preview", 25).close()
            assertFalse(reported.await(100, TimeUnit.MILLISECONDS))
            assertTrue(watchdog.snapshots().isEmpty())
            assertTrue(timer.queue.isEmpty())
        } finally { timer.shutdownNow() }
    }

    @Test fun threadNamesWithRemoteAddressesOrPluginIdsAreReducedToFixedCategories() {
        assertEquals("http-worker", LoadingDiagnostics.threadCategory("OkHttp https://private.example/token"))
        assertEquals("plugin-worker", LoadingDiagnostics.threadCategory("plugin-quickjs-private-id"))
        assertEquals("other", LoadingDiagnostics.threadCategory("arbitrary secret name"))
        assertEquals("coroutine-worker", LoadingDiagnostics.threadCategory("DefaultDispatcher-worker-42"))
    }
}
