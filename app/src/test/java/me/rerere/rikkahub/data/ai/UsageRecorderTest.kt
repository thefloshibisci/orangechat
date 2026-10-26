package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

class UsageRecorderTest {
    private val setting = ProviderSetting.OpenAI()
    private val params = TextGenerationParams(Model(modelId = "api-model-name"))
    private val messages = listOf(UIMessage.user("private request body"))
    private val tag = UsageTag(UsageSource.PROACTIVE, Uuid.random(), Uuid.random())

    @Test
    fun `cumulative partial snapshots write one row and preserve delegate stream and identity`() = runBlocking {
        withFixture { fixture ->
            val final = TokenUsage(20, 5, 8, 25)
            val chunks = listOf(
                chunk(TokenUsage(promptTokens = 20)), chunk(null),
                chunk(TokenUsage(completionTokens = 5)), chunk(final), chunk(final),
            )
            val delegate = FakeProvider(chunks)
            val provider = fixture.recorder.wrap(delegate, tag)
            assertEquals(chunks, provider.streamText(setting, messages, params).toList())
            fixture.awaitWrites()
            val row = fixture.records.single()
            assertEquals(20, row.promptTokens)
            assertEquals(5, row.completionTokens)
            assertEquals(8, row.cachedTokens)
            assertEquals(25, row.totalTokens)
            assertTrue(row.usageReported)
            assertEquals("SUCCESS", row.outcome)
            assertEquals(tag.source.name, row.source)
            assertEquals(tag.assistantId.toString(), row.assistantId)
            assertEquals(tag.conversationId.toString(), row.conversationId)
            assertEquals(setting.id.toString(), row.providerId)
            assertEquals(params.model.id.toString(), row.modelId)
            assertEquals("api-model-name", row.modelName)
            assertEquals(123L, row.createdAt)
            assertEquals(1, delegate.streamCalls)
            assertSame(messages, delegate.requestMessages)
            assertSame(params, delegate.requestParams)
        }
    }

    @Test
    fun `reported components supply exact total when provider total is absent`() = runBlocking {
        withFixture { fixture ->
            val provider = fixture.recorder.wrap(FakeProvider(listOf(
                chunk(TokenUsage(promptTokens = 20, completionTokens = 5)),
            )), tag)
            provider.streamText(setting, messages, params).toList()
            fixture.awaitWrites()
            assertEquals(25, fixture.records.single().totalTokens)
            assertEquals(20, fixture.records.single().promptTokens)
        }
    }

    @Test
    fun `Claude split counters are combined and a larger Gemini reported total is preserved`() = runBlocking {
        withFixture { fixture ->
            val claude = fixture.recorder.wrap(FakeProvider(listOf(
                chunk(TokenUsage(promptTokens = 100, totalTokens = 100)),
                chunk(TokenUsage(completionTokens = 20, totalTokens = 20)),
            )), UsageTag(UsageSource.CHAT))
            claude.streamText(setting, messages, params).toList()
            val gemini = fixture.recorder.wrap(FakeProvider(listOf(
                chunk(TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = 160)),
            )), UsageTag(UsageSource.TITLE))
            gemini.streamText(setting, messages, params).toList()
            fixture.awaitWrites()
            val claudeRow = fixture.records.single { it.source == "CHAT" }
            assertEquals(100, claudeRow.promptTokens)
            assertEquals(20, claudeRow.completionTokens)
            assertEquals(120, claudeRow.totalTokens)
            assertEquals(160, fixture.records.single { it.source == "TITLE" }.totalTokens)
        }
    }

    @Test
    fun `tool rounds and retries remain separate rows`() = runBlocking {
        withFixture { fixture ->
            val provider = fixture.recorder.wrap(FakeProvider(listOf(chunk(TokenUsage(20, 5, 8, 25)))), tag)
            repeat(3) { provider.streamText(setting, messages, params).toList() }
            provider.generateText(setting, messages, params)
            fixture.awaitWrites()
            assertEquals(4, fixture.records.size)
            assertEquals(80, fixture.records.sumOf { it.promptTokens })
        }
    }

    @Test
    fun `stream failure and upstream cancellation retain partial usage and exact failure`() = runBlocking {
        for (failure in listOf(IllegalStateException("failed"), CancellationException("cancelled"))) {
            withFixture { fixture ->
                val provider = fixture.recorder.wrap(FakeProvider(listOf(chunk(TokenUsage(20, 1, 8, 21))), failure), tag)
                assertSame(failure, runCatching { provider.streamText(setting, messages, params).toList() }.exceptionOrNull())
                fixture.awaitWrites()
                val row = fixture.records.single()
                assertEquals(20, row.promptTokens)
                assertEquals(1, row.completionTokens)
                assertEquals(if (failure is CancellationException) "CANCELLED" else "ERROR", row.outcome)
                assertTrue(row.usageReported)
            }
        }
    }

    @Test
    fun `collector cancellation keeps only received usage and propagates unchanged`() = runBlocking {
        withFixture { fixture ->
            val provider = fixture.recorder.wrap(FakeProvider(listOf(
                chunk(TokenUsage(20, 1, 8, 21)), chunk(TokenUsage(20, 10, 8, 30)),
            )), tag)
            val stop = CancellationException("user stopped")
            assertSame(stop, runCatching {
                provider.streamText(setting, messages, params).collect { throw stop }
            }.exceptionOrNull())
            fixture.awaitWrites()
            assertEquals(1, fixture.records.single().completionTokens)
            assertEquals("CANCELLED", fixture.records.single().outcome)
        }
    }

    @Test
    fun `absent usage and explicit zero usage remain distinct requests`() = runBlocking {
        withFixture { fixture ->
            for (usage in listOf(null, TokenUsage())) {
                val provider = fixture.recorder.wrap(FakeProvider(listOf(chunk(usage))), tag)
                provider.streamText(setting, messages, params).toList()
                provider.generateText(setting, messages, params)
            }
            fixture.awaitWrites()
            assertEquals(4, fixture.records.size)
            assertEquals(2, fixture.records.count { it.usageReported })
            assertEquals(2, fixture.records.count { !it.usageReported })
            assertTrue(fixture.records.all { it.totalTokens == 0 && it.outcome == "SUCCESS" })
        }
    }

    @Test
    fun `nonstream errors and cancellation record unknown usage without changing failure`() = runBlocking {
        for (failure in listOf(IllegalStateException("rejected"), CancellationException("stopped"))) {
            withFixture { fixture ->
                val provider = fixture.recorder.wrap(FakeProvider(emptyList(), failure), tag)
                assertSame(failure, runCatching { provider.generateText(setting, messages, params) }.exceptionOrNull())
                fixture.awaitWrites()
                val row = fixture.records.single()
                assertFalse(row.usageReported)
                assertEquals(0, row.totalTokens)
                assertEquals(if (failure is CancellationException) "CANCELLED" else "ERROR", row.outcome)
            }
        }
    }

    @Test
    fun `stream is cold and recollection makes and records a fresh real request`() = runBlocking {
        withFixture { fixture ->
            val delegate = FakeProvider(listOf(chunk(TokenUsage(10, 2, 0, 12))))
            val provider = fixture.recorder.wrap(delegate, tag)
            val stream = provider.streamText(setting, messages, params)
            fixture.awaitWrites()
            assertEquals(0, delegate.streamCalls)
            assertTrue(fixture.records.isEmpty())
            repeat(2) { stream.toList() }
            fixture.awaitWrites()
            assertEquals(2, delegate.streamCalls)
            assertEquals(2, fixture.records.size)
        }
    }

    @Test
    fun `flow construction failure records an unknown failed request`() = runBlocking {
        withFixture { fixture ->
            val failure = IllegalStateException("could not start")
            val delegate = FakeProvider(emptyList(), constructionFailure = failure)
            val stream = fixture.recorder.wrap(delegate, tag).streamText(setting, messages, params)
            assertSame(failure, runCatching { stream.toList() }.exceptionOrNull())
            fixture.awaitWrites()
            assertEquals(1, delegate.streamCalls)
            assertEquals("ERROR", fixture.records.single().outcome)
            assertFalse(fixture.records.single().usageReported)
        }
    }

    @Test
    fun `rewrapping changes attribution without double counting and params identity is fallback`() = runBlocking {
        withFixture { fixture ->
            val delegate = FakeProvider(listOf(chunk(TokenUsage(10, 2, 0, 12))))
            val first = fixture.recorder.wrap(delegate, tag)
            val provider = fixture.recorder.wrap(first, UsageTag(UsageSource.TRANSLATION))
            val scopedParams = params.copy(conversationId = Uuid.random().toString())
            val response = provider.generateText(setting, messages, scopedParams)
            fixture.awaitWrites()
            assertSame(delegate.chunks.single(), response)
            assertSame(messages, delegate.requestMessages)
            assertSame(scopedParams, delegate.requestParams)
            val row = fixture.records.single()
            assertEquals("TRANSLATION", row.source)
            assertNull(row.assistantId)
            assertEquals(scopedParams.conversationId, row.conversationId)
        }
    }

    @Test
    fun `failing database cannot change successful response stream or cancellation`() = runBlocking {
        withFixture(failWrite = true) { fixture ->
            val response = chunk(TokenUsage(20, 5, 8, 25))
            val delegate = FakeProvider(listOf(response))
            val provider = fixture.recorder.wrap(delegate, tag)
            assertSame(response, provider.generateText(setting, messages, params))
            assertEquals(listOf(response), provider.streamText(setting, messages, params).toList())
            val cancellation = CancellationException("cancel")
            assertSame(cancellation, runCatching {
                provider.streamText(setting, messages, params).collect { throw cancellation }
            }.exceptionOrNull())
            fixture.awaitWrites()
            assertEquals(3, fixture.writeAttempts.size)
            assertTrue(fixture.records.isEmpty())
        }
    }

    @Test
    fun `unrelated provider operations pass through without inventing text usage`() = runBlocking {
        withFixture { fixture ->
            val delegate = FakeProvider(emptyList())
            val provider = fixture.recorder.wrap(delegate, tag)
            assertSame(delegate.models, provider.listModels(setting))
            assertEquals("balance", provider.getBalance(setting))
            assertSame(delegate.embeddingResult, provider.generateEmbedding(setting, EmbeddingGenerationParams(params.model, listOf("input"))))
            assertSame(delegate.imageResult, provider.generateImage(setting, ImageGenerationParams(params.model, "image")))
            fixture.awaitWrites()
            assertTrue(fixture.records.isEmpty())
        }
    }

    private suspend fun withFixture(failWrite: Boolean = false, block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(failWrite)
        try { block(fixture) } finally { fixture.scope.cancel() }
    }

    private class Fixture(failWrite: Boolean) {
        val records = CopyOnWriteArrayList<UsageRecordEntity>()
        val writeAttempts = CopyOnWriteArrayList<UsageRecordEntity>()
        val scope = CoroutineScope(SupervisorJob())
        private val dao = Proxy.newProxyInstance(
            UsageRecordDAO::class.java.classLoader, arrayOf(UsageRecordDAO::class.java),
        ) { _, method, args ->
            check(method.name == "insert") { "Unexpected DAO method: ${method.name}" }
            val row = args!![0] as UsageRecordEntity
            writeAttempts.add(row)
            if (failWrite) error("database unavailable")
            records.add(row)
            Unit
        } as UsageRecordDAO
        val recorder = UsageRecorder(dao, scope) { 123L }
        suspend fun awaitWrites() { scope.coroutineContext[Job]!!.children.toList().joinAll() }
    }

    private fun chunk(usage: TokenUsage?) = MessageChunk("chunk", "model", emptyList(), usage)

    private class FakeProvider(
        val chunks: List<MessageChunk>,
        private val failure: Exception? = null,
        private val constructionFailure: Exception? = null,
    ) : Provider<ProviderSetting> {
        var streamCalls = 0
        var generateCalls = 0
        var requestMessages: List<UIMessage>? = null
        var requestParams: TextGenerationParams? = null
        val models = listOf(Model(modelId = "delegate-model"))
        val embeddingResult = EmbeddingGenerationResult("model", listOf(listOf(1f)))
        val imageResult = ImageGenerationResult(emptyList())
        override suspend fun streamText(
            providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams,
        ): Flow<MessageChunk> {
            streamCalls++
            requestMessages = messages
            requestParams = params
            constructionFailure?.let { throw it }
            return flow {
                chunks.forEach { emit(it) }
                failure?.let { throw it }
            }
        }
        override suspend fun generateText(
            providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams,
        ): MessageChunk {
            generateCalls++
            requestMessages = messages
            requestParams = params
            failure?.let { throw it }
            return chunks.single()
        }
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = models
        override suspend fun getBalance(providerSetting: ProviderSetting): String = "balance"
        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting, params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = embeddingResult
        override suspend fun generateImage(
            providerSetting: ProviderSetting, params: ImageGenerationParams,
        ): ImageGenerationResult = imageResult
    }
}
