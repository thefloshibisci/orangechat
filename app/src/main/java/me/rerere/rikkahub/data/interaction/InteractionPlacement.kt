package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Resolve live card state from durable annotations, never the stale tool snapshot. */
internal fun interactionToolId(tool: UIMessagePart.Tool): String? {
    if (!tool.isExecuted || tool.toolName !in setOf("send_red_packet", "send_transfer", "request_voice_call")) return null
    return tool.output.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull {
        runCatching { Json.parseToJsonElement(it.text).jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }
}

internal fun inlineInteractionCards(messages: List<UIMessage>): Map<String, ChatInteraction> {
    val cards = messages.flatMap { it.annotations }.filterIsInstance<ChatInteraction>()
        .filter { it.actor == "assistant" }.associateBy { it.id }
    return messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
        .mapNotNull { interactionToolId(it) }.distinct().mapNotNull { id -> cards[id]?.let { id to it } }.toMap()
}
