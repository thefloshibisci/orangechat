package me.rerere.tts.provider.providers

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tts.model.SpeechStyle
import me.rerere.tts.controller.TextChunker
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test

class MiniMaxSpeechRequestTest {
    private val setting = TTSProviderSetting.MiniMax(model = "speech-2.8-hd", voiceId = "my-cloned-voice", speed = 1.1f)

    @Test fun `tts queue preserves separate sentence style through actual request building`() {
        val raw = "[[voice emotion=happy speed=1.2 pitch=2]]今天开心。[[/voice]]" +
            "[[voice emotion=calm speed=0.8 pitch=-2]]慢慢说。[[/voice]]"
        val chunks = TextChunker().splitSpeech(raw)
        assertEquals(2, chunks.size)
        assertNotEquals(chunks[0].id, chunks[1].id)
        assertEquals(listOf(0, 1), chunks.map { it.index })
        val requests = chunks.map { miniMaxRequestBody(setting, TTSRequest(it.text, it.style)) }
        assertEquals(listOf("1.2", "0.8"), requests.map { it["voice_setting"]!!.jsonObject["speed"]!!.jsonPrimitive.content })
        assertEquals(listOf("2", "-2"), requests.map { it["voice_setting"]!!.jsonObject["pitch"]!!.jsonPrimitive.content })
        assertEquals(listOf("happy", "calm"), requests.map { it["voice_setting"]!!.jsonObject["emotion"]!!.jsonPrimitive.content })
    }

    @Test fun `28 sends supported per-utterance style and retains cloned voice`() {
        val body = miniMaxRequestBody(setting, TTSRequest("你好(laughs)<#0.3#>呀", SpeechStyle("happy", .9f, -2, .5f)))
        assertEquals("my-cloned-voice", body["voice_setting"]!!.jsonObject["voice_id"]!!.jsonPrimitive.content)
        val voice = body["voice_setting"]!!.jsonObject
        assertEquals("happy", voice["emotion"]!!.jsonPrimitive.content)
        assertEquals("0.9", voice["speed"]!!.jsonPrimitive.content)
        assertEquals("-2", voice["pitch"]!!.jsonPrimitive.content)
        assertEquals("0.5", voice["vol"]!!.jsonPrimitive.content)
        assertEquals("你好(laughs)<#0.3#>呀", body["text"]!!.jsonPrimitive.content)
    }

    @Test fun `28 drops unsupported whisper and fluent and auto instead of sending invalid emotion`() {
        for (emotion in listOf("whisper", "fluent", "auto", "unknown")) {
            val body = miniMaxRequestBody(setting, TTSRequest("你好", SpeechStyle(emotion)))
            assertFalse(body["voice_setting"]!!.jsonObject.containsKey("emotion"))
        }
    }

    @Test fun `switching to26 permits whisper but strips28-only sound tags`() {
        val body = miniMaxRequestBody(setting.copy(model = "speech-2.6-hd"),
            TTSRequest("你好(laughs)<#0.3#>呀", SpeechStyle("whisper")))
        assertEquals("whisper", body["voice_setting"]!!.jsonObject["emotion"]!!.jsonPrimitive.content)
        assertEquals("你好<#0.3#>呀", body["text"]!!.jsonPrimitive.content)
    }

    @Test fun `old and unknown models never receive unsupported new controls`() {
        val request = TTSRequest("你好(sighs)<#0.3#>呀", SpeechStyle("whisper", .8f, 2, 1f))
        val old = miniMaxRequestBody(setting.copy(model = "speech-02-hd"), request)
        assertFalse(old["voice_setting"]!!.jsonObject.containsKey("emotion"))
        assertEquals("你好<#0.3#>呀", old["text"]!!.jsonPrimitive.content)
        val unknown = miniMaxRequestBody(setting.copy(model = "custom-model"), request)
        assertEquals(setOf("voice_id"), unknown["voice_setting"]!!.jsonObject.keys)
        assertEquals("你好呀", unknown["text"]!!.jsonPrimitive.content)
    }

    @Test fun `plain utterance retains manually configured defaults`() {
        val voice = miniMaxRequestBody(setting, TTSRequest("你好"))["voice_setting"]!!.jsonObject
        assertEquals("calm", voice["emotion"]!!.jsonPrimitive.content)
        assertEquals("1.1", voice["speed"]!!.jsonPrimitive.content)
        assertFalse(voice.containsKey("pitch"))
        assertFalse(voice.containsKey("vol"))
    }

    @Test fun `official audio event without legacy ced is accepted`() {
        val data = decodeMiniMaxAudioEvent("""{"data":{"audio":"0102","status":1},"base_resp":{"status_code":0}}""")
        assertEquals("0102", data!!.audio)
        assertEquals("", data.ced)
        assertNull(decodeMiniMaxAudioEvent("[DONE]"))
        assertNull(decodeMiniMaxAudioEvent("""{"data":null,"base_resp":{"status_code":0}}"""))
    }

    @Test fun `server parameter rejection surfaces as an error instead of silently succeeding`() {
        val error = runCatching {
            decodeMiniMaxAudioEvent("""{"data":null,"base_resp":{"status_code":1004,"status_msg":"invalid emotion"}}""")
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("1004"))
    }
}
