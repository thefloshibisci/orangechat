package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.AudioInputMode
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.google.InteractionsAPI
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ToolAudioRequestTest {
    @get:Rule val temporary = TemporaryFolder()
    private val client = OkHttpClient()
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="
    private val image = UIMessagePart.Image("data:image/png;base64,$png")
    private val directModel = Model(modelId = "test-audio", audioInputMode = AudioInputMode.AUDIO,
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE))

    private fun wav(): Pair<UIMessagePart.Audio, ByteArray> {
        val bytes = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(4); putShort(0); putShort(1000)
        }.array()
        val file = temporary.newFile("original-${System.nanoTime()}.wav").apply { writeBytes(bytes) }
        // Match Android's file URI while respecting the host path in Windows Robolectric.
        return UIMessagePart.Audio("file://" + file.toURI().rawPath) to bytes
    }

    private fun history(vararg tools: UIMessagePart.Tool) = listOf(
        UIMessage(role = MessageRole.ASSISTANT, parts = tools.toList())
    )

    private fun tool(id: String, vararg parts: UIMessagePart) =
        UIMessagePart.Tool(id, "video_$id", "{}", parts.toList())

    private fun openAi(messages: List<UIMessage>, model: Model = directModel): JsonArray {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
        method.isAccessible = true
        return method.invoke(ChatCompletionsAPI(client, KeyRoulette.default()), messages, model) as JsonArray
    }

    private fun google(messages: List<UIMessage>, supportsAudio: Boolean = true): JsonArray {
        val method = GoogleProvider::class.java.getDeclaredMethod("buildContents", List::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(GoogleProvider(client), messages, false, supportsAudio) as JsonArray
    }

    @Test fun `OpenAI parallel tools keep every result before user images and actual audio bytes`() {
        val (audio, bytes) = wav()
        val result = openAi(history(
            tool("one", UIMessagePart.Text("first"), image, audio),
            tool("two", UIMessagePart.Text("second"), audio),
        ))
        assertEquals(listOf("assistant", "tool", "tool", "user", "user"),
            result.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("one", "two"), result.slice(1..2).map { it.jsonObject["tool_call_id"]!!.jsonPrimitive.content })
        val firstMedia = result[3].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("text", "image_url", "input_audio"), firstMedia.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("data:image/png;base64,$png", firstMedia[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        val payload = firstMedia[2].jsonObject["input_audio"]!!.jsonObject
        assertEquals("wav", payload["format"]!!.jsonPrimitive.content)
        assertArrayEquals(bytes, Base64.getDecoder().decode(payload["data"]!!.jsonPrimitive.content))
        assertFalse(result[1].toString().contains("input_audio"))
        assertEquals(1, result[4].jsonObject["content"]!!.jsonArray.count { it.jsonObject["type"]?.jsonPrimitive?.content == "input_audio" })
    }

    @Test fun `OpenAI ASR mode keeps transcript and never leaks a raw audio path`() {
        val secretPath = "file:///private/token-secret.wav"
        val result = openAi(history(tool("one", UIMessagePart.VoiceMessage(secretPath, transcript = "实际语音文字"))),
            directModel.copy(audioInputMode = AudioInputMode.ASR)).toString()
        assertFalse(result.contains("input_audio"))
        assertFalse(result.contains(secretPath))
        assertTrue(result.contains("实际语音文字"))
        assertTrue(result.contains("transcript only"))
    }

    @Test fun `OpenAI unsupported or missing audio is explicit without exposing the encoding error`() {
        for (path in listOf("file:///private/token-secret.m4a", "file:///private/token-secret.wav")) {
            val result = openAi(history(tool("one", UIMessagePart.Audio(path)))).toString()
            assertFalse(result.contains("input_audio"))
            assertFalse(result.contains("token-secret"))
            assertTrue(result.contains("Audio unavailable"))
            assertTrue(result.contains("Do not infer"))
        }
    }

    @Test fun `Google user turn contains matching parallel responses existing images and real audio`() {
        val (audio, bytes) = wav()
        val result = google(history(tool("one", image, audio), tool("two", UIMessagePart.Text("done"), audio)))
        assertEquals(listOf("model", "user"), result.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val calls = result[0].jsonObject["parts"]!!.jsonArray.map { it.jsonObject["functionCall"]!!.jsonObject["id"]!!.jsonPrimitive.content }
        val parts = result[1].jsonObject["parts"]!!.jsonArray
        assertEquals(calls, parts.take(2).map { it.jsonObject["functionResponse"]!!.jsonObject["id"]!!.jsonPrimitive.content })
        val media = parts.mapNotNull { it.jsonObject["inlineData"]?.jsonObject }
        assertEquals(listOf("image/png", "audio/wav", "audio/wav"), media.map { it["mimeType"]!!.jsonPrimitive.content })
        assertEquals(png, media[0]["data"]!!.jsonPrimitive.content)
        media.drop(1).forEach { assertArrayEquals(bytes, Base64.getDecoder().decode(it["data"]!!.jsonPrimitive.content)) }
    }

    @Test fun `Google unavailable audio leaves an honest marker and ASR fallback stays text`() {
        val secretPath = "file:///private/token-secret.wav"
        val missing = google(history(tool("one", UIMessagePart.Audio(secretPath)))).toString()
        assertFalse(missing.contains("token-secret"))
        assertFalse(missing.contains("inlineData"))
        assertTrue(missing.contains("Audio unavailable"))
        val (audio, _) = wav()
        val disabled = google(history(tool("one", UIMessagePart.VoiceMessage(audio.url, transcript = "只有转写"))), false).toString()
        assertFalse(disabled.contains("inlineData"))
        assertTrue(disabled.contains("只有转写"))
        assertTrue(disabled.contains("transcript only"))
    }

    @Test fun `Interactions appends user audio after all parallel results without moving images`() {
        val (audio, bytes) = wav()
        val result = InteractionsAPI(client).buildInput(history(tool("one", image, audio), tool("two", audio)))
        assertEquals(listOf("function_call", "function_call", "function_result", "function_result", "user_input"),
            result.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals(listOf("one", "two"), result.slice(2..3).map { it.jsonObject["call_id"]!!.jsonPrimitive.content })
        assertEquals("image", result[2].jsonObject["result"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        val audios = result.last().jsonObject["content"]!!.jsonArray
        assertEquals(2, audios.size)
        audios.forEach {
            assertEquals("audio", it.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("audio/wav", it.jsonObject["mime_type"]!!.jsonPrimitive.content)
            assertArrayEquals(bytes, Base64.getDecoder().decode(it.jsonObject["data"]!!.jsonPrimitive.content))
        }
    }

    @Test fun `Interactions request respects ASR model setting and keeps the transcript fallback`() {
        val (audio, _) = wav()
        val result = InteractionsAPI(client).buildRequestBody(history(tool("one",
            UIMessagePart.VoiceMessage(audio.url, transcript = "转写保留"))),
            TextGenerationParams(directModel.copy(audioInputMode = AudioInputMode.ASR)), false)
        assertFalse(result.toString().contains("audio/wav"))
        assertFalse(result.toString().contains(audio.url))
        assertTrue(result.toString().contains("转写保留"))
        assertTrue(result.toString().contains("transcript only"))
    }

    @Test fun `Interactions malformed audio produces safe text without aborting tool pairing`() {
        val secretPath = "file:///private/token-secret.wav"
        val input = InteractionsAPI(client).buildInput(history(tool("one", UIMessagePart.Audio(secretPath))))
        assertEquals(listOf("function_call", "function_result", "user_input"),
            input.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        val marker = input.last().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("text", marker["type"]!!.jsonPrimitive.content)
        assertTrue(marker["text"]!!.jsonPrimitive.content.contains("Audio unavailable"))
        assertFalse(input.toString().contains("token-secret"))
    }
}
