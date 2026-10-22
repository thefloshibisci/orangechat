package me.rerere.rikkahub.service

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.rikkahub.data.ai.hasDurableToolEvidence
import me.rerere.rikkahub.data.ai.recoverToolHistoryForRequest
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationNodeChanges
import kotlin.uuid.Uuid

/** Recover selected tool history before applying the legacy incomplete-message cleanup. */
internal fun Conversation.withRecoveredToolHistory(json: Json): Conversation {
    requireMessageNodesLoaded()
    val archived = activeCompressedMessageNodeIds
    val recovered = messageNodes.filterNot { it.id in archived }.map { it.currentMessage }
        .recoverToolHistoryForRequest(json).associateBy { it.id }
    val nodes = messageNodes.mapNotNull { node ->
        if (node.id in archived) return@mapNotNull node
        val selected = recovered.getValue(node.currentMessage.id)
        val tools = selected.getTools()
        val removeSelected = tools.any { !it.isExecuted } && tools.none {
            !it.isExecuted && it.approvalState.canResumeToolExecution()
        }
        val messages = if (removeSelected) node.messages.filterNot { it.id == selected.id } else
            node.messages.map { if (it.id == selected.id) selected else it }
        if (messages.isEmpty()) return@mapNotNull null
        val index = if (removeSelected) node.selectIndex - 1 else node.selectIndex
        node.copy(messages = messages, selectIndex = index.takeIf { it in messages.indices } ?: 0)
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

/** A delayed UI emission must never undo a tool checkpoint that has already reached storage. */
internal fun Conversation.withGenerationMessages(
    messages: List<UIMessage>,
    targets: Map<Uuid, Uuid> = emptyMap(),
): Conversation = generationMessageMutation(messages, targets).conversation

/** Only append/replace operations can create this bounded persistence plan. */
internal class ConversationMessageMutation(
    val previous: Conversation,
    val conversation: Conversation,
    val changes: ConversationNodeChanges,
) {
    fun removedFileCandidates() = previous.copy(messageNodes = changes.upserts.mapNotNull {
        previous.messageNodes.getOrNull(it.first)
    }).files.toSet() - conversation.copy(messageNodes = changes.upserts.map { it.second }).files.toSet()

    /** An unchanged node or an unselected branch may still own the same nested tool-output file. */
    fun unreferencedFiles() = removedFileCandidates().let { candidates ->
        if (candidates.isEmpty()) emptyList() else {
            val retained = conversation.files.toSet()
            candidates.filterNot { it in retained }
        }
    }
}

/** Index identities once, instead of searching the complete history for every returned message. */
internal fun Conversation.generationMessageMutation(
    messages: List<UIMessage>,
    targets: Map<Uuid, Uuid> = emptyMap(),
): ConversationMessageMutation {
    requireMessageNodesLoaded()
    val nodes = messageNodes.toMutableList()
    val nodeIndices = HashMap<Uuid, Int>()
    val messageIndices = HashMap<Uuid, Pair<Int, Int>>()
    nodes.forEachIndexed { index, node ->
        nodeIndices.putIfAbsent(node.id, index)
        node.messages.forEachIndexed { alternative, message -> messageIndices.putIfAbsent(message.id, index to alternative) }
    }
    val archived = activeCompressedMessageNodeIds
    val upserts = linkedMapOf<Int, MessageNode>()
    messages.forEach { incoming ->
        val location = messageIndices[incoming.id]
        val index = location?.first ?: targets[incoming.id]?.let(nodeIndices::get)
        if (index != null && nodes[index].id in archived) return@forEach
        if (index == null) {
            val node = incoming.toMessageNode()
            val appended = nodes.size
            nodes.add(node)
            nodeIndices[node.id] = appended
            messageIndices[incoming.id] = appended to 0
            upserts[appended] = node
        } else {
            val node = nodes[index]
            val alternative = location?.second
            val saved = alternative?.let { node.messages[it] }
            val merged = incoming.preservingToolEvidence(saved)
            if (saved == merged) return@forEach
            val changed = if (alternative != null) node.copy(
                messages = node.messages.toMutableList().also { it[alternative] = merged },
            ) else node.copy(messages = node.messages + merged, selectIndex = node.messages.size)
            nodes[index] = changed
            messageIndices[incoming.id] = index to (alternative ?: node.messages.size)
            upserts[index] = changed
        }
    }
    val after = if (upserts.isEmpty()) this else copy(messageNodes = nodes)
    return ConversationMessageMutation(this, after, ConversationNodeChanges(upserts.map { it.key to it.value }, emptySet()))
}

private fun UIMessage.preservingToolEvidence(saved: UIMessage?): UIMessage {
    if (saved === this) return this
    val evidence = saved?.getTools()?.filter { it.hasDurableToolEvidence }.orEmpty().associateBy { it.toolCallId }
    if (evidence.isEmpty()) return this
    val incomingIds = getTools().map { it.toolCallId }.toSet()
    return copy(parts = parts.map { part ->
        if (part !is UIMessagePart.Tool) part else {
            val durable = evidence[part.toolCallId]
            if (durable != null && (!part.hasDurableToolEvidence || (durable.isExecuted && !part.isExecuted))) durable else part
        }
    } + evidence.values.filter { it.toolCallId !in incomingIds })
}
