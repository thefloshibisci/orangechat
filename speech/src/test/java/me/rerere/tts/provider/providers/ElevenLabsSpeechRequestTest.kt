package me.rerere.tts.provider.providers

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tts.model.SpeechStyle
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test

class ElevenLabsSpeechRequestTest {
    private val setting = TTSProviderSetting.ElevenLabs(modelId = "eleven_v4", voiceId = "saved-voice")

    @Test fun `v4 sends only supported settings and native tags`() {
        val body = elevenLabsRequestBody(setting, TTSRequest("[whispers]你好[[voice emotion=happy]]呀(laughs)<#0.3#>[[/voice]]",
            SpeechStyle(emotion = "happy", speed = .5f, pitch = -2, volume = 2f, stability = .3f, similarityBoost = .8f)))
        assertEquals(setOf("text", "model_id", "voice_settings"), body.keys)
        assertEquals("[whispers]你好呀", body["text"]!!.jsonPrimitive.content)
        assertEquals("eleven_v4", body["model_id"]!!.jsonPrimitive.content)
        val voice = body["voice_settings"]!!.jsonObject
        assertEquals(setOf("stability", "similarity_boost"), voice.keys)
        assertEquals("0.3", voice["stability"]!!.jsonPrimitive.content)
        assertEquals("0.8", voice["similarity_boost"]!!.jsonPrimitive.content)
    }

    @Test fun `output format belongs in query and voice id cannot change request route`() {
        val url = elevenLabsSpeechUrl(setting.copy(baseUrl = "https://example.invalid/v1/", voiceId = "a/b?c"))
        assertEquals("mp3_44100_128", url.queryParameter("output_format"))
        assertEquals(listOf("v1", "text-to-speech", "a/b?c"), url.pathSegments)
        assertFalse(elevenLabsRequestBody(setting, TTSRequest("hello")).containsKey("output_format"))
    }

    @Test fun `v3 stability is discrete and nonfinite imported values are sanitized`() {
        val body = elevenLabsRequestBody(setting.copy(modelId = "eleven_v3", stability = .68f, similarityBoost = Float.NaN), TTSRequest("hello"))
        val voice = body["voice_settings"]!!.jsonObject
        assertEquals("0.5", voice["stability"]!!.jsonPrimitive.content)
        assertEquals("0.75", voice["similarity_boost"]!!.jsonPrimitive.content)
    }

    @Test fun `omitted controls retain configured voice defaults`() {
        val body = elevenLabsRequestBody(setting.copy(stability = .4f, similarityBoost = .9f), TTSRequest("hello"))
        assertEquals("0.4", body["voice_settings"]!!.jsonObject["stability"]!!.jsonPrimitive.content)
        assertEquals("0.9", body["voice_settings"]!!.jsonObject["similarity_boost"]!!.jsonPrimitive.content)
    }
}
