package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ModelTurnTiming
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.ToolTiming
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class GenerationFlowCompatibilityTest {
    @Test
    fun `provider starts before local updates without adding a placeholder or phase timing`() = runBlocking {
        val model = Model()
        val history = listOf(UIMessage.user("question"))
        val updates = mutableListOf<List<UIMessage>>()
        val provider = FakeProvider {
            assertTrue(updates.isEmpty())
            emit(MessageChunk("reply", "model", listOf(UIMessageChoice(
                index = 0, delta = UIMessage.assistant("answer"), message = null, finishReason = null,
            ))))
        }
        val result = generateModelTurn(provider, ProviderSetting.OpenAI(), history, history,
            TextGenerationParams(model), model, true, true, { updates += it })
        assertEquals(1, updates.size)
        assertEquals("answer", updates.single().last().toText())
        assertTrue(result.last().modelTimings.isEmpty())
        assertEquals(1, updates.map { it.last().id }.distinct().size)
        assertEquals(history, provider.request)
    }

    @Test
    fun `cancellation before the first response adds no empty message or phase timing`() = runBlocking {
        val model = Model()
        val history = listOf(UIMessage.user("question"))
        val updates = mutableListOf<List<UIMessage>>()
        try {
            generateModelTurn(FakeProvider { throw CancellationException("stop") }, ProviderSetting.OpenAI(),
                history, history, TextGenerationParams(model), model, true, true, { updates += it })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(updates.isEmpty())
        assertEquals(1, history.size)
    }

    @Test
    fun `durable execution checkpoints do not collect phase timing`() = runBlocking {
        val call = UIMessagePart.Tool("id", "example", "{}", approvalState = ToolApprovalState.Approved)
        val checkpoints = mutableListOf<UIMessagePart.Tool>()
        val result = executeGenerationToolBatch(GenerationToolApproval(listOf(call)),
            listOf(Tool("example", "", execute = {
                assertEquals("started", checkpoints.single().executionCheckpointState)
                assertNull(checkpoints.single().timing)
                assertFalse(checkpoints.single().isExecuted)
                listOf(UIMessagePart.Text("done"))
            })), Json, { checkpoints += it }).single()
        assertNull(result.timing)
        assertEquals("completed", result.executionCheckpointState)
        assertEquals(result, checkpoints.last())
    }

    @Test
    fun `records from the earlier trial retain timing fields without collecting new ones`() {
        val old = Json.decodeFromString<UIMessagePart.Tool>("""{"toolCallId":"one","toolName":"test","input":"{}"}""")
        assertNull(old.timing)
        val timed = old.copy(timing = ToolTiming(100, 250, 400, 700, 300))
        assertEquals(timed, Json.decodeFromString<UIMessagePart.Tool>(Json.encodeToString(timed)))
        val message = UIMessage.assistant("earlier reply").copy(
            parts = listOf(timed), modelTimings = listOf(ModelTurnTiming(1000, 3700, 8900, true)),
        )
        assertEquals(message, Json.decodeFromString<UIMessage>(Json.encodeToString(message)))
    }

    private class FakeProvider(val response: suspend kotlinx.coroutines.flow.FlowCollector<MessageChunk>.() -> Unit) : Provider<ProviderSetting> {
        var request: List<UIMessage>? = null
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): MessageChunk = error("unused")
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> {
            request = messages
            return flow { response() }
        }
        override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): ImageGenerationResult = error("unused")
    }
}
