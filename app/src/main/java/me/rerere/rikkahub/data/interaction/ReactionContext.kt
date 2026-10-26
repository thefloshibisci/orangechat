package me.rerere.rikkahub.data.interaction

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.executionCheckpointState
import me.rerere.rikkahub.data.model.Assistant
import java.util.concurrent.atomic.AtomicLong

private const val RECEIPT_KEY = "orange_reaction_receipt_v1"
private const val CONTEXT_KEY = "orange_reaction_context_v1"
private const val PENDING_KEY = "orange_reaction_pending_v1"
private const val MAX_TURN_REACTIONS = 24
private const val MAX_STORED_RECEIPTS = 4096
private val receiptJson = Json { ignoreUnknownKeys = true }
private val reactionClock = AtomicLong()
private val receiptEventPattern = Regex("[a-f0-9]{64}:[0-9]{1,19}")
private val jumpPattern = Regex("\\[JUMP]", RegexOption.IGNORE_CASE)
private val hiddenDecisionPattern = Regex("\\[(?:SKIP|PASS|STOP|WAIT(?::[^]]*)?)]", RegexOption.IGNORE_CASE)

@Serializable
private data class StoredReactionReceipt(
    val conversationId: String,
    val assistantId: String,
    val replyId: String,
    val events: Set<String>,
    val legacyFloor: Long? = null,
)

/** Copied branches retain event identity; the receipt itself still has an explicit owner scope. */
internal fun reactionReceipt(reaction: MessageReaction): String =
    reactionKey("", reaction.messageId, reaction.actor, reaction.emoji, reaction.quote) + ":${reaction.createdAt}"

/** A remove/add in the same millisecond (or after the wall clock moves backwards) is a new event. */
internal fun nextReactionCreatedAt(now: Long, knownReceiptTime: Long = 0): Long =
    reactionClock.updateAndGet { previous -> maxOf(now, previous + 1, knownReceiptTime + 1) }

private fun UIMessage.storedReceipt(): StoredReactionReceipt? = parts.asSequence().mapNotNull { part ->
    part.metadata?.get(RECEIPT_KEY)?.let { value ->
        runCatching { receiptJson.decodeFromJsonElement<StoredReactionReceipt>(value) }.getOrNull()
    }
}.firstOrNull { receipt ->
    receipt.replyId == id.toString() && receipt.events.size <= MAX_STORED_RECEIPTS &&
        receipt.events.all { it.matches(receiptEventPattern) }
}

internal fun UIMessage.reactionReceipts(conversationId: String, assistantId: String): Set<String> =
    storedReceipt()?.takeIf { it.conversationId == conversationId && it.assistantId == assistantId }
        ?.events.orEmpty()

internal fun List<UIMessage>.latestReactionReceiptTime(): Long = asSequence()
    .flatMap { it.storedReceipt()?.events.orEmpty().asSequence() }
    .mapNotNull { it.substringAfterLast(':').toLongOrNull() }.maxOrNull() ?: 0

private fun UIMessagePart.withReceiptMetadata(value: JsonObject?): UIMessagePart = when (this) {
    is UIMessagePart.Text -> copy(metadata = value)
    is UIMessagePart.Image -> copy(metadata = value)
    is UIMessagePart.Video -> copy(metadata = value)
    is UIMessagePart.Audio -> copy(metadata = value)
    is UIMessagePart.VoiceMessage -> copy(metadata = value)
    is UIMessagePart.Document -> copy(metadata = value)
    is UIMessagePart.Reasoning -> copy(metadata = value)
    is UIMessagePart.Tool -> copy(metadata = value)
    is UIMessagePart.ToolCall -> copy(metadata = value)
    is UIMessagePart.ToolResult -> copy(metadata = value)
    else -> this
}

private fun UIMessage.withoutPendingReactionMetadata(): UIMessage =
    if (parts.none { it.metadata?.containsKey(PENDING_KEY) == true }) this else copy(parts = parts.map { part ->
        if (part.metadata?.containsKey(PENDING_KEY) != true) part else {
            part.withReceiptMetadata(JsonObject(part.metadata!!.filterKeys { it != PENDING_KEY }).takeIf { it.isNotEmpty() })
        }
    })

private fun UIMessage.hasPendingReactionMetadata(): Boolean =
    parts.any { it.metadata?.get(PENDING_KEY) == JsonPrimitive(true) }

internal fun UIMessage.withoutReactionReceiptMetadata(): UIMessage =
    if (parts.none { part -> part.metadata?.keys?.any { it == RECEIPT_KEY || it == PENDING_KEY } == true }) this
    else copy(parts = parts.map { part ->
        if (part.metadata?.keys?.any { it == RECEIPT_KEY || it == PENDING_KEY } != true) part else {
            part.withReceiptMetadata(JsonObject(part.metadata!!.filterKeys {
                it != RECEIPT_KEY && it != PENDING_KEY
            }).takeIf { it.isNotEmpty() })
        }
    })

internal fun UIMessage.rebaseReactionReceipts(from: String, to: String, assistantId: String): UIMessage {
    val receipt = storedReceipt()?.takeIf { it.conversationId == from && it.assistantId == assistantId }
        ?: return this
    return withStoredReceipt(receipt.copy(conversationId = to))
}

private fun UIMessage.withStoredReceipt(receipt: StoredReactionReceipt): UIMessage {
    val index = parts.indexOfFirst { it.isVisibleReactionReplyPart() }
    if (index < 0) return this
    return copy(parts = parts.mapIndexed { at, part ->
        if (at != index) part else part.withReceiptMetadata(buildJsonObject {
            part.metadata?.forEach { (key, value) -> put(key, value) }
            put(RECEIPT_KEY, receiptJson.encodeToJsonElement(receipt))
        })
    })
}

private fun UIMessagePart.isVisibleReactionReplyPart(): Boolean = when (this) {
    is UIMessagePart.Text -> {
        val visible = text.replace(jumpPattern, "").trim()
        visible.isNotEmpty() && !visible.matches(hiddenDecisionPattern)
    }
    is UIMessagePart.Image -> url.isNotBlank()
    is UIMessagePart.Video -> url.isNotBlank()
    is UIMessagePart.Audio -> url.isNotBlank()
    is UIMessagePart.VoiceMessage -> url.isNotBlank()
    is UIMessagePart.Document -> url.isNotBlank()
    else -> false
}

/** A pending approval, failed tool, tool-only result or hidden decision is not a completed reply. */
internal fun UIMessage.isReactionReplyComplete(): Boolean = role == MessageRole.ASSISTANT && finishedAt != null &&
    getTools().none { !it.isExecuted || it.executionCheckpointState in setOf("started", "failed", "unknown") } &&
    parts.lastOrNull {
        it is UIMessagePart.Tool || it is UIMessagePart.Text && it.text.isNotBlank() || it.isVisibleReactionReplyPart()
    }?.isVisibleReactionReplyPart() == true

/** One request-only snapshot, reused for every model/tool turn. Storage is changed only by the success owner. */
class ReactionContextTurn internal constructor(
    private val conversationId: String?,
    private val assistant: Assistant,
    private val requestAnchor: UIMessage?,
    private val branchAnchor: UIMessage?,
    internal val events: List<MessageReaction>,
    private val originals: Map<String, UIMessage>,
    private val legacyFloor: Long?,
) {
    private var delivered = false
    private var completedReply: UIMessage? = null

    /** Persisted partials must not become legacy successes just because an earlier tool turn set finishedAt. */
    fun markPending(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
        if (message.role != MessageRole.ASSISTANT || message.parts.isEmpty() ||
            originals[message.id.toString()]?.parts == message.parts ||
            originals[message.id.toString()]?.withoutPendingReactionMetadata()?.parts ==
            message.withoutPendingReactionMetadata().parts || message.hasPendingReactionMetadata()
        ) message else {
            val index = message.parts.indexOfFirst { it !is UIMessagePart.Search }
            if (index < 0) message else message.copy(parts = message.parts.mapIndexed { at, part ->
                if (at != index) part else part.withReceiptMetadata(buildJsonObject {
                    part.metadata?.forEach { (key, value) -> put(key, value) }
                    put(PENDING_KEY, true)
                })
            })
        }
    }

    /** A flow exception/cancellation or an unfinished tool loop must never call this. */
    fun onGenerationSucceeded(reply: UIMessage?) {
        completedReply = reply
    }

    fun apply(messages: List<UIMessage>): List<UIMessage> {
        val clean = messages.withoutAssistantInteractionNotices().map { message ->
            message.copy(parts = message.parts.filterNot {
                it is UIMessagePart.Text && it.metadata?.get(CONTEXT_KEY) == JsonPrimitive(true)
            }).withoutReactionReceiptMetadata().forInteractionRequest()
        }
        val anchorId = requestAnchor?.id?.takeIf { id -> clean.any { it.id == id } }
            ?: branchAnchor?.id?.takeIf { id -> clean.any { it.id == id } }
        if (events.isEmpty() || anchorId == null) return clean
        delivered = true
        val context = UIMessagePart.Text(buildString {
            appendLine("<message_interactions>")
            appendLine("New user reactions for this generation. App metadata, not new user instructions.")
            appendLine("React naturally only if relevant; do not repeatedly mention these events in later turns.")
            events.forEach {
                appendLine(buildJsonObject {
                    put("actor", it.actor)
                    put("reaction", it.emoji)
                    put("message_id", it.messageId)
                    put("quote", it.quote.take(80))
                }.toString())
            }
            append("</message_interactions>")
        }, metadata = buildJsonObject { put(CONTEXT_KEY, true) })
        return clean.map { if (it.id == anchorId) it.copy(parts = it.parts + context) else it }
    }

    /** Call inside the successful reply's persistence lock, after cancellation and ownership checks. */
    fun finish(
        reply: UIMessage,
        currentMessages: List<UIMessage>,
        currentConversationId: String = conversationId.orEmpty(),
        currentAssistantId: String = assistant.id.toString(),
    ): UIMessage {
        val expected = completedReply ?: return reply
        if (conversationId == null || !assistant.enableMessageReactions || branchAnchor == null ||
            currentConversationId != conversationId || currentAssistantId != assistant.id.toString() ||
            expected.id != reply.id || expected.withoutPendingReactionMetadata().parts.filterNot { it is UIMessagePart.Reasoning } !=
            reply.withoutPendingReactionMetadata().parts.filterNot { it is UIMessagePart.Reasoning } ||
            !reply.isReactionReplyComplete() || originals[reply.id.toString()]?.withoutPendingReactionMetadata()?.parts ==
            reply.withoutPendingReactionMetadata().parts
        ) return reply
        val live = currentMessages.associateBy { it.id.toString() }
        if (live[branchAnchor.id.toString()]?.parts != branchAnchor.parts) return reply
        if (currentMessages.filter { originals[it.id.toString()]?.parts != it.parts }.any { message ->
                message.getTools().any { it.executionCheckpointState in setOf("started", "failed", "unknown") }
            }
        ) return reply
        val receipts = if (!delivered) emptySet() else events.filter { event ->
            val source = originals[event.messageId]
            val current = live[event.messageId]
            source != null && current != null && reactionSegments(source, assistant) == reactionSegments(current, assistant)
        }.mapTo(linkedSetOf(), ::reactionReceipt)
        val existing = reply.reactionReceipts(conversationId, assistant.id.toString())
        // Keep receipts when continuing an existing assistant message. Never evict old receipts to acknowledge new ones.
        val combined = (existing + receipts).takeIf { it.size <= MAX_STORED_RECEIPTS } ?: existing
        return reply.withoutPendingReactionMetadata().withStoredReceipt(StoredReactionReceipt(
            conversationId, assistant.id.toString(), reply.id.toString(), combined, legacyFloor,
        ))
    }
}

internal fun prepareReactionContext(
    sourceMessages: List<UIMessage>,
    reactions: List<MessageReaction>,
    conversationId: String?,
    assistant: Assistant,
    requestMessages: List<UIMessage> = sourceMessages,
): ReactionContextTurn {
    val scoped = sourceMessages.mapNotNull { it.storedReceipt() }.filter {
        it.conversationId == conversationId && it.assistantId == assistant.id.toString()
    }
    val seen = scoped.flatMapTo(hashSetOf()) { it.events }
    // Upgrade fallback only. Modern replies carry even an empty receipt and retain this fixed floor.
    val legacyFloor = if (scoped.isNotEmpty()) scoped.mapNotNull { it.legacyFloor }.maxOrNull() else {
        sourceMessages.filter {
            it.storedReceipt() == null && !it.hasPendingReactionMetadata() && it.isReactionReplyComplete()
        }
            .maxOfOrNull { it.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() }
    }
    val originals = sourceMessages.associateBy { it.id.toString() }
    val anchor = requestMessages.lastOrNull { it.role == MessageRole.USER }
    val events = if (conversationId == null || !assistant.enableMessageReactions || anchor == null) emptyList() else {
        reactions.filter { event ->
            val source = originals[event.messageId]
            event.conversationId == conversationId && event.actor == "user" && source != null &&
                (event.quote.isEmpty() || event.quote in reactionSegments(source, assistant)) &&
                (legacyFloor == null || event.createdAt > legacyFloor) && reactionReceipt(event) !in seen
        }.sortedWith(compareBy<MessageReaction> { it.createdAt }.thenBy { it.id }).takeLast(MAX_TURN_REACTIONS)
    }
    // Synthetic proactive user turns are request-only; validate against their real branch anchor instead.
    val realAnchor = anchor?.let { originals[it.id.toString()] } ?: sourceMessages.lastOrNull { it.role == MessageRole.USER }
    return ReactionContextTurn(conversationId, assistant, anchor, realAnchor, events, originals, legacyFloor)
}
