package me.rerere.rikkahub.data.gongdu

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

/** Foreground observer only. Events survive app restarts; the in-memory chat queue is not a receipt. */
class GongduDoorbell(
    private val repo: GongduRepository,
    private val settings: SettingsStore,
    private val conversations: ConversationRepository,
    private val chat: ChatService,
    private val scope: AppScope,
) {
    private var job: Job? = null
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(chat.isForeground, repo.setting.map { Triple(it.doorbellEnabled, it.targetConversationId, it.serverId) }
                .distinctUntilChanged()) { foreground, config -> foreground to config }.collectLatest { (foreground, config) ->
                val target = config.second
                if (!foreground || !config.first || target == null || config.third == null) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    try {
                        repo.refreshInbox()
                        val pending = settings.settingsFlowRaw.first().gongdu.inbox.events.filterNot { it.acknowledged }
                        for (event in pending) deliver(target, event)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Durable inbox remains pending; next foreground poll retries. */ }
                    delay(60_000)
                }
            }
        }
    }

    private suspend fun deliver(target: Uuid, event: GongduEvent) {
        val conversation = conversations.getConversationById(target) ?: return
        val live = settings.settingsFlowRaw.first()
        if (live.gongdu.targetConversationId != target || !live.gongdu.doorbellEnabled ||
            live.assistants.none { it.id == conversation.assistantId && live.gongdu.serverId in it.mcpServers }) return
        val marker = marker(event.id)
        if (!conversation.messageNodes.any { node -> node.messages.any { hasReceipt(it.parts, marker) } }) {
            val queued = chat.submitMessage(target, listOf(UIMessagePart.Text(
                "小红书有一条新互动：${event.summary}\n请根据这条真实通知回应；公开发帖或回复仍遵守工具确认设置。",
                metadata = buildJsonObject { put("gongdu_event_id", marker) },
            ))) ?: return
            queued.completion.await()
        }
        // Only acknowledge a durable conversation record, never just an accepted queue request.
        if (conversations.getConversationById(target)?.messageNodes?.any { node -> node.messages.any { hasReceipt(it.parts, marker) } } == true) repo.acknowledge(event.id)
    }

    companion object {
        fun hasReceipt(parts: List<UIMessagePart>, id: String): Boolean = parts.any {
            it is UIMessagePart.Text && (it.metadata?.get("gongdu_event_id") as? JsonPrimitive)?.content == id
        }
        fun marker(id: String) = "[共度事件:${java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }}]"
    }
}
