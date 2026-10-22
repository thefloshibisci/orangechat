package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/** Snapshots describe the state BEFORE each compression; the current prefix supplies the final boundary. */
internal fun Conversation.compressionDividerLabels(
    displayedNodes: List<MessageNode>,
    showHistory: Boolean,
): Map<Uuid, List<String>> {
    if (!showHistory || !messageNodesLoaded || compressedSummary.isBlank()) return emptyMap()
    val count = activeCompressedMessageNodeIds.size
    if (count == 0) return emptyMap()
    val validSnapshots = compressionHistory.withIndex().filter { (_, snapshot) ->
        snapshot.compressedNodeCount in 0 until count &&
            messageNodes.getOrNull(snapshot.compressedNodeCount - 1)?.id == snapshot.lastCompressedNodeId
    }
    val numbered = compressionHistory.firstOrNull()?.compressedNodeCount == 0 &&
        validSnapshots.size == compressionHistory.size &&
        compressionHistory.zipWithNext().all { (a, b) -> a.compressedNodeCount < b.compressedNodeCount }
    val boundaries = linkedMapOf<Int, String>()
    fun label(number: Int) = if (numbered) "以上为第 ${number} 次压缩前对话" else "以上为压缩前对话"
    validSnapshots.forEach { (index, snapshot) ->
        if (snapshot.compressedNodeCount > 0) boundaries[snapshot.compressedNodeCount] = label(index)
    }
    boundaries[count] = label(compressionHistory.size)

    // Hidden system/proactive markers must not make a boundary disappear or move past newer dialogue.
    val visibleIds = displayedNodes.mapTo(hashSetOf()) { it.id }
    val labels = linkedMapOf<Uuid, MutableList<String>>()
    var lastVisible: Uuid? = null
    messageNodes.take(count).forEachIndexed { index, node ->
        if (node.id in visibleIds) lastVisible = node.id
        boundaries[index + 1]?.let { text ->
            lastVisible?.let { id -> labels.getOrPut(id) { mutableListOf() }.add(text) }
        }
    }
    return labels
}

@Composable
internal fun CompressionHistoryDivider(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.File01, contentDescription = null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text,
                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
