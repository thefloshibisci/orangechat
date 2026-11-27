/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.service.ProactiveTask
import me.rerere.rikkahub.data.service.ProactiveTaskExecutor
import me.rerere.rikkahub.data.service.ProactiveTaskStatus
import java.time.Instant
import kotlin.uuid.Uuid

private const val PROACTIVE_PUBLISHER_TAG = "ChatService"

/**
 * Coordinates locked proactive message appends and idempotent ledger-backed publication
 * against [ConversationSession] and [ConversationRepository].
 */
internal class ChatProactivePublisher(
    private val conversationRepo: ConversationRepository,
    private val launchWithConversationReference: (Uuid, suspend () -> Unit) -> Unit,
    private val getOrCreateSession: (Uuid) -> ConversationSession,
    private val isConversationDeleted: (Uuid) -> Boolean,
    private val updateConversation: (Uuid, Conversation) -> Unit,
    private val saveConversationLocked: suspend (Uuid, Conversation) -> Unit,
) {
    fun addProactiveMessage(conversationId: Uuid, aiMessage: UIMessage) {
        launchWithConversationReference(conversationId) {
            try {
                appendProactiveAiMessageUnderLock(conversationId, aiMessage)
            } catch (e: Exception) {
                Log.e(PROACTIVE_PUBLISHER_TAG, "addProactiveMessage failed, conversationId=$conversationId", e)
            }
        }
    }

    /**
     * 在 saveMutex 保护下，把 AI 主动生成的消息追加到对话并落库。
     * 与 sendMessage/regenerate 等共享同一把锁，避免 read-modify-write 竞态导致消息被覆盖。
     */
    private suspend fun appendProactiveAiMessageUnderLock(
        conversationId: Uuid,
        aiMessage: UIMessage,
    ) {
        val session = getOrCreateSession(conversationId)

        // 等待当前正在进行的生成任务（如果有）先完全结束，再追加这条主动消息。
        // 消息现已按 UUID 写回；等待仍确保主动回复位于本轮工具续接之后。
        session.getJob()?.let { job ->
            if (job.isActive) {
                Log.i(
                    PROACTIVE_PUBLISHER_TAG,
                    "appendProactiveAiMessageUnderLock: waiting for ongoing generation to finish, conversationId=$conversationId",
                )
                job.join()
            }
        }

        session.withStateLock {
            // 优先从数据库读取完整对话，避免 session 被 idle 清除后用空对话覆盖数据库已有数据
            val currentConversation = conversationRepo.getConversationById(conversationId)
                ?: session.state.value
            val updated = currentConversation.copy(
                messageNodes = currentConversation.messageNodes + aiMessage.toMessageNode(),
                updateAt = Instant.now(),
            )
            updateConversation(conversationId, updated)
            saveConversationLocked(conversationId, updated)
        }
    }

    /** Isolated background output enters chat only at this guarded, non-suspending publication point.
     * Both deletion and normal user writes share the session lock. Never recreate a deleted window. */
    suspend fun publishProactiveMessages(
        conversationId: Uuid,
        assistantId: Uuid,
        anchorId: Uuid,
        task: ProactiveTask,
        messages: List<UIMessage>,
        executor: ProactiveTaskExecutor,
    ): Boolean {
        val session = getOrCreateSession(conversationId)
        return session.withStateLock {
            currentCoroutineContext().ensureActive()
            if (isConversationDeleted(conversationId) || session.isDeleted) return@withStateLock false
            if (!conversationRepo.existsConversationById(conversationId)) return@withStateLock false
            currentCoroutineContext().ensureActive()
            session.requireActive()
            if (isConversationDeleted(conversationId)) return@withStateLock false
            val current = session.state.value
            if (current.assistantId != assistantId ||
                current.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id != anchorId ||
                messages.isEmpty() ||
                messages.any { message ->
                    message.role != MessageRole.ASSISTANT || current.messageNodes.any { node ->
                        node.messages.any { it.id == message.id }
                    }
                }
            ) return@withStateLock false
            if (!executor.prepareCommit(task, messages)) return@withStateLock false
            val committing = executor.startCommit(task) ?: return@withStateLock false
            val payload = executor.decodeCommitPayload(committing) ?: run {
                executor.store.abandonCommit(
                    task.taskId,
                    ProactiveTaskStatus.Failed,
                    "invalid_commit_payload",
                )
                return@withStateLock false
            }
            val saved = withContext(NonCancellable) {
                conversationRepo.appendProactiveMessagesIdempotently(conversationId, assistantId, anchorId, payload)
            } ?: run {
                // A null result is a definitive guard rejection, not an ambiguous database failure.
                // Retaining CommitPending here used to lock out every subsequent proactive task.
                executor.store.abandonCommit(
                    task.taskId,
                    ProactiveTaskStatus.Interrupted,
                    "publication_guard_changed",
                )
                return@withStateLock false
            }
            // Room already committed. A ledger receipt failure must not hide the saved message or
            // cause regeneration: keep its payload for exact-ID reconciliation on the next check.
            runCatching { executor.markCommitSent(committing) }.onFailure {
                Log.w(PROACTIVE_PUBLISHER_TAG, "Proactive message committed; ledger receipt needs reconciliation", it)
            }
            updateConversation(conversationId, saved)
            session.persistedConversation = saved
            session.messageJournal.invalidate()
            true
        }
    }
}
