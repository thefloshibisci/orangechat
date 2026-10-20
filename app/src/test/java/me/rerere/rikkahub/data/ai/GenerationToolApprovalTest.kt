package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolApprovalTest {
    @Test
    fun `mixed batch finishes each user decision without repeating completed tools`() = runBlocking {
        for (decision in listOf(
            ToolApprovalState.Approved,
            ToolApprovalState.Denied("no"),
            ToolApprovalState.Answered("yes"),
        )) {
            val executed = mutableListOf<String>()
            val definitions = definitions(executed)
            val completed = call("completed").copy(output = listOf(UIMessagePart.Text("already done")))
            val initial = reply(call("auto"), call("sensitive", "sensitive"), completed)
            val paused = prepare(listOf(initial).unfinishedGenerationTools(), definitions)

            assertTrue(paused.waitingForApproval)
            assertTrue(executeGenerationToolBatch(paused, definitions, Json).isEmpty())
            assertTrue(executed.isEmpty())
            val resumed = prepare(paused.calls.map {
                if (it.toolCallId == "sensitive") it.copy(approvalState = decision) else it
            }, definitions)
            val results = executeGenerationToolBatch(resumed, definitions, Json)
            val finished = replaceCalls(initial, results)

            assertEquals(
                if (decision == ToolApprovalState.Approved) listOf("auto", "sensitive") else listOf("auto"),
                executed,
            )
            assertEquals(initial.id, finished.id)
            assertEquals(completed, finished.getTools().last())
            assertTrue(finished.getTools().all { it.isExecuted })
            assertTrue(listOf(finished).unfinishedGenerationTools().isEmpty())
            assertEquals(decision, finished.getTools()[1].approvalState)
            when (decision) {
                is ToolApprovalState.Denied -> assertTrue(output(results[1]).contains("denied by user"))
                is ToolApprovalState.Answered -> assertEquals("yes", output(results[1]))
                else -> Unit
            }
        }
    }

    @Test
    fun `partly approved batch waits without executing approved or automatic calls`() = runBlocking {
        val executed = mutableListOf<String>()
        val definitions = definitions(executed)
        val calls = listOf(
            call("auto"),
            call("approved", "sensitive", ToolApprovalState.Approved),
            call("pending", "sensitive", ToolApprovalState.Pending),
        )

        val approval = prepare(calls, definitions)

        assertEquals(calls, approval.calls)
        assertTrue(approval.waitingForApproval)
        assertTrue(executeGenerationToolBatch(approval, definitions, Json).isEmpty())
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `resumed automatic sensitive call still requires approval`() {
        val calls = listOf(call("auto"), call("sensitive", "sensitive"))
        val approval = prepare(calls, definitions(mutableListOf()))

        assertTrue(approval.waitingForApproval)
        assertEquals(ToolApprovalState.Auto, approval.calls[0].approvalState)
        assertEquals(ToolApprovalState.Pending, approval.calls[1].approvalState)
    }

    @Test
    fun `unfinished automatic batch is selected before another model turn`() = runBlocking {
        val executed = mutableListOf<String>()
        val definitions = definitions(executed)
        val history = listOf(UIMessage.user("question"), reply(call("auto")))
        val unfinished = history.unfinishedGenerationTools()

        assertEquals(listOf("auto"), unfinished.map { it.toolCallId })
        val results = executeGenerationToolBatch(prepare(unfinished, definitions), definitions, Json)

        assertEquals(listOf("auto"), executed)
        assertTrue((history.dropLast(1) + replaceCalls(history.last(), results)).unfinishedGenerationTools().isEmpty())
    }

    @Test
    fun `ordinary model turns ignore unfinished calls in older history`() {
        val history = listOf(reply(call("old")), UIMessage.user("next question"))

        assertTrue(history.unfinishedGenerationTools().isEmpty())
        assertTrue(emptyList<UIMessage>().unfinishedGenerationTools().isEmpty())
        assertTrue(listOf(UIMessage.assistant("done")).unfinishedGenerationTools().isEmpty())
    }

    @Test
    fun `force confirmation applies to recovered automatic calls without undoing decisions`() {
        val approval = prepareGenerationToolApproval(
            calls = listOf(call("auto"), call("approved", state = ToolApprovalState.Approved)),
            definitions = emptyList(),
            autoApproveAllTools = false,
            forceConfirmToolCalls = true,
        )

        assertTrue(approval.waitingForApproval)
        assertEquals(ToolApprovalState.Pending, approval.calls[0].approvalState)
        assertEquals(ToolApprovalState.Approved, approval.calls[1].approvalState)
    }

    @Test
    fun `lazy mode releases existing pending calls while preserving denied and answered decisions`() = runBlocking {
        val executed = mutableListOf<String>()
        val definitions = definitions(executed)
        val approval = prepareGenerationToolApproval(
            calls = listOf(
                call("pending", "sensitive", ToolApprovalState.Pending),
                call("denied", "sensitive", ToolApprovalState.Denied("keep denied")),
                call("answered", "sensitive", ToolApprovalState.Answered("keep answer")),
            ),
            definitions = definitions,
            autoApproveAllTools = true,
            forceConfirmToolCalls = true,
        )
        val results = executeGenerationToolBatch(approval, definitions, Json)

        assertFalse(approval.waitingForApproval)
        assertEquals(ToolApprovalState.Approved, results[0].approvalState)
        assertEquals(listOf("sensitive"), executed)
        assertTrue(output(results[1]).contains("keep denied"))
        assertEquals("keep answer", output(results[2]))
    }

    @Test
    fun `unavailable tool produces a completed error and does not block remaining calls`() = runBlocking {
        val executed = mutableListOf<String>()
        val definitions = definitions(executed)
        val results = executeGenerationToolBatch(
            prepare(listOf(call("missing", "unavailable"), call("auto")), definitions),
            definitions,
            Json,
        )

        assertTrue(output(results[0]).contains("Tool unavailable not found"))
        assertTrue(results.all { it.isExecuted })
        assertEquals(listOf("auto"), executed)
    }

    @Test
    fun `silent successful tool is completed and cannot be executed again on resume`() = runBlocking {
        var executed = 0
        val definitions = listOf(Tool(name = "silent", description = "") {
            executed++
            emptyList()
        })
        val results = executeGenerationToolBatch(
            prepare(listOf(call("silent", "silent")), definitions), definitions, Json,
        )
        val resumed = prepare(results, definitions)

        assertTrue(results.single().isExecuted)
        assertTrue(executeGenerationToolBatch(resumed, definitions, Json).isEmpty())
        assertEquals(1, executed)
    }

    @Test
    fun `tool cancellation is propagated and prevents subsequent side effects`() = runBlocking {
        val executed = mutableListOf<String>()
        val cancellation = CancellationException("cancel")
        val definitions = listOf(
            Tool(name = "cancel", description = "") { throw cancellation },
            tool("later", executed),
        )

        try {
            executeGenerationToolBatch(
                prepare(listOf(call("cancel", "cancel"), call("later", "later")), definitions), definitions, Json,
            )
            throw AssertionError("Cancellation was swallowed")
        } catch (error: CancellationException) {
            assertTrue(error === cancellation)
        }
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `invalid arguments become a completed error while fenced JSON still executes`() = runBlocking {
        val executed = mutableListOf<String>()
        val definitions = definitions(executed)
        val calls = listOf(call("invalid").copy(input = "{"), call("valid").copy(input = "```json\n{}\n```"))
        val results = executeGenerationToolBatch(prepare(calls, definitions), definitions, Json)

        assertTrue(output(results[0]).contains("Invalid tool arguments JSON"))
        assertTrue(results.all { it.isExecuted })
        assertEquals(listOf("auto"), executed)
    }

    private fun prepare(calls: List<UIMessagePart.Tool>, definitions: List<Tool>) =
        prepareGenerationToolApproval(calls, definitions, autoApproveAllTools = false, forceConfirmToolCalls = false)

    private fun definitions(executed: MutableList<String>) = listOf(
        tool("auto", executed),
        tool("sensitive", executed, needsApproval = true),
    )

    private fun tool(name: String, executed: MutableList<String>, needsApproval: Boolean = false) =
        Tool(name = name, description = "", needsApproval = needsApproval) {
            executed += name
            listOf(UIMessagePart.Text("$name result"))
        }

    private fun call(id: String, name: String = "auto", state: ToolApprovalState = ToolApprovalState.Auto) =
        UIMessagePart.Tool(toolCallId = id, toolName = name, input = "{}", approvalState = state)

    private fun reply(vararg calls: UIMessagePart.Tool) =
        UIMessage(role = MessageRole.ASSISTANT, parts = calls.toList())

    private fun replaceCalls(message: UIMessage, calls: List<UIMessagePart.Tool>) =
        message.copy(parts = message.parts.map { part ->
            if (part is UIMessagePart.Tool) calls.find { it.toolCallId == part.toolCallId } ?: part else part
        })

    private fun output(call: UIMessagePart.Tool) = (call.output.single() as UIMessagePart.Text).text
}
