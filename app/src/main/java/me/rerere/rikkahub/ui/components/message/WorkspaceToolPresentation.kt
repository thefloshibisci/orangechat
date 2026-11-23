package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

/** Workspace results begin with a machine label; do not concatenate it into their JSON payload. */
internal fun workspaceToolContent(output: List<UIMessagePart>): JsonElement? =
    output.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull {
        runCatching { JsonInstant.parseToJsonElement(it.text) as? JsonObject }.getOrNull()
    }

internal fun workspaceToolDiff(output: List<UIMessagePart>): String? =
    output.firstNotNullOfOrNull { (it.metadata?.get("diff") as? JsonPrimitive)?.contentOrNull }

internal fun workspaceToolMachine(output: List<UIMessagePart>): String? =
    output.firstNotNullOfOrNull { (it.metadata?.get("machine") as? JsonPrimitive)?.contentOrNull }

internal fun workspaceToolFailed(output: List<UIMessagePart>): Boolean {
    val content = workspaceToolContent(output) as? JsonObject ?: return false
    return (content["ok"] as? JsonPrimitive)?.booleanOrNull == false ||
        (content["isError"] as? JsonPrimitive)?.booleanOrNull == true ||
        (content["error"] as? JsonPrimitive)?.contentOrNull != null
}
