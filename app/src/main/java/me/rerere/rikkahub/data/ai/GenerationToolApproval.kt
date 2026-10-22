/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal fun List<UIMessage>.unfinishedGenerationTools(): List<UIMessagePart.Tool> =
    lastOrNull()?.getTools()?.filter { !it.isExecuted }.orEmpty()

internal data class GenerationToolApproval(
    val calls: List<UIMessagePart.Tool>,
    val waitingForApproval: Boolean = false,
)

/** Apply the same approval policy to new calls and a whole batch resumed after user interaction. */
internal fun prepareGenerationToolApproval(
    calls: List<UIMessagePart.Tool>,
    definitions: List<Tool>,
    autoApproveAllTools: Boolean,
    forceConfirmToolCalls: Boolean,
): GenerationToolApproval {
    val prepared = calls.filter { !it.isExecuted }.map { call ->
        when {
            autoApproveAllTools && call.isPending -> call.copy(approvalState = ToolApprovalState.Approved)
            autoApproveAllTools -> call
            call.approvalState is ToolApprovalState.Auto &&
                (forceConfirmToolCalls || definitions.find { it.name == call.toolName }?.needsApproval == true) -> {
                call.copy(approvalState = ToolApprovalState.Pending)
            }

            else -> call
        }
    }
    return GenerationToolApproval(prepared, prepared.any { it.isPending })
}

/** No call in an approval-paused batch may run until every decision has been received. */
internal suspend fun executeGenerationToolBatch(
    approval: GenerationToolApproval,
    definitions: List<Tool>,
    json: Json,
    onCheckpoint: suspend (UIMessagePart.Tool) -> Unit = {},
): List<UIMessagePart.Tool> {
    if (approval.waitingForApproval) return emptyList()

    val results = mutableListOf<UIMessagePart.Tool>()
    for (call in approval.calls.filter { !it.isExecuted }) {
        currentCoroutineContext().ensureActive()
        val recovered = call.recoverInterruptedExecution(json)
        val result = if (recovered.isExecuted) recovered else when (val state = call.approvalState) {
            is ToolApprovalState.Denied -> call.copy(
                output = toolError(
                    json,
                    "Tool execution denied by user. Reason: ${state.reason.ifBlank { "No reason provided" }}",
                )
            ).withExecutionCheckpoint("denied")

            is ToolApprovalState.Answered -> call.copy(output = listOf(UIMessagePart.Text(state.answer)))
                .withExecutionCheckpoint("answered")
            is ToolApprovalState.Pending -> error("Tool batch still requires approval")
            else -> executeGenerationTool(call, definitions, json, onCheckpoint)
        }
        // A returned result must survive cancellation of the caller or a later tool.
        // Persistence failures propagate and stop the batch; they are never converted into tool errors.
        withContext(NonCancellable) { onCheckpoint(result) }
        results += result
        currentCoroutineContext().ensureActive()
    }
    return results
}

private suspend fun executeGenerationTool(
    call: UIMessagePart.Tool,
    definitions: List<Tool>,
    json: Json,
    onCheckpoint: suspend (UIMessagePart.Tool) -> Unit,
): UIMessagePart.Tool {
    val definition = definitions.find { it.name == call.toolName }
        ?: return call.copy(output = toolError(json, "Tool ${call.toolName} not found"))
            .withExecutionCheckpoint("unavailable")
    val cleanedInput = call.input.trim()
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val args = try {
        json.parseToJsonElement(cleanedInput.ifBlank { "{}" })
    } catch (error: Exception) {
        return call.copy(output = toolError(json, "Invalid tool arguments JSON for ${call.toolName}: ${error.message}"))
            .withExecutionCheckpoint("invalid")
    }

    val started = call.withExecutionCheckpoint("started")
    onCheckpoint(started)
    currentCoroutineContext().ensureActive()
    return try {
        val output = definition.execute(args)
        // Execution is represented by nonempty output, including a successful silent tool.
        started.copy(output = output.ifEmpty { listOf(UIMessagePart.Text("""{"status":"completed"}""")) })
            .withExecutionCheckpoint("completed")
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        started.copy(output = toolError(
            json,
            "[${error.javaClass.name}] ${error.message}\n${error.stackTraceToString()}",
        )).withExecutionCheckpoint("failed")
    }
}

private fun toolError(json: Json, message: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(json.encodeToString(buildJsonObject { put("error", message) }))
)
