/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import androidx.core.net.toUri
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.interaction.ChatInteractionRepository
import me.rerere.rikkahub.data.interaction.rebaseReactionReceipts
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import kotlin.uuid.Uuid

internal class ChatMessageMutations(
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager,
    private val chatInteractionRepository: ChatInteractionRepository,
    private val getOrCreateSession: (Uuid) -> ConversationSession,
    private val getConversationFlow: (Uuid) -> StateFlow<Conversation>,
    private val preprocessUserInputParts: (List<UIMessagePart>, Assistant) -> List<UIMessagePart>,
    private val saveConversation: suspend (Uuid, Conversation) -> Unit,
    private val saveConversationLocked: suspend (Uuid, Conversation) -> Unit,
) {
    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>,
    ) {
        if (parts.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val barrier = synchronized(session) {
            check(!session.isGenerating) { "请先停止生成再编辑历史消息" }
            session.blockQueueDispatch()
        }
        try {
            val currentConversation = getConversationFlow(conversationId).value
            currentConversation.requireEditableMessage(messageId)
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantOf(currentConversation)
            val processedParts = preprocessUserInputParts(parts, assistant)
            session.withStateLock {
                val latestConversation = session.state.value
                latestConversation.requireEditableMessage(messageId)
                var edited = false

                val updatedNodes = latestConversation.messageNodes.map { node ->
                    if (!node.messages.any { it.id == messageId }) {
                        return@map node
                    }
                    edited = true

                    node.copy(
                        messages = node.messages + UIMessage(
                            role = node.role,
                            parts = processedParts,
                        ),
                        selectIndex = node.messages.size,
                    )
                }

                if (!edited) return@withStateLock

                saveConversationLocked(conversationId, latestConversation.copy(messageNodes = updatedNodes))
            }
        } finally {
            barrier.close()
        }
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid,
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val forkConversationId = Uuid.random()
        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            },
                        ).rebaseReactionReceipts(
                            conversationId.toString(),
                            forkConversationId.toString(),
                            currentConversation.assistantId.toString(),
                        )
                    },
                )
            }

        val forkState = currentConversation.compressionStateForFork(
            currentConversation.messageNodes.take(targetNodeIndex + 1),
            copiedNodes,
        )
        val forkConversation = Conversation(
            id = forkConversationId,
            assistantId = currentConversation.assistantId,
            config = currentConversation.config,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            compressionSummaries = forkState.compressionSummaries,
            compressedMessageNodeIds = forkState.compressedMessageNodeIds,
            sendCompressedSummary = currentConversation.sendCompressedSummary,
            compressionHistory = forkState.compressionHistory,
        )

        saveConversation(forkConversation.id, forkConversation)
        chatInteractionRepository.copyForFork(
            conversationId,
            forkConversation.id,
            copiedNodes.flatMap { it.messages }.mapTo(hashSetOf()) { it.id.toString() },
        )
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int,
    ) {
        val session = getOrCreateSession(conversationId)
        session.withStateLock {
            val currentConversation = session.state.value
            val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw NotFoundException("Message node not found")
            currentConversation.requireEditableMessage(targetNode.currentMessage.id)

            if (selectIndex !in targetNode.messages.indices) {
                throw BadRequestException("Invalid selectIndex")
            }

            if (targetNode.selectIndex == selectIndex) {
                return@withStateLock
            }

            val updatedNodes = currentConversation.messageNodes.map { node ->
                if (node.id == nodeId) {
                    node.copy(selectIndex = selectIndex)
                } else {
                    node
                }
            }

            saveConversationLocked(conversationId, currentConversation.copy(messageNodes = updatedNodes))
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val session = getOrCreateSession(conversationId)
        session.withStateLock {
            val currentConversation = session.state.value
            currentConversation.requireEditableMessage(messageId)
            val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

            if (updatedConversation == null) {
                if (failIfMissing) {
                    throw NotFoundException("Message not found")
                }
                return@withStateLock
            }

            saveConversationLocked(conversationId, updatedConversation)
            chatInteractionRepository.deleteForMessage(conversationId, messageId)
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri()), forceCopy = true).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Tool -> copy(output = output.map { it.copyWithForkedFileUrl() })
            else -> this
        }
    }
}

internal fun buildConversationAfterMessageDelete(
    conversation: Conversation,
    messageId: Uuid,
): Conversation? {
    val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
        node.messages.any { it.id == messageId }
    }
    if (targetNodeIndex == -1) {
        return null
    }

    val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
        if (index != targetNodeIndex) {
            return@mapIndexedNotNull node
        }

        val nextMessages = node.messages.filterNot { it.id == messageId }
        if (nextMessages.isEmpty()) {
            return@mapIndexedNotNull null
        }

        val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
        node.copy(
            messages = nextMessages,
            selectIndex = nextSelectIndex,
        )
    }

    return conversation.copy(messageNodes = updatedNodes)
}
