package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CodexResponseAPITest {
    @Test fun `astra manual model sends valid reasoning and function tools`() {
        val model = Model(modelId = "gpt-6-astra", abilities = me.rerere.ai.registry.ModelRegistry.MODEL_ABILITIES.getData("gpt-6-astra"))
        assertTrue(ModelAbility.TOOL in model.abilities)
        assertTrue(ModelAbility.REASONING in model.abilities)
        val tool = me.rerere.ai.core.Tool(name = "lookup", description = "lookup", parameters = {
            me.rerere.ai.core.InputSchema.Obj(buildJsonObject { put("query", buildJsonObject { put("type", "string") }) })
        }, execute = { emptyList() })
        val api = CodexResponseAPI(OkHttpClient())
        val body = api.buildRequestBody(listOf(UIMessage.user("hello")), TextGenerationParams(model, tools = listOf(tool)))
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("function", body["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("lookup", body["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        val high = api.buildRequestBody(emptyList(), TextGenerationParams(model, reasoningLevel = ReasoningLevel.HIGH))
        assertEquals("high", high["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test fun `dynamic reasoning constraints and cache identity survive tool turns`() {
        val model = Model(modelId = "future-discovered-model", abilities = listOf(ModelAbility.REASONING), codexReasoningEfforts = listOf("medium", "high"))
        val request = TextGenerationParams(model, conversationId = "chat-one")
        val api = CodexResponseAPI(OkHttpClient())
        val first = api.buildRequestBody(listOf(UIMessage.user("first")), request)
        val next = api.buildRequestBody(listOf(UIMessage.user("first"), UIMessage.assistant("answer"), UIMessage.user("next")), request)
        assertEquals("medium", first["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(first["prompt_cache_key"], next["prompt_cache_key"])
        assertNotEquals(codexCacheKey(request), codexCacheKey(request.copy(conversationId = "chat-two")))
        assertNull(codexCacheKey(request.copy(conversationId = null)))
        assertNull(codexReasoningEffort(request.copy(reasoningLevel = ReasoningLevel.AUTO)))
        assertEquals("high", codexReasoningEffort(request.copy(reasoningLevel = ReasoningLevel.XHIGH)))
    }

    private val params = TextGenerationParams(Model(modelId = "discovered-model", abilities = listOf(ModelAbility.REASONING)))

    @Test fun `subscription request cannot override protocol fields`() {
        val api = CodexResponseAPI(OkHttpClient())
        val body = api.buildRequestBody(listOf(UIMessage.system("first"), UIMessage.system("second"), UIMessage.user("hello")),
            params.copy(temperature = 1f, maxTokens = 42, customBody = listOf(CustomBody("store", JsonPrimitive(true))), reasoningLevel = ReasoningLevel.HIGH))
        assertEquals("first\nsecond", body["instructions"]?.jsonPrimitive?.content)
        assertEquals("false", body["store"].toString())
        assertEquals("true", body["stream"].toString())
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("max_output_tokens"))
        assertEquals("high", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals(1, body["input"]?.jsonArray?.size)
    }

    @Test fun `stream keeps actual tool call id and aggregates text and usage`() = runBlocking {
        var request: Request? = null
        val events = listOf(
            """{"type":"response.output_text.delta","delta":"hello"}""",
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_internal","call_id":"call_public","name":"lookup","arguments":"{}"}}""",
            """{"type":"response.function_call_arguments.done","item_id":"fc_internal","arguments":"{}"}""",
            """{"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12,"input_tokens_details":{"cached_tokens":5}}}}""",
        ).joinToString("") { "data: $it\n\n" }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(events.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val result = CodexResponseAPI(client).generateText("test-token", "test-account", listOf(UIMessage.user("hello")),
            params.copy(customHeaders = listOf(CustomHeader("Authorization", "malicious"), CustomHeader("Host", "evil.test"))))
        assertEquals(CodexResponseAPI.ENDPOINT, request?.url.toString())
        assertEquals("Bearer test-token", request?.header("Authorization"))
        assertNull(request?.header("Host"))
        assertEquals(12, result.usage?.totalTokens)
        assertEquals(5, result.usage?.cachedTokens)
        val message = result.choices.single().message!!
        assertEquals("hello", message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        val tool = message.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("call_public", tool.toolCallId)
        assertEquals("{}", tool.input)
        val input = CodexResponseAPI(client).buildMessages(listOf(message.copy(parts = listOf(tool.copy(output = listOf(UIMessagePart.Text("result")))))))
        assertEquals("call_public", input[0].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("function_call_output", input[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test fun `malformed stream cannot expose raw server body in exception`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: secret-access-token-broken-json\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val error = runCatching { CodexResponseAPI(client).streamText("token", "account", listOf(UIMessage.user("x")), params).toList() }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error.toString().contains("secret-access"))
        generateSequence(error) { it.cause }.forEach { assertFalse(it.toString().contains("secret-access")) }
    }

    @Test fun `codex configuration roundtrip carries no credentials or endpoint`() {
        val json = Json { encodeDefaults = true }
        val value: ProviderSetting = ProviderSetting.Codex()
        val encoded = json.encodeToString(value)
        assertTrue(json.decodeFromString<ProviderSetting>(encoded) is ProviderSetting.Codex)
        listOf("apiKey", "accessToken", "refreshToken", "baseUrl").forEach { assertFalse(encoded.contains(it)) }
        val old = """{"type":"openai","apiKey":"old-key","baseUrl":"https://example.com/v1"}"""
        val decoded = json.decodeFromString<ProviderSetting>(old) as ProviderSetting.OpenAI
        assertEquals("old-key", decoded.apiKey)
        assertEquals("https://example.com/v1", decoded.baseUrl)
    }

    @Test fun `stopping collection cancels the HTTP call`() = runBlocking {
        var activeCall: Call? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            activeCall = chain.call()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        CodexResponseAPI(client).streamText("token", "account", listOf(UIMessage.user("x")), params).first()
        repeat(20) { if (activeCall?.isCanceled() != true) delay(10) }
        assertTrue(activeCall?.isCanceled() == true)
    }

    @Test fun `stream without completion fails instead of reporting success`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val error = runCatching { CodexResponseAPI(client).generateText("token", "account", listOf(UIMessage.user("x")), params) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("before completion"))
    }
}
