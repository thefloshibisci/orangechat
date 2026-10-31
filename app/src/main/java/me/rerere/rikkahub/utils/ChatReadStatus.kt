package me.rerere.rikkahub.utils

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.interaction.avatarInteraction
import kotlin.uuid.Uuid

/** A local reply indicator, not a provider read receipt. Use only the selected conversation branch. */
internal fun repliedUserMessageIds(messages: List<UIMessage>): Set<Uuid> {
    val replied = mutableSetOf<Uuid>()
    val pending = mutableSetOf<Uuid>()
    for (message in messages) {
        if (message.avatarInteraction() != null) continue
        if (message.role == MessageRole.USER) {
            if (message.toText().contains("[主动消息上下文]")) {
                // A scheduled message is not a response to pending user messages.
                pending.clear()
            } else {
                pending.add(message.id)
            }
        } else if (message.role == MessageRole.ASSISTANT && message.toText().trim() != "[SKIP]" &&
            message.parts.any { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text.isNotBlank()
                    is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
                    is UIMessagePart.Tool -> part.toolName.isNotBlank()
                    is UIMessagePart.Image -> part.url.isNotBlank()
                    is UIMessagePart.Video -> part.url.isNotBlank()
                    is UIMessagePart.Audio -> part.url.isNotBlank()
                    is UIMessagePart.VoiceMessage -> part.url.isNotBlank()
                    is UIMessagePart.Document -> part.url.isNotBlank()
                    else -> false
                }
            }
        ) {
            replied.addAll(pending)
            pending.clear()
        }
    }
    return replied
}
