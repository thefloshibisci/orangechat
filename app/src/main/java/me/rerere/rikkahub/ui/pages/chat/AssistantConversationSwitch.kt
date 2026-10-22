package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.uuid.Uuid

/** A new chat reads the selected assistant from disk, so selection must be durable before navigation. */
internal suspend fun selectAssistantConversation(
    assistantId: Uuid,
    createNew: Boolean,
    persistSelection: suspend (Uuid) -> Unit,
    findConversation: suspend (Uuid) -> Uuid?,
): Uuid {
    persistSelection(assistantId)
    val conversationId = if (createNew) Uuid.random() else findConversation(assistantId) ?: Uuid.random()
    currentCoroutineContext().ensureActive()
    return conversationId
}
