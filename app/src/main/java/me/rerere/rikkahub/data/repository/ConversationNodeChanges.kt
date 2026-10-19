package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode

internal data class ConversationNodeChanges(
    val upserts: List<Pair<Int, MessageNode>>,
    val deletions: Set<String>,
) {
    val changedIds: Set<String> get() = upserts.map { it.second.id.toString() }.toSet() + deletions
}

/** The baseline is the session's last successful commit, never an incomplete list projection. */
internal fun conversationNodeChanges(previous: Conversation, current: Conversation): ConversationNodeChanges {
    previous.requireMessageNodesLoaded()
    current.requireMessageNodesLoaded()
    require(previous.id == current.id)
    val before = previous.messageNodes.withIndex().associateBy { it.value.id }
    val currentIds = current.messageNodes.map { it.id }.toSet()
    require(currentIds.size == current.messageNodes.size) { "Duplicate message node id" }
    return ConversationNodeChanges(
        upserts = current.messageNodes.mapIndexedNotNull { index, node ->
            val old = before[node.id]
            if (old == null || old.index != index || old.value.selectIndex != node.selectIndex ||
                old.value.messages != node.messages
            ) index to node else null
        },
        deletions = (before.keys - currentIds).map { it.toString() }.toSet(),
    )
}
