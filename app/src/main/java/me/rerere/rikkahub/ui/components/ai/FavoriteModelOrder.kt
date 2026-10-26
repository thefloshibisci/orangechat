package me.rerere.rikkahub.ui.components.ai

import kotlin.uuid.Uuid

internal fun moveFavoriteByKey(order: List<Uuid>, fromKey: Any, toKey: Any): List<Uuid> {
    val from = order.indexOfFirst { "favorite:$it" == fromKey }
    val to = order.indexOfFirst { "favorite:$it" == toKey }
    if (from < 0 || to < 0 || from == to) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

/** Retain concurrent additions/removals while applying the dragged order to surviving entries. */
internal fun mergeFavoriteOrder(current: List<Uuid>, dragged: List<Uuid>): List<Uuid> {
    val survivors = dragged.filter { it in current }.iterator()
    return current.map { if (it in dragged) survivors.next() else it }
}
