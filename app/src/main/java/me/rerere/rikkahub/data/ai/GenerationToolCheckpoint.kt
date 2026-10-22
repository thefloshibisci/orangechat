package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.canResumeToolExecution

private const val TOOL_EXECUTION_CHECKPOINT = "orange_tool_execution"

internal val UIMessagePart.Tool.executionCheckpointState: String?
    get() = ((metadata?.get(TOOL_EXECUTION_CHECKPOINT) as? JsonObject)
        ?.get("state") as? JsonPrimitive)?.content

internal val UIMessagePart.Tool.hasDurableToolEvidence: Boolean
    get() = metadata?.containsKey(TOOL_EXECUTION_CHECKPOINT) == true

internal fun UIMessagePart.Tool.withExecutionCheckpoint(state: String): UIMessagePart.Tool = copy(
    metadata = buildJsonObject {
        metadata?.forEach { (key, value) -> put(key, value) }
        put(TOOL_EXECUTION_CHECKPOINT, buildJsonObject {
            put("state", state)
            put("toolCallId", toolCallId)
        })
    },
)

/** A started call may have performed its external effect. Recovery must never automatically replay it. */
internal fun UIMessagePart.Tool.recoverInterruptedExecution(json: Json): UIMessagePart.Tool {
    if (isExecuted || !hasDurableToolEvidence) return this
    return copy(output = listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
        put("error", "Tool execution was interrupted; its result is unknown. Verify the external state before retrying.")
        put("execution_state", "unknown")
        put("tool_call_id", toolCallId)
        put("retryable", false)
    })))).withExecutionCheckpoint("unknown")
}

internal fun List<UIMessage>.recoverInterruptedToolExecutions(json: Json): List<UIMessage> = map { message ->
    val parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool) part.recoverInterruptedExecution(json) else part
    }
    if (parts == message.parts) message else message.copy(parts = parts)
}

/** Close interrupted history before legacy invalid-message filters can discard saved receipts. */
internal fun List<UIMessage>.recoverToolHistoryForRequest(
    json: Json,
    allowLastMessageResume: Boolean = true,
): List<UIMessage> = mapIndexed { index, message ->
    val tools = message.getTools()
    // Preserve the existing treatment of entirely unstarted legacy batches.
    if (tools.none { it.isExecuted || it.hasDurableToolEvidence }) return@mapIndexed message
    val recovered = tools.map { it.recoverInterruptedExecution(json) }
    val resumeActiveBatch = allowLastMessageResume && index == lastIndex && recovered.any {
        !it.isExecuted && it.approvalState.canResumeToolExecution()
    }
    val finished = if (resumeActiveBatch) recovered else recovered.map { call ->
        if (call.isExecuted) call else when (val approval = call.approvalState) {
            is ToolApprovalState.Answered -> call.copy(output = listOf(UIMessagePart.Text(approval.answer)))
                .withExecutionCheckpoint("answered")
            is ToolApprovalState.Denied -> call.copy(output = listOf(UIMessagePart.Text(json.encodeToString(
                buildJsonObject {
                    put("error", "Tool execution denied by user. Reason: ${approval.reason.ifBlank { "No reason provided" }}")
                },
            )))).withExecutionCheckpoint("denied")
            else -> call.copy(output = listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                put("status", "cancelled")
                put("error", "The interrupted tool batch ended before this call started; no action was executed.")
                put("tool_call_id", call.toolCallId)
            })))).withExecutionCheckpoint("cancelled")
        }
    }
    if (finished == tools) message else {
        val byId = finished.associateBy { it.toolCallId }
        message.copy(parts = message.parts.map { part ->
            if (part is UIMessagePart.Tool) byId[part.toolCallId] ?: part else part
        })
    }
}

internal fun List<UIMessage>.replaceLastGenerationTools(calls: List<UIMessagePart.Tool>): List<UIMessage> {
    val message = lastOrNull() ?: return this
    val byId = calls.associateBy { it.toolCallId }
    return dropLast(1) + message.copy(parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool) byId[part.toolCallId] ?: part else part
    })
}
