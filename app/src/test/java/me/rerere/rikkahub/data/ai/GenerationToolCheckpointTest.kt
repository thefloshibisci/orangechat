package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class GenerationToolCheckpointTest {
    private fun call(id: String, state: ToolApprovalState = ToolApprovalState.Auto) =
        UIMessagePart.Tool(id, id, "{}", approvalState = state)

    private fun history(vararg calls: UIMessagePart.Tool) = listOf(
        UIMessage.user("question"),
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("trace")) + calls),
    )

    private fun prepare(calls: List<UIMessagePart.Tool>, definitions: List<Tool>) =
        prepareGenerationToolApproval(calls, definitions, false, false)

    private fun output(tool: UIMessagePart.Tool) = (tool.output.single() as UIMessagePart.Text).text

    @Test
    fun `second tool cancellation preserves first result and recovery never repeats an unknown call`() = runBlocking {
        val effects = mutableListOf<String>()
        val definitions = listOf(
            Tool("first", "") { effects += "first"; listOf(UIMessagePart.Text("receipt first")) },
            Tool("second", "") { effects += "second"; throw CancellationException("interrupted after effect") },
            Tool("third", "") { effects += "third"; listOf(UIMessagePart.Text("receipt third")) },
        )
        var stored = history(call("first"), call("second"), call("third"))
        val checkpoints = mutableListOf<String>()
        try {
            executeGenerationToolBatch(prepare(stored.unfinishedGenerationTools(), definitions), definitions, Json) {
                stored = stored.replaceLastGenerationTools(listOf(it))
                checkpoints += "${it.toolCallId}:${it.executionCheckpointState}"
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // The persisted first receipt and second started marker survive this failed batch.
        }
        assertEquals(listOf("first:started", "first:completed", "second:started"), checkpoints)
        assertEquals("receipt first", output(stored.last().getTools()[0]))
        assertFalse(stored.last().getTools()[1].isExecuted)

        // Simulate process death: only the serialized persisted history exists on restart.
        val restored = Json.decodeFromString<List<UIMessage>>(Json.encodeToString(stored))
        stored = restored.recoverInterruptedToolExecutions(Json)
        val unknown = stored.last().getTools()[1]
        assertEquals("unknown", unknown.executionCheckpointState)
        assertTrue(output(unknown).contains("Verify the external state"))
        assertEquals("second", Json.parseToJsonElement(output(unknown)).jsonObject
            .getValue("tool_call_id").jsonPrimitive.content)
        executeGenerationToolBatch(prepare(stored.unfinishedGenerationTools(), definitions), definitions, Json) {
            stored = stored.replaceLastGenerationTools(listOf(it))
        }
        assertEquals(listOf("first", "second", "third"), effects)
        assertTrue(stored.last().getTools().all { it.isExecuted })
    }

    @Test
    fun `failed start checkpoint stops before every external effect`() = runBlocking {
        var effects = 0
        val definitions = listOf(Tool("first", "") { effects++; emptyList() })
        val failure = IllegalStateException("database unavailable")
        try {
            executeGenerationToolBatch(prepare(listOf(call("first")), definitions), definitions, Json) { throw failure }
            fail("Expected persistence failure")
        } catch (caught: IllegalStateException) {
            // Coroutine stacktrace recovery may copy the exception but must preserve its cause.
            assertTrue(generateSequence<Throwable>(caught) { it.cause }.any { it === failure })
        }
        assertEquals(0, effects)
    }

    @Test
    fun `failed result checkpoint stops next tool and restart keeps ambiguous result without replay`() = runBlocking {
        val effects = mutableListOf<String>()
        val definitions = listOf("first", "second").map { name ->
            Tool(name, "") { effects += name; listOf(UIMessagePart.Text("done")) }
        }
        var stored = history(call("first"), call("second"))
        val failure = IllegalStateException("disk full after external response")
        try {
            executeGenerationToolBatch(prepare(stored.unfinishedGenerationTools(), definitions), definitions, Json) {
                if (it.isExecuted) throw failure
                stored = stored.replaceLastGenerationTools(listOf(it))
            }
            fail("Expected persistence failure")
        } catch (caught: IllegalStateException) {
            assertTrue(generateSequence<Throwable>(caught) { it.cause }.any { it === failure })
        }
        assertEquals(listOf("first"), effects)
        val recovered = stored.recoverInterruptedToolExecutions(Json).last().getTools().first()
        assertEquals("unknown", recovered.executionCheckpointState)
        assertTrue(recovered.isExecuted)
        val onlyRecovered = prepare(listOf(recovered), definitions)
        assertTrue(executeGenerationToolBatch(onlyRecovered, definitions, Json).isEmpty())
        assertEquals(listOf("first"), effects)
    }

    @Test
    fun `tool ignoring cancellation still persists its success before cancellation propagates`() = runBlocking {
        val effects = mutableListOf<String>()
        val definitions = listOf(
            Tool("first", "") {
                effects += "first"
                currentCoroutineContext().cancel()
                listOf(UIMessagePart.Text("external receipt"))
            },
            Tool("second", "") { effects += "second"; emptyList() },
        )
        var stored = history(call("first"), call("second"))
        try {
            withContext(Job()) {
                executeGenerationToolBatch(prepare(stored.unfinishedGenerationTools(), definitions), definitions, Json) {
                    if (it.isExecuted) delay(1) // A real suspending write must work in the canceled job.
                    stored = stored.replaceLastGenerationTools(listOf(it))
                }
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Completed result survives; second tool never starts.
        }
        assertEquals(listOf("first"), effects)
        assertEquals("completed", stored.last().getTools().first().executionCheckpointState)
        assertEquals("external receipt", output(stored.last().getTools().first()))
        assertFalse(stored.last().getTools().last().hasDurableToolEvidence)
    }

    @Test
    fun `denied answered unavailable and invalid calls persist terminal markers without executing`() = runBlocking {
        val definitions = listOf(Tool("invalid", "") { error("Invalid input must not execute") })
        val calls = listOf(
            call("denied", ToolApprovalState.Denied("no")),
            call("answered", ToolApprovalState.Answered("answer")),
            call("unavailable"),
            call("invalid").copy(input = "{"),
        )
        val persisted = mutableListOf<UIMessagePart.Tool>()
        val results = executeGenerationToolBatch(prepare(calls, definitions), definitions, Json) { persisted += it }
        assertEquals(results, persisted)
        assertTrue(persisted.all { it.isExecuted && it.hasDurableToolEvidence })
        assertEquals(listOf("denied", "answered", "unavailable", "invalid"), persisted.map { it.executionCheckpointState })
        assertEquals("answer", output(persisted[1]))
    }

    @Test
    fun `pending interrupted marker recovers before approval and preserves original metadata and message ids`() {
        val original = call("first", ToolApprovalState.Pending).copy(metadata = buildJsonObject {
            put("provider", "untouched")
        }).withExecutionCheckpoint("started")
        val old = history(original) + UIMessage.user("new turn")
        val recovered = old.recoverInterruptedToolExecutions(Json)
        val tool = recovered[1].getTools().single()
        assertTrue(tool.isExecuted)
        assertEquals(ToolApprovalState.Pending, tool.approvalState)
        assertEquals("untouched", tool.metadata!!.getValue("provider").jsonPrimitive.content)
        assertEquals(old.map { it.id }, recovered.map { it.id })
        assertEquals(old.first(), recovered.first())
        assertEquals(old.last(), recovered.last())
        assertEquals(recovered, recovered.recoverInterruptedToolExecutions(Json))
        assertFalse(prepareGenerationToolApproval(listOf(tool), emptyList(), false, true).waitingForApproval)
    }

    @Test
    fun `recovery through execution helper returns unknown without invoking the tool`() = runBlocking {
        var effects = 0
        val definition = Tool("first", "") { effects++; emptyList() }
        val started = call("first").withExecutionCheckpoint("started")
        val persisted = mutableListOf<UIMessagePart.Tool>()
        val results = executeGenerationToolBatch(prepare(listOf(started), listOf(definition)), listOf(definition), Json) {
            persisted += it
        }
        assertEquals(0, effects)
        assertEquals("unknown", results.single().executionCheckpointState)
        assertEquals(results, persisted)
    }
}
