package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.ai.transformers.VoiceMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.limitRecentAudio
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.attachmentUrls
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SavedSpeechHistoryTest {
    @Test fun `saved voice survives message roundtrip and attachment traversal but is not reuploaded to model`() = runBlocking {
        val audio = UIMessagePart.Audio("file:///saved-voice.mp3", buildJsonObject {
            put(GENERATED_SPEECH, true); put("transcript", "这是已发送语音"); put("duration_ms", 18000)
        })
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "call-1", toolName = "text_to_speech", input = "{}",
            output = listOf(UIMessagePart.Text("{\"success\":true}"), audio),
        )))
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(message))
        assertEquals(message, restored)
        assertTrue("Attachment cleanup/backup can see nested audio", attachmentUrls(listOf(restored)).contains(audio.url))
        val userVoice = UIMessage.user("").copy(parts = listOf(UIMessagePart.Audio("file:///user.wav")))
        val limited = limitRecentAudio(listOf(userVoice, restored), 1)
        assertTrue(limited.first().parts.single() is UIMessagePart.Audio)
        for (modelId in listOf("gemini-2.5-flash", "text-model")) {
            val model = Model(modelId = modelId)
            val ctx = TransformerContext(RuntimeEnvironment.getApplication(), model, Assistant(maxContextVoices = 1),
                Settings.dummy().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(model)))))
            val adapted = VoiceMessageTransformer.transform(ctx, listOf(restored)).single().parts.single() as UIMessagePart.Tool
            assertTrue(adapted.output.none { it is UIMessagePart.Audio })
            assertEquals("[已发送语音] 这是已发送语音", (adapted.output.last() as UIMessagePart.Text).text)
            assertEquals(audio, (restored.parts.single() as UIMessagePart.Tool).output.last())
        }
    }
}
