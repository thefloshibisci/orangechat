package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole

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
