package me.rerere.rikkahub.data.files

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal fun UIMessagePart.attachmentUrl(): String? = when (this) {
    is UIMessagePart.Image -> url
    is UIMessagePart.Document -> url
    is UIMessagePart.Audio -> url
    is UIMessagePart.Video -> url
    else -> null
}

/** Preserve message identity, text, tool calls and alternative branches, even if all media is removed. */
internal fun filterAttachments(
    messages: List<UIMessage>,
    keep: (UIMessagePart) -> Boolean,
): List<UIMessage> {
    fun filter(parts: List<UIMessagePart>): List<UIMessagePart> {
        var changed = false
        val result = parts.mapNotNull { part ->
            if (part.attachmentUrl() != null && !keep(part)) {
                changed = true
                null
            } else if (part is UIMessagePart.Tool) {
                val output = filter(part.output)
                if (output === part.output) part else {
                    changed = true
                    // An empty output means "not executed" to the tool runner. Keep completed tools completed.
                    part.copy(output = output.ifEmpty { listOf(UIMessagePart.Text("[附件已移除]")) })
                }
            } else part
        }
        return if (changed) result else parts
    }
    var changed = false
    val result = messages.map { message ->
        val parts = filter(message.parts)
        if (parts === message.parts) message else {
            changed = true
            message.copy(parts = parts)
        }
    }
    return if (changed) result else messages
}

internal fun attachmentUrls(messages: List<UIMessage>): Set<String> = buildSet {
    fun visit(parts: List<UIMessagePart>) {
        parts.forEach { part ->
            part.attachmentUrl()?.let { add(it) }
            if (part is UIMessagePart.Tool) visit(part.output)
        }
    }
    messages.forEach { visit(it.parts) }
}
