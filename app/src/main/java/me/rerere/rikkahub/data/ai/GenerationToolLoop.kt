package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal enum class GenerationToolUpdate { APPROVAL, RESULTS }

/**
 * Shared model -> tools -> model control flow. Entry points retain prompt selection, transforms,
 * approval policy, persistence and proactive trace behavior through callbacks.
 */
internal suspend fun runGenerationToolLoop(
    initialMessages: List<UIMessage>,
    maxSteps: Int,
    reserveFinalModelTurn: Boolean = false,
    resumeApprovedTools: Boolean = false,
    toolLimitError: String = "Tool limit reached before a final reply",
    toolsForStep: suspend (Int) -> List<Tool>,
    generateTurn: suspend (List<UIMessage>, List<Tool>, Int) -> List<UIMessage>,
    prepareTools: (List<UIMessagePart.Tool>, List<Tool>) -> GenerationToolApproval = { calls, _ ->
        GenerationToolApproval(calls)
    },
    executeTool: suspend (UIMessagePart.Tool, Tool?, Int) -> UIMessagePart.Tool?,
    onToolsUpdated: suspend (List<UIMessage>, GenerationToolUpdate) -> Unit,
    onStep: suspend (Int, Boolean) -> Unit = { _, _ -> },
): List<UIMessage> {
    var messages = initialMessages
    val steps = if (reserveFinalModelTurn) 0..maxSteps else 0 until maxSteps
    for (step in steps) {
        currentCoroutineContext().ensureActive()
        val finalTurn = reserveFinalModelTurn && step == maxSteps
        onStep(step, finalTurn)
        val definitions = toolsForStep(step)
        val resumable = if (resumeApprovedTools) {
            messages.lastOrNull()?.getTools()?.filter { it.canResumeExecution }.orEmpty()
        } else {
            emptyList()
        }

        val calls = if (resumable.isEmpty()) {
            messages = generateTurn(messages, definitions, step)
            val generated = messages.lastOrNull()?.getTools()?.filter { !it.isExecuted }.orEmpty()
            if (generated.isEmpty()) return messages
            if (finalTurn) error(toolLimitError)
            val approval = prepareTools(generated, definitions)
            if (approval.calls != generated) {
                messages = replaceGenerationTools(messages, approval.calls)
                onToolsUpdated(messages, GenerationToolUpdate.APPROVAL)
            }
            if (approval.waitingForApproval) return messages
            approval.calls
        } else {
            if (finalTurn) error(toolLimitError)
            resumable
        }

        val results = mutableListOf<UIMessagePart.Tool>()
        for (call in calls) {
            currentCoroutineContext().ensureActive()
            executeTool(call, definitions.find { it.name == call.toolName }, step)?.let(results::add)
        }
        if (results.isEmpty()) return messages
        messages = replaceGenerationTools(messages, results)
        onToolsUpdated(messages, GenerationToolUpdate.RESULTS)
    }
    return messages
}

/** Replace only matching tool parts, preserving message IDs and every non-tool part. */
internal fun replaceGenerationTools(
    messages: List<UIMessage>,
    tools: List<UIMessagePart.Tool>,
): List<UIMessage> {
    val last = messages.last()
    return messages.dropLast(1) + last.copy(parts = last.parts.map { part ->
        if (part is UIMessagePart.Tool) {
            tools.find { it.toolCallId == part.toolCallId } ?: part
        } else {
            part
        }
    })
}
