package me.rerere.rikkahub.data.ai

import android.app.Application
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real loopback HTTP/SSE, with synthetic input and credentials only. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CompressionProviderStreamTest {
    private class Endpoint(private val hang: Boolean) : AutoCloseable {
        val received = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean()
        private val release = CountDownLatch(1)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/chat/completions") { exchange ->
                try {
                    val request = exchange.requestBody.bufferedReader().readText()
                    check(request.contains("\"stream\":true"))
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.bufferedWriter().use { output ->
                        fun send(event: String) { output.write("data: $event\n\n"); output.flush() }
                        if (hang) {
                            received.complete(Unit)
                            while (!release.await(20, TimeUnit.MILLISECONDS)) {
                                output.write(": keepalive\n\n")
                                output.flush()
                            }
                        } else {
                            send("""{"id":"test","model":"synthetic","choices":[{"delta":{"content":"complete summary"},"finish_reason":null}]}""")
                            send("""{"id":"test","model":"synthetic","choices":[{"delta":{},"finish_reason":"stop"}]}""")
                            send("[DONE]")
                        }
                    }
                } catch (e: Exception) {
                    if (!received.isCompleted) received.completeExceptionally(e)
                } finally {
                    exchange.close()
                }
            }
            start()
        }
        private val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.set(true) }
        }).build()

        suspend fun stream() = ChatCompletionsAPI(http, KeyRoulette.default()).streamText(
            ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "synthetic"),
            listOf(UIMessage.user("synthetic conversation")),
            TextGenerationParams(model = Model(modelId = "synthetic")),
        )

        override fun close() {
            release.countDown()
            server.stop(0)
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun realSseCompletesAndReturnsSummary() = runBlocking {
        Endpoint(hang = false).use { endpoint ->
            assertEquals("complete summary", collectCompressionSummary(5_000, endpoint::stream))
        }
    }

    @Test fun keepaliveDoesNotPreventDeadlineAndActualHttpCallIsCancelled() = runBlocking {
        Endpoint(hang = true).use { endpoint ->
            val failure = runCatching { collectCompressionSummary(2_000, endpoint::stream) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("超时"))
            assertTrue(endpoint.received.isCompleted)
            assertTrue(endpoint.cancelled.get())
        }
    }

    @Test fun userCancellationClosesActualHttpCall() = runBlocking {
        Endpoint(hang = true).use { endpoint ->
            val job = launch { collectCompressionSummary(10_000, endpoint::stream) }
            withTimeout(5_000) { endpoint.received.await() }
            job.cancelAndJoin()
            assertTrue(endpoint.cancelled.get())
        }
    }
}
