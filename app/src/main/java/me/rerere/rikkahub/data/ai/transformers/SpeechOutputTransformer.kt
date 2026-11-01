package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tts.model.SpeechMarkup

/** Clean displayed/history words while retaining exact per-sentence delivery for calls and replay. */
object SpeechOutputTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        messages.map(::speechTranscript)

    override suspend fun onGenerationFinish(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        messages.map(::speechTranscript)
}

internal fun speechTranscript(message: UIMessage): UIMessage {
    if (message.role != MessageRole.ASSISTANT ||
        message.parts.none { it is UIMessagePart.Text && SpeechMarkup.hasControls(it.text) }) return message
    return message.copy(parts = message.parts.map { part ->
        if (part !is UIMessagePart.Text || !SpeechMarkup.hasControls(part.text)) part
        else part.copy(
            text = SpeechMarkup.transcript(part.text),
            metadata = JsonObject(part.metadata.orEmpty() + (SpeechMarkup.SOURCE_METADATA to JsonPrimitive(part.text))),
        )
    })
}

fun UIMessage.speechText(): String = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") {
    val source = (it.metadata?.get(SpeechMarkup.SOURCE_METADATA) as? JsonPrimitive)?.content
    // Editing the visible words invalidates old delivery; never replay a stale hidden sentence.
    source?.takeIf { raw -> SpeechMarkup.transcript(raw) == it.text } ?: it.text
}
