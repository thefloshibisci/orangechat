/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class LocalTaskPostUsage(
    val modelCalls: Int = 0,
    val lastTurnAt: Long = 0,
    val likes: Set<String> = emptySet(),
    val replies: Map<String, Int> = emptyMap(),
)

/** Durable task record. Counters live here so a restart cannot silently replay the same work. */
@Serializable
data class LocalTaskRecord(
    val taskId: String,
    val mode: LocalTaskMode = LocalTaskMode.INTERACT,
    val postId: String? = null,
    val assistantId: String? = null,
    val clientKey: String? = null,
    val mediaIds: List<String> = emptyList(),
    val state: LocalTaskState = LocalTaskState.QUEUED,
    val round: Int = 0,
    val turns: Int = 0,
    val comments: Int = 0,
    val likes: Int = 0,
    val modelCalls: Int = 0,
    val commentsTruncated: Boolean = false,
    val endReason: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val pendingReview: List<LocalTaskPendingActionDto> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val finishedAt: Long? = null,
) {
    fun toStatus(): LocalTaskStatusDto = LocalTaskStatusDto(
        taskId = taskId,
        mode = mode.wire,
        state = state.wire,
        postId = postId,
        assistantId = assistantId,
        round = round,
        turns = turns,
        comments = comments,
        likes = likes,
        modelCalls = modelCalls,
        commentsTruncated = commentsTruncated,
        endReason = endReason,
        error = errorCode?.let { LocalTaskErrorDto(it, errorMessage.orEmpty()) },
        pendingReview = pendingReview,
        createdAt = createdAt,
        updatedAt = updatedAt,
        finishedAt = finishedAt,
    )
}

/**
 * 本地任务账本。**加字段 = 升版本**：新增任何字段都必须同时
 *  1. 把 [LocalTasksApi.LEDGER_VERSION] 上调；
 *  2. 在 [LocalTaskLedgerCodec.decode] 里为旧数据写一段显式迁移。
 * 因为 decode 严格要求 version == LEDGER_VERSION 且拒绝缺 canonical 字段的账本——
 * 不带迁移的新字段会让所有存量账本直接 decode 失败。
 */
@Serializable
data class LocalTaskLedgerState(
    val version: Int = LocalTasksApi.LEDGER_VERSION,
    val dayKey: Long = -1,
    val modelCallsToday: Int = 0,
    val posts: Map<String, LocalTaskPostUsage> = emptyMap(),
    val tasks: Map<String, LocalTaskRecord> = emptyMap(),
)

/**
 * Shared validation for native persistence and backup import; never supplies missing budgets.
 *
 * decode 拒绝缺 canonical 字段的账本，所以 [LocalTaskLedgerState] 每加一个字段都得
 * 配套升 [LocalTasksApi.LEDGER_VERSION] 并在这里写显式迁移。
 */
object LocalTaskLedgerCodec {
    fun decode(raw: String, json: Json = LocalTaskJson.task): LocalTaskLedgerState {
        val document = json.parseToJsonElement(raw)
        val version = document.jsonObject["version"]?.jsonPrimitive?.intOrNull
        check(version == LocalTasksApi.LEDGER_VERSION) { "Missing or unsupported local task ledger version" }
        return json.decodeFromString(LocalTaskLedgerState.serializer(), raw).also { decoded ->
            // Version 1 has always persisted defaults. Nullable nulls are deliberately omitted,
            // but a missing counter/collection must never become a fresh budget via defaults.
            checkComplete(document, LocalTaskJson.task.parseToJsonElement(LocalTaskJson.task.encodeToString(decoded)))
            check(decoded.dayKey >= -1 && decoded.modelCallsToday >= 0)
            check(decoded.dayKey != -1L || decoded.modelCallsToday == 0)
            decoded.posts.forEach { (key, usage) ->
                check(key.isNotBlank() && usage.modelCalls >= 0 && usage.lastTurnAt >= 0)
                check(usage.likes.all { it.isNotBlank() })
                check(usage.replies.all { (assistant, count) -> assistant.isNotBlank() && count >= 0 })
            }
            val reviewIds = mutableSetOf<String>()
            decoded.tasks.forEach { (key, task) ->
                check(key.isNotBlank() && task.taskId == key)
                check(listOf(task.round, task.turns, task.comments, task.likes, task.modelCalls).all { it >= 0 })
                check(task.createdAt >= 0 && task.updatedAt >= 0 && (task.finishedAt == null || task.finishedAt >= 0))
                task.pendingReview.forEach { action ->
                    check(action.id.isNotBlank() && reviewIds.add(action.id))
                    check(action.assistantId.isNotBlank() && action.createdAt >= 0)
                }
            }
        }
    }

    private fun checkComplete(actual: JsonElement, canonical: JsonElement) {
        when (canonical) {
            is JsonObject -> {
                val objectValue = actual.jsonObject
                canonical.forEach { (key, value) ->
                    check(key in objectValue) { "Missing local task ledger field: $key" }
                    checkComplete(objectValue.getValue(key), value)
                }
            }
            is JsonArray -> {
                check(actual is JsonArray && actual.size == canonical.size)
                canonical.forEachIndexed { index, value -> checkComplete(actual[index], value) }
            }
            else -> Unit // The serializer already checked primitive types.
        }
    }

}

/**
 * Pure, synchronously guarded budget/idempotency ledger. Persistence is delegated to
 * [LocalTaskKeyValueStore]. Writes must be confirmed before a task or model call is admitted.
 */
class LocalTaskLedger(
    private val store: LocalTaskKeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failed = false
    private var state: LocalTaskLedgerState = load()
        get() {
            check(!failed) { "Local task ledger failed; recovery required" }
            store.checkAvailable()
            return field
        }
        set(value) {
            check(!failed) { "Local task ledger failed; recovery required" }
            store.checkAvailable()
            field = value
        }

    @Synchronized
    fun snapshot(): LocalTaskLedgerState = state

    @Synchronized
    fun task(taskId: String): LocalTaskRecord? = state.tasks[taskId]

    @Synchronized
    fun activeTasks(): List<LocalTaskRecord> = state.tasks.values
        .filter { !it.state.isTerminal }
        .sortedBy { it.createdAt }

    @Synchronized
    fun activeTask(postId: String, assistantId: String?): LocalTaskRecord? = state.tasks.values
        .filter { record ->
            record.mode == LocalTaskMode.INTERACT &&
                record.postId == postId &&
                !record.state.isTerminal &&
                (assistantId == null || record.assistantId == assistantId)
        }
        .maxByOrNull { it.createdAt }

    @Synchronized
    fun activePublishTask(assistantId: String?): LocalTaskRecord? = state.tasks.values
        .filter { record ->
            record.mode == LocalTaskMode.PUBLISH &&
                !record.state.isTerminal &&
                (assistantId == null || record.assistantId == assistantId)
        }
        .maxByOrNull { it.createdAt }

    @Synchronized
    fun findByClientKey(clientKey: String): LocalTaskRecord? = state.tasks.values
        .filter { it.clientKey == clientKey }
        .maxByOrNull { it.createdAt }

    @Synchronized
    fun createTask(record: LocalTaskRecord): LocalTaskRecord {
        val normalized = record.copy(
            createdAt = if (record.createdAt > 0) record.createdAt else clock(),
            updatedAt = clock(),
        )
        state = state.copy(tasks = state.tasks + (normalized.taskId to normalized))
        pruneTasks()
        persist()
        return normalized
    }

    @Synchronized
    fun updateTask(taskId: String, transform: (LocalTaskRecord) -> LocalTaskRecord): LocalTaskRecord? {
        val current = state.tasks[taskId] ?: return null
        val updated = transform(current).copy(updatedAt = clock())
        state = state.copy(tasks = state.tasks + (taskId to updated))
        persist()
        return updated
    }

    /**
     * Any task left queued/running by a previous process is closed as interrupted and is never
     * replayed. Called once when the host service is created.
     */
    @Synchronized
    fun markInterruptedOnStartup(): Int {
        val now = clock()
        var changed = 0
        val updated = LinkedHashMap<String, LocalTaskRecord>()
        state.tasks.forEach { (taskId, record) ->
            if (record.state == LocalTaskState.QUEUED || record.state == LocalTaskState.RUNNING) {
                updated[taskId] = record.copy(
                    state = LocalTaskState.INTERRUPTED,
                    endReason = "app_restart",
                    finishedAt = now,
                    updatedAt = now,
                )
                changed++
            } else {
                updated[taskId] = record
            }
        }
        if (changed > 0) {
            state = state.copy(tasks = updated)
            persist()
        }
        return changed
    }

    // ------------------------------------------------------------------ budget

    @Synchronized
    fun hasDailyBudget(settings: LocalTaskSettings): Boolean {
        rollDay()
        return settings.dailyModelCallBudget > 0 && state.modelCallsToday < settings.dailyModelCallBudget
    }

    /** Atomically reserves one model call for [usageKey]; false when any cap is hit. */
    @Synchronized
    fun tryChargeModelCall(usageKey: String, settings: LocalTaskSettings): Boolean {
        rollDay()
        if (settings.dailyModelCallBudget <= 0) return false
        val usage = state.posts[usageKey] ?: LocalTaskPostUsage()
        val perPostCap = settings.maxModelCallsPerPost.coerceAtLeast(1)
        if (usage.modelCalls >= perPostCap) return false
        if (state.modelCallsToday >= settings.dailyModelCallBudget) return false
        val updatedUsage = usage.copy(modelCalls = usage.modelCalls + 1, lastTurnAt = clock())
        state = state.copy(
            modelCallsToday = state.modelCallsToday + 1,
            posts = state.posts + (usageKey to updatedUsage),
        )
        prunePosts()
        persist()
        return true
    }

    /** Returns a reservation when the runner decided not to call the model at all. */
    @Synchronized
    fun refundModelCall(usageKey: String) {
        val usage = state.posts[usageKey] ?: return
        if (usage.modelCalls <= 0 && state.modelCallsToday <= 0) return
        val updated = usage.copy(modelCalls = (usage.modelCalls - 1).coerceAtLeast(0))
        state = state.copy(
            modelCallsToday = (state.modelCallsToday - 1).coerceAtLeast(0),
            posts = state.posts + (usageKey to updated),
        )
        persist()
    }

    @Synchronized
    fun lastTurnAt(usageKey: String): Long = state.posts[usageKey]?.lastTurnAt ?: 0L    @Synchronized
    fun modelCallsFor(usageKey: String): Int = state.posts[usageKey]?.modelCalls ?: 0

    @Synchronized
    fun replyCount(usageKey: String, assistantId: String): Int =
        state.posts[usageKey]?.replies?.get(assistantId) ?: 0

    @Synchronized
    fun canReply(usageKey: String, assistantId: String, settings: LocalTaskSettings): Boolean {
        val limit = settings.replyLimitFor(assistantId)
        return limit > 0 && replyCount(usageKey, assistantId) < limit
    }

    @Synchronized
    fun recordReply(usageKey: String, assistantId: String) {
        val usage = state.posts[usageKey] ?: LocalTaskPostUsage()
        val replies = usage.replies.toMutableMap()
        replies[assistantId] = (replies[assistantId] ?: 0) + 1
        state = state.copy(posts = state.posts + (usageKey to usage.copy(replies = replies)))
        persist()
    }

    @Synchronized
    fun hasLiked(usageKey: String, assistantId: String): Boolean =
        state.posts[usageKey]?.likes?.contains(assistantId) == true

    @Synchronized
    fun recordLike(usageKey: String, assistantId: String) {
        val usage = state.posts[usageKey] ?: LocalTaskPostUsage()
        state = state.copy(
            posts = state.posts + (usageKey to usage.copy(likes = usage.likes + assistantId)),
        )
        persist()
    }

    // ------------------------------------------------------------------ review

    @Synchronized
    fun pendingReviews(): List<LocalTaskPendingActionDto> = state.tasks.values
        .flatMap { it.pendingReview }
        .sortedBy { it.createdAt }

    @Synchronized
    fun findReview(reviewId: String): Pair<LocalTaskRecord, LocalTaskPendingActionDto>? {
        state.tasks.values.forEach { record ->
            record.pendingReview.firstOrNull { it.id == reviewId }?.let { return record to it }
        }
        return null
    }

    @Synchronized
    fun removeReview(reviewId: String): Boolean {
        var removed = false
        val updated = LinkedHashMap<String, LocalTaskRecord>()
        state.tasks.forEach { (taskId, record) ->
            val remaining = record.pendingReview.filterNot { it.id == reviewId }
            if (remaining.size != record.pendingReview.size) {
                removed = true
                updated[taskId] = record.copy(pendingReview = remaining)
            } else {
                updated[taskId] = record
            }
        }
        if (removed) {
            state = state.copy(tasks = updated)
            persist()
        }
        return removed
    }

    // ------------------------------------------------------------------ internals

    private fun load(): LocalTaskLedgerState {
        val raw = store.read(LocalTasksApi.KEY_LEDGER) ?: return LocalTaskLedgerState()
        return try {
            LocalTaskLedgerCodec.decode(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Local task ledger is corrupt; recovery required", error)
        }
    }

    private fun persist() {
        try {
            store.write(LocalTasksApi.KEY_LEDGER, LocalTaskJson.task.encodeToString(state))
        } catch (error: Exception) {
            failed = true
            throw IllegalStateException("Local task ledger write failed; recovery required", error)
        }
    }

    private fun rollDay() {
        val key = clock() / LocalTasksApi.DAY_MILLIS
        if (state.dayKey != key) {
            state = state.copy(dayKey = key, modelCallsToday = 0)
            persist()
        }
    }

    private fun pruneTasks() {
        val active = state.tasks.values.filter { !it.state.isTerminal }
        val finished = state.tasks.values
            .filter { it.state.isTerminal }
            .sortedByDescending { it.createdAt }
            .take(LocalTasksApi.MAX_TASK_RECORDS)
        val kept = (active + finished).associateBy { it.taskId }
        if (kept.size != state.tasks.size) {
            state = state.copy(tasks = kept)
        }
    }

    private fun prunePosts() {
        if (state.posts.size <= LocalTasksApi.MAX_POST_USAGE_RECORDS) return
        val kept = state.posts.entries
            .sortedByDescending { it.value.lastTurnAt }
            .take(LocalTasksApi.MAX_POST_USAGE_RECORDS)
            .associate { it.key to it.value }
        state = state.copy(posts = kept)
    }
}
