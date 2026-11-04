package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.provider.AudioInputMode
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaInputAvailabilityTest {
    private val gemini = Model(modelId = "gemini-3.8-flash", displayName = "gemini")

    @Test fun googleProviderEnablesVideoAndGeminiAudio() {
        val google = ProviderSetting.Google()
        assertNull(videoInputUnavailableReason(gemini, google))
        assertNull(audioInputUnavailableReason(gemini, google))
    }

    @Test fun nonGoogleProviderExplainsTheResolvedTypeInsteadOfHidingVideo() {
        val reason = videoInputUnavailableReason(gemini, ProviderSetting.OpenAI(useResponseApi = true))
        assertNotNull(reason)
        assertTrue(reason!!.contains("gemini-3.8-flash"))
        assertTrue(reason.contains("Responses API"))
    }

    @Test fun providerOverrideIsCalledOutBecauseItMasksTheParentGoogleSetting() {
        val overridden = gemini.copy(providerOverwrite = ProviderSetting.OpenAI())
        assertTrue(videoInputUnavailableReason(overridden, ProviderSetting.OpenAI())!!.contains("供应商覆盖"))
    }

    @Test fun asrModeAndMissingModelHaveSpecificReasons() {
        val asr = gemini.copy(audioInputMode = AudioInputMode.ASR)
        assertTrue(audioInputUnavailableReason(asr, ProviderSetting.Google())!!.contains("语音转写"))
        assertEquals("当前助手没有可用的聊天模型，无法发送视频", videoInputUnavailableReason(null, null))
    }
}
