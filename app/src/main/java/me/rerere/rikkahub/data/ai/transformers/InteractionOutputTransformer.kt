package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

// These are app-generated card records, not the assistant's conversational reply.
private val interactionRecord = Regex(
    "\\[(?:AI|用户)发起(?:红包|转账|语音通话)](?:(?!\\[(?:AI|用户)发起)[\\s\\S]){0,1200}?\\[互动单号:[^\\]\\r\\n]{1,128}]",
)
private val interactionId = Regex("\\[互动单号:[A-Za-z0-9_-]{1,128}]")
private val unfinishedRecord = Regex("\\[(?:AI|用户)发起(?:红包|转账|语音通话)](?:[ \t]*[¥￥]|[ \t]*·)[\\s\\S]*$")

internal fun interactionTranscript(message: UIMessage, streaming: Boolean = false): UIMessage {
    // Keep the durable annotation and its model-facing text; ChatMessage renders that as a card.
    if (message.role != MessageRole.ASSISTANT || message.annotations.any { it is UIMessageAnnotation.ChatInteraction }) return message
    val parts = message.parts.mapNotNull { part ->
        if (part !is UIMessagePart.Text) return@mapNotNull part
        var text = interactionRecord.replace(part.text, "")
        text = interactionId.replace(text, "")
        if (streaming) text = unfinishedRecord.replace(text, "")
        if (text == part.text) part else text.trim().takeIf { it.isNotEmpty() }?.let { part.copy(text = it) }
    }
    return if (parts == message.parts) message else message.copy(parts = parts)
}

object InteractionOutputTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(ctx: TransformerContext, messages: List<UIMessage>) =
        messages.map { interactionTranscript(it, streaming = true) }

    override suspend fun onGenerationFinish(ctx: TransformerContext, messages: List<UIMessage>) =
        messages.map { interactionTranscript(it) }
}
