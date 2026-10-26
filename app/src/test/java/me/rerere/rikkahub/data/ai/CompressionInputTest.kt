package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEGACY_DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.STRUCTURED_V1_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.STRUCTURED_V2_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.STRUCTURED_V3_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.buildCompressionPrompt
import me.rerere.rikkahub.data.ai.prompts.resolveCompressPrompt
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class CompressionInputTest {
    @Test fun `tool-only responses and voice transcripts survive compression input`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("call1", "workspace_shell", "{\"command\":\"pwd\"}",
                listOf(UIMessagePart.Text("D:/project/results.txt"))),
            UIMessagePart.Reasoning("PRIVATE_REASONING"),
        ))
        val input = message.compressionTranscript()
        assertTrue(input.contains("workspace_shell"))
        assertTrue(input.contains("pwd"))
        assertTrue(input.contains("D:/project/results.txt"))
        assertFalse(input.contains("PRIVATE_REASONING"))
        val voice = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.VoiceMessage("file:///recording.ogg", transcript = "不要打包，先修 UI"),
        ))
        assertTrue(voice.compressionTranscript().contains("不要打包，先修 UI"))
    }

    @Test fun `embedded media is represented without dumping base64 into text requests`() {
        val input = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Image("data:image/png;base64,SECRET_PAYLOAD"),
            UIMessagePart.Document("file:///report.pdf", "report.pdf", "application/pdf"),
        )).compressionTranscript()
        assertFalse(input.contains("SECRET_PAYLOAD"))
        assertTrue(input.contains("report.pdf"))
    }

    @Test fun `seventy large messages including tools are sent in one lossless request`() = runBlocking {
        val texts = (1..70).map { index ->
            UIMessage.assistant("Message $index: " + "正文🍊\n".repeat(800)).let {
                it.copy(parts = it.parts + UIMessagePart.Tool("tool$index", "read", "{query:$index}",
                    listOf(UIMessagePart.Text("result$index: " + "tool data ".repeat(80)))))
            }.compressionTranscript()
        }
        val inputs = mutableListOf<String>()
        assertEquals("SUMMARY", compressTextOnce(texts) { inputs += it; "SUMMARY" })
        assertEquals(1, inputs.size)
        assertEquals(texts.joinToString("\n\n"), inputs.single())
        assertTrue(inputs.single().length > 24_000 * 12)
        assertTrue(inputs.single().contains("result70:"))
    }

    @Test fun `whole input preserves ordering whitespace and supplementary unicode`() = runBlocking {
        val texts = listOf("[USER]: first🍊\n  indented", "[ASSISTANT]: second\n", "[USER]: last")
        var calls = 0
        compressTextOnce(texts) {
            calls++
            assertEquals(texts.joinToString("\n\n"), it)
            "SUMMARY"
        }
        assertEquals(1, calls)
    }

    @Test fun `context rejection and network failures propagate without splitting or retrying`() = runBlocking {
        for (failure in listOf(HttpException("maximum context length exceeded"),
            HttpException("429 rate limit"), java.io.IOException("timeout"))) {
            var calls = 0
            val result = runCatching {
                compressTextOnce(listOf("x".repeat(300_000))) { calls++; throw failure }
            }
            assertSame(failure, result.exceptionOrNull())
            assertEquals(1, calls)
        }
    }

    @Test fun `blank input is rejected before making a provider request`() = runBlocking {
        var calls = 0
        assertTrue(runCatching { compressTextOnce(listOf(" ", "\n")) { calls++; "summary" } }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun `empty result aborts and cancellation propagates`() = runBlocking {
        assertTrue(runCatching { compressTextOnce(listOf("source")) { " " } }.isFailure)
        val cancellation = CancellationException("cancel")
        val propagated = runCatching {
            compressTextOnce(listOf("source")) { throw cancellation }
        }.exceptionOrNull()
        assertTrue(propagated is CancellationException)
        assertEquals(cancellation.message, propagated?.message)
    }

    @Test fun `summary excludes thinking and rejects tool responses and blank text`() {
        val message = UIMessage.assistant("<think>do not store this</think>\nSummary")
        assertEquals("Summary", message.compressionSummaryText())
        assertTrue(runCatching { UIMessage.assistant("<think>unfinished").compressionSummaryText() }.isFailure)
        assertTrue(runCatching { message.copy(parts = message.parts +
            UIMessagePart.Tool("call", "execute", "{}")).compressionSummaryText() }.isFailure)
    }

    @Test fun `historical defaults upgrade while user edits and current default remain exact`() {
        assertEquals(DEFAULT_COMPRESS_PROMPT, resolveCompressPrompt(LEGACY_DEFAULT_COMPRESS_PROMPT))
        assertEquals(DEFAULT_COMPRESS_PROMPT, resolveCompressPrompt(STRUCTURED_V1_COMPRESS_PROMPT))
        assertEquals(DEFAULT_COMPRESS_PROMPT, resolveCompressPrompt(STRUCTURED_V2_COMPRESS_PROMPT))
        assertEquals(DEFAULT_COMPRESS_PROMPT, resolveCompressPrompt(STRUCTURED_V3_COMPRESS_PROMPT))
        assertNotEquals(STRUCTURED_V1_COMPRESS_PROMPT, DEFAULT_COMPRESS_PROMPT)
        assertEquals(DEFAULT_COMPRESS_PROMPT, resolveCompressPrompt(DEFAULT_COMPRESS_PROMPT))
        listOf(LEGACY_DEFAULT_COMPRESS_PROMPT, STRUCTURED_V1_COMPRESS_PROMPT,
            STRUCTURED_V2_COMPRESS_PROMPT, STRUCTURED_V3_COMPRESS_PROMPT, DEFAULT_COMPRESS_PROMPT).forEach {
            val custom = it + "\nKeep my personal style"
            assertEquals(custom, resolveCompressPrompt(custom))
        }
        assertEquals(DEFAULT_COMPRESS_PROMPT, Settings().compressPrompt)
        assertEquals("Summarize {content}", resolveCompressPrompt("Summarize {content}"))
    }

    @Test fun `template expansion never reinterprets placeholders inside historical data`() {
        val prompt = buildCompressionPrompt("{content}|{target_tokens}|{locale}|{additional_context}",
            "literal {locale} and {target_tokens}", 1000, "keep {content}", "Chinese")
        assertEquals("literal {locale} and {target_tokens}|1000|Chinese|Additional instructions from user: keep {content}", prompt)
    }

    @Test fun `compression fallback uses conversation assistant even when global selection changes`() {
        val firstModel = Model(modelId = "first")
        val otherModel = Model(modelId = "other")
        val overrideModel = Model(modelId = "compression")
        val first = Assistant(chatModelId = firstModel.id)
        val other = Assistant(chatModelId = otherModel.id)
        val settings = Settings(assistantId = other.id, assistants = listOf(first, other),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(firstModel, otherModel, overrideModel))))
        assertEquals(firstModel, settings.compressionModelFor(first.id))
        assertEquals(overrideModel, settings.copy(compressModelId = overrideModel.id).compressionModelFor(first.id))
    }
}
