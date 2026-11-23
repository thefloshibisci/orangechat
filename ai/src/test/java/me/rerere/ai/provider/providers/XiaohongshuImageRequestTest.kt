package me.rerere.ai.provider.providers

import android.graphics.Bitmap
import kotlinx.serialization.json.JsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.CodexResponseAPI
import me.rerere.ai.provider.providers.google.InteractionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.RequestImageEncoding
import me.rerere.ai.util.encodeBase64
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class XiaohongshuImageRequestTest {
    @get:Rule val temporary = TemporaryFolder()
    private val image = UIMessagePart.Image("https://sns-webpic-qc.xhscdn.com/signed/test!webp")
    private val messages get() = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("shared note"), image)),
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("call_image", "gongdu_result_page",
            "{}", listOf(UIMessagePart.Text("image page"), image)))),
        UIMessage.user("continue this old conversation"),
    )

    private fun client(code: Int, payload: ByteArray, onRequest: () -> Unit) =
        OkHttpClient.Builder().addInterceptor { chain ->
            onRequest()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
                .body(payload.toResponseBody()).build()
        }.build()

    private fun requests(): List<JsonArray> {
        val http = OkHttpClient()
        val chat = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
            .apply { isAccessible = true }.invoke(ChatCompletionsAPI(http, KeyRoulette.default()), messages,
                Model(inputModalities = listOf(Modality.TEXT, Modality.IMAGE))) as JsonArray
        val claude = ClaudeProvider::class.java.getDeclaredMethod("buildMessages", List::class.java,
            Boolean::class.javaPrimitiveType, ClaudePromptCacheTtl::class.java).apply { isAccessible = true }
            .invoke(ClaudeProvider(http), messages, false, ClaudePromptCacheTtl.FIVE_MINUTES) as JsonArray
        val google = GoogleProvider::class.java.getDeclaredMethod("buildContents", List::class.java)
            .apply { isAccessible = true }.invoke(GoogleProvider(http), messages) as JsonArray
        return listOf(chat, ResponseAPI(http).buildMessages(messages), CodexResponseAPI(http).buildMessages(messages),
            claude, google, InteractionsAPI(http).buildInput(messages))
    }

    @Test fun `historical user and tool images send actual bytes through all providers and reuse cached download`() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        var calls = 0
        val directory = temporary.newFolder()
        RequestImageEncoding.configure(directory, client(200, output.toByteArray()) { calls++ })
        val encoded = image.encodeBase64().getOrThrow()
        assertEquals("image/jpeg", encoded.mimeType)
        assertTrue(encoded.base64.startsWith("data:image/jpeg;base64,"))
        val bytes = Base64.getDecoder().decode(encoded.base64.substringAfter(','))
        assertEquals(0xff, bytes[0].toInt() and 255)
        assertEquals(0xd8, bytes[1].toInt() and 255)
        val raw = image.encodeBase64(false).getOrThrow().base64
        assertEquals(encoded.base64.substringAfter(','), raw)
        requests().forEach { request ->
            assertFalse(request.toString().contains("xhscdn.com"))
            assertEquals("Both the user image and tool image must contain pixels", 2, request.toString().split(raw).size - 1)
            assertTrue(request.toString().contains("continue this old conversation"))
            assertTrue(request.toString().contains("call_image") || request.toString().contains("gongdu_result_page"))
        }
        RequestImageEncoding.configure(directory, client(403, byteArrayOf()) { calls++ })
        assertEquals(encoded, image.encodeBase64().getOrThrow())
        assertEquals(1, calls)
    }

    @Test fun `expired historical images produce explicit placeholders without breaking text or tool pairing`() {
        var calls = 0
        RequestImageEncoding.configure(temporary.newFolder(), client(403, byteArrayOf()) { calls++ })
        requests().forEach { request ->
            val serialized = request.toString()
            assertFalse(serialized.contains("xhscdn.com"))
            assertTrue(serialized, serialized.contains("Image unavailable"))
            assertTrue(serialized.contains("continue this old conversation"))
            assertTrue(serialized.contains("call_image") || serialized.contains("gongdu_result_page"))
        }
        assertEquals(1, calls)
    }

    @Test fun `ordinary remote URLs keep their existing behavior`() {
        RequestImageEncoding.configure(temporary.newFolder(), client(500, byteArrayOf()) { error("must not download") })
        val ordinary = UIMessagePart.Image("https://images.example/photo.png")
        assertEquals(ordinary.url, ordinary.encodeBase64().getOrThrow().base64)
    }
}
