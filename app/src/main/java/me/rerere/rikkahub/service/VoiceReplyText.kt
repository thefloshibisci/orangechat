package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.speechText
import kotlin.uuid.Uuid

/** Same queue boundary as bot replies, retaining synthesis hints hidden from the transcript. */
internal fun queuedSpeechReplyText(messages: List<UIMessage>, userMessageId: Uuid): String {
    val index = messages.indexOfFirst { it.id == userMessageId && it.role == MessageRole.USER }
    if (index < 0) return ""
    return messages.drop(index + 1).takeWhile { it.role != MessageRole.USER }
        .filter { it.role == MessageRole.ASSISTANT }.joinToString("\n") { it.speechText() }
}
