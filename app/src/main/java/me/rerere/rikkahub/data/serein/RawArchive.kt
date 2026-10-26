package me.rerere.rikkahub.data.serein

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.EXTRA_INFO_METADATA_KEY
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/** Reserved metadata for request-only memory and synthetic messages; literal user tags are not removed. */
const val SEREIN_RECALL_METADATA_KEY = "orangechat_serein_recall"
const val SYNTHETIC_METADATA_KEY = "orangechat_synthetic"

internal fun UIMessage.rawArchiveText(): String? {
    if (role != MessageRole.USER && role != MessageRole.ASSISTANT) return null
    val texts = parts.filterIsInstance<UIMessagePart.Text>()
    fun UIMessagePart.Text.flag(key: String) = (metadata?.get(key) as? JsonPrimitive)?.booleanOrNull == true
    if (texts.any { it.flag(SYNTHETIC_METADATA_KEY) }) return null
    return texts.filterNot {
        it.flag(EXTRA_INFO_METADATA_KEY) || it.flag(SEREIN_RECALL_METADATA_KEY)
    }.joinToString("\n\n") { it.text }.takeIf { it.isNotBlank() }
}

/** Caller owns the SAME Room transaction as the changed chat nodes. No provider/network calls. */
internal class RawArchive(private val dao: RawArchiveDAO) {
    suspend fun sync(
        assistantId: String,
        conversationId: String,
        upserts: List<Pair<Int, MessageNode>>,
        removedNodeIds: Collection<String> = emptyList(),
        origin: String = "live",
    ) {
        require(origin == "live" || origin == "backfill")
        dao.removeOtherHeads(assistantId, conversationId)
        dao.removeOtherRevisions(assistantId, conversationId)
        removedNodeIds.toList().chunked(200).forEach { dao.removeHeads(assistantId, conversationId, it) }
        for ((index, node) in upserts) {
            val nodeId = node.id.toString()
            val existing = dao.heads(assistantId, conversationId, nodeId).associateBy { it.messageId }
            val heads = node.messages.mapIndexedNotNull { variantIndex, message ->
                val text = message.rawArchiveText() ?: return@mapIndexedNotNull null
                val messageId = message.id.toString()
                val previous = existing[messageId]?.let { dao.revision(assistantId, conversationId, it.revisionId) }
                    ?: dao.latest(assistantId, conversationId, messageId)
                val state = if (message.role == MessageRole.USER || message.finishedAt != null) "complete" else "pending"
                val sameSource = previous != null && previous.role == message.role.name.lowercase() && previous.createdAt == message.createdAt.toString()
                val revision = when {
                    sameSource && previous.text == text -> {
                        // A later save/backfill cannot turn a completed original into a draft.
                        if (previous.state == "pending" && state == "complete") {
                            previous.copy(state = state, finishedAt = message.finishedAt?.toString()).also { dao.updatePendingRevision(it) }
                        } else previous
                    }
                    sameSource && previous.state == "pending" -> {
                        previous.copy(text = text, state = state, finishedAt = message.finishedAt?.toString())
                            .also { dao.updatePendingRevision(it) }
                    }
                    else -> RawArchiveRevision(
                        id = Uuid.random().toString(), assistantId = assistantId, conversationId = conversationId,
                        messageId = messageId, revision = (dao.latest(assistantId, conversationId, messageId)?.revision ?: 0) + 1,
                        role = message.role.name.lowercase(), text = text, createdAt = message.createdAt.toString(),
                        finishedAt = message.finishedAt?.toString(), recordedAt = System.currentTimeMillis(), origin = origin, state = state,
                    ).also { dao.insertRevision(it) }
                }
                RawArchiveHead(assistantId, conversationId, nodeId, messageId, revision.id, index, variantIndex, variantIndex == node.selectIndex)
            }
            // Always replace the node's pointers, including when its new text is empty/tool-only.
            dao.removeHeads(assistantId, conversationId, listOf(nodeId))
            if (heads.isNotEmpty()) dao.insertHeads(heads)
        }
    }
}

internal fun rawArchiveSearchPattern(query: String): String = "%" + query.trim()
    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
