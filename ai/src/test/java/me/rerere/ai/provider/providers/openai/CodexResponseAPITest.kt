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
        assertTrue(error is CodexStreamFailure)
        assertTrue(error?.message.orEmpty().contains("未收到完成事件"))
    }

    @Test fun `null encrypted_content and null usage complete cleanly and only codex reasoning replays`() = runBlocking {
        val events = listOf(
            """{"type":"response.output_item.added","item":{"type":"reasoning","id":"rs_1","encrypted_content":null}}""",
            """{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","delta":"thinking"}""",
            """{"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_1","encrypted_content":null}}""",
            """{"type":"response.output_text.delta","delta":"ok"}""",
            """{"type":"response.completed","response":{"usage":null}}""",
        ).joinToString("") { "data: $it\n\n" }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(events.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val result = CodexResponseAPI(client).generateText("token", "account", listOf(UIMessage.user("hi")), params)
        val message = result.choices.single().message!!
        assertEquals("ok", message.parts.filterIsInstance<UIMessagePart.Text>().filter { it.text.isNotEmpty() }.single().text)
        val codexReasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertNull(codexReasoning.metadata?.get("encrypted_content"))
        assertEquals("codex", codexReasoning.metadata?.get("reasoning_provider")?.jsonPrimitive?.content)

        val foreignReasoning = UIMessagePart.Reasoning(
            reasoning = "foreign",
            createdAt = kotlin.time.Clock.System.now(),
            finishedAt = null,
            metadata = buildJsonObject { put("reasoning_id", "rs_foreign"); put("encrypted_content", "enc_foreign") },
        )
        val validCodexReasoning = UIMessagePart.Reasoning(
            reasoning = "valid",
            createdAt = kotlin.time.Clock.System.now(),
            finishedAt = null,
            metadata = buildJsonObject {
                put("reasoning_provider", "codex")
                put("reasoning_id", "rs_codex")
                put("encrypted_content", "enc_codex")
            },
        )
        val input = CodexResponseAPI(client).buildMessages(
            listOf(
                UIMessage.user("q"),
                UIMessage(
                    role = me.rerere.ai.core.MessageRole.ASSISTANT,
                    parts = listOf(foreignReasoning, validCodexReasoning, UIMessagePart.Text("answer")),
                ),
            ),
        )
        val reasoningItems = input.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals(1, reasoningItems.size)
        assertEquals("enc_codex", reasoningItems.single()["encrypted_content"]?.jsonPrimitive?.content)
        val messageItems = input.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "message" }
        assertEquals(2, messageItems.size)
        assertEquals("input_text", messageItems[0]["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("output_text", messageItems[1]["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `null tool parameters fallback and duplicate tool names are deduplicated`() {
        val noParamsTool = me.rerere.ai.core.Tool(name = "dup_tool", description = "first", parameters = { null }, execute = { emptyList() })
        val secondDup = me.rerere.ai.core.Tool(name = "dup_tool", description = "second", parameters = { null }, execute = { emptyList() })
        val body = CodexResponseAPI(OkHttpClient()).buildRequestBody(
            listOf(UIMessage.user("hi")),
            params.copy(tools = listOf(noParamsTool, secondDup)),
        )
        val tools = body["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("first", tools.single().jsonObject["description"]?.jsonPrimitive?.content)
        val schema = tools.single().jsonObject["parameters"]!!.jsonObject
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertTrue(schema.containsKey("properties"))
    }

    @Test fun `structured http and sse errors surface sanitized details without leaking secrets or transport paths`() = runBlocking {
        val httpErrorClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad Request")
                .body("""{"error":{"code":"invalid_schema","param":"tools[0]","message":"Bad Bearer secret-tok eyJhbGciOi.payload.sig at https://internal.example/v1"}}"""
                    .toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val httpErr = runCatching {
            CodexResponseAPI(httpErrorClient).streamText("token", "account", listOf(UIMessage.user("x")), params).toList()
        }.exceptionOrNull()
        assertTrue(httpErr is CodexHttpFailure)
        val httpMsg = httpErr!!.message.orEmpty()
        assertTrue(httpMsg, httpMsg.contains("HTTP 400") && httpMsg.contains("invalid_schema"))
        assertFalse(httpMsg, httpMsg.contains("secret-tok") || httpMsg.contains("eyJhbGciOi") || httpMsg.contains("internal.example"))

        val sseFailedBody = "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"Too many tokens sk-abc123\"}}}\n\n"
        val sseErrorClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(sseFailedBody.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val sseErr = runCatching {
            CodexResponseAPI(sseErrorClient).streamText("token", "account", listOf(UIMessage.user("x")), params).toList()
        }.exceptionOrNull()
        assertTrue(sseErr is CodexStreamFailure)
        assertTrue(sseErr!!.message.orEmpty().contains("context_length_exceeded"))
        assertFalse(sseErr.message.orEmpty().contains("sk-abc123"))

        val transportClient = OkHttpClient.Builder().addInterceptor { _ ->
            throw java.net.SocketTimeoutException("failed to connect to chatgpt.com/198.51.100.2:443")
        }.build()
        val transportErr = runCatching {
            CodexResponseAPI(transportClient).streamText("token", "account", listOf(UIMessage.user("x")), params).toList()
        }.exceptionOrNull()
        assertTrue(transportErr is CodexStreamFailure)
        val tMsg = transportErr!!.message.orEmpty()
        assertTrue(tMsg, tMsg.contains("SocketTimeoutException"))
        assertFalse(tMsg, tMsg.contains("198.51.100.2") || tMsg.contains("chatgpt.com"))
    }

    @Test fun `cloudflare html, plain text gateway errors, early stream end, and malformed sse events surface structured failures`() = runBlocking {
        val cfClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .header("cf-mitigated", "challenge")
                .body("<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>Cloudflare Bearer secret-html</body></html>"
                    .toResponseBody("text/html".toMediaType()))
                .build()
        }.build()
        val cfErr = runCatching {
            CodexResponseAPI(cfClient).streamText("token", "account", listOf(UIMessage.user("x")), params).toList()
        }.exceptionOrNull()
        assertTrue(cfErr is CodexHttpFailure)
        val cfMsg = cfErr!!.message.orEmpty()
        assertTrue(cfMsg, cfMsg.contains("403") && cfMsg.contains("Cloudflare"))
        assertFalse(cfMsg, cfMsg.contains("secret-html") || cfMsg.contains("<!DOCTYPE"))

        val gatewayClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(502).message("Bad Gateway")
                .body("upstream timeout Bearer tok-gw sk-secret123".toResponseBody("text/plain".toMediaType()))
                .build()
        }.build()
        val gwErr = runCatching {
            CodexResponseAPI(gatewayClient).streamText("token", "account", listOf(UIMessage.user("x")), params).toList()
        }.exceptionOrNull()
        assertTrue(gwErr is CodexHttpFailure)
        val gwMsg = gwErr!!.message.orEmpty()
        assertTrue(gwMsg, gwMsg.contains("502") && gwMsg.contains("upstream timeout"))
        assertFalse(gwMsg, gwMsg.contains("tok-gw") || gwMsg.contains("sk-secret123"))

        val earlyDoneClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val earlyDoneErr = runCatching {
            CodexResponseAPI(earlyDoneClient).streamText("token", "account", listOf(UIMessage.user("x")), params).toList()
        }.exceptionOrNull()
        assertTrue(earlyDoneErr is CodexStreamFailure)
        assertTrue(earlyDoneErr!!.message.orEmpty().contains("[DONE]"))

        val prematureCloseClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"i1\",\"delta\":\"hi\"}\n\n"
                    .toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val closeErr = runCatching {
            CodexResponseAPI(prematureCloseClient).streamText("token", "account", listOf(UIMessage.user("x")), params).toList()
        }.exceptionOrNull()
        assertTrue(closeErr is CodexStreamFailure)

        val nonJsonSseClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: \"Bearer secret-non-json sk-secret456\"\n\n".toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val nonJsonErr = runCatching {
            CodexResponseAPI(nonJsonSseClient).streamText("token", "account", listOf(UIMessage.user("x")), params).toList()
        }.exceptionOrNull()
        assertTrue(nonJsonErr is CodexStreamFailure)
        assertFalse(nonJsonErr!!.message.orEmpty().contains("secret-non-json") || nonJsonErr.message.orEmpty().contains("sk-secret456"))
    }

    @Test fun `rfc6749 oauth error json extracts code and redacts rt tokens`() {
        val detail = extractCodexHttpErrorDetail(
            cfMitigated = null,
            contentType = "application/json",
            bodyText = """{"error":"invalid_grant","error_description":"Refresh token rt_secret123 expired"}""",
        )
        assertNotNull(detail)
        assertTrue(detail!!, detail.contains("invalid_grant"))
        assertFalse(detail, detail.contains("rt_secret123"))
        assertTrue(detail, detail.contains("[redacted]"))
    }
}
