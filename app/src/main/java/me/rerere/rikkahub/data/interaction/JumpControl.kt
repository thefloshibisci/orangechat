package me.rerere.rikkahub.data.interaction

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private val jumpControl = Regex("\\[JUMP]", RegexOption.IGNORE_CASE)

/** Hide control syntax without consuming the raw navigation instruction. */
internal fun UIMessage.withoutJumpControl(): UIMessage {
    if (role != MessageRole.ASSISTANT) return this
    val clean = parts.mapNotNull { part ->
        if (part !is UIMessagePart.Text || !jumpControl.containsMatchIn(part.text)) part
        else jumpControl.replace(part.text, "").trim().takeIf { it.isNotBlank() }?.let { part.copy(text = it) }
    }
    return if (clean == parts) this else copy(parts = clean)
}
