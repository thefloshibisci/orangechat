package me.rerere.rikkahub.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.workflow.execution.WorkflowActionRunner
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowStepRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowActionRunnerTest {
    private val args = JsonObject(emptyMap())

    @Test
    fun successfulStepsExposeOrderedProgress() = runBlocking {
        val progress = mutableListOf<List<WorkflowStepRun>>()
        val result = WorkflowActionRunner().run(
            listOf(WorkflowAction("first", args), WorkflowAction("second", args)),
            listOf(tool("first", "one"), tool("second", "two")),
        ) { progress += it }

        assertTrue(result.success)
        assertEquals(listOf("SUCCESS", "SUCCESS"), result.steps.map { it.status })
        assertEquals(listOf("RUNNING", "PENDING"), progress[1].map { it.status })
        assertEquals("one", result.steps[0].outputSummary)
    }

    @Test
    fun structuredFailureStopsLaterSideEffects() = runBlocking {
        var laterCalled = false
        val result = WorkflowActionRunner().run(
            listOf(WorkflowAction("first", args), WorkflowAction("second", args)),
            listOf(
                tool("first", "{\"ok\":false,\"error\":\"permission denied\"}"),
                Tool(name = "second", description = "") {
                    laterCalled = true
                    emptyList()
                },
            ),
        )

        assertFalse(result.success)
        assertFalse(laterCalled)
        assertEquals(listOf("FAILED", "SKIPPED"), result.steps.map { it.status })
        assertEquals("permission denied", result.steps[0].errorMessage)
    }

    @Test
    fun cancellationIsRethrown() = runBlocking {
        var cancelled = false
        try {
            WorkflowActionRunner().run(
                listOf(WorkflowAction("cancel", args)),
                listOf(Tool(name = "cancel", description = "") { throw CancellationException("cancel") }),
            )
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private fun tool(name: String, text: String) = Tool(name = name, description = "") {
        listOf(UIMessagePart.Text(text))
    }
}
