package me.rerere.rikkahub.workflow.execution

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowActionErrorPolicy

/** Executes workflow actions sequentially with variables, retries and failure policies. */
class WorkflowActionRunner {

    data class RunResult(val success: Boolean, val error: String?, val summary: String)

    suspend fun run(actions: List<WorkflowAction>, availableTools: List<Tool>): RunResult {
        val outputs = mutableListOf<String>()
        val variables = mutableMapOf<String, String>()
        for ((idx, action) in actions.withIndex()) {
            val resolvedArgs = resolveVariables(action.args, variables) as JsonObject
            val argsJson = resolvedArgs.toString()
            val hardlineReason = HardlineCommandGuard.checkTool(action.tool, argsJson)
            if (hardlineReason != null) {
                logSafe("workflow hardline-blocked action $idx tool=${action.tool}: $hardlineReason")
                return RunResult(false, "action $idx: hardline:$hardlineReason", outputs.joinToString("\n"))
            }
            val tool = availableTools.find { it.name == action.tool }
                ?: return RunResult(false, "action $idx: unknown_tool:${action.tool}", outputs.joinToString("\n"))
            var out: List<me.rerere.ai.ui.UIMessagePart>? = null
            var lastError: String? = null
            for (attempt in 0..action.retryCount) {
                try {
                    out = withTimeoutOrNull(action.timeoutSeconds * 1000L) {
                        tool.execute(resolvedArgs)
                    }
                    lastError = if (out == null) "${action.tool} exceeded ${action.timeoutSeconds}s" else null
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    lastError = "${t::class.simpleName}: ${t.message.orEmpty()}".take(500)
                    logSafe("workflow action $idx attempt $attempt tool=${action.tool} threw: ${t.message}")
                }
                if (out != null) break
            }
            val completedOut = out
            if (completedOut == null) {
                outputs += "[$idx] ${action.tool}: FAILED ${lastError.orEmpty()}"
                if (action.onError == WorkflowActionErrorPolicy.CONTINUE) continue
                return RunResult(false, "action $idx: ${lastError.orEmpty()}", outputs.joinToString("\n"))
            }
            val text = completedOut.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            outputs += "[$idx] ${action.tool}: ${text.take(200)}"
            action.outputVariable?.takeIf { it.isNotBlank() }?.let { variables[it] = text }
        }
        return RunResult(true, null, outputs.joinToString("\n").take(2000))
    }

    internal fun resolveVariables(element: JsonElement, variables: Map<String, String>): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> resolveVariables(value, variables) })
        is JsonArray -> JsonArray(element.map { resolveVariables(it, variables) })
        is JsonPrimitive -> if (!element.isString) element else {
            VARIABLE_PATTERN.replace(element.contentOrNull.orEmpty()) { match ->
                variables[match.groupValues[1]] ?: match.value
            }.let(::JsonPrimitive)
        }
        else -> element
    }

    private fun logSafe(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private companion object {
        const val TAG = "WorkflowActionRunner"
        val VARIABLE_PATTERN = Regex("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}")
    }
}
