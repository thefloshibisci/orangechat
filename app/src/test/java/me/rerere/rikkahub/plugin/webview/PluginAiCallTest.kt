package me.rerere.rikkahub.plugin.webview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class PluginAiCallTest {
    private val model = Model(modelId = "test-model")
    private val provider = ProviderSetting.OpenAI(models = listOf(model))
    private val assistant = Assistant(chatModelId = model.id)
    private val settings = Settings(
        providers = listOf(provider),
        assistants = listOf(assistant),
        assistantId = assistant.id,
    )

    private fun result(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test
    fun `missing permission rejects before loading user settings or constructing a prompt`() = runBlocking {
        val call = PluginAiCall(
            permissions = listOf("provider_credentials", "conversation_sync"),
            loadSettings = { error("Must not load user settings") },
            generateText = { _, _, _ -> error("Must not call provider") },
        )
        val denied = result(call.call("help") { error("Must not build prompt") })
        assertFalse(denied.getValue("success").jsonPrimitive.boolean)
        assertTrue(denied.getValue("error").jsonPrimitive.content.contains("ai_chat"))
    }

    @Test
    fun `blank prompt rejects before loading configuration`() = runBlocking {
        val call = PluginAiCall(
            permissions = listOf("ai_chat"),
            loadSettings = { error("Must not load user settings") },
            generateText = { _, _, _ -> error("Must not call provider") },
        )
        val denied = result(call.call(" \n\t") { error("Must not build prompt") })
        assertEquals("prompt is required", denied.getValue("error").jsonPrimitive.content)
        assertFalse(denied.getValue("success").jsonPrimitive.boolean)
    }

    @Test
    fun `missing configured model returns an error without invoking provider`() = runBlocking {
        val call = PluginAiCall(
            permissions = listOf("ai_chat"),
            loadSettings = { settings.copy(providers = emptyList()) },
            generateText = { _, _, _ -> error("Must not call provider") },
        )
        val failed = result(call.call("help") { error("Must not build prompt") })
        assertEquals("No chat model configured", failed.getValue("error").jsonPrimitive.content)
        assertFalse(failed.getValue("success").jsonPrimitive.boolean)
    }

    @Test
    fun `authorized page and overlay keep their own system prompts and current assistant model`() = runBlocking {
        val systemPrompts = mutableListOf<String>()
        val output = "回答 \"quoted\" \\ \n\t\r\u0000"
        val call = PluginAiCall(
            permissions = listOf("ai_chat"),
            loadSettings = { settings },
            generateText = { selectedProvider, messages, params ->
                assertSame(provider, selectedProvider)
                assertEquals(model, params.model)
                assertEquals(0.7f, requireNotNull(params.temperature), 0f)
                assertTrue(params.tools.isEmpty())
                assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), messages.map { it.role })
                systemPrompts += (messages.first().parts.single() as UIMessagePart.Text).text
                assertEquals("help", (messages.last().parts.single() as UIMessagePart.Text).text)
                output
            },
        )
        for (systemPrompt in listOf("reading context", "pomodoro context")) {
            val succeeded = result(call.call("help") { systemPrompt })
            assertTrue(succeeded.getValue("success").jsonPrimitive.boolean)
            assertEquals(output, succeeded.getValue("text").jsonPrimitive.content)
        }
        assertEquals(listOf("reading context", "pomodoro context"), systemPrompts)
    }

    @Test
    fun `provider failure has valid JSON even with quotes backslashes and newlines`() = runBlocking {
        val message = "provider \"unavailable\" \\path\nretry\t\u0000"
        val failure = IllegalStateException(message)
        var logged: Exception? = null
        val call = PluginAiCall(
            permissions = listOf("ai_chat"),
            loadSettings = { settings },
            generateText = { _, _, _ -> throw failure },
            onError = { logged = it },
        )
        val failed = result(call.call("help") { "context" })
        assertFalse(failed.getValue("success").jsonPrimitive.boolean)
        assertEquals(message, failed.getValue("error").jsonPrimitive.content)
        assertSame(failure, logged)
    }

    @Test
    fun `settings loading failure returns an error without calling provider`() = runBlocking {
        val call = PluginAiCall(
            permissions = listOf("ai_chat"),
            loadSettings = { throw IllegalStateException() },
            generateText = { _, _, _ -> error("Must not call provider") },
        )
        val failed = result(call.call("help") { "context" })
        assertFalse(failed.getValue("success").jsonPrimitive.boolean)
        assertEquals("AI generation failed", failed.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `provider cancellation propagates without creating an error response`() = runBlocking {
        val cancellation = CancellationException("closed")
        val call = PluginAiCall(
            permissions = listOf("ai_chat"),
            loadSettings = { settings },
            generateText = { _, _, _ -> throw cancellation },
            onError = { error("Cancellation must not be reported as provider failure") },
        )
        try {
            call.call("help") { "context" }
            fail("Expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }

    @Test
    fun `cancellation during settings loading prevents a later provider request`() = runBlocking {
        val call = PluginAiCall(
            permissions = listOf("ai_chat"),
            loadSettings = {
                currentCoroutineContext().cancel()
                settings
            },
            generateText = { _, _, _ -> error("Must not call provider after cancellation") },
            onError = { error("Cancellation must propagate") },
        )
        try {
            withContext(Job()) { call.call("help") { "context" } }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // The child job's cancellation must not become a successful/error bridge response.
        }
    }

    @Test
    fun `cancellation ignored by a provider still prevents publishing its response`() = runBlocking {
        val call = PluginAiCall(
            permissions = listOf("ai_chat"),
            loadSettings = { settings },
            generateText = { _, _, _ ->
                currentCoroutineContext().cancel()
                "late response"
            },
            onError = { error("Cancellation must propagate") },
        )
        try {
            withContext(Job()) { call.call("help") { "context" } }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // No bridge result is returned to the caller.
        }
    }

    @Test
    fun `bridge result quotes callback and response as independent JSON strings`() {
        val callback = "callback');evil();//\"\\\n\u2028\u2029"
        val response = """{"success":true,"text":"quoted \"line\"\n"}"""
        val script = pluginAiResultJavascript(callback, response)
        val arguments = script.removePrefix("window.__bridgeResult(").removeSuffix("));")
        val separator = ", JSON.parse("
        val callbackJson = arguments.substringBefore(separator)
        val resultJson = arguments.substringAfter(separator)
        assertEquals(callback, Json.parseToJsonElement(callbackJson).jsonPrimitive.content)
        assertEquals(response, Json.parseToJsonElement(resultJson).jsonPrimitive.content)
        assertFalse(script.contains('\u2028'))
        assertFalse(script.contains('\u2029'))
    }
}
