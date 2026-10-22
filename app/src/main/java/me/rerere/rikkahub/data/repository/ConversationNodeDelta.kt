package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.Conversation

/**
 * An internal append/replace journal produced while holding the session state lock.
 * Unlisted nodes are trusted to be unchanged; arbitrary snapshots still require the full diff.
 */
internal class ConversationNodeDelta(
    val previous: Conversation,
    val current: Conversation,
    changes: ConversationNodeChanges,
) {
    val changes = changes.copy(upserts = changes.upserts.toList(), deletions = changes.deletions.toSet())

    init {
        previous.requireMessageNodesLoaded()
        current.requireMessageNodesLoaded()
        require(previous.id == current.id) { "A node delta cannot span conversations" }
        require(this.changes.deletions.isEmpty()) { "A node delta only supports appends and replacements" }
        require(current.messageNodes.size >= previous.messageNodes.size) { "A node delta cannot truncate history" }
        require(this.changes.upserts.map { it.first }.toSet().size == this.changes.upserts.size) {
            "Duplicate node delta index"
        }
        require(this.changes.upserts.map { it.second.id }.toSet().size == this.changes.upserts.size) {
            "Duplicate node delta id"
        }
        this.changes.upserts.forEach { (index, node) ->
            require(index in current.messageNodes.indices && current.messageNodes[index] === node) {
                "A node delta must refer to the exact current node"
            }
            require(index >= previous.messageNodes.size || previous.messageNodes[index].id == node.id) {
                "A node delta cannot reorder or replace node identities"
            }
            require(node.messages.isNotEmpty() && node.selectIndex in node.messages.indices) {
                "A node delta must preserve a valid selected message"
            }
        }
        require(this.changes.upserts.count { it.first >= previous.messageNodes.size } ==
            current.messageNodes.size - previous.messageNodes.size) {
            "Every appended node must be included in the delta"
        }
    }

    fun appliesTo(previous: Conversation, current: Conversation): Boolean =
        previous.messageNodesLoaded && current.messageNodesLoaded &&
            previous.id == this.previous.id && current.id == this.current.id &&
            previous.messageNodes === this.previous.messageNodes &&
            current.messageNodes === this.current.messageNodes
}
