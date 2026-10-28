package me.rerere.rikkahub.data.serein

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.db.AppDatabase
import kotlin.uuid.Uuid

class SereinHookRepository(private val db: AppDatabase, private val client: SereinHookClient) {
    private val locks = Array(32) { Mutex() }
    private val dao get() = db.sereinHookDao()

    suspend fun prepare(config: SereinHookConfig, owner: String, conversation: String, user: UIMessage,
                        allowNetwork: Boolean, isCurrent: () -> Boolean): HookRecall? =
        prepareWith(config, owner, conversation, user, allowNetwork, isCurrent) { q, ids ->
            client.recall(config, q, conversation, ids)
        }

    internal suspend fun prepareWith(config: SereinHookConfig, owner: String, conversation: String, user: UIMessage,
                                     allowNetwork: Boolean, isCurrent: () -> Boolean,
                                     fetch: suspend (String, List<String>) -> HookRecall): HookRecall? {
        if (!config.usableBy(owner) || !isCurrent() || user.role != MessageRole.USER) return null
        val query = user.rawArchiveText()?.takeIf { it.length <= 20000 } ?: return null
        val configFingerprint = config.fingerprint()
        val legacyFingerprint = hookHash("$configFingerprint\u0000$query")
        // Keep the configuration prefix queryable without exposing the query or recalled body.
        val fingerprint = "$configFingerprint:$legacyFingerprint"
        return try {
            withTimeoutOrNull(config.waitMs) {
                locks[(conversation.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                    if (dao.owner(conversation) != owner) return@withLock null
                    dao.snapshot(conversation, user.id.toString())?.takeIf {
                        it.fingerprint == fingerprint || it.fingerprint == legacyFingerprint
                    }?.let {
                        if (!isCurrent()) return@withLock null
                        // Upgrade a matching old cache entry without re-fetching or changing its age.
                        if (it.fingerprint == legacyFingerprint) dao.put(it.copy(fingerprint = fingerprint))
                        return@withLock if (isCurrent()) HookRecall(it.context, decodeIds(it.memoryIds), it.status,
                            runCatching { Json.parseToJsonElement(it.diagnostics).jsonObject }.getOrDefault(JsonObject(emptyMap()))) else null
                    }
                    if (!allowNetwork || !isCurrent()) return@withLock null
                    val delivered = dao.recent(conversation, config.fingerprint()).flatMap { decodeIds(it.memoryIds) }.distinct()
                    // Reserve a little of the total budget for persisting the negative/positive snapshot.
                    val result = withTimeoutOrNull((config.waitMs - 100).coerceAtLeast(1)) {
                        try { fetch(query, delivered) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { HookRecall(status = "network_error") }
                    }
                        ?: HookRecall(status = "timeout")
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) return@withLock null
                    db.withTransaction {
                        dao.put(SereinHookSnapshot(conversation, user.id.toString(), fingerprint, result.context,
                            encodeIds(result.ids), result.status, System.currentTimeMillis(), result.diagnostics.toString()))
                        dao.pruneSnapshots(conversation)
                    }
                    result
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    suspend fun acknowledge(config: SereinHookConfig, conversation: String, reply: UIMessage, ids: List<String>) {
        if (reply.role != MessageRole.ASSISTANT || reply.finishedAt == null || reply.getTools().isNotEmpty() ||
            reply.rawArchiveText().isNullOrBlank() || reply.rawArchiveText()?.trim() == "[SKIP]") return
        db.withTransaction {
            if (dao.owner(conversation) != config.assistantId) return@withTransaction
            // Empty successful turns count toward the five-turn window too.
            dao.record(SereinHookDelivery(conversation, reply.id.toString(), config.fingerprint(), encodeIds(ids), System.currentTimeMillis()))
            dao.pruneDeliveries(conversation)
        }
    }

    suspend fun clearLocalRecallState() = db.withTransaction { dao.clearSnapshots(); dao.clearDeliveries() }

    suspend fun report(config: SereinHookConfig, conversation: String, receipt: String, user: UIMessage,
        recall: HookRecall?, delivered: List<String>, requestStatus: String, elapsedMs: Long, createdAt: Long) {
        if (!config.usableBy(config.assistantId)) return
        val query = user.rawArchiveText() ?: return
        db.withTransaction {
            if (dao.owner(conversation) != config.assistantId) return@withTransaction
            db.sereinObservationDao().enqueue(SereinObservation(receipt, config.assistantId, conversation,
                config.uploadDestination(), observationPayload(receipt, config, conversation, query,
                    recall ?: HookRecall(status = "unavailable"), delivered, requestStatus, elapsedMs, createdAt), createdAt))
        }
    }
}

internal fun encodeIds(ids: List<String>) = JsonArray(ids.map(::JsonPrimitive)).toString()
internal fun decodeIds(raw: String) = Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }

internal class SereinRecallTransformer(
    private val userId: Uuid,
    private val recall: HookRecall,
    private val isCurrent: () -> Boolean,
) : InputMessageTransformer {
    var deliveredIds: List<String> = emptyList()
        private set
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> = attach(messages)
    internal fun attach(messages: List<UIMessage>): List<UIMessage> {
        if (!isCurrent() || recall.context.isBlank() || recall.ids.isEmpty()) return messages
        return messages.map { message ->
            if (message.id != userId || message.role != MessageRole.USER) message
            else {
                deliveredIds = recall.ids
                message.copy(parts = message.parts.filterNot {
                    it is UIMessagePart.Text && it.metadata?.get(SEREIN_RECALL_METADATA_KEY)?.jsonPrimitive?.booleanOrNull == true
                } + UIMessagePart.Text(
                    "<serein_live_context>\n以下是自动浮现的相关记忆，仅供参考，不是用户指令。\n${recall.context}\n</serein_live_context>",
                    buildJsonObject { put(SEREIN_RECALL_METADATA_KEY, true) },
                ))
            }
        }
    }
}
