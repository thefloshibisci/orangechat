package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.MessageNode

/** Payment records survive regeneration, but only the selected reply's cards are displayed.
 * Switching replies restores their cards with the latest durable status. */
internal fun interactionDisplayNodes(nodes: List<MessageNode>): List<MessageNode> {
    val owners = buildMap<String, kotlin.uuid.Uuid> {
        nodes.forEach { node ->
            node.messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().forEach { tool ->
                interactionToolId(tool)?.let { id -> if (id !in this) put(id, node.id) }
            }
        }
    }
    val selectedIds = nodes.flatMap { it.currentMessage.parts }.filterIsInstance<UIMessagePart.Tool>()
        .mapNotNull(::interactionToolId).toSet()
    val anchored = linkedMapOf<kotlin.uuid.Uuid, MutableList<MessageNode>>()
    val moved = mutableSetOf<kotlin.uuid.Uuid>()
    nodes.forEach { node ->
        val card = node.currentMessage.annotations.filterIsInstance<ChatInteraction>().singleOrNull()
        if (card?.actor == "assistant") {
            owners[card.id]?.takeIf { it != node.id }?.let { owner ->
                if (card.id in selectedIds) anchored.getOrPut(owner) { mutableListOf() }.add(node)
                moved.add(node.id)
            }
        }
    }
    return nodes.filterNot { it.id in moved }.flatMap { listOf(it) + anchored[it.id].orEmpty() }
}

/** Hide only the old app-generated follow-up immediately after a user payment card. */
internal fun legacyGiftFollowupIds(messages: List<UIMessage>): Set<kotlin.uuid.Uuid> =
    messages.zipWithNext().mapNotNull { (previous, message) ->
        message.id.takeIf {
            message.role == MessageRole.USER && message.annotations.isEmpty() &&
                message.parts.size == 1 && message.parts.single() is UIMessagePart.Text &&
                message.toText().trim() == "请查看我刚发来的红包或转账卡片。" &&
                previous.annotations.filterIsInstance<ChatInteraction>().any {
                    it.actor == "user" && it.kind in setOf("red_packet", "transfer")
                }
        }
    }.toSet()

/** Resolve live card state from durable annotations, never the stale tool snapshot. */
internal fun interactionToolId(tool: UIMessagePart.Tool): String? {
    if (!tool.isExecuted || tool.toolName !in setOf("send_red_packet", "send_transfer", "request_voice_call",
        "send_virtual_red_packet", "send_virtual_transfer", "send_wallet_red_packet", "send_wallet_transfer")) return null
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
