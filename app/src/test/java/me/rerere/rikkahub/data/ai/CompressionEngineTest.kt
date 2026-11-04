package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class CompressionEngineTest {
    @Test fun `remote error naming another model preserves selected model and cause`() = runBlocking {
        val remote = IllegalStateException("unknown provider for model gemini-3.8-flash-high")
        val failure = runCatching {
            withCompressionModelContext("test provider", "gpt-6-astra") { throw remote }
        }.exceptionOrNull()!!
        assertTrue(failure.message.orEmpty().contains("test provider / gpt-6-astra"))
        assertTrue(failure.message.orEmpty().contains(remote.message!!))
        assertSame(remote, failure.cause)
    }

    @Test fun `model context preserves successful summary and caller cancellation`() = runBlocking {
        assertEquals("summary", withCompressionModelContext("provider", "model") { "summary" })
        val cancelled = CancellationException("cancelled")
        val failure = runCatching {
            withCompressionModelContext("provider", "model") { throw cancelled }
        }.exceptionOrNull()
        assertSame(cancelled, failure)
    }

    private fun delta(text: String = "", reason: String? = null, reasoning: Boolean = false) = MessageChunk(
        "test", "test", listOf(UIMessageChoice(0, UIMessage.assistant(text).let {
            if (reasoning) it.copy(parts = listOf(UIMessagePart.Reasoning(text))) else it
        }, null, reason)),
    )

    @Test fun `stream combines chunks and excludes reasoning and empty usage events`() = runBlocking {
        val result = collectCompressionSummary {
            flowOf(delta("private thinking", reasoning = true), delta("first "),
                MessageChunk("test", "test", emptyList()), delta("second", "stop"))
        }
        assertEquals("first second", result)
    }

    @Test fun `keepalive only stream times out and closes connection`() = runBlocking {
        var closed = false
        val failure = runCatching {
            collectCompressionSummary(100) {
                flow {
                    try { while (true) { emit(MessageChunk("test", "test", emptyList())); delay(10) } }
                    finally { closed = true }
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("超时"))
        assertTrue(closed)
    }

    @Test fun `caller cancellation propagates and closes stream`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var closed = false
        val job = launch {
            collectCompressionSummary {
                flow {
                    try { started.complete(Unit); awaitCancellation() }
                    finally { closed = true }
                }
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(closed)
        assertTrue(job.isCancelled)
    }

    @Test fun `partial or reasoning only results never become successful summary`() = runBlocking {
        for (stream in listOf(flowOf(delta("partial", "length")), flowOf(delta("thinking", reasoning = true)),
            flowOf(delta("partial"), delta("more", "max_tokens")))) {
            assertTrue(runCatching { collectCompressionSummary { stream } }.isFailure)
        }
        val tool = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("id", "call", "{}")))
        assertTrue(runCatching {
            collectCompressionSummary { flowOf(MessageChunk("test", "test", listOf(UIMessageChoice(0, tool, null, null)))) }
        }.isFailure)
    }

    @Test fun `operation deadline aborts before saving`() = runBlocking {
        var saved = false
        val failure = runCatching {
            withCompressionDeadline(100) {
                compressTextOnce(listOf("x".repeat(48_000))) { delay(1000); "summary" }
                saved = true
                Unit
            }
        }.exceptionOrNull()
        assertFalse(saved)
        assertTrue(failure?.message.orEmpty().contains("超时"))
    }

    @Test fun `successful full text response is used without an extra reduction request`() = runBlocking {
        var calls = 0
        val result = compressTextOnce(listOf("x".repeat(300_000))) {
            calls++
            collectCompressionSummary { flowOf(delta("whole summary", "stop")) }
        }
        assertEquals("whole summary", result)
        assertEquals(1, calls)
    }
}
