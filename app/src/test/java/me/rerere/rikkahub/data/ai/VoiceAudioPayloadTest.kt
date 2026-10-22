package me.rerere.rikkahub.data.ai

import android.app.Application
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.ai.transformers.VoiceMessageTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.hooks.recordedVoicePart
import me.rerere.rikkahub.ui.hooks.completedRecordingAfter
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.ui.hooks.DirectAudioRecorder
import me.rerere.rikkahub.testing.HostFileUriShadow
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class, shadows = [HostFileUriShadow::class])
class VoiceAudioPayloadTest {
    @Test fun routesGeminiThroughBothTransportsButRespectsAsrOverride() {
        val model = Model(modelId = "google/gemini-2.5-flash")
        assertTrue(model.supportsDirectAudio(ProviderSetting.Google()))
        assertTrue(model.supportsDirectAudio(ProviderSetting.OpenAI()))
        assertFalse(model.supportsDirectAudio(ProviderSetting.OpenAI(useResponseApi = true)))
        assertFalse(model.copy(audioInputMode = AudioInputMode.ASR).supportsDirectAudio(ProviderSetting.Google()))
        assertFalse(Model(modelId = "text-model").supportsDirectAudio(ProviderSetting.OpenAI()))
        assertTrue(Model(modelId = "audio-alias", audioInputMode = AudioInputMode.AUDIO)
            .supportsDirectAudio(ProviderSetting.OpenAI()))
    }

    @Test fun conversationModelWinsOverGlobalAssistantAndRespectsProviderOverrides() {
        val textModel = Model(modelId = "text-model")
        val audioModel = Model(modelId = "proxy-gemini-3.8-flash-high")
        val textAssistant = Assistant(chatModelId = textModel.id)
        val audioAssistant = Assistant(chatModelId = audioModel.id)
        val settings = Settings.dummy().copy(
            assistants = listOf(textAssistant, audioAssistant), assistantId = textAssistant.id,
            chatModelId = textModel.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(textModel, audioModel))),
        )
        val selected = requireNotNull(settings.getChatModelForAssistant(audioAssistant.id))
        assertEquals(audioModel, selected)
        assertTrue(selected.supportsDirectAudio(selected.findProvider(settings.providers)))
        val override = audioModel.copy(providerOverwrite = ProviderSetting.Google())
        val overriddenSettings = settings.copy(providers = listOf(
            ProviderSetting.OpenAI(models = listOf(override), useResponseApi = true)))
        assertTrue(override.supportsDirectAudio(override.findProvider(overriddenSettings.providers)))
        assertFalse(Model(modelId = "notgemini-model").supportsDirectAudio(ProviderSetting.OpenAI()))
        assertFalse(Model(modelId = "gemini-image").supportsDirectAudio(ProviderSetting.Google()))
    }

    @Test fun completionWaitsForIdleAndNeverReusesThePreviousRecording() {
        val previous = "/voice/old.wav"
        val next = "/voice/new.wav"
        assertNull(ASRState(audioFilePath = previous).completedRecordingAfter(previous))
        val saving = ASRState(status = ASRStatus.Stopping, audioFilePath = next)
        assertNull(saving.completedRecordingAfter(previous))
        assertEquals(next, saving.copy(status = ASRStatus.Idle).completedRecordingAfter(previous))
    }

    @Test fun asrModePreservesTranscriptWhileDirectRecordingsStayAudio() = runBlocking {
        val model = Model(modelId = "gemini-3.8-flash-high", audioInputMode = AudioInputMode.ASR)
        val settings = Settings.dummy().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val ctx = TransformerContext(RuntimeEnvironment.getApplication(), model, Assistant(), settings)
        val asr = recordedVoicePart("/voice.wav", 5000, "transcribed words", sendAudio = false)
        val transformed = VoiceMessageTransformer.transform(ctx, listOf(UIMessage.user("").copy(parts = listOf(asr))))
        assertEquals("transcribed words", transformed.single().toText())
        val direct = recordedVoicePart("/voice.wav", 5000, "ignored transcript", sendAudio = true)
        assertTrue(direct is UIMessagePart.Audio)
        // ASR-only requests disclose missing audio instead of sending unsupported input.
        val adapted = VoiceMessageTransformer.transform(ctx,
            listOf(UIMessage.user("").copy(parts = listOf(direct)))).single().parts.single()
        assertTrue(adapted is UIMessagePart.Text)
        assertTrue((adapted as UIMessagePart.Text).text.contains("没有转写文本"))
    }

    @Test fun switchingAwayFromGeminiDoesNotLetHistoricalAudioBlockNewText() = runBlocking {
        val source = UIMessage.user("").copy(parts = listOf(
            UIMessagePart.Audio("file:///old.m4a"),
            UIMessagePart.Audio("file:///old.mp3"),
            UIMessagePart.VoiceMessage("/old.m4a", 5000, "known transcript"),
        ))
        val next = UIMessage.user("继续聊天")
        val model = Model(modelId = "claude-opus-5-5")
        val settings = Settings.dummy().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val ctx = TransformerContext(RuntimeEnvironment.getApplication(), model, Assistant(), settings)
        val result = VoiceMessageTransformer.transform(ctx, listOf(source, next))
        assertEquals(next, result[1])
        assertTrue(result[0].parts.all { it is UIMessagePart.Text })
        assertTrue(result[0].toText().contains("known transcript"))
        assertTrue(source.parts[0] is UIMessagePart.Audio)
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
        method.isAccessible = true
        assertFalse((method.invoke(api, result, model) as JsonArray).toString().contains("input_audio"))
    }

    @Test fun unsupportedOpenAiFormatsFallbackButNativeGoogleKeepsOriginalAudio() = runBlocking {
        val model = Model(modelId = "gemini-3.8-flash-high")
        val original = UIMessage.user("").copy(parts = listOf(
            UIMessagePart.Audio("file:///old.m4a"),
            UIMessagePart.VoiceMessage("/old.ogg", 5000, "fallback transcript"),
        ))
        suspend fun transform(provider: ProviderSetting): UIMessage {
            val settings = Settings.dummy().copy(providers = listOf(provider))
            return VoiceMessageTransformer.transform(
                TransformerContext(RuntimeEnvironment.getApplication(), model, Assistant(), settings), listOf(original)
            ).single()
        }
        val openai = transform(ProviderSetting.OpenAI(models = listOf(model)))
        assertTrue(openai.parts.all { it is UIMessagePart.Text })
        assertEquals("fallback transcript", (openai.parts[1] as UIMessagePart.Text).text)
        val google = transform(ProviderSetting.Google(models = listOf(model)))
        assertEquals(original.parts[0], google.parts[0])
        assertTrue(google.parts[1] is UIMessagePart.Audio)
    }

    @Test fun wavBytesSurviveOpenAiAndGoogleMessageSerialization() = runBlocking {
        val bytes = DirectAudioRecorder.wavHeader(4) + byteArrayOf(0, 0, 1, 0)
        val file = File.createTempFile("voice", ".wav", RuntimeEnvironment.getApplication().cacheDir)
        try {
            file.writeBytes(bytes)
            val model = Model(modelId = "gemini-3.8-flash-high")
            val provider = ProviderSetting.OpenAI(models = listOf(model))
            val settings = Settings.dummy().copy(providers = listOf(provider))
            val ctx = TransformerContext(RuntimeEnvironment.getApplication(), model, Assistant(), settings)
            val recorded = recordedVoicePart(file.absolutePath, 5000, "must not replace audio", sendAudio = true)
            val input = UIMessage(role = MessageRole.USER, parts = listOf(
                UIMessagePart.Text("听听这段音频"), recorded))
            val message = VoiceMessageTransformer.transform(ctx, listOf(input)).single()
            val uploaded = input.copy(parts = listOf(
                UIMessagePart.Text("听听这段音频"), UIMessagePart.Audio(Uri.fromFile(file).toString())))
            assertEquals(uploaded, message)
            val legacy = input.copy(parts = listOf(UIMessagePart.VoiceMessage(
                url = file.absolutePath, duration = 5000, transcript = "must not replace audio")))
            assertEquals(recorded, VoiceMessageTransformer.transform(ctx, listOf(legacy)).single().parts.single())
            val openai = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
            method.isAccessible = true
            val messages = method.invoke(openai, listOf(message), Model(modelId = "gemini-2.5-flash")) as JsonArray
            val audio = messages[0].jsonObject["content"]!!.jsonArray[1].jsonObject
            assertEquals("input_audio", audio["type"]!!.jsonPrimitive.content)
            val data = audio["input_audio"]!!.jsonObject
            assertEquals("wav", data["format"]!!.jsonPrimitive.content)
            assertArrayEquals(bytes, Base64.decode(data["data"]!!.jsonPrimitive.content, Base64.DEFAULT))
            val google = GoogleProvider(OkHttpClient())
            val googleMethod = GoogleProvider::class.java.getDeclaredMethod("buildContents", List::class.java)
            googleMethod.isAccessible = true
            val contents = googleMethod.invoke(google, listOf(message)) as JsonArray
            val inline = contents[0].jsonObject["parts"]!!.jsonArray[1].jsonObject["inlineData"]!!.jsonObject
            assertEquals("audio/wav", inline["mimeType"]!!.jsonPrimitive.content)
            assertArrayEquals(bytes, Base64.decode(inline["data"]!!.jsonPrimitive.content, Base64.DEFAULT))
        } finally { file.delete() }
    }
}
