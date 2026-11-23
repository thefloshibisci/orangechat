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
    fun `claude sends images after every tool result instead of inside them`() {
        val result = claudeMessages(messages)
        val calls = result[1].jsonObject["content"]!!.jsonArray
        val outputs = result[2].jsonObject["content"]!!.jsonArray
        assertEquals(names.map { "call_$it" }, calls.map { it.jsonObject["id"]!!.jsonPrimitive.content })

        // tool_result 必须全部排在前面，且只含文字——网关会丢掉里面的图片块
        val results = outputs.take(names.size).map { it.jsonObject }
        assertEquals(names.map { "call_$it" }, results.map { it["tool_use_id"]!!.jsonPrimitive.content })
        results.forEachIndexed { index, output ->
            val content = output["content"]!!.jsonArray
            assertEquals("result_${names[index]}", content.single().jsonObject["text"]!!.jsonPrimitive.content)
        }

        // 每张图片前紧邻工具名、调用 ID 和调用内序号。
        val trailing = outputs.drop(names.size).map { it.jsonObject }
        assertEquals(4, trailing.size)
        assertEquals("[Tool take_screenshot, tool_call_id=call_take_screenshot, image 1/1]",
            trailing[0]["text"]!!.jsonPrimitive.content)
        assertEquals("[Tool generate_image, tool_call_id=call_generate_image, image 1/1]",
            trailing[2]["text"]!!.jsonPrimitive.content)
        val images = listOf(trailing[1], trailing[3])
        assertEquals(2, images.size)
        images.forEach { image ->
            assertEquals("image", image["type"]!!.jsonPrimitive.content)
            val source = image["source"]!!.jsonObject
            assertEquals("base64", source["type"]!!.jsonPrimitive.content)
            assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
            assertPng(source["data"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `claude labels each image from parallel same-name calls including failures`() {
        val tools = listOf(
            UIMessagePart.Tool("first", "workspace_read_file", "{\"path\":\"a\"}", listOf(
                UIMessagePart.Text("first result"), UIMessagePart.Image(dataUri), UIMessagePart.Image("unsupported:bad"),
            )),
            UIMessagePart.Tool("second", "workspace_read_file", "{\"path\":\"b\"}", listOf(
                UIMessagePart.Text("second result"), UIMessagePart.Image(dataUri), UIMessagePart.Image(dataUri),
            )),
        )
        val result = claudeMessages(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = tools)))
        val outputs = result[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(10, outputs.size)
        assertEquals(listOf("first", "second"), outputs.take(2).map { it["tool_use_id"]!!.jsonPrimitive.content })
        assertEquals(listOf("first result", "second result"), outputs.take(2).map {
            it["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        })
        listOf("first" to 1, "first" to 2, "second" to 1, "second" to 2).forEachIndexed { index, (id, number) ->
            val label = outputs[2 + index * 2]
            val image = outputs[3 + index * 2]
            assertEquals("[Tool workspace_read_file, tool_call_id=$id, image $number/2]",
                label["text"]!!.jsonPrimitive.content)
            if (id == "first" && number == 2) {
                assertEquals("text", image["type"]!!.jsonPrimitive.content)
                assertTrue(image["text"]!!.jsonPrimitive.content.contains("Image unavailable"))
            } else {
                assertEquals("image", image["type"]!!.jsonPrimitive.content)
                assertPng(image["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `claude image-only result keeps tool_result non-empty`() {
        val tool = UIMessagePart.Tool("only", "take_screenshot", "{}", listOf(UIMessagePart.Image(dataUri)))
        val result = claudeMessages(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))))
        val content = result[1].jsonObject["content"]!!.jsonArray
        val toolResult = content.first().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertTrue(toolResult["text"]!!.jsonPrimitive.content.contains("see the following attachment"))
        assertEquals("image", content.last().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `claude failed attachment is explicit not an empty content block`() {
        val tool = UIMessagePart.Tool("bad", "take_screenshot", "{}", listOf(UIMessagePart.Image("unsupported:bad")))
        val result = claudeMessages(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))))
        val content = result[1].jsonObject["content"]!!.jsonArray
        val block = content.last().jsonObject
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
