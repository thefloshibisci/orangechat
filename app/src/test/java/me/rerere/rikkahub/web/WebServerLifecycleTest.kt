package me.rerere.rikkahub.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WebServerLifecycleTest {
    @Test fun `concurrent starts create exactly one server`() = runBlocking {
        val lifecycle = WebServerLifecycle<Int>()
        val release = CompletableDeferred<Unit>()
        var creates = 0
        val commands = (1..12).map {
            async(start = CoroutineStart.UNDISPATCHED) {
                lifecycle.start { creates++; release.await(); creates }
            }
        }
        assertEquals(1, creates)
        release.complete(Unit)
        commands.awaitAll()
        assertEquals(1, creates)
    }

    @Test fun `restart awaits shutdown and blocks a following start`() = runBlocking {
        val lifecycle = WebServerLifecycle<String>()
        val events = mutableListOf<String>()
        lifecycle.start { "old" }
        val release = CompletableDeferred<Unit>()
        val restart = async(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.restart(
                create = { events += "new"; "new" },
                close = { events += "stop $it"; release.await(); events += "stopped" },
            )
        }
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.start { events += "duplicate"; "duplicate" }
        }
        assertEquals(listOf("stop old"), events)
        release.complete(Unit)
        awaitAll(restart, next)
        assertEquals(listOf("stop old", "stopped", "new"), events)
        lifecycle.stop { events += "stop $it" }
        assertEquals("stop new", events.last())
    }

    @Test fun `failed stop retains ownership and never starts a replacement`() = runBlocking {
        val lifecycle = WebServerLifecycle<String>()
        lifecycle.start { "old" }
        try {
            lifecycle.restart({ fail("must not start"); "new" }, { error("stop failed") })
            fail("must propagate failure")
        } catch (_: IllegalStateException) { }
        lifecycle.stop { assertEquals("old", it) }
        lifecycle.start { "recovered" }
        lifecycle.stop { assertEquals("recovered", it) }
    }

    @Test fun `failed creation can be retried and stopping twice closes once`() = runBlocking {
        val lifecycle = WebServerLifecycle<String>()
        try { lifecycle.start { error("bind failed") }; fail() } catch (_: IllegalStateException) { }
        lifecycle.start { "good" }
        var closes = 0
        repeat(2) { lifecycle.stop { assertEquals("good", it); closes++ } }
        assertEquals(1, closes)
    }
}
