package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/** Limit reply observers to the requested user turn, even when the next queue item starts instantly. */
internal fun queuedReplyText(messages: List<UIMessage>, userMessageId: Uuid): String {
    val index = messages.indexOfFirst { it.id == userMessageId && it.role == MessageRole.USER }
    if (index < 0) return ""
    return messages.drop(index + 1).takeWhile { it.role != MessageRole.USER }
        .filter { it.role == MessageRole.ASSISTANT }.joinToString("\n") { it.toText() }
}

/** Await this queue entry, rather than the conversation's last assistant or a shared completion event. */
internal suspend fun ConversationSession.awaitQueuedBotReply(
    timeoutMillis: Long,
    initialize: suspend () -> Unit,
    submit: () -> QueuedMessage?,
    cancelRequest: (Uuid) -> Unit,
): String = withRefSuspend {
    withTimeoutOrNull(timeoutMillis) {
        initialize()
        state.value.requireMessageNodesLoaded()
        val entry = submit() ?: return@withTimeoutOrNull "（消息为空）"
        try {
            val reply = entry.reply.await()?.trim()
            reply?.takeIf { it.isNotBlank() }
                ?: if (reply == null) "（本次消息已撤回或中断）"
                else "（无回复，请在应用中查看错误或待批准的工具）"
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            "（本次回复已中断，请重试）"
        } catch (_: MessageQueuePausedException) {
            "（发送队列已暂停，请先在应用中继续）"
        } catch (_: Exception) {
            "（本次回复失败，请在应用中查看错误）"
        } finally {
            if (!entry.reply.isCompleted) cancelRequest(entry.id)
        }
    } ?: "（思考超时，请稍后再试）"
}

/** Await the exact send job, including instant completion, and release the session on every exit. */
internal suspend fun ConversationSession.awaitBotReply(
    timeoutMillis: Long,
    initialize: suspend () -> Unit,
    send: () -> Job?,
): String = withRefSuspend {
    withTimeoutOrNull(timeoutMillis) {
        initialize()
        state.value.requireMessageNodesLoaded()
        val previousReply = state.value.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
        val job = send() ?: return@withTimeoutOrNull "（消息为空）"
        try {
            job.join()
            if (job.isCancelled) return@withTimeoutOrNull "（本次回复已中断，请重试）"
            val reply = state.value.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                ?.takeIf { it.id != previousReply }?.toText()?.trim()
            reply?.takeIf { it.isNotBlank() } ?: "（无回复，请在应用中查看错误或待批准的工具）"
        } finally {
            if (!job.isCompleted) job.cancel()
        }
    } ?: "（思考超时，请稍后再试）"
}
