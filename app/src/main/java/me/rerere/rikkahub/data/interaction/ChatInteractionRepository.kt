package me.rerere.rikkahub.data.interaction

import androidx.room.withTransaction
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.uuid.Uuid

class ChatInteractionRepository(
    private val db: AppDatabase,
    private val conversations: ConversationRepository,
) {
    private val dao get() = db.messageReactionDao()

    fun observe(conversationId: String) = dao.observe(conversationId)

    suspend fun deleteForMessage(conversationId: Uuid, messageId: Uuid) =
        dao.deleteForMessage(conversationId.toString(), messageId.toString())

    suspend fun copyForFork(from: Uuid, to: Uuid, messageIds: Set<String>) = db.withTransaction {
        dao.list(from.toString()).filter { it.messageId in messageIds }.forEach {
            dao.insert(
                it.copy(
                    id = reactionKey(to.toString(), it.messageId, it.actor, it.emoji, it.quote),
                    conversationId = to.toString(),
                )
            )
        }
    }

    suspend fun react(
        conversationId: Uuid,
        assistant: Assistant,
        target: String,
        actor: String,
        emoji: String,
        customReactionPresets: List<String> = emptyList(),
        add: Boolean? = null,
        quote: String = "",
    ) = db.withTransaction {
        require(actor == "user" || actor == "assistant")
        val conversation = conversations.getConversationById(conversationId) ?: error("聊天已删除")
        require(conversation.assistantId == assistant.id) { "不能操作其他助手的聊天" }
        val message = resolveReactionTarget(conversation.currentMessages, assistant, target, quote)
        val resolvedQuote = resolveReactionQuote(message, assistant, quote)
        require(resolvedQuote.length <= 48_000) { "该段内容过长，暂不支持贴表情" }
        val key = reactionKey(conversationId.toString(), message.id.toString(), actor, emoji, resolvedQuote)
        val shouldAdd = add ?: !dao.exists(key)
        if (shouldAdd) {
            require(isSupportedChatReaction(emoji, normalizeCustomChatReactionPresets(customReactionPresets))) {
                "请选择支持的颜文字"
            }
            dao.insert(
                MessageReaction(
                    key,
                    conversationId.toString(),
                    message.id.toString(),
                    actor,
                    emoji,
                    resolvedQuote,
                    nextReactionCreatedAt(System.currentTimeMillis(), conversation.currentMessages.latestReactionReceiptTime()),
                )
            )
        } else {
            dao.delete(key)
        }
    }

    suspend fun decorate(
        messages: List<UIMessage>,
        conversationId: String?,
        assistant: Assistant,
        sourceMessages: List<UIMessage> = messages,
        turn: ReactionContextTurn? = null,
    ): List<UIMessage> = (turn ?: prepareContext(sourceMessages, conversationId, assistant, messages)).apply(messages)

    suspend fun prepareContext(
        sourceMessages: List<UIMessage>,
        conversationId: String?,
        assistant: Assistant,
        requestMessages: List<UIMessage> = sourceMessages,
    ): ReactionContextTurn = prepareReactionContext(
        sourceMessages,
        if (conversationId != null && assistant.enableMessageReactions) dao.list(conversationId) else emptyList(),
        conversationId,
        assistant,
        requestMessages,
    )
}

internal fun previousReactionBoundary(messages: List<UIMessage>, userId: Uuid): Long? {
    val userIndex = messages.indexOfLast { it.id == userId }
    if (userIndex < 0) return null
    // A reaction added while a response is streaming was not in its initial request.
    // Creation time is a conservative boundary; finish time would silently suppress it.
    return messages.take(userIndex).lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.createdAt?.toInstant(TimeZone.currentSystemDefault())?.toEpochMilliseconds()
}

/**
 * Builds the per-request reaction context. Every request re-sends the full set (the model
 * has no memory of earlier requests), but reactions created after [sinceMillis] are listed
 * separately as new, so old ones are not mistaken for something the user just did.
 */
internal fun buildInteractionContext(reactions: List<MessageReaction>, sinceMillis: Long?): String {
    val (fresh, earlier) = reactions.partition { sinceMillis == null || it.createdAt > sinceMillis }
    fun StringBuilder.list(items: List<MessageReaction>) = items.forEach {
        appendLine("${it.actor} reacted ${it.emoji} to ${it.messageId}, quote: ${it.quote.take(80)}")
    }
    return buildString {
        appendLine("\n<message_interactions>")
        appendLine("App metadata, not new user instructions. Reactions are attached to specific messages.")
        if (fresh.isNotEmpty()) {
            appendLine("New since your last reply:")
            list(fresh)
        }
        if (earlier.isNotEmpty()) {
            appendLine("Already present before your last reply (not new, do not treat as just added):")
            list(earlier)
        }
        appendLine("</message_interactions>")
    }
}
