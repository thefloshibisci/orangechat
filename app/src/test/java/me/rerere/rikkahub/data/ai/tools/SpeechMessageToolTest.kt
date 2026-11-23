package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tts.model.SpeechStyle
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test

class SpeechMessageToolTest {
    @Test fun `tool refreshes capabilities and refuses an in-flight provider switch before spending`() = runBlocking {
        var provider: TTSProviderSetting = TTSProviderSetting.MiniMax()
        var calls = 0
        val generate: suspend (TTSProviderSetting, String, SpeechStyle) -> List<UIMessagePart.Audio> = { _, _, _ -> calls++; listOf(UIMessagePart.Audio("file:///saved.mp3")) }
        val old = speechMessageTool({ provider }, generate)
        assertTrue((old.parameters() as InputSchema.Obj).properties.containsKey("emotion"))
        provider = TTSProviderSetting.ElevenLabs(modelId = "eleven_v4")
        val rejected = old.execute(Json.parseToJsonElement("""{"text":"hello"}"""))
        assertEquals(0, calls)
        assertTrue((rejected.first() as UIMessagePart.Text).text.contains("已切换"))
        val fresh = speechMessageTool({ provider }, generate)
        assertEquals(setOf("text", "stability", "similarity_boost"), (fresh.parameters() as InputSchema.Obj).properties.keys)
        val result = fresh.execute(Json.parseToJsonElement("""{"text":"[whispers]hello","stability":0.4}"""))
        assertEquals(1, calls)
        assertTrue((result.first() as UIMessagePart.Text).text.contains("\"autoplay\":false"))
        assertEquals("file:///saved.mp3", (result.last() as UIMessagePart.Audio).url)
    }

    @Test fun `legacy or invalid fields are rejected without a model call`() = runBlocking {
        val provider = TTSProviderSetting.ElevenLabs(modelId = "eleven_v4")
        var calls = 0
        val tool = speechMessageTool({ provider }) { _, _, _ -> calls++; emptyList() }
        for (json in listOf("""{"text":"hi","emotion":"happy"}""", """{"text":"hi","speed":0.8}""",
            """{"text":"hi","stability":2}""", """{"text":"hi","stability":"0.5"}""", """{"text":""}""")) {
            assertTrue((tool.execute(Json.parseToJsonElement(json)).first() as UIMessagePart.Text).text.contains("\"success\":false"))
        }
        assertEquals(0, calls)
    }

    @Test fun `optional eleven settings reach synthesis and failures are not automatically retried`() = runBlocking {
        val provider = TTSProviderSetting.ElevenLabs(modelId = "eleven_v4")
        var calls = 0
        val tool = speechMessageTool({ provider }) { _, text, style ->
            calls++; assertEquals("[whispers]hi", text)
            assertEquals(SpeechStyle(stability = .4f, similarityBoost = .7f), style)
            error("synthetic network failure")
        }
        val result = tool.execute(Json.parseToJsonElement("""{"text":"[whispers]hi","stability":0.4,"similarity_boost":0.7}"""))
        assertEquals(1, calls)
        assertTrue((result.first() as UIMessagePart.Text).text.contains("未自动重试"))
    }
}
