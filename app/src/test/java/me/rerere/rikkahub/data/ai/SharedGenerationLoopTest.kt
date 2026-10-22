package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SharedGenerationLoopTest {
    @Test
    fun `streaming turn keeps reasoning text and tools on one local reply id`() = runBlocking {
        val provider = RecordingProvider(
            listOf(
                chunk(UIMessagePart.Reasoning("thinking")),
                chunk(UIMessagePart.Text("reply")),
                chunk(UIMessagePart.Tool("call", "example", "{\"id\":")),
                chunk(UIMessagePart.Tool("call", "example", "\"one\"}")),
            )
        )
        val model = Model()
        val history = listOf(UIMessage.user("question"))
        val updates = mutableListOf<List<UIMessage>>()
        val result = generateModelTurn(
            providerImpl = provider,
            providerSetting = ProviderSetting.OpenAI(),
            requestMessages = history,
            initialMessages = history,
            params = TextGenerationParams(model),
            model = model,
            stream = true,
            mergeUsage = true,
            onUpdateMessages = { updates += it },
        )
        assertEquals(4, updates.size)
        assertEquals(1, updates.map { it.last().id }.distinct().size)
        assertEquals(history.single(), result.first())
        assertEquals("thinking", result.last().parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("{\"id\":\"one\"}", result.last().getTools().single().input)
    }

    @Test
    fun `usage deltas merge into the current reply`() = runBlocking {
        val provider = RecordingProvider(
            listOf(
                chunk(UIMessagePart.Text("reply")).copy(usage = TokenUsage(promptTokens = 10)),
                MessageChunk("usage", "model", emptyList(), TokenUsage(completionTokens = 3)),
            )
        )
        val model = Model()
        val history = listOf(UIMessage.user("question"))
        val result = generateModelTurn(
            provider, ProviderSetting.OpenAI(), history, history, TextGenerationParams(model), model,
            stream = true, mergeUsage = true, onUpdateMessages = {},
        )
        assertEquals("reply", result.last().toText())
        assertEquals(TokenUsage(promptTokens = 10, completionTokens = 3, totalTokens = 13), result.last().usage)
    }

    @Test
    fun `shared loop resumes approved tools without regenerating them`() = runBlocking {
        val chat = UIMessage.user("question")
        val reply = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "approved",
                toolName = "example",
                input = "{}",
                approvalState = ToolApprovalState.Approved,
            )
        ))
        val final = UIMessage.assistant("done")
        var generated = 0
        val executed = mutableListOf<String>()
        val result = runGenerationToolLoop(
            initialMessages = listOf(chat, reply),
            maxSteps = 4,
            resumeApprovedTools = true,
            toolsForStep = { listOf(Tool("example", "", execute = { emptyList() })) },
            generateTurn = { messages, _, _ ->
                generated++
                messages + final
            },
            executeTool = { call, _, _ ->
                executed += call.toolCallId
                call.copy(output = listOf(UIMessagePart.Text("result")))
            },
            onToolsUpdated = { _, _ -> },
        )
        assertEquals(listOf("approved"), executed)
        assertEquals(1, generated)
        assertTrue(result[result.lastIndex - 1].getTools().single().isExecuted)
        assertEquals("done", result.last().toText())
    }

    @Test
    fun `mixed approval pauses the whole batch before execution`() = runBlocking {
        val calls = listOf(
            UIMessagePart.Tool("auto", "auto", "{}"),
            UIMessagePart.Tool("sensitive", "sensitive", "{}"),
        )
        val reply = UIMessage.assistant("").copy(parts = calls)
        val chat = UIMessage.user("question")
        val definitions = listOf(
            Tool("auto", "", execute = { emptyList() }),
            Tool("sensitive", "", needsApproval = true, execute = { emptyList() }),
        )
        var executed = false
        var updates = 0
        val result = runGenerationToolLoop(
            initialMessages = listOf(chat),
            maxSteps = 4,
            toolsForStep = { definitions },
            generateTurn = { messages, _, _ -> messages + reply },
            prepareTools = { generated, tools ->
                prepareGenerationToolApproval(generated, tools, autoApproveAllTools = false, forceConfirmToolCalls = false)
            },
            executeTool = { call, _, _ ->
                executed = true
                call
            },
            onToolsUpdated = { _, _ -> updates++ },
        )
        assertFalse(executed)
        assertEquals(1, updates)
        assertEquals(ToolApprovalState.Pending, result.last().getTools().single { it.toolCallId == "sensitive" }.approvalState)
    }

    @Test
    fun `proactive final turn cannot execute another tool`() = runBlocking {
        val chat = UIMessage.user("question")
        val toolReply = UIMessage.assistant("").copy(
            parts = listOf(UIMessagePart.Tool("over-budget", "example", "{}")),
        )
        val definitions = listOf(Tool("example", "", execute = { emptyList() }))
        var generated = 0
        try {
            runGenerationToolLoop(
                initialMessages = listOf(chat),
                maxSteps = 0,
                reserveFinalModelTurn = true,
                toolsForStep = { definitions },
                generateTurn = { messages, _, _ ->
                    generated++
                    messages + toolReply
                },
                executeTool = { call, _, _ -> call },
                onToolsUpdated = { _, _ -> },
            )
            fail("Final turn must not execute another tool")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("before a final reply"))
        }
        assertEquals(1, generated)
    }

    @Test
    fun `replace tools preserves the assistant message identity`() {
        val tool = UIMessagePart.Tool("one", "example", "{}")
        val message = UIMessage.assistant("").copy(parts = listOf(tool, UIMessagePart.Text("kept")))
        val replaced = replaceGenerationTools(
            listOf(UIMessage.user("question"), message),
            listOf(tool.copy(output = listOf(UIMessagePart.Text("result")))),
        ).last()
        assertEquals(message.id, replaced.id)
        assertEquals("kept", replaced.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertTrue(replaced.getTools().single().isExecuted)
    }

    private fun chunk(part: UIMessagePart) = MessageChunk(
        id = "chunk",
        model = "model",
        choices = listOf(UIMessageChoice(
            index = 0,
            delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)),
            message = null,
            finishReason = null,
        )),
    )

    private class RecordingProvider(private val chunks: List<MessageChunk>) : Provider<ProviderSetting> {
        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow { chunks.forEach { emit(it) } }

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = chunks.single()

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = error("unused")

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): ImageGenerationResult = error("unused")
    }
}
