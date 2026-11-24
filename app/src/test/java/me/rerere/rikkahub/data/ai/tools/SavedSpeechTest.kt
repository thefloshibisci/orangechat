package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.SpeechStyle
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SavedSpeechTest {
    @Test fun `eleven generates once for a multiline message and preserves native tags`() = runBlocking {
        val source = "[whispers]你好。\n\n[slow]这是第二句话。"
        var calls = 0
        val clips = generateSavedSpeech(TTSProviderSetting.ElevenLabs(modelId = "eleven_v4"), source, SpeechStyle(stability = .4f),
            synthesize = { chunk ->
                calls++; assertTrue(chunk.text.contains("[whispers]")); assertTrue(chunk.text.contains("[slow]"))
                assertEquals(.4f, chunk.style.stability)
                TTSResponse(byteArrayOf(1, 2), AudioFormat.MP3)
            }, persist = { _, transcript ->
                UIMessagePart.Audio("file:///voice.mp3", buildJsonObject {
                    put(GENERATED_SPEECH, true); put("duration_ms", 19500); put("transcript", transcript)
                })
            })
        assertEquals(1, calls)
        assertEquals(19500, clips.single().speechDurationMs())
        assertFalse(clips.single().speechTranscript().contains("[whispers]"))
        val restored = Json.decodeFromString<UIMessagePart>(Json.encodeToString<UIMessagePart>(clips.single())) as UIMessagePart.Audio
        assertEquals(clips.single(), restored)
        assertTrue(restored.isGeneratedSpeech())
    }

    @Test fun `partial paid audio is retained and errors never retry failed chunks`() = runBlocking {
        var calls = 0
        val tool = speechMessageTool({ provider }) { selected, text, style ->
            generateSavedSpeech(selected, text, style, synthesize = {
                calls++
                if (calls == 2) error("second chunk failed")
                TTSResponse(byteArrayOf(1, 2), AudioFormat.MP3)
            }, persist = { _, _ -> UIMessagePart.Audio("file:///first.mp3") })
        }
        val result = tool.execute(buildJsonObject {
            put("text", "[[voice emotion=calm]]第一句。[[/voice]][[voice emotion=happy]]第二句。[[/voice]]")
        })
        assertEquals(2, calls)
        assertEquals("file:///first.mp3", result.filterIsInstance<UIMessagePart.Audio>().single().url)
        assertTrue((result.first() as UIMessagePart.Text).text.contains("\"success\":false"))
    }

    private val provider = TTSProviderSetting.MiniMax(model = "speech-2.8-hd")

    @Test fun `pcm is saved with correct duration-bearing wav header not estimated from text`() {
        val pcm = ByteArray(24000 * 2 * 20)
        val (bytes, format) = speechFileBytes(TTSResponse(pcm, AudioFormat.PCM, 24000))
        assertEquals(AudioFormat.WAV, format)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(48000, header.getInt(28))
        assertEquals(pcm.size, header.getInt(40))
        assertEquals(20000, header.getInt(40) * 1000L / header.getInt(28))
    }

    @Test fun `empty audio fails without creating a playable attachment`() = runBlocking {
        var saves = 0
        val failed = runCatching { generateSavedSpeech(provider, "hello", SpeechStyle(),
            synthesize = { TTSResponse(byteArrayOf(), AudioFormat.MP3) },
            persist = { _, _ -> saves++; UIMessagePart.Audio("file:///bad.mp3") }) }
        assertTrue(failed.isFailure); assertEquals(0, saves)
    }
}
