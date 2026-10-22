package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SystemToolsSetting
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

private const val TAG = "ExtraInfoRequest"

/** Private, excluded from Android auto-backup: snapshots may contain screen text or notifications. */
fun extraInfoSnapshotDir(context: Context): File = File(context.noBackupFilesDir, "extra_info_snapshots")

fun deleteExtraInfoSnapshots(dir: File, conversationId: Uuid) {
    runCatching { File(dir, "$conversationId.json").delete() }
        .onFailure { Log.w(TAG, "Could not delete extra-info snapshots", it) }
}

@Serializable
private data class StoredSnapshot(
    /** Hash of the user text the snapshot was collected for; an edited message never reuses it. */
    val textHash: String,
    /** Hash of assistant + text + non-secret settings; only gates re-collection for the latest turn. */
    val fingerprint: String,
    val content: String? = null,
)

/**
 * Per-message extra-info snapshots.
 *
 * Each user message keeps the exact snapshot it was first sent with, and every later request
 * re-attaches it byte-for-byte. Previously only the latest user message carried a snapshot, so
 * each new turn silently removed it from the previous message and broke the provider's prefix
 * cache from that point onward. Tool continuations and retries reuse the stored snapshot instead
 * of observing the device again.
 */
internal class ExtraInfoRequestCache(
    private val capacity: Int = 32,
    private val directory: File? = null,
    private val maxMessagesPerConversation: Int = 1024,
) {
    private val conversations = linkedMapOf<Uuid, LinkedHashMap<String, StoredSnapshot>>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(
        conversationId: Uuid,
        assistantId: Uuid,
        user: UIMessage,
        option: SystemToolsSetting,
        allowCollection: Boolean,
        collect: suspend () -> String?,
    ): String? = withContext(Dispatchers.IO) {
        if (!option.extraInfoInjectionEnabled) return@withContext null
        val fingerprint = fingerprint(assistantId, user, option)
        synchronized(conversations) { load(conversationId)[user.id.toString()] }
            ?.takeIf { it.fingerprint == fingerprint }
            ?.let { return@withContext it.content }
        // A resumed tool chain without a matching snapshot must not observe an unrelated screen.
        if (!allowCollection) return@withContext null
        val content = collect()
        currentCoroutineContext().ensureActive()
        synchronized(conversations) {
            val entries = load(conversationId)
            entries.remove(user.id.toString())
            entries[user.id.toString()] = StoredSnapshot(textHash(user), fingerprint, content)
            while (entries.size > maxMessagesPerConversation.coerceAtLeast(1)) entries.remove(entries.keys.first())
            persist(conversationId, entries)
        }
        return@withContext content
    }

    /** Stored snapshots for historical user messages whose text is unchanged. */
    suspend fun snapshots(
        conversationId: Uuid,
        messages: List<UIMessage>,
        option: SystemToolsSetting,
    ): Map<Uuid, String> = withContext(Dispatchers.IO) {
        if (!option.extraInfoInjectionEnabled) return@withContext emptyMap()
        val entries = synchronized(conversations) { HashMap(load(conversationId)) }
        if (entries.isEmpty()) return@withContext emptyMap()
        return@withContext buildMap {
            messages.forEach { message ->
                if (message.role != MessageRole.USER) return@forEach
                val stored = entries[message.id.toString()] ?: return@forEach
                val content = stored.content
                if (!content.isNullOrBlank() && stored.textHash == textHash(message)) put(message.id, content)
            }
        }
    }

    /** Caller holds the lock. */
    private fun load(conversationId: Uuid): LinkedHashMap<String, StoredSnapshot> {
        conversations[conversationId]?.let { entries ->
            conversations.remove(conversationId)
            conversations[conversationId] = entries
            return entries
        }
        val entries = LinkedHashMap<String, StoredSnapshot>()
        directory?.let { dir ->
            runCatching {
                val file = File(dir, "$conversationId.json")
                if (file.isFile) entries.putAll(json.decodeFromString<Map<String, StoredSnapshot>>(file.readText()))
            }.onFailure { Log.w(TAG, "Could not read extra-info snapshots", it) }
        }
        conversations[conversationId] = entries
        while (conversations.size > capacity.coerceAtLeast(1)) conversations.remove(conversations.keys.first())
        return entries
    }

    /** Caller holds the lock. A failed write only costs cache stability after a restart. */
    private fun persist(conversationId: Uuid, entries: Map<String, StoredSnapshot>) {
        val dir = directory ?: return
        runCatching {
            dir.mkdirs()
            val target = File(dir, "$conversationId.json")
            val temp = File(dir, "$conversationId.json.tmp")
            temp.writeText(json.encodeToString(entries))
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "rename failed" }
            }
        }.onFailure { Log.w(TAG, "Could not persist extra-info snapshots", it) }
    }

    private fun textHash(user: UIMessage): String = sha256(user.toText())

    private fun fingerprint(assistantId: Uuid, user: UIMessage, option: SystemToolsSetting): String {
        // Secrets are blanked so they never enter the persisted hash input.
        val safeOption = option.copy(amapApiKey = "", ocrApiKey = "")
        return sha256("$assistantId\u0000${user.toText()}\u0000$safeOption")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal class ExtraInfoRequestTransformer(
    private val snapshots: Map<Uuid, String>,
) : InputMessageTransformer {
    constructor(userId: Uuid?, content: String?) :
        this(if (userId != null && !content.isNullOrBlank()) mapOf(userId to content) else emptyMap())

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        attachExtraInfoSnapshots(messages, snapshots)
}

internal fun attachExtraInfoToRequest(
    messages: List<UIMessage>,
    userId: Uuid?,
    content: String?,
): List<UIMessage> {
    if (userId == null || content.isNullOrBlank()) return messages
    return attachExtraInfoSnapshots(messages, mapOf(userId to content))
}

internal fun attachExtraInfoSnapshots(
    messages: List<UIMessage>,
    snapshots: Map<Uuid, String>,
): List<UIMessage> {
    if (snapshots.isEmpty()) return messages
    return messages.map { message ->
        val content = snapshots[message.id]
        if (content.isNullOrBlank() || message.role != MessageRole.USER) message
        else message.copy(parts = message.parts.filterNot { it.isExtraInfoInjectionPart() } + extraInfoMessagePart(content))
    }
}
