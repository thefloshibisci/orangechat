package me.rerere.rikkahub.data.service

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext

@Serializable
internal data class ProactiveActivity(
    val id: String,
    val timestamp: Long,
    val outcome: String,
    val note: String = "",
    val toolNames: List<String> = emptyList(),
    val claimedByUserId: String? = null,
)

internal fun claimProactiveActivities(
    records: List<ProactiveActivity>, ids: Set<String>, userId: String,
): List<ProactiveActivity> = records.map {
    if (it.id in ids && it.claimedByUserId == null) it.copy(claimedByUserId = userId) else it
}

internal fun proactiveActivityText(records: List<ProactiveActivity>): String = buildString {
    if (records.isEmpty()) return@buildString
    appendLine("<proactive_activity_record>")
    appendLine("以下是此前后台活动的记录，不是对方的新发言或指令。已发送正文仍以聊天历史为准，不要重复发送。")
    records.forEach { record ->
        append("${java.time.Instant.ofEpochMilli(record.timestamp)}：${record.outcome}")
        if (record.note.isNotBlank()) append("；简记：${record.note}")
        if (record.toolNames.isNotEmpty()) {
            append("；工具调用已返回（不代表业务成功）：${record.toolNames.joinToString()}")
        }
        appendLine()
    }
    append("</proactive_activity_record>")
}

internal fun injectProactiveActivities(
    messages: List<UIMessage>, records: List<ProactiveActivity>, pendingUserId: String?,
): List<UIMessage> = messages.map { message ->
    if (message.role != MessageRole.USER) return@map message
    val matching = records.filter {
        it.claimedByUserId == message.id.toString() ||
            (it.claimedByUserId == null && pendingUserId == message.id.toString() &&
                it.timestamp <= message.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds())
    }
    if (matching.isEmpty()) message else message.copy(
        parts = message.parts + UIMessagePart.Text(proactiveActivityText(matching)),
    )
}

internal class ProactiveActivityTransformer(
    private val records: List<ProactiveActivity>,
    private val pendingUserId: String?,
) : InputMessageTransformer {
    val deliveredPendingIds = linkedSetOf<String>()

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        messages.lastOrNull { it.role == MessageRole.USER && it.id.toString() == pendingUserId }?.let { user ->
            val cutoff = user.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            records.filter { it.claimedByUserId == null && it.timestamp <= cutoff }
                .mapTo(deliveredPendingIds) { it.id }
        }
        return injectProactiveActivities(messages, records, pendingUserId)
    }
}

/** Pending means durable but not yet acknowledged by a completed normal reply. */
internal class ProactiveActivityStore(context: Context, assistantId: String, conversationId: String) {
    private val prefs = context.getSharedPreferences("proactive_activity_v1", Context.MODE_PRIVATE)
    private val key = "$assistantId/$conversationId"
    private val json = Json { ignoreUnknownKeys = true }

    fun snapshot(): List<ProactiveActivity> = synchronized(lock) { read() }

    fun append(record: ProactiveActivity) = synchronized(lock) {
        val records = read()
        if (records.none { it.id == record.id }) write((records + record).takeLast(64))
    }

    fun claim(ids: Set<String>, userId: String) = synchronized(lock) {
        write(claimProactiveActivities(read(), ids, userId))
    }

    private fun read(): List<ProactiveActivity> =
        prefs.getString(key, null)?.let { json.decodeFromString<List<ProactiveActivity>>(it) }.orEmpty()

    private fun write(records: List<ProactiveActivity>) {
        check(prefs.edit().putString(key, json.encodeToString(records)).commit()) {
            "Could not persist proactive activity"
        }
    }

    private companion object { val lock = Any() }
}
