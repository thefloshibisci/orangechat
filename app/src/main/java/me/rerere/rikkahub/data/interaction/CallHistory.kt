package me.rerere.rikkahub.data.interaction

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.CallTranscriptEntry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tts.model.SpeechMarkup

internal enum class CallCardDestination { Incoming, Active, History }

internal fun ChatInteraction.callDestination(activeConversationId: String?, activeStartedAt: Long): CallCardDestination = when {
    status == "pending" && actor == "assistant" -> CallCardDestination.Incoming
    conversationId == activeConversationId &&
        ((status == "connecting" && activeStartedAt == 0L) ||
            (status == "accepted" && activeStartedAt > 0 && connectedAtMillis == activeStartedAt)) -> CallCardDestination.Active
    else -> CallCardDestination.History
}

/** An asynchronous hang-up must never finish a later call in the same conversation. */
internal fun ChatInteraction.belongsToEndingCall(startedAt: Long?, connectingIds: Set<String>): Boolean =
    status in setOf("connecting", "accepted") && when {
        startedAt == null -> true
        startedAt > 0 -> connectedAtMillis == startedAt
        else -> status == "connecting" && id in connectingIds
    }

/** Older calls have no dedicated transcript: recover only actual words inside this call's dates. */
internal fun ChatInteraction.transcriptFrom(messages: List<UIMessage>, userName: String, assistantName: String): List<CallTranscriptEntry> {
    callTranscript?.let { return it }
    val start = connectedAtMillis ?: return emptyList()
    val end = endedAtMillis ?: return emptyList()
    return messages.mapNotNull { message ->
        val time = message.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        if (time !in start..end || message.role !in setOf(MessageRole.USER, MessageRole.ASSISTANT) ||
            message.annotations.any { it is ChatInteraction }) return@mapNotNull null
        val words = message.parts.mapNotNull {
            when (it) {
                is UIMessagePart.Text -> SpeechMarkup.transcript(it.text)
                is UIMessagePart.VoiceMessage -> it.transcript
                else -> null
            }
        }.joinToString("\n").trim()
        if (words.isBlank()) null else CallTranscriptEntry(
            if (message.role == MessageRole.USER) "user" else "assistant",
            if (message.role == MessageRole.USER) userName else assistantName, words, time)
    }
}
