package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ChatCompletionsReliabilityTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
    private fun messages(history: List<UIMessage>, supportsImage: Boolean = true): JsonArray {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
        method.isAccessible = true
        val modalities = if (supportsImage) listOf(Modality.TEXT, Modality.IMAGE) else listOf(Modality.TEXT)
        return method.invoke(api, history, Model(modelId = "test-model", inputModalities = modalities)) as JsonArray
    }

    @Test
    fun `null SSE envelopes keep usage and do not prevent a later valid delta`() {
        for (choices in listOf("null", "[]", "[null]", "[{\"delta\":null,\"message\":null}]")) {
            val chunk = api.parseStreamChunk(obj("""{"choices":$choices,"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}"""))
            assertTrue(chunk.choices.isEmpty())
            assertEquals(5, chunk.usage!!.totalTokens)
        }
        val chunk = api.parseStreamChunk(obj("""{"choices":[{"delta":null,"message":{"role":"assistant","content":"hello"}}]}"""))
        assertEquals("hello", (chunk.choices.single().delta!!.parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `actual malformed tool protocol is still rejected`() {
        try {
            api.parseStreamChunk(obj("""{"choices":[{"delta":{"tool_calls":[{"type":"unsupported"}]}}]}"""))
            fail("Unsupported tool calls must not be silently ignored")
        } catch (_: IllegalStateException) {
            // The stream listener will close with this error.
        }
    }

    @Test
    fun `encoding strips blank blocks while retaining original nonblank text`() {
        val text = "  indented text\n"
        val parts = listOf(UIMessagePart.Text(" "), UIMessagePart.Text(text), UIMessagePart.Image("https://example.com/image.png"))
        val encoded = encodeChatContentParts(parts, supportsImage = true, allowAudio = true)
        assertEquals(2, encoded.size)
        assertEquals(text, encoded[0].jsonObject["text"]!!.jsonPrimitive.content)
        val result = messages(listOf(UIMessage(role = MessageRole.USER, parts = parts)))
        assertEquals(2, result.single().jsonObject["content"]!!.jsonArray.size)
    }

    @Test
    fun `unsupported image only turns are omitted and captions survive`() {
        val image = UIMessagePart.Image("https://example.com/image.png")
        val result = messages(listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(image)),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(image)),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(" caption "), image)),
        ), supportsImage = false)
        assertEquals(1, result.size)
        assertEquals(" caption ", result.single().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unreadable attachment produces an explicit nonempty replacement`() {
        for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
            val result = messages(listOf(UIMessage(role = role, parts = listOf(UIMessagePart.Image("missing-image:unavailable")))))
            assertEquals("[Image unavailable: the attachment could not be read.]", result.single().jsonObject["content"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `empty and image only tool results retain ids and nonempty tool text`() {
        val first = UIMessagePart.Tool("call_empty", "empty", "", listOf(UIMessagePart.Text(" ")))
        val second = UIMessagePart.Tool("call_image", "image", "{}", listOf(UIMessagePart.Image("https://example.com/image.png")))
        val result = messages(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first, second))))
        assertEquals(listOf("assistant", "tool", "tool", "user"), result.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("call_empty", result[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("call_image", result[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertTrue(result[1].jsonObject["content"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(result[2].jsonObject["content"]!!.jsonPrimitive.content.isNotBlank())
        val args = result[0].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        assertEquals("{}", args)
        assertEquals("", first.input)
    }
}
