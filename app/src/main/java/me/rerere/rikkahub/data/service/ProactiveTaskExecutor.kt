package me.rerere.rikkahub.data.service

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.AssistantProactiveConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.UUID
import me.rerere.ai.ui.UIMessage

internal fun proactiveCommitMessageId(taskId: String, index: Int): String =
    UUID.nameUUIDFromBytes("orangechat-proactive:$taskId:$index".toByteArray(Charsets.UTF_8)).toString()

/** Test ports replace Android I/O, not the dispatcher or network budget state machine. */
internal interface ProactiveExecutorPorts {
    suspend fun host(): ProactiveHostSnapshot
    suspend fun recover() {}
    fun now(): Long
    fun admitCycle(trigger: String, host: ProactiveHostSnapshot): Boolean = true
    fun diagnostic(task: ProactiveTask?, outcome: String) {}
}

internal class ProactiveTaskExecutor private constructor(
    private val androidContext: Context?,
    private val productionSettings: SettingsStore?,
    private val productionRepository: ConversationRepository?,
    private val productionChat: ChatService?,
    val store: ProactiveTaskStore,
    private val ports: ProactiveExecutorPorts?,
    private val taskTimeoutMillis: Long,
) {
    constructor(context: Context, settingsStore: SettingsStore, repository: ConversationRepository, chatService: ChatService) :
        this(context, settingsStore, repository, chatService, ProactiveTaskStore(context), null, 180_000L)

    internal constructor(store: ProactiveTaskStore, ports: ProactiveExecutorPorts, taskTimeoutMillis: Long = 180_000L) :
        this(null, null, null, null, store, ports, taskTimeoutMillis)

    private val context get() = requireNotNull(androidContext)
    private val settingsStore get() = requireNotNull(productionSettings)
    private val repository get() = requireNotNull(productionRepository)
    private val chatService get() = requireNotNull(productionChat)
    private fun now() = ports?.now() ?: System.currentTimeMillis()
    private fun diagnostic(task: ProactiveTask?, outcome: String) {
        // Diagnostics must never abort admission, sibling tasks or terminal cleanup.
        runCatching {
            if (ports != null) ports.diagnostic(task, outcome) else {
                android.util.Log.i("ProactiveExecutor", "assistant=${task?.assistantId} outcome=$outcome")
                task?.let { ProactiveActivityStore(context, it.assistantId, it.conversationId).append(ProactiveActivity(
                    id = "dispatch:${it.taskId}:$outcome", timestamp = now(), outcome = outcome,
                )) }
            }
        }
    }

    /**
     * 跟随最新：把每个**未固定**的参与者的目标窗口解析到该助手最近更新的会话，
     * 有变化才写设置（`settingsStore.update` 会同步刷新 settingsFlow，
     * 随后的 [host] 立刻能看到新目标）。
     *
     * 仅生产路径有效；测试走 ports，不参与。
     */
    suspend fun syncLatestConversations() {
        if (ports != null) return
        val settings = settingsStore.settingsFlow.first { !it.init }
        val configs = settings.proactiveMessageSetting.assistantConfigs.orEmpty()
        if (configs.isEmpty()) return
        val scheduledAssistants = ProactiveWakeupStore(context).all().mapTo(hashSetOf()) { it.assistantId }
        val latest = mutableMapOf<String, String>()
        configs.filter { it.enabled && !it.pinConversation && it.assistantId !in scheduledAssistants }.forEach { config ->
            val assistantId = runCatching { Uuid.parse(config.assistantId) }.getOrNull() ?: return@forEach
            repository.getMostRecentConversationId(assistantId)?.let { latest[config.assistantId] = it.toString() }
        }
        if (latest.isEmpty()) return
        settingsStore.update { current ->
            val old = current.proactiveMessageSetting.assistantConfigs.orEmpty()
            val moved = followLatestConversations(old, latest)
            if (moved == old) current else current.copy(
                proactiveMessageSetting = current.proactiveMessageSetting.copy(assistantConfigs = moved),
            )
        }
    }

    suspend fun migrate() = migrationMutex.withLock {
        val settings = settingsStore.settingsFlow.first { !it.init }
        if (settings.proactiveMessageSetting.assistantConfigs != null) {
            if (settings.proactiveMessageSetting.globalPolicy.budgetZoneId.isBlank()) settingsStore.update { latest ->
                latest.copy(proactiveMessageSetting = latest.proactiveMessageSetting.copy(
                    globalPolicy = latest.proactiveMessageSetting.globalPolicy.copy(budgetZoneId = ZoneId.systemDefault().id),
                ))
            }
            return@withLock
        }
        val legacy = settings.proactiveMessageSetting.assistantId
        val assistant = if (legacy.isBlank()) settings.getCurrentAssistant()
            else settings.assistants.singleOrNull { it.id.toString() == legacy }
        val conversation = assistant?.let { repository.getMostRecentConversationId(it.id) }
            ?.let { repository.getConversationById(it) }
        val frozen = if (assistant != null && conversation != null && conversation.assistantId == assistant.id) listOf(
            AssistantProactiveConfig(assistant.id.toString(), conversation.id.toString()),
        ) else emptyList()
        settingsStore.update { latest ->
            val old = latest.proactiveMessageSetting
            if (old.assistantConfigs != null) latest else latest.copy(proactiveMessageSetting = old.copy(
                assistantConfigs = frozen.filter { config -> latest.assistants.any { it.id.toString() == config.assistantId } },
                globalPolicy = old.globalPolicy.copy(budgetZoneId = ZoneId.systemDefault().id),
            ))
        }
    }

    suspend fun conversationSnapshot(id: Uuid): Conversation? {
        val persisted = repository.getConversationById(id) ?: return null
        val live = chatService.getOrCreateSession(id).state.value
        return if (live.messageNodesLoaded) live else persisted
    }

    suspend fun host(additionalWindows: Collection<String> = emptyList()): ProactiveHostSnapshot {
        ports?.let { return it.host() }
        val settings = settingsStore.settingsFlow.value
        val windows = settings.proactiveMessageSetting.assistantConfigs.orEmpty().map { it.conversationId } + additionalWindows
        val conversations = windows.distinct().mapNotNull { window ->
            runCatching { Uuid.parse(window) }.getOrNull()?.let { id ->
                conversationSnapshot(id)
            }
        }
        return snapshot(conversations)
    }

    private fun snapshot(conversations: List<Conversation>): ProactiveHostSnapshot {
        val settings = settingsStore.settingsFlow.value
        return ProactiveHostSnapshot(
            setting = settings.proactiveMessageSetting,
            assistantIds = settings.assistants.mapTo(hashSetOf()) { it.id.toString() },
            conversationOwners = conversations.associate { it.id.toString() to it.assistantId.toString() },
            userAnchors = conversations.mapNotNull { conversation ->
                conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.let {
                    conversation.id.toString() to it.id.toString()
                }
            }.toMap(),
            busyConversations = conversations.filter { chatService.getOrCreateSession(it.id).isGenerating }
                .mapTo(hashSetOf()) { it.id.toString() },
        )
    }

    suspend fun drain(trigger: String, run: suspend (ProactiveTask) -> Unit) {
        if (ports == null) {
            initialize(context)
            migrate()
            // 迁移之后立即跟随最新窗口，保证本轮 host()/claimNext 看到的是新目标。
            syncLatestConversations()
            // A concurrent drain is already reconciling durable intents; peers may still claim
            // unrelated windows instead of waiting behind its Room I/O.
            if (recoveryMutex.tryLock()) try {
                recoverCommits(recordSent = ::recordRecoveredSent, commit = ::commitRecovered)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostic(null, "recovery_failed:${error::class.simpleName}")
            } finally { recoveryMutex.unlock() }
        } else try {
            ports.recover()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostic(null, "recovery_failed:${error::class.simpleName}")
        }
        val claimed = dispatchMutex.withLock {
            val timestamp = now()
            val wakeups = if (ports == null) ProactiveWakeupStore(context) else null
            val appointments = wakeups?.all().orEmpty()
            val host = host(appointments.map { it.conversationId })
            if (ports == null) ProactiveMessageService.durableNextSlot(context)?.let { legacyDue ->
                store.migrateLegacyNextSlot(host.setting, legacyDue, timestamp)
                check(ProactiveMessageService.clearDurableNextSlot(context)) { "Could not migrate proactive decision slot" }
            }
            if (!host.setting.enabled || (trigger != "manual" && host.setting.globalPolicy.paused)) {
                return@withLock emptyList<ProactiveTask>()
            }
            // Per-conversation slots provide pacing; a global cooldown would block an
            // unrelated participant whose own slot is already due.
            val admitted = ports?.admitCycle(trigger, host) ?: true
            if (!admitted) return@withLock emptyList<ProactiveTask>()
            val eligibleAppointments = appointments.filter { appointment ->
                val config = host.setting.assistantConfigs?.singleOrNull { it.assistantId == appointment.assistantId }
                val reason = when {
                    !host.setting.allowAiScheduledWakeup -> "appointment_disabled"
                    appointment.assistantId !in host.assistantIds -> "appointment_assistant_removed"
                    config?.acceptsWindow(appointment.conversationId, true) != true -> "appointment_participant_changed"
                    appointment.timeMillis > timestamp -> null
                    host.conversationOwners[appointment.conversationId] != appointment.assistantId -> "appointment_conversation_removed"
                    host.userAnchors[appointment.conversationId] == null -> "no_user_message"
                    else -> null
                }
                if (reason != null && wakeups?.consume(appointment) == true) wakeups.finish(appointment, reason)
                reason == null
            }
            val byAssistant = eligibleAppointments.groupBy { it.assistantId }.mapValues { (_, values) ->
                values.minBy { it.timeMillis }
            }
            store.snapshot().tasks.filter { task ->
                val appointment = byAssistant[task.assistantId]
                task.status == ProactiveTaskStatus.Pending && (!proactiveTaskAllowed(task, host, timestamp) ||
                    (task.trigger != "manual" && task.appointmentToken != appointment?.token))
            }.forEach { store.cancelTask(it.taskId) }
            host.setting.assistantConfigs.orEmpty().filter { it.enabled }.forEach { config ->
                val appointment = byAssistant[config.assistantId].takeUnless { trigger == "manual" }
                if (trigger != "manual" && appointment != null && appointment.timeMillis > timestamp) return@forEach
                val conversationId = appointment?.conversationId ?: config.conversationId
                val anchor = host.userAnchors[conversationId]
                if (anchor == null) {
                    diagnostic(ProactiveTask(assistantId = config.assistantId, conversationId = config.conversationId,
                        anchorUserMessageId = "", trigger = trigger, createdAtMillis = timestamp), "no_user_anchor")
                    return@forEach
                }
                if (trigger == "manual") store.promotePendingToManual(config.assistantId, timestamp)
                if (store.snapshot().tasks.none { it.assistantId == config.assistantId &&
                        it.status in setOf(ProactiveTaskStatus.Pending, ProactiveTaskStatus.Running, ProactiveTaskStatus.CommitPending) }) {
                    val candidate = ProactiveTask(
                        assistantId = config.assistantId, conversationId = conversationId,
                        anchorUserMessageId = anchor,
                        trigger = if (trigger == "manual") "manual" else if (appointment != null) "appointment" else "automatic",
                        appointmentToken = appointment?.token, createdAtMillis = timestamp,
                    )
                    if (store.slotAllows(candidate, timestamp)) store.enqueue(candidate)
                }
            }
            val claimed = buildList {
                while (true) {
                    val next = store.claimNext(host, timestamp, manualBatch = trigger == "manual") ?: break
                    store.setNextSlot(next, defaultProactiveDueAt(host.setting, timestamp))
                    add(next)
                }
            }
            // Automatic cycles only: a due slot that could not be claimed for a persistent
            // reason must not schedule another alarm + foreground service + wake lock a
            // minute later. Defer it to the matching retry horizon instead.
            if (trigger != "manual") {
                store.deferStalledSlots(
                    host = host,
                    now = timestamp,
                    claimedAssistantIds = claimed.mapTo(hashSetOf()) { it.assistantId },
                    noAnchorRetryAt = timestamp + TimeUnit.MINUTES.toMillis(proactiveNoGenerationSlotMinutes().toLong()),
                    retryAt = timestamp + TimeUnit.MINUTES.toMillis(30),
                )
            }
            claimed
        }
        // No dispatcher lock is held across a provider or a task completion. Per-conversation
        // claims, cancellation and reservations remain durable in the shared store.
        supervisorScope {
            claimed.forEach { task -> launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
                jobs[task.taskId] = currentCoroutineContext()[Job]!!
                try {
                    kotlinx.coroutines.withTimeout(taskTimeoutMillis) { run(task) }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    diagnostic(task, "generation_timeout")
                } catch (error: kotlinx.coroutines.CancellationException) {
                    diagnostic(task, "generation_cancelled")
                    throw error
                } catch (error: Exception) {
                    diagnostic(task, "generation_failed:${error::class.simpleName}")
                } finally {
                    jobs.remove(task.taskId)
                    runCatching { store.finish(task.taskId, ProactiveTaskStatus.Failed, "generation_finished_without_send") }
                        .onFailure { diagnostic(task, "terminal_persistence_failed") }
                }
            } }
        }
    }

    suspend fun observeRevocations() {
        settingsStore.settingsFlow.collect { settings ->
            val policy = settings.proactiveMessageSetting
            val active = store.snapshot().tasks.filter { it.status in setOf(ProactiveTaskStatus.Pending, ProactiveTaskStatus.Running) }
            active.filter { task ->
                !policy.enabled || (task.trigger != "manual" && policy.globalPolicy.paused) ||
                    settings.assistants.none { it.id.toString() == task.assistantId } ||
                    policy.assistantConfigs?.singleOrNull { it.assistantId == task.assistantId }?.let {
                        !it.acceptsWindow(task.conversationId, task.appointmentToken != null) ||
                            (task.appointmentToken != null && !policy.allowAiScheduledWakeup) ||
                            (task.admittedConfig != null && task.admittedConfig != it)
                    } != false
            }.forEach { task ->
                if (store.cancelTask(task.taskId)) jobs[task.taskId]?.cancel()
            }
        }
    }

    suspend fun bindJob(task: ProactiveTask) {
        jobs[task.taskId] = currentCoroutineContext()[Job]!!
        requireAllowed(task)
    }

    suspend fun requireAllowed(task: ProactiveTask) {
        currentCoroutineContext().ensureActive()
        if (!store.canCommit(task.taskId, host(listOf(task.conversationId)), now())) {
            throw kotlinx.coroutines.CancellationException("Proactive task revoked")
        }
    }

    suspend fun <T> networkTurn(task: ProactiveTask, call: suspend () -> T): T {
        val token = store.reserveTurn(task.taskId, host(listOf(task.conversationId)), now())
            ?: throw kotlinx.coroutines.CancellationException("Proactive budget or authorization exhausted")
        try {
            if (!store.markDispatched(token, host(listOf(task.conversationId)), now())) {
                throw kotlinx.coroutines.CancellationException("Proactive task revoked before network dispatch")
            }
            return kotlinx.coroutines.withTimeout(taskTimeoutMillis) { call() }
        } finally { store.settleTurn(token) }
    }

    suspend fun prepareCommit(task: ProactiveTask, messages: List<UIMessage>): Boolean {
        require(messages.isNotEmpty())
        val ids = messages.indices.map { proactiveCommitMessageId(task.taskId, it) }
        val identified = messages.mapIndexed { index, message -> message.copy(id = kotlin.uuid.Uuid.parse(ids[index])) }
        val payload = JsonInstant.encodeToString(identified)
        if (payload.toByteArray(Charsets.UTF_8).size > ProactiveTaskStore.PROACTIVE_COMMIT_MAX_BYTES) return false
        return store.beginCommit(task.taskId, host(listOf(task.conversationId)), System.currentTimeMillis(), ids, payload)
    }

    fun startCommit(task: ProactiveTask): ProactiveTask? = store.startCommit(task.taskId)

    fun markCommitSent(task: ProactiveTask): Boolean = store.markCommitSent(task.taskId)

    fun decodeCommitPayload(task: ProactiveTask): List<UIMessage>? = runCatching {
        JsonInstant.decodeFromString<List<UIMessage>>(requireNotNull(task.commitPayload))
    }.getOrNull()?.takeIf { messages ->
        messages.isNotEmpty() && messages.size == task.commitMessageIds.size &&
            messages.map { it.id.toString() } == task.commitMessageIds &&
            messages.sumOf { JsonInstant.encodeToString(it).toByteArray(Charsets.UTF_8).size } <=
            ProactiveTaskStore.PROACTIVE_COMMIT_MAX_BYTES
    }

    suspend fun commitRecovered(task: ProactiveTask, messages: List<UIMessage>): Boolean {
        val conversationId = runCatching { Uuid.parse(task.conversationId) }.getOrNull() ?: return false
        val session = chatService.getOrCreateSession(conversationId)
        return session.withStateLock {
            val settings = settingsStore.settingsFlow.value
            val assistantId = runCatching { Uuid.parse(task.assistantId) }.getOrNull() ?: return@withStateLock false
            if (settings.proactiveMessageSetting.assistantConfigs?.singleOrNull { it.assistantId == task.assistantId }
                    ?.acceptsWindow(task.conversationId, task.appointmentToken != null) != true ||
                settings.assistants.none { it.id == assistantId } || session.isDeleted
            ) {
                store.abandonCommit(task.taskId, ProactiveTaskStatus.Interrupted, "commit_guard_changed")
                return@withStateLock true
            }
            val conversation = repository.getConversationById(conversationId)
                ?: run {
                    store.abandonCommit(task.taskId, ProactiveTaskStatus.Interrupted, "conversation_missing")
                    return@withStateLock true
                }
            val live = session.state.value
            val current = if (live.messageNodesLoaded) live else conversation
            if (current.assistantId != assistantId ||
                current.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id.toString() != task.anchorUserMessageId
            ) {
                store.abandonCommit(task.taskId, ProactiveTaskStatus.Interrupted, "anchor_changed")
                return@withStateLock true
            }
            val anchorId = runCatching { Uuid.parse(task.anchorUserMessageId) }.getOrNull()
                ?: run {
                    store.abandonCommit(task.taskId, ProactiveTaskStatus.Interrupted, "invalid_anchor")
                    return@withStateLock true
                }
            val saved = repository.appendProactiveMessagesIdempotently(
                conversationId, assistantId, anchorId, messages,
            ) ?: return@withStateLock false
            chatService.updateConversation(saved.id, saved)
            session.persistedConversation = saved
            session.messageJournal.invalidate()
            true
        }
    }

    fun recordRecoveredSent(task: ProactiveTask, messages: List<UIMessage>) {
        val stateStore = ProactiveMessageStateStore(
            context, "${task.assistantId}_${task.conversationId}",
        )
        stateStore.recordCommittedIds(task.commitMessageIds)
    }

    suspend fun recoverCommits(
        recordSent: (ProactiveTask, List<UIMessage>) -> Unit,
        commit: suspend (ProactiveTask, List<UIMessage>) -> Boolean,
    ) {
        for (task in store.pendingCommits()) {
            val payload = decodeCommitPayload(task)
            if (payload == null) {
                store.abandonCommit(task.taskId, ProactiveTaskStatus.Failed, "invalid_commit_payload")
                continue
            }
            // First reconcile an already committed Room transaction, even if settings/anchor
            // changed after it. Never append in that case; only repair the ledger receipt.
            val conversationId = runCatching { Uuid.parse(task.conversationId) }.getOrNull()
            if (conversationId == null) {
                store.abandonCommit(task.taskId, ProactiveTaskStatus.Interrupted, "invalid_conversation_id")
                continue
            }
            val persisted = try {
                kotlinx.coroutines.withTimeout(15_000L) { repository.getConversationById(conversationId) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                diagnostic(task, "commit_snapshot_timeout")
                continue
            } catch (error: Exception) {
                diagnostic(task, "commit_snapshot_failed:${error::class.simpleName}")
                continue
            }
            val persistedById = persisted?.messageNodes?.flatMap { it.messages }?.associateBy { it.id }.orEmpty()
            if (persisted?.assistantId?.toString() == task.assistantId &&
                payload.all { persistedById[it.id] == it }
            ) {
                recordSent(task, payload)
                store.markCommitSent(task.taskId)
                diagnostic(task, "commit_recovered_existing")
                continue
            }
            val host = host(listOf(task.conversationId))
            val now = System.currentTimeMillis()
            if (!proactiveTaskAllowed(task, host, now)) {
                store.abandonCommit(task.taskId, ProactiveTaskStatus.Interrupted, "commit_guard_changed")
                continue
            }
            val started = store.startCommit(task.taskId) ?: continue
            try {
                val committed = kotlinx.coroutines.withTimeout(15_000L) { commit(started, payload) }
                if (!committed) {
                    // A definitive guard/conflict rejection cannot occupy this conversation forever.
                    store.abandonCommit(task.taskId, ProactiveTaskStatus.Interrupted, "commit_rejected")
                    diagnostic(task, "commit_rejected")
                    continue
                }
                recordSent(task, payload)
                store.markCommitSent(task.taskId)
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                diagnostic(task, "commit_recovery_timeout")
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                // Unknown Room outcome retains the exact durable payload, but cannot block peers.
                diagnostic(task, "commit_recovery_failed:${error::class.simpleName}")
            }
        }
    }

    companion object {
        private val dispatchMutex = Mutex()
        private val recoveryMutex = Mutex()
        private val migrationMutex = Mutex()
        private val startLock = Any()
        private var started = false
        private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

        fun initialize(context: Context) = synchronized(startLock) {
            if (!started) {
                val store = ProactiveTaskStore(context)
                store.interruptRunning()
                store.cancel()
                started = true
            }
        }

        fun cancel(context: Context, assistantId: String? = null) {
            ProactiveTaskStore(context).cancel(assistantId).forEach { jobs[it]?.cancel() }
        }
    }
}
