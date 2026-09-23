package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OcrToolImageTest {
    private val tools = listOf("take_screenshot", "generate_image", "camera_capture", "mcp_image").map { name ->
        UIMessagePart.Tool(name, name, "{}", listOf(
            UIMessagePart.Text("Image saved"),
            UIMessagePart.Image("file:///cache/$name.png"),
            UIMessagePart.Image("data:image/png;base64,aA=="),
        ))
    }

    @Test
    fun `tool images survive a text default without invoking OCR`() = runBlocking {
        val original = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = tools))
        val result = transformUploadedImagesForOcr(original, Model()) { error("Tool images must not become OCR") }
        assertEquals(original, result)
        tools.forEachIndexed { index, tool -> assertSame(tool, result.single().parts[index]) }
    }

    @Test
    fun `ordinary upload OCR does not change adjacent tool images or stored history`() = runBlocking {
        val upload = UIMessagePart.Image("file:///cache/upload.png")
        val original = listOf(UIMessage(role = MessageRole.USER, parts = listOf(upload)),
            UIMessage(role = MessageRole.ASSISTANT, parts = tools))
        val calls = mutableListOf<String>()
        val result = transformUploadedImagesForOcr(original, Model()) { calls += it.url; "Recognized" }
        assertEquals(listOf(upload.url), calls)
        assertEquals(listOf(UIMessagePart.Text("Recognized")), result.first().parts)
        assertEquals(tools, result.last().parts)
        assertEquals(listOf(upload), original.first().parts)
        assertEquals(original.map { it.id }, result.map { it.id })
    }

    @Test
    fun `vision models preserve uploads and tool attachments`() = runBlocking {
        val original = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///cache/upload.png"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = tools))
        val result = transformUploadedImagesForOcr(original,
            Model(inputModalities = listOf(Modality.TEXT, Modality.IMAGE))) { error("No OCR for vision") }
        assertSame(original, result)
    }

    @Test
    fun `tool attachments reach request content after the OCR pipeline`() = runBlocking {
        val image = UIMessagePart.Image("data:image/png;base64,aA==")
        val original = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = tools.take(2).map {
            it.copy(output = listOf(UIMessagePart.Text("Saved"), image))
        }))
        val model = Model()
        val transformed = transformUploadedImagesForOcr(original, model) { error("Must preserve tool image") }
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
        method.isAccessible = true
        val request = method.invoke(ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default()), transformed, model) as JsonArray
        assertEquals(listOf("assistant", "tool", "tool", "user", "user"),
            request.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        request.takeLast(2).forEach { message ->
            assertEquals(image.url, message.jsonObject["content"]!!.jsonArray[1]
                .jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        }
        assertEquals(original, transformed)
    }
}
