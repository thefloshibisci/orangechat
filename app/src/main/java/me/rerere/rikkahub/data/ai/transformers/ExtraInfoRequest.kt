package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SystemToolsSetting
import kotlin.uuid.Uuid

/** Bounded, memory-only snapshots. Retries and tool-approval continuations reuse the same observation. */
internal class ExtraInfoRequestCache(private val capacity: Int = 32) {
    private data class Key(
        val assistantId: Uuid,
        val userId: Uuid,
        val query: String,
        val option: SystemToolsSetting,
    )
    private data class Entry(val key: Key, val content: String?)
    private val entries = linkedMapOf<Uuid, Entry>()

    suspend fun resolve(
        conversationId: Uuid,
        assistantId: Uuid,
        user: UIMessage,
        option: SystemToolsSetting,
        allowCollection: Boolean,
        collect: suspend () -> String?,
    ): String? {
        if (!option.extraInfoInjectionEnabled) return null
        val key = Key(assistantId, user.id, user.toText(), option)
        synchronized(entries) { entries[conversationId]?.takeIf { it.key == key } }?.let { return it.content }
        // A resumed tool chain without a matching snapshot must not observe an unrelated screen.
        if (!allowCollection) return null
        val content = collect()
        currentCoroutineContext().ensureActive()
        synchronized(entries) {
            entries.remove(conversationId)
            entries[conversationId] = Entry(key, content)
            while (entries.size > capacity.coerceAtLeast(1)) entries.remove(entries.keys.first())
        }
        return content
    }
}

internal class ExtraInfoRequestTransformer(
    private val userId: Uuid?,
    private val content: String?,
) : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        attachExtraInfoToRequest(messages, userId, content)
}

internal fun attachExtraInfoToRequest(
    messages: List<UIMessage>,
    userId: Uuid?,
    content: String?,
): List<UIMessage> {
    if (userId == null || content.isNullOrBlank()) return messages
    return messages.map { message ->
        if (message.id == userId && message.role == MessageRole.USER) {
            message.copy(parts = message.parts.filterNot { it.isExtraInfoInjectionPart() } + extraInfoMessagePart(content))
        } else message
    }
}
