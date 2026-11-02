package me.rerere.tts.provider

import me.rerere.tts.model.SpeechStyle
import me.rerere.tts.model.TTSRequest
import org.junit.Assert.*
import org.junit.Test

class SpeechCompatibilityTest {
    @Test fun `nonMinimax providers receive clean words and no unverified hints`() {
        val request = TTSRequest("你好(laughs)<#0.3#>呀", SpeechStyle("happy", .8f, -2))
        val settings = listOf(TTSProviderSetting.OpenAI(), TTSProviderSetting.Gemini(), TTSProviderSetting.SystemTTS(),
            TTSProviderSetting.Qwen(), TTSProviderSetting.Groq(), TTSProviderSetting.XAI(),
            TTSProviderSetting.MiMo(), TTSProviderSetting.ElevenLabs())
        settings.forEach { setting ->
            val compatible = compatibleSpeechRequest(setting, request)
            assertEquals(setting.name, "你好呀", compatible.text)
            assertEquals(SpeechStyle(), compatible.style)
        }
    }

    @Test fun `call prompts advertise only verified capabilities of current model`() {
        val prompt28 = speechDeliveryPrompt(TTSProviderSetting.MiniMax(model = "speech-2.8-hd"))
        assertTrue(prompt28.contains("(laughs)"))
        assertTrue(prompt28.contains("不支持whisper/fluent"))
        val prompt26 = speechDeliveryPrompt(TTSProviderSetting.MiniMax(model = "speech-2.6-hd"))
        assertTrue(prompt26.contains("还可用fluent、whisper"))
        assertFalse(prompt26.contains("(laughs)"))
        assertFalse(speechDeliveryPrompt(TTSProviderSetting.OpenAI()).contains("[[voice"))
    }
}
