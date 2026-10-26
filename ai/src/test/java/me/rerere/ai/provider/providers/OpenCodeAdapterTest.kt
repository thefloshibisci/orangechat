package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class OpenCodeAdapterTest {
    private val go = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/inference/go/v1", apiKey = "test-key")

    @Test fun `recognizes current and legacy API roots without matching foreign URLs`() {
        for (path in listOf("inference/go/v1", "zen/go/v1/", "inference/go/openai/v1")) {
            assertEquals("https://opencode.ai/inference/go", openCodeApiRoot("https://opencode.ai/$path"))
        }
        assertEquals("https://opencode.ai/inference", openCodeApiRoot("https://opencode.ai/zen/v1"))
        for (url in listOf("https://opencode.ai.evil/zen/go/v1", "https://evil/opencode.ai/zen/v1",
            "http://opencode.ai/zen/v1", "https://opencode.ai:8443/zen/v1", "https://opencode.ai/custom/v1",
            "https://user@opencode.ai/zen/v1", "https://opencode.ai/zen/v1?token=secret")) {
            assertNull(url, openCodeApiRoot(url))
        }
    }

    @Test fun `routes subscription and pay as you go models to their documented protocols`() {
        for ((model, protocol) in mapOf("gpt-6-luna" to OpenCodeProtocol.RESPONSES,
            "grok-4.7" to OpenCodeProtocol.RESPONSES, "claude-haiku-5-5" to OpenCodeProtocol.ANTHROPIC,
            "gemini-4-argon" to OpenCodeProtocol.GOOGLE, "minimax-m3" to OpenCodeProtocol.ANTHROPIC,
            "qwen3.8-max" to OpenCodeProtocol.ANTHROPIC, "minimax-m3.1-flash" to OpenCodeProtocol.CHAT,
            "kimi-k3" to OpenCodeProtocol.CHAT, "deepseek-v4.1-flash" to OpenCodeProtocol.CHAT)) {
            assertEquals(model, protocol, openCodeProtocol(model, go = true))
        }
        assertEquals(OpenCodeProtocol.CHAT, openCodeProtocol("minimax-m3", go = false))
        assertEquals(OpenCodeProtocol.CHAT, openCodeProtocol("qwen3.8-max", go = false))
        assertEquals(OpenCodeProtocol.ANTHROPIC, openCodeProtocol("qwen3.6-plus", go = false))
    }

    @Test fun `preserves generation options and stable session for all transports`() {
        val tools = listOf(Tool(name = "lookup", description = "lookup", execute = { error("not executed") }))
        for (model in listOf("kimi-k3", "gpt-6-luna", "claude-haiku-5-5", "gemini-4-argon")) {
            val params = TextGenerationParams(Model(modelId = model), temperature = 0.4f, maxTokens = 8192,
                tools = tools, reasoningLevel = ReasoningLevel.HIGH, conversationId = "conversation-one",
                customHeaders = listOf(CustomHeader("X-Custom", "keep"), CustomHeader("user-agent", "generic")),
                customBody = listOf(CustomBody("custom", JsonPrimitive("keep"))))
            val adapted = adaptOpenCodeRequest(go, params)
            assertEquals(params.copy(customHeaders = adapted.params.customHeaders), adapted.params)
            assertEquals(listOf("OrangeChat/1.0"), adapted.params.customHeaders.filter { it.name.equals("user-agent", true) }.map { it.value })
            assertEquals("conversation-one", adapted.params.customHeaders.single { it.name == "x-opencode-session" }.value)
            assertEquals(go.id, adapted.provider.id)
            assertEquals("conversation-two", adaptOpenCodeRequest(go, params.copy(conversationId = "conversation-two"))
                .params.customHeaders.single { it.name == "x-opencode-session" }.value)
        }
    }

    @Test fun `OFF stays none and explicit reasoning body override wins`() {
        val params = TextGenerationParams(Model(modelId = "kimi-k3", abilities = listOf(ModelAbility.REASONING)))
        assertEquals(JsonPrimitive("none"), adaptOpenCodeRequest(go, params).params.customBody.single().value)
        val custom = params.copy(customBody = listOf(CustomBody("reasoning_effort", JsonPrimitive("high"))))
        assertEquals(custom.customBody, adaptOpenCodeRequest(go, custom).params.customBody)
        assertTrue(adaptOpenCodeRequest(go, params.copy(reasoningLevel = ReasoningLevel.AUTO)).params.customBody.isEmpty())
    }

    @Test fun `ordinary providers retain object identity and all settings`() {
        val setting = go.copy(baseUrl = "https://api.openai.com/v1", useResponseApi = true, responsesPath = "/custom")
        val params = TextGenerationParams(Model(modelId = "claude-haiku-5-5"), conversationId = "private")
        val adapted = adaptOpenCodeRequest(setting, params)
        assertSame(setting, adapted.provider)
        assertSame(params, adapted.params)
    }

    @Test fun `catalog uses shared model endpoint and real chat requests carry stable session`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val catalog = request.method == "GET"
            val contentType = if (catalog) "application/json" else "text/event-stream"
            val body = if (catalog) """{"data":[{"id":"kimi-k3"}]}"""
                else "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", contentType).body(body.toResponseBody(contentType.toMediaType())).build()
        }.build()
        val provider = OpenAIProvider(client)
        assertEquals("kimi-k3", provider.listModels(go.copy(baseUrl = "https://opencode.ai/zen/go/v1")).single().modelId)
        assertEquals("/inference/go/v1/models", requests.single().url.encodedPath)
        val params = TextGenerationParams(Model(modelId = "kimi-k3"), conversationId = "chat-one")
        val history = listOf(UIMessage.user("hi"))
        assertTrue(provider.streamText(go, history, params).toList().isNotEmpty())
        val request = requests.last()
        assertEquals("/inference/go/openai/v1/chat/completions", request.url.encodedPath)
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals("OrangeChat/1.0", request.header("User-Agent"))
        assertEquals("chat-one", request.header("x-opencode-session"))
    }

    @Test fun `nonstreaming entry point dispatches each protocol with matching credentials`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val response = when {
                request.url.encodedPath.endsWith("/responses") -> """{"id":"r","model":"gpt-6-luna","output":[]}"""
                request.url.encodedPath.endsWith("/messages") -> """{"id":"r","model":"claude-haiku-5-5","content":[],"stop_reason":"end_turn"}"""
                request.url.encodedPath.contains("/google/") -> """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{}}"""
                else -> """{"id":"r","model":"kimi-k3","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val provider = OpenAIProvider(client)
        for ((model, suffix) in mapOf("kimi-k3" to "/openai/v1/chat/completions", "gpt-6-luna" to "/openai/v1/responses",
            "claude-haiku-5-5" to "/anthropic/v1/messages", "gemini-4-argon" to "/google/v1beta/models/gemini-4-argon:generateContent")) {
            provider.generateText(go, listOf(UIMessage.user("hi")), TextGenerationParams(Model(modelId = model), conversationId = "chat-one"))
            val request = requests.last()
            assertEquals("/inference/go$suffix", request.url.encodedPath)
            assertEquals("chat-one", request.header("x-opencode-session"))
            assertEquals("OrangeChat/1.0", request.header("User-Agent"))
            val authHeader = when {
                suffix.contains("anthropic") -> "x-api-key"
                suffix.contains("google") -> "x-goog-api-key"
                else -> "Authorization"
            }
            assertEquals(if (authHeader == "Authorization") "Bearer test-key" else "test-key", request.header(authHeader))
        }
    }
}
