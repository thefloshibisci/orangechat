package me.rerere.tts.provider

import me.rerere.tts.model.SpeechStyle
import me.rerere.tts.model.TTSRequest
import org.junit.Assert.*
import org.junit.Test

class SpeechCapabilitiesTest {
    @Test fun `v4 exposes different controls from minimax without leaking configuration`() {
        val mini = speechCapabilities(TTSProviderSetting.MiniMax(model = "speech-2.8-hd"))
        val eleven = speechCapabilities(TTSProviderSetting.ElevenLabs(modelId = "eleven_v4", apiKey = "PRIVATE_KEY"))
        assertEquals(setOf("text", "emotion", "speed", "pitch", "volume"), mini.parameters.keys)
        assertEquals(setOf("text", "stability", "similarity_boost"), eleven.parameters.keys)
        assertTrue(eleven.description.contains("eleven_v4"))
        assertTrue(eleven.description.contains("[whispers]"))
        assertFalse(eleven.description.contains("PRIVATE_KEY"))
        assertTrue(speechDeliveryPrompt(TTSProviderSetting.ElevenLabs(modelId = "eleven_v4")).contains("[whispers]"))
    }

    @Test fun `other and unknown providers expose only implemented controls`() {
        listOf(null, TTSProviderSetting.OpenAI(), TTSProviderSetting.MiniMax(model = "custom"),
            TTSProviderSetting.SystemTTS(), TTSProviderSetting.Gemini()).forEach {
            assertEquals(setOf("text"), speechCapabilities(it).parameters.keys)
        }
    }

    @Test fun `switch drops old provider hints but preserves eleven native tags`() {
        val request = TTSRequest("[whispers]你好(laughs)<#0.3#>呀", SpeechStyle("happy", .8f, -2, stability = .4f))
        val eleven = compatibleSpeechRequest(TTSProviderSetting.ElevenLabs(modelId = "eleven_v4"), request)
        assertEquals("[whispers]你好呀", eleven.text)
        assertEquals(SpeechStyle(stability = .4f), eleven.style)
        assertEquals("你好(laughs)<#0.3#>呀", compatibleSpeechRequest(TTSProviderSetting.MiniMax(), request).text)
        assertEquals("你好呀", compatibleSpeechRequest(TTSProviderSetting.OpenAI(), request).text)
    }
}
