/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val config: ConversationConfig? = null,
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    val compressionSummaries: List<ConversationSummary> = emptyList(),
    val compressedMessageNodeIds: Set<Uuid> = emptySet(),
    val compressionRevision: Long = 0,
    val sendCompressedSummary: Boolean = true,
    val compressionHistory: List<ConversationCompressionSnapshot> = emptyList(),
    @Transient
    val newConversation: Boolean = false,
    // List projections and a failed initial load must never become replacement history.
    @Transient
    val messageNodesLoaded: Boolean = true,
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    val compressedSummary: String get() = compressionSummaries.joinToString("\n\n") { it.text }

    val hasCompressionState: Boolean
        get() = compressionSummaries.isNotEmpty() || compressedMessageNodeIds.isNotEmpty()

    // Coverage is a prefix of real nodes. Never silently hide history with no summary.
    val activeCompressedMessageNodeIds: Set<Uuid>
        get() = messageNodes.takeWhile { it.id in compressedMessageNodeIds }.map { it.id }.toSet()

    val visibleMessageNodes: List<MessageNode>
        get() {
            val archived = activeCompressedMessageNodeIds
            return if (compressedSummary.isBlank() || archived.size == messageNodes.size) messageNodes else
                messageNodes.filterNot { it.id in archived }
        }

    fun messagesForGeneration(messageRange: ClosedRange<Int>? = null): List<UIMessage> {
        requireMessageNodesLoaded()
        val archived = activeCompressedMessageNodeIds
        check(compressedMessageNodeIds.isEmpty() || compressedSummary.isNotBlank()) {
            "压缩记录缺少摘要，请先撤销压缩再继续。"
        }
        check(archived.isEmpty() || archived.size < messageNodes.size) {
            "压缩记录没有保留近期原文，请先撤销压缩再继续。"
        }
        val nodes = if (messageRange == null) messageNodes else {
            check(messageRange.start == 0 && (archived.isEmpty() || archived.size - 1 <= messageRange.endInclusive)) {
                "此处早于摘要覆盖范围，请从此消息分叉后继续。"
            }
            messageNodes.slice(messageRange.start..messageRange.endInclusive)
        }
        return nodes.filterNot { it.id in archived }.map { it.currentMessage }
    }

    fun requireMessageNodesLoaded() {
        check(messageNodesLoaded) { "消息尚未完整加载，请重新打开会话后操作。原有记录未被修改。" }
    }

    fun summaryForGeneration(): String? = if (sendCompressedSummary) {
        compressionSummaries.filter { it.sendToModel }.joinToString("\n\n") { it.text }.ifBlank { null }
    } else null

    fun compressionCandidates(keepRecentMessages: Int): List<MessageNode> {
        messagesForGeneration() // Validate damaged state before starting any provider call.
        val nodes = visibleMessageNodes
        var boundary = (nodes.size - keepRecentMessages.coerceAtLeast(1)).coerceAtLeast(0)
        // Keep the entire last user turn, including its tool calls/results.
        while (boundary > 0 && nodes.getOrNull(boundary)?.role != MessageRole.USER) boundary--
        return nodes.take(boundary)
    }

    fun withCompressionResult(
        base: Conversation,
        sources: List<MessageNode>,
        summaries: List<ConversationSummary>,
    ): Conversation {
        check(compressionRevision == base.compressionRevision &&
            compressionSummaries == base.compressionSummaries &&
            compressedMessageNodeIds == base.compressedMessageNodeIds &&
            sendCompressedSummary == base.sendCompressedSummary &&
            compressionHistory == base.compressionHistory) { "摘要已变更，请重新压缩。" }
        val prefix = base.messageNodes.takeWhile { node ->
            node.id in base.activeCompressedMessageNodeIds || sources.any { it.id == node.id }
        }
        check(messageNodes.take(prefix.size).map { it.copy(isFavorite = false) } ==
            prefix.map { it.copy(isFavorite = false) }) { "待压缩原文已变更，请重新压缩。" }
        require(summaries.isNotEmpty() && summaries.all { it.text.isNotBlank() })
        return copy(
            compressionSummaries = summaries,
            compressedMessageNodeIds = activeCompressedMessageNodeIds + sources.map { it.id },
            compressionRevision = compressionRevision + 1,
            compressionHistory = compressionHistory + ConversationCompressionSnapshot(
                summaries = compressionSummaries,
                compressedNodeCount = activeCompressedMessageNodeIds.size,
                lastCompressedNodeId = messageNodes.getOrNull(activeCompressedMessageNodeIds.size - 1)?.id,
                sendCompressedSummary = sendCompressedSummary,
            ),
            chatSuggestions = emptyList(),
        )
    }

    fun withSummaryEdits(revision: Long, summaries: List<ConversationSummary>, enabled: Boolean): Conversation {
        check(revision == compressionRevision) { "摘要已在后台更新，请关闭后重新打开。" }
        require(summaries.isNotEmpty() && summaries.all { it.text.isNotBlank() }) { "摘要不能为空，请使用撤销压缩。" }
        return copy(compressionSummaries = summaries, sendCompressedSummary = enabled,
            compressionRevision = compressionRevision + 1)
    }

    fun withoutCompression(revision: Long): Conversation {
        check(revision == compressionRevision) { "摘要已在后台更新，请关闭后重新打开。" }
        return copy(compressionSummaries = emptyList(), compressedMessageNodeIds = emptySet(),
            compressionRevision = compressionRevision + 1, sendCompressedSummary = true, compressionHistory = emptyList())
    }

    /** A missing/invalid checkpoint must never fall back to clearing all previous compression. */
    val latestCompressionSnapshot: ConversationCompressionSnapshot?
        get() = compressionHistory.lastOrNull()?.takeIf { snapshot ->
            snapshot.compressedNodeCount in 0..activeCompressedMessageNodeIds.size &&
                messageNodes.getOrNull(snapshot.compressedNodeCount - 1)?.id == snapshot.lastCompressedNodeId &&
                (snapshot.compressedNodeCount == 0 || snapshot.summaries.any { it.text.isNotBlank() })
        }

    val latestCompressionUndoRestoredCount: Int
        get() = latestCompressionSnapshot?.let {
            activeCompressedMessageNodeIds.size - it.compressedNodeCount
        } ?: 0

    fun undoLatestCompression(revision: Long): Conversation {
        check(revision == compressionRevision) { "摘要已在后台更新，请关闭后重新打开。" }
        val previous = checkNotNull(latestCompressionSnapshot) { "没有可恢复的压缩快照，无法只撤销最近一次。" }
        return copy(
            compressionSummaries = previous.summaries,
            compressedMessageNodeIds = messageNodes.take(previous.compressedNodeCount).map { it.id }.toSet(),
            sendCompressedSummary = previous.sendCompressedSummary,
            compressionHistory = compressionHistory.dropLast(1),
            compressionRevision = compressionRevision + 1,
            chatSuggestions = emptyList(),
        )
    }

    fun requireEditableMessage(messageId: Uuid) {
        check(getMessageNodeByMessageId(messageId)?.id !in activeCompressedMessageNodeIds) {
            "压缩前原文只供查看，请从此处分叉，或先撤销压缩。"
        }
    }

    fun compressionStateForFork(originals: List<MessageNode>, copies: List<MessageNode>): Conversation {
        val inherited = activeCompressedMessageNodeIds.isNotEmpty() &&
            originals.lastOrNull()?.id !in activeCompressedMessageNodeIds &&
            originals.map { it.id }.containsAll(activeCompressedMessageNodeIds)
        val idMap = originals.zip(copies).associate { (old, new) -> old.id to new.id }
        return copy(
            messageNodes = copies,
            compressionSummaries = if (inherited) compressionSummaries else emptyList(),
            compressedMessageNodeIds = if (inherited) activeCompressedMessageNodeIds.mapNotNull { idMap[it] }.toSet() else emptySet(),
            compressionRevision = 0,
            compressionHistory = if (inherited) compressionHistory.map { snapshot ->
                snapshot.copy(lastCompressedNodeId = snapshot.lastCompressedNodeId?.let { idMap[it] })
            } else emptyList(),
        )
    }

    // Identity-based updates work with either full history or a filtered request window.
    fun updateCurrentMessages(messages: List<UIMessage>, targets: Map<Uuid, Uuid> = emptyMap()): Conversation {
        requireMessageNodesLoaded()
        val nodes = messageNodes.toMutableList()
        val archived = activeCompressedMessageNodeIds
        val nodeIndexes = mutableMapOf<Uuid, Int>()
        val locations = mutableMapOf<Uuid, Pair<Int, Int>>()
        nodes.forEachIndexed { nodeIndex, node ->
            nodeIndexes.putIfAbsent(node.id, nodeIndex)
            node.messages.forEachIndexed { messageIndex, message ->
                locations.putIfAbsent(message.id, nodeIndex to messageIndex)
            }
        }
        var changed = false
        messages.forEach { message ->
            val location = locations[message.id]
            val index = location?.first ?: nodeIndexes[targets[message.id]]
            // A generation started before compression may still return its full
            // old window. Its chunks must leave newly archived nodes intact.
            if (index != null && nodes[index].id in archived) return@forEach
            if (index == null) {
                val node = message.toMessageNode()
                locations[message.id] = nodes.size to 0
                nodeIndexes[node.id] = nodes.size
                nodes.add(node)
                changed = true
            } else {
                val node = nodes[index]
                val existing = location?.second
                if (existing != null) {
                    if (node.messages[existing] != message) {
                        nodes[index] = node.copy(messages = node.messages.toMutableList().also { it[existing] = message })
                        changed = true
                    }
                } else {
                    locations[message.id] = index to node.messages.size
                    nodes[index] = node.copy(messages = node.messages + message, selectIndex = node.messages.size)
                    changed = true
                }
            }
        }
        return if (changed) copy(messageNodes = nodes) else this
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class ConversationCompressionSnapshot(
    val summaries: List<ConversationSummary>,
    // Coverage is a contiguous prefix. Store its boundary rather than copying thousands of UUIDs per round.
    val compressedNodeCount: Int,
    val lastCompressedNodeId: Uuid?,
    val sendCompressedSummary: Boolean,
)

enum class CompressionUndoMode { LATEST, ALL }

@Serializable
data class ConversationSummary(
    val id: Uuid = Uuid.random(),
    val text: String,
    val sendToModel: Boolean = true,
) {
    fun splitLines(): List<ConversationSummary> = text.lines().filter { it.isNotBlank() }.mapIndexed { index, line ->
        copy(id = if (index == 0) id else Uuid.random(), text = line.trim())
    }.ifEmpty { listOf(this) }
}

fun summaryParagraphs(text: String): List<ConversationSummary> =
    text.trim().split(Regex("\n\\s*\n")).filter { it.isNotBlank() }.map { ConversationSummary(text = it.trim()) }

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
