package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.files.QueueAttachmentReferences
import kotlin.uuid.Uuid

data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val interaction: UIMessageAnnotation.AvatarInteraction? = null,
    val isEditing: Boolean = false,
    val transientSystemPrompt: String? = null,
    val enableTools: Boolean = true,
    val completion: CompletableDeferred<Unit> = CompletableDeferred(),
    val reply: CompletableDeferred<String?> = CompletableDeferred(),
)

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

class MessageQueuePausedException : IllegalStateException("发送队列已暂停，请继续或撤回待发消息")

/** Pending input remains outside chat history; dispatched attachments stay pinned until completion. */
class MessageQueue internal constructor(
    private val attachmentReferences: QueueAttachmentReferences = QueueAttachmentReferences(),
) {
    private val mutableState = MutableStateFlow(MessageQueueState())
    val state = mutableState.asStateFlow()
    private val inFlight = linkedMapOf<Uuid, QueuedMessage>()

    val hasPendingOrInFlight: Boolean get() = synchronized(this) {
        state.value.messages.isNotEmpty() || inFlight.isNotEmpty()
    }
    val inFlightMessages: List<QueuedMessage> get() = synchronized(this) { inFlight.values.toList() }

    @Synchronized
    fun enqueue(
        parts: List<UIMessagePart>,
        answer: Boolean = true,
        interaction: UIMessageAnnotation.AvatarInteraction? = null,
        transientSystemPrompt: String? = null,
        enableTools: Boolean = true,
    ): QueuedMessage? {
        if (parts.isEmptyInputMessage()) return null
        val message = QueuedMessage(
            parts = parts.toList(), answer = answer, interaction = interaction,
            transientSystemPrompt = transientSystemPrompt, enableTools = enableTools,
        )
        // Resolve a blocked caller before publishing the entry: no observer can attach under this monitor.
        // Content and completion remain available for an explicit user resume.
        if (state.value.paused) message.reply.completeExceptionally(MessageQueuePausedException())
        publish(state.value.copy(messages = state.value.messages + message))
        return message
    }

    @Synchronized
    fun takeNext(): QueuedMessage? {
        val current = state.value
        if (current.paused) return null
        val next = current.messages.firstOrNull()?.takeUnless { it.isEditing } ?: return null
        inFlight[next.id] = next
        publish(current.copy(messages = current.messages.drop(1)))
        return next
    }

    fun complete(id: Uuid, result: String? = null, error: Throwable? = null) {
        val message = synchronized(this) {
            val removed = inFlight.remove(id) ?: return
            refreshPins(state.value.messages)
            removed
        }
        if (error == null) {
            message.reply.complete(result)
            message.completion.complete(Unit)
        } else {
            message.reply.completeExceptionally(error)
            message.completion.completeExceptionally(error)
        }
    }

    fun remove(id: Uuid): QueuedMessage? {
        val removed = synchronized(this) {
            val current = state.value
            val message = current.messages.firstOrNull { it.id == id } ?: return null
            publish(current.copy(messages = current.messages.filterNot { it.id == id }))
            message
        }
        withdraw(removed)
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val current = state.value
        val message = current.messages.firstOrNull { it.id == id && !it.isEditing } ?: return null
        publish(current.copy(messages = current.messages.map {
            if (it.id == id) it.copy(isEditing = true) else it
        }))
        return message
    }

    /** Null parts cancel editing and preserve the original attachments and observers. */
    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val current = state.value
        val previous = current.messages.firstOrNull { it.id == id } ?: return null
        publish(current.copy(messages = current.messages.map {
            if (it.id == id) it.copy(parts = parts?.toList() ?: it.parts, isEditing = false) else it
        }))
        return previous.takeIf { parts != null }
    }

    fun pause() {
        val messages = synchronized(this) {
            publish(state.value.copy(paused = true))
            state.value.messages.toList()
        }
        messages.forEach { it.reply.completeExceptionally(MessageQueuePausedException()) }
    }

    @Synchronized
    fun resume() {
        publish(state.value.copy(paused = false))
    }

    fun failReplyWaiters(message: String) {
        val pending = synchronized(this) { state.value.messages.toList() }
        pending.forEach { it.reply.completeExceptionally(IllegalStateException(message)) }
    }

    /** Used by session deletion/cleanup; no queued content can be dispatched after this mutation. */
    fun clear(): List<QueuedMessage> {
        val removed = synchronized(this) {
            val messages = state.value.messages + inFlight.values
            inFlight.clear()
            attachmentReferences.release()
            mutableState.value = MessageQueueState(paused = true)
            messages
        }
        removed.forEach(::withdraw)
        return removed
    }

    private fun publish(next: MessageQueueState) {
        refreshPins(next.messages)
        mutableState.value = next
    }

    private fun refreshPins(pending: List<QueuedMessage>) {
        attachmentReferences.update((pending + inFlight.values).flatMap { it.parts })
    }

    private fun withdraw(message: QueuedMessage) {
        message.reply.complete(null)
        message.completion.cancel(CancellationException("Queued message was withdrawn"))
    }
}
