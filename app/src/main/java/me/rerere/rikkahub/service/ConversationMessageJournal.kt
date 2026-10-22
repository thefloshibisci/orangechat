package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationNodeChanges
import me.rerere.rikkahub.data.repository.ConversationNodeDelta

/** Bound to immutable node-list identities, never to a structurally equal but uncommitted snapshot. */
internal class ConversationMessageJournal {
    private var pending: ConversationNodeDelta? = null

    fun record(mutation: ConversationMessageMutation, committed: Conversation?) {
        val existing = pending
        val baseline = when {
            committed == null -> null
            existing != null && existing.previous.messageNodes === committed.messageNodes &&
                existing.current.messageNodes === mutation.previous.messageNodes -> committed
            committed.messageNodes === mutation.previous.messageNodes -> committed
            else -> null
        }
        if (baseline == null) {
            invalidate()
            return
        }
        val changed = linkedMapOf<Int, MessageNode>()
        if (existing != null && existing.previous.messageNodes === baseline.messageNodes &&
            existing.current.messageNodes === mutation.previous.messageNodes
        ) existing.changes.upserts.forEach { (index, node) -> changed[index] = node }
        mutation.changes.upserts.forEach { (index, node) -> changed[index] = node }
        pending = ConversationNodeDelta(baseline, mutation.conversation,
            ConversationNodeChanges(changed.map { it.key to it.value }, emptySet()))
    }

    fun deltaFor(committed: Conversation?, current: Conversation): ConversationNodeDelta? {
        if (committed == null) return null
        if (committed.messageNodes === current.messageNodes) return ConversationNodeDelta(
            committed, current, ConversationNodeChanges(emptyList(), emptySet()),
        )
        return pending?.takeIf { it.appliesTo(committed, current) }?.let {
            ConversationNodeDelta(committed, current, it.changes)
        }
    }

    fun invalidate() { pending = null }

    /** Call only after the database transaction succeeds; failed writes retain their journal. */
    fun committed() { pending = null }
}
