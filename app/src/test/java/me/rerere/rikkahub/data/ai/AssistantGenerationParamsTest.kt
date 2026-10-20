package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.CodexResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.toHeaders
import me.rerere.rikkahub.data.model.Assistant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantGenerationParamsTest {
    private val provider = ProviderSetting.OpenAI()
    private val model = Model(
        modelId = "test-chat",
        abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
    )
    private val history = listOf(UIMessage.user("question"))

    @Test
    fun `unset sampling stays absent in regular and proactive provider requests`() {
        val assistant = Assistant()
        val regular = params(assistant, tools = listOf(tool("lookup")))
        val proactive = params(assistant, tools = emptyList())

        for (request in listOf(regular, proactive)) {
            val body = chatBody(request)
            assertFalse(body.containsKey("temperature"))
            assertFalse(body.containsKey("top_p"))
            assertFalse(body.containsKey("max_tokens"))
            assertFalse(body.containsKey("reasoning_effort"))
        }
        assertEquals(regular.copy(tools = emptyList()), proactive)
    }

    @Test
    fun `explicit sampling and reasoning reach provider without new defaults`() {
        val request = params(Assistant(
            temperature = 0.45f,
            topP = 0.9f,
            maxTokens = 4096,
            reasoningLevel = ReasoningLevel.HIGH,
        ))
        val body = chatBody(request)

        assertEquals(JsonPrimitive(0.45f), body["temperature"])
        assertEquals(JsonPrimitive(0.9f), body["top_p"])
        assertEquals(JsonPrimitive(4096), body["max_tokens"])
        assertEquals(JsonPrimitive("high"), body["reasoning_effort"])
    }

    @Test
    fun `model overrides preserve assistant nested fields and header ordering`() {
        val assistant = Assistant(
            customHeaders = listOf(CustomHeader("X-Config", "assistant")),
            customBodies = listOf(
                CustomBody("routing", buildJsonObject {
                    put("region", "assistant")
                    put("keep", "assistant-only")
                }),
                CustomBody("max_tokens", JsonPrimitive(1024)),
            ),
        )
        val customizedModel = model.copy(
            customHeaders = listOf(CustomHeader("X-Config", "model")),
            customBodies = listOf(
                CustomBody("routing", buildJsonObject { put("region", "model") }),
                CustomBody("max_tokens", JsonPrimitive(2048)),
            ),
        )
        val request = params(assistant, model = customizedModel)
        val body = chatBody(request)

        assertEquals(JsonPrimitive(2048), body["max_tokens"])
        assertEquals(JsonPrimitive("model"), body["routing"]!!.jsonObject["region"])
        assertEquals(JsonPrimitive("assistant-only"), body["routing"]!!.jsonObject["keep"])
        assertEquals(listOf("assistant", "model"), request.customHeaders.toHeaders().values("X-Config"))
        assertEquals("assistant", assistant.customHeaders.single().value)
        assertEquals(2, assistant.customBodies.size)
    }

    @Test
    fun `only subscription provider routes conversation identity and keeps cache across turns`() {
        val assistant = Assistant(reasoningLevel = ReasoningLevel.HIGH)
        for (setting in listOf(provider, ProviderSetting.Google(), ProviderSetting.Claude())) {
            val request = params(assistant, provider = setting)
            assertNull(request.conversationId)
        }

        val subscription = params(assistant, provider = ProviderSetting.Codex())
        val first = codexBody(history, subscription)
        val next = codexBody(history + UIMessage.assistant("answer") + UIMessage.user("next"), subscription)
        assertTrue(first.containsKey("prompt_cache_key"))
        assertEquals(first["prompt_cache_key"], next["prompt_cache_key"])
        val other = params(assistant, provider = ProviderSetting.Codex(), conversationId = "other")
        val anonymous = params(assistant, provider = ProviderSetting.Codex(), conversationId = null)
        assertNotEquals(first["prompt_cache_key"], codexBody(history, other)["prompt_cache_key"])
        assertFalse(codexBody(history, anonymous).containsKey("prompt_cache_key"))
        assertEquals(JsonPrimitive("high"), first["reasoning"]!!.jsonObject["effort"])
    }

    @Test
    fun `regular and light proactive requests retain their independently selected tools`() {
        val lookup = tool("lookup")
        val write = tool("write", needsApproval = true)
        val assistant = Assistant(reasoningLevel = ReasoningLevel.MEDIUM)
        val regular = params(assistant, tools = listOf(lookup, write))
        val proactive = params(assistant, tools = listOf(lookup))

        fun toolNames(request: TextGenerationParams) = chatBody(request)["tools"]!!.jsonArray.map {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }

        assertEquals(listOf("lookup", "write"), toolNames(regular))
        assertEquals(listOf("lookup"), toolNames(proactive))
        assertEquals(regular.copy(tools = proactive.tools), proactive)
        assertTrue(regular.tools.last().needsApproval)
        assertFalse(proactive.tools.single().needsApproval)
    }

    @Test
    fun `provider thinking restrictions and proactive finalizer overrides remain independent`() {
        val glmProvider = provider.copy(baseUrl = "https://open.bigmodel.cn/api/paas/v4")
        val request = params(
            Assistant(temperature = 0.8f, topP = 0.9f, reasoningLevel = ReasoningLevel.HIGH),
            provider = glmProvider,
            tools = listOf(tool("lookup")),
        )
        val thinking = chatBody(request, glmProvider)
        assertFalse(thinking.containsKey("temperature"))
        assertFalse(thinking.containsKey("top_p"))
        assertEquals(JsonPrimitive("enabled"), thinking["thinking"]!!.jsonObject["type"])
        assertTrue(thinking.containsKey("tools"))

        // The background finalizer still deliberately disables both tools and thinking for its
        // extra visible-text turn; projecting assistant settings must not re-enable either one.
        val finalized = chatBody(request.copy(tools = emptyList(), reasoningLevel = ReasoningLevel.OFF), glmProvider)
        assertFalse(finalized.containsKey("tools"))
        assertEquals(JsonPrimitive("disabled"), finalized["thinking"]!!.jsonObject["type"])
        assertEquals(ReasoningLevel.HIGH, request.reasoningLevel)
        assertEquals(listOf("lookup"), request.tools.map { it.name })
    }

    private fun params(
        assistant: Assistant,
        model: Model = this.model,
        provider: ProviderSetting = this.provider,
        tools: List<Tool> = emptyList(),
        conversationId: String? = "conversation-one",
    ) = buildAssistantGenerationParams(assistant, model, provider, tools, conversationId)

    private fun tool(name: String, needsApproval: Boolean = false) = Tool(
        name = name,
        description = name,
        needsApproval = needsApproval,
        execute = { error("Request preparation must not execute tools") },
    )

    private fun chatBody(
        params: TextGenerationParams,
        provider: ProviderSetting.OpenAI = this.provider,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        return method.invoke(api, history, params, provider, false) as JsonObject
    }

    private fun codexBody(messages: List<UIMessage>, params: TextGenerationParams): JsonObject {
        // The ai module's internal method has a module suffix in its JVM name.
        val method = CodexResponseAPI::class.java.declaredMethods.single {
            it.name.startsWith("buildRequestBody") &&
                it.parameterTypes.contentEquals(arrayOf(List::class.java, TextGenerationParams::class.java))
        }.apply { isAccessible = true }
        return method.invoke(CodexResponseAPI(OkHttpClient()), messages, params) as JsonObject
    }
}
