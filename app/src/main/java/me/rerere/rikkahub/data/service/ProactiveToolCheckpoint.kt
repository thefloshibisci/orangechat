package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.executionCheckpointState

/** Persist tool evidence without the hidden wake-up USER prompt, reasoning, notes or decision text. */
internal fun proactiveToolCheckpointMessage(message: UIMessage): UIMessage {
    require(message.role == MessageRole.ASSISTANT)
    return message.copy(parts = message.parts.filterIsInstance<UIMessagePart.Tool>())
}

/** After the user returns, only fill results for already-started calls in the same owned message. */
internal fun mergeProactiveToolCompletion(existing: UIMessage, incoming: UIMessage): UIMessage? {
    if (existing.id != incoming.id || existing.role != MessageRole.ASSISTANT || incoming.role != MessageRole.ASSISTANT) {
        return null
    }
    val completed = incoming.getTools().filter {
        it.isExecuted && it.executionCheckpointState in setOf("completed", "failed", "unknown")
    }.associateBy { it.toolCallId }
    var changed = false
    val parts = existing.parts.map { part ->
        if (part is UIMessagePart.Tool && !part.isExecuted && part.executionCheckpointState == "started") {
            completed[part.toolCallId]?.takeIf { it.toolName == part.toolName && it.input == part.input }?.also {
                changed = true
            } ?: part
        } else part
    }
    return if (changed) existing.copy(parts = parts) else null
}
