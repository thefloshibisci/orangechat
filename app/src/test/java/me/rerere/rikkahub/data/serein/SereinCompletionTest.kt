package me.rerere.rikkahub.data.serein

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.*
import org.junit.Assert.*
import org.junit.Test

class SereinCompletionTest {
    private val at = LocalDateTime.parse("2026-11-06T20:00:00")
    private val tool = UIMessagePart.Tool("call", "synthetic", "{}")
    private val result = tool.copy(output = listOf(UIMessagePart.Text("PRIVATE TOOL OUTPUT")))
    private fun reply(call: UIMessagePart.Tool = result) = UIMessage.assistant("").copy(
        createdAt = at, finishedAt = at, parts = listOf(call, UIMessagePart.Text("final answer")),
    )

    @Test fun completedToolReplyHasTextAndSuccessWithoutExportingToolOutput() {
        val final = reply().withGenerationCompletion()
        assertTrue(final.isSereinCompletedReply())
        assertEquals("final answer", final.uploadText())
        assertFalse(final.uploadText()!!.contains("PRIVATE TOOL OUTPUT"))
        assertNull(reply().uploadText()) // Merely finishing the tool is not finishing the model turn.
        assertFalse(reply().isSereinCompletedReply())
    }

    @Test fun pendingPartialToolOnlyAndChangedRepliesCannotReuseCompletion() {
        val complete = reply().withGenerationCompletion()
        assertFalse(complete.copy(finishedAt = null).isSereinCompletedReply())
        assertFalse(reply(tool.copy(approvalState = ToolApprovalState.Pending)).withGenerationCompletion().isSereinCompletedReply())
        assertFalse(reply().copy(parts = listOf(result)).withGenerationCompletion().isSereinCompletedReply())
        assertFalse(complete.withoutGenerationCompletion().isSereinCompletedReply())
        val edited = complete.copy(parts = complete.parts.map {
            if (it is UIMessagePart.Text) it.copy(text = "new partial") else it
        })
        assertFalse(edited.isSereinCompletedReply())
        assertNull(edited.uploadText())
        assertFalse(UIMessage.assistant("[SKIP]").copy(finishedAt = at).isSereinCompletedReply())
    }

    @Test fun toolLoopOnlyPublishesCompletionAfterFinalModelTurn() = runBlocking {
        var acknowledged = 0
        val final = runGenerationToolLoop(
            initialMessages = listOf(UIMessage.user("question")), maxSteps = 3,
            toolsForStep = { emptyList() },
            generateTurn = { messages, _, step -> if (step == 0) messages + reply(tool) else messages },
            executeTool = { call, _, _ -> call.copy(output = result.output) },
            onToolsUpdated = { messages, _ -> assertNull(messages.last().uploadText()) },
            onCompleted = { messages ->
                acknowledged++
                messages.dropLast(1) + messages.last().withGenerationCompletion()
            },
        )
        assertEquals(1, acknowledged)
        assertTrue(final.last().isSereinCompletedReply())
    }

    @Test fun exhaustedAndCancelledToolLoopsNeverConfirmDelivery() = runBlocking {
        for (cancel in listOf(false, true)) {
            var acknowledged = false
            try {
                runGenerationToolLoop(
                    initialMessages = listOf(UIMessage.user("question")), maxSteps = 1,
                    toolsForStep = { emptyList() }, generateTurn = { messages, _, _ -> messages + reply(tool) },
                    executeTool = { call, _, _ -> if (cancel) throw CancellationException("synthetic") else call.copy(output = result.output) },
                    onToolsUpdated = { messages, _ -> assertNull(messages.last().uploadText()) },
                    onCompleted = { acknowledged = true; it },
                )
            } catch (_: CancellationException) { assertTrue(cancel) }
            assertFalse(acknowledged)
        }
    }

    @Test fun failedContinuationCanRetryWithoutExecutingTheCompletedToolAgain() = runBlocking {
        var completedTurns = 0
        var toolExecutions = 0
        val checkpoint = listOf(UIMessage.user("question"), reply())
        for (failTurn in listOf(true, false)) {
            try {
                val final = runGenerationToolLoop(
                    initialMessages = checkpoint, maxSteps = 2, resumeApprovedTools = true,
                    toolsForStep = { emptyList() },
                    generateTurn = { messages, _, _ ->
                        if (failTurn) throw IllegalStateException("synthetic stream failure")
                        messages
                    },
                    executeTool = { call, _, _ -> toolExecutions++; call },
                    onToolsUpdated = { _, _ -> fail("Already executed tools must not run again") },
                    onCompleted = { messages ->
                        completedTurns++
                        messages.dropLast(1) + messages.last().withGenerationCompletion()
                    },
                )
                assertFalse(failTurn)
                assertTrue(final.last().isSereinCompletedReply())
            } catch (error: IllegalStateException) {
                assertTrue(failTurn)
                assertEquals("synthetic stream failure", error.message)
                assertEquals(0, completedTurns)
                assertNull(checkpoint.last().uploadText())
            }
        }
        assertEquals(0, toolExecutions)
        assertEquals(1, completedTurns)
    }
}
