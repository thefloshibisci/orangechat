package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.openai.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Inspect the actual serialized request builders without sending a network request. */
class PaymentToolRequestTest {
    private val client = OkHttpClient()
    private val messages = listOf(UIMessage.user("收下红包"))
    private val model = Model(modelId = "test-model", abilities = listOf(ModelAbility.TOOL))
    private fun interactionsApi(): Any = Class.forName("me.rerere.ai.provider.providers.google.InteractionsAPI")
        .getDeclaredConstructor(OkHttpClient::class.java, KeyRoulette::class.java).newInstance(client, KeyRoulette.default())
    private fun params(model: Model = this.model, virtual: Boolean = true) = TextGenerationParams(model,
        tools = buildRichChatTools(false, { _, _, _, _ -> error("no payment") },
            { _, _ -> error("no payment") }, pretendRich = virtual))

    private fun invoke(target: Any, prefix: String, vararg args: Any): JsonObject {
        val method = target.javaClass.declaredMethods.single { method ->
            (method.name == prefix || method.name.startsWith(prefix + '$')) &&
                method.parameterCount == args.size && !method.isSynthetic &&
                method.parameterTypes.zip(args).all { (type, arg) ->
                    type.isInstance(arg) || (type == Boolean::class.javaPrimitiveType && arg is Boolean)
                }
        }
        method.isAccessible = true
        return method.invoke(target, *args) as JsonObject
    }

    private fun names(body: JsonObject): Set<String> = body["tools"]?.jsonArray.orEmpty().flatMap { entry ->
        val obj = entry.jsonObject
        when {
            "functionDeclarations" in obj -> obj.getValue("functionDeclarations").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            "function" in obj -> listOf(obj.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
            "name" in obj -> listOf(obj.getValue("name").jsonPrimitive.content)
            else -> emptyList()
        }
    }.toSet()

    @Test fun everySupportedTransportReceivesSendAndAcceptToolsInBothPaymentModes() {
        for (virtual in listOf(true, false)) {
            val p = params(virtual = virtual)
            val bodies = listOf(
                invoke(ChatCompletionsAPI(client, KeyRoulette.default()), "buildChatCompletionRequest", messages, p, ProviderSetting.OpenAI(), false),
                invoke(ResponseAPI(client), "buildRequestBody", ProviderSetting.OpenAI(), messages, p, false),
                invoke(CodexResponseAPI(client), "buildRequestBody", messages, p),
                invoke(ClaudeProvider(client), "buildMessageRequest", ProviderSetting.Claude(), messages, p, false),
                invoke(GoogleProvider(client), "buildCompletionRequestBody", messages, p),
                invoke(interactionsApi(), "buildRequestBody", messages, p, false),
            )
            val expected = p.tools.map { it.name }.toSet()
            bodies.forEachIndexed { index, body -> assertEquals("transport $index / virtual=$virtual", expected, names(body)) }
        }
    }

    @Test fun availabilityDiagnosticsMatchWhatTheRequestActuallyContains() {
        val noCapability = model.copy(abilities = emptyList())
        val p = params(noCapability)
        val regular = invoke(ChatCompletionsAPI(client, KeyRoulette.default()), "buildChatCompletionRequest", messages, p, ProviderSetting.OpenAI(), false)
        assertTrue(names(regular).isEmpty())
        assertNotNull(paymentToolUnavailableReason(noCapability, ProviderSetting.OpenAI()))
        val subscription = invoke(CodexResponseAPI(client), "buildRequestBody", messages, p)
        assertEquals(p.tools.map { it.name }.toSet(), names(subscription))
        assertNull(paymentToolUnavailableReason(noCapability, ProviderSetting.Codex()))
        val searchModel = model.copy(tools = setOf(BuiltInTools.Search))
        val searchParams = params(searchModel)
        val google = invoke(GoogleProvider(client), "buildCompletionRequestBody", messages, searchParams)
        assertTrue(names(google).isEmpty())
        assertTrue(paymentToolUnavailableReason(searchModel, ProviderSetting.Google())!!.contains("覆盖"))
        val interactions = invoke(interactionsApi(), "buildRequestBody", messages, searchParams, false)
        assertEquals(searchParams.tools.map { it.name }.toSet(), names(interactions))
        assertNull(paymentToolUnavailableReason(searchModel, ProviderSetting.Google(useInteractionsApi = true)))
    }
}
