package me.rerere.rikkahub.data.ai

import android.app.Application
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Full compression engine and Google request adapter, synthetic HTTP only. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CompressionConversationRequestTest {
    @Test fun frozenConversationModelProducesSummaryAndRetainsOriginals() = runBlocking {
        val model = Model(modelId = "gemini-conversation-test")
        val assistant = Assistant(chatModelId = Model().id)
        val provider = ProviderSetting.Google(apiKey = "synthetic-key", models = listOf(model))
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant), providers = listOf(provider))
        val chat = Conversation(assistantId = assistant.id, config = ConversationConfig(chatModelId = model.id),
            messageNodes = listOf(MessageNode.of(UIMessage.user("保留这句话")), MessageNode.of(UIMessage.assistant("也保留回复")),
                MessageNode.of(UIMessage.user("最近一轮原文")), MessageNode.of(UIMessage.assistant("最近的回复"))))
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertTrue(chain.request().url.encodedPath.contains("gemini-conversation-test:streamGenerateContent"))
            val requestBody = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            assertTrue(requestBody.contains("保留这句话"))
            assertTrue(requestBody.contains("也保留回复"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(("data: " + """{"candidates":[{"content":{"role":"model","parts":[{"text":"合成测试摘要"}]},"finishReason":"STOP"}]}""" + "\n\n")
                    .toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val scope = CoroutineScope(SupervisorJob())
        val dao = Proxy.newProxyInstance(UsageRecordDAO::class.java.classLoader, arrayOf(UsageRecordDAO::class.java)) { _, method, _ ->
            check(method.name == "insert"); Unit
        } as UsageRecordDAO
        try {
            val engine = CompressionEngine(ProviderManager(client, RuntimeEnvironment.getApplication()), UsageRecorder(dao, scope))
            val request = engine.request(settings.withConversationConfig(chat), assistant.id, 1000, "", chat.id)
            val candidates = chat.compressionCandidates(0)
            val summary = compressTextOnce(candidates.map { it.currentMessage.compressionTranscript() }, request)
            assertEquals("合成测试摘要", summary)
            assertEquals(1, requests)
            val saved = chat.withCompressionResult(chat, candidates, summaryParagraphs(summary))
            assertEquals(chat.messageNodes, saved.messageNodes)
            assertEquals(summary, saved.summaryForGeneration())
            assertEquals(chat.currentMessages.takeLast(2), saved.messagesForGeneration())
        } finally {
            scope.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
