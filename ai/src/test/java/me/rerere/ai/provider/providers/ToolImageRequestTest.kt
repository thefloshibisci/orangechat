package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.CodexResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ToolImageRequestTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="
    private val dataUri = "data:image/png;base64,$png"
    private val names = listOf("take_screenshot", "time", "generate_image")
    private val messages = listOf(UIMessage.user("Capture and draw"), UIMessage(
        role = MessageRole.ASSISTANT,
        parts = names.map { name ->
            UIMessagePart.Tool("call_$name", name, "{}", buildList {
                add(UIMessagePart.Text("result_$name"))
                if (name != "time") add(UIMessagePart.Image(dataUri))
            })
        },
    ))

    @Test
    fun `claude tool results contain decodable images and matching ids`() {
        val result = claudeMessages(messages)
        val calls = result[1].jsonObject["content"]!!.jsonArray
        val outputs = result[2].jsonObject["content"]!!.jsonArray
        assertEquals(names.map { "call_$it" }, calls.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(names.map { "call_$it" }, outputs.map { it.jsonObject["tool_use_id"]!!.jsonPrimitive.content })
        outputs.forEachIndexed { index, output ->
            val content = output.jsonObject["content"]!!.jsonArray
            assertEquals("result_${names[index]}", content.first().jsonObject["text"]!!.jsonPrimitive.content)
            if (index != 1) {
                val source = content[1].jsonObject["source"]!!.jsonObject
                assertEquals("base64", source["type"]!!.jsonPrimitive.content)
                assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
                assertPng(source["data"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `claude failed attachment is explicit not an empty content block`() {
        val tool = UIMessagePart.Tool("bad", "take_screenshot", "{}", listOf(UIMessagePart.Image("unsupported:bad")))
        val result = claudeMessages(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))))
        val block = result[1].jsonObject["content"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertTrue(block["text"]!!.jsonPrimitive.content.contains("Image unavailable"))
    }

    @Test
    fun `chat completions sends all results before actual image data even with default capabilities`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
        method.isAccessible = true
        val result = method.invoke(api, messages, Model()) as JsonArray
        assertEquals(names.map { "call_$it" }, result.slice(2..4).map { it.jsonObject["tool_call_id"]!!.jsonPrimitive.content })
        result.takeLast(2).forEach { message ->
            val url = message.jsonObject["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
            assertEquals(dataUri, url)
            assertPng(url.substringAfter(','))
        }
    }

    @Test
    fun `google sends raw inline images after every function response`() {
        val provider = GoogleProvider(OkHttpClient())
        val method = GoogleProvider::class.java.getDeclaredMethod("buildContents", List::class.java)
        method.isAccessible = true
        val result = method.invoke(provider, messages) as JsonArray
        val userWithResults = result.first { message ->
            message.jsonObject["role"]?.jsonPrimitive?.content == "user" &&
                message.jsonObject["parts"]?.jsonArray?.any { it.jsonObject.containsKey("functionResponse") } == true
        }
        val outputs = userWithResults.jsonObject["parts"]!!.jsonArray
        val responses = outputs.filter { it.jsonObject.containsKey("functionResponse") }
        assertEquals(names, responses.map { it.jsonObject["functionResponse"]!!.jsonObject["name"]!!.jsonPrimitive.content })
        val images = outputs.filter { it.jsonObject.containsKey("inlineData") }
        assertEquals(2, images.size)
        images.forEach { image ->
            val data = image.jsonObject["inlineData"]!!.jsonObject
            assertEquals("image/png", data["mimeType"]!!.jsonPrimitive.content)
            assertPng(data["data"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `responses and subscription requests retain images and result ids`() {
        for (result in listOf(ResponseAPI(OkHttpClient()).buildMessages(messages), CodexResponseAPI(OkHttpClient()).buildMessages(messages))) {
            val outputs = result.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }
            assertEquals(names.map { "call_$it" }, outputs.map { it.jsonObject["call_id"]!!.jsonPrimitive.content })
            val images = result.flatMap { item -> (item.jsonObject["content"] as? JsonArray).orEmpty() }
                .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" }
            assertEquals(2, images.size)
            images.forEach { image ->
                val url = image.jsonObject["image_url"]!!.jsonPrimitive.content
                assertEquals(dataUri, url)
                assertPng(url.substringAfter(','))
            }
        }
    }

    private fun claudeMessages(input: List<UIMessage>): JsonArray {
        val method = ClaudeProvider::class.java.getDeclaredMethod("buildMessages", List::class.java,
            Boolean::class.javaPrimitiveType, ClaudePromptCacheTtl::class.java)
        method.isAccessible = true
        return method.invoke(ClaudeProvider(OkHttpClient()), input, false, ClaudePromptCacheTtl.FIVE_MINUTES) as JsonArray
    }

    private fun assertPng(data: String) {
        assertEquals(png, data)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            Base64.getDecoder().decode(data).take(8).toByteArray())
    }
}
