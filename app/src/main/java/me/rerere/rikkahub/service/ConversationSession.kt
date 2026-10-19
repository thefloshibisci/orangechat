/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val debugLog: (String) -> Unit = { message ->
        Log.d(TAG, message)
        Unit
    },
    private val onIdle: (Uuid) -> Unit,
) {
    // 会话状态
    val state = MutableStateFlow(initial)
    private val deleted = AtomicBoolean(false)
    val isDeleted: Boolean get() = deleted.get()
    internal var persistedConversation: Conversation? = null
    internal val messageJournal = ConversationMessageJournal()
    private val lifecycleEpoch = AtomicLong(0)
    internal val epoch: Long get() = lifecycleEpoch.get()

    internal fun requireActive() {
        if (isDeleted) throw CancellationException("Conversation was deleted")
    }

    internal fun markDeleted(): List<Job> {
        deleted.set(true)
        lifecycleEpoch.incrementAndGet()
        return cancelJobs()
    }

    internal suspend fun initialize(
        load: suspend () -> Conversation?,
        create: suspend () -> Conversation,
    ): Conversation = withStateLock {
        if (!state.value.messageNodesLoaded) {
            val saved = load()
            val loaded = saved ?: create()
            require(loaded.id == id)
            loaded.requireMessageNodesLoaded()
            messageJournal.invalidate()
            persistedConversation = saved
            state.value = loaded
        }
        state.value
    }

    /** Keep the flow and references held by open screens alive across Undo. */
    internal suspend fun restore(persist: suspend () -> Conversation) = withContext(NonCancellable) {
        withRefSuspend {
            saveMutex.withLock {
                check(!isGenerating) { "会话仍在生成，请稍后恢复。" }
                val restored = persist()
                require(restored.id == id)
                restored.requireMessageNodesLoaded()
                lifecycleEpoch.incrementAndGet()
                messageJournal.invalidate()
                persistedConversation = restored
                state.value = restored
                deleted.set(false)
            }
        }
    }

    internal fun reopenAfterFailedDelete() {
        check(!isGenerating)
        deleted.set(false)
    }

    internal suspend fun deleteAndAwait(persistDeletion: suspend () -> Unit) = withContext(NonCancellable) {
        val jobs = markDeleted()
        jobs.forEach { it.join() }
        saveMutex.withLock {
            persistDeletion()
            persistedConversation = null
            messageJournal.invalidate()
            state.value = state.value.copy(messageNodes = emptyList(), chatSuggestions = emptyList())
            cleanup()
        }
    }

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val activeJobs = mutableSetOf<Job>()
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = synchronized(this) { activeJobs.any { it.isActive } }
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating

    /**
     * 保存互斥锁：保护"读取最新对话状态 -> 修改 messageNodes -> 落库"这一整段操作的原子性。
     *
     * 主消息生成(sendMessage)、语音通话挂断反馈(notifyVoiceCallDeclined)、标题生成(generateTitle)、
     * 建议生成(generateSuggestion)、重新生成(regenerateAtMessage)、工具审批(handleToolApproval)
     * 都可能对同一个 conversationId 并发触发保存 —— 它们各自都是"读旧对话 -> 追加/修改自己那部分 -> 整体存"，
     * 如果不加锁，谁后存谁就会把对方刚写入的消息覆盖掉。
     *
     * 这个锁只包裹"读-改-存"这个原子动作本身，不包裹耗时的 AI 生成请求过程，
     * 避免辅助任务（标题/建议/语音反馈）因为长时间持锁而互相卡住。
     */
    val saveMutex = Mutex()

    /** Keep this session alive while serializing database loads and state/metadata saves. */
    internal suspend fun <T> withStateLock(block: suspend () -> T): T = withRefSuspend {
        saveMutex.withLock { requireActive(); block() }
    }

    /** Persist only metadata, then patch the latest state without replacing in-flight messages. */
    internal suspend fun saveGeneratedTitle(
        title: String,
        expectedEpoch: Long = epoch,
        persist: suspend () -> Boolean,
    ) {
        withStateLock {
            if (epoch != expectedEpoch) return@withStateLock
            if (persist()) state.value = state.value.copy(title = title)
        }
    }

    internal suspend fun saveGeneratedSuggestions(
        suggestions: List<String>,
        expectedEpoch: Long = epoch,
        persist: suspend () -> Boolean,
    ) {
        withStateLock {
            if (epoch != expectedEpoch) return@withStateLock
            if (persist()) state.value = state.value.copy(chatSuggestions = suggestions)
        }
    }

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        debugLog("acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        debugLog("release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
        if (isDeleted) { job?.cancel(); return }
        val previous = _generationJob.value
        _generationJob.value = job
        if (cancelPrevious) previous?.cancel()
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion {
            synchronized(this) {
                activeJobs.remove(job)
                if (_generationJob.value === job) {
                    _generationJob.value = null
                }
                if (refCount.get() <= 0 && !isGenerating) {
                    scheduleIdleCheck()
                }
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    @Synchronized
    fun cancelJobs(): List<Job> = activeJobs.toList().also { jobs ->
        jobs.asReversed().forEach { it.cancel() }
    }

    /**
     * 原子地尝试把 newJob 注册为当前会话的生成任务，仅当当前没有生成任务在进行时才成功。
     * 用于主动消息/激进模式等"低优先级"生成源：如果已有生成在跑（无论是正常聊天还是
     * 另一路主动消息），直接返回 false，调用方应放弃本次触发，而不是排队等待。
     *
     * 与 setJob() 的区别：setJob() 是"强制抢占"（会取消旧 job），用于用户主动发消息这种
     * 应该无条件打断旧生成的场景；tryClaimGeneration() 是"礼貌性尝试"，用于不应该打断
     * 已有生成的低优先级场景。
     *
     * 注意：调用方在拿不到 Job 引用（返回 false）时，应打印包含 conversationId 的 debug
     * 日志说明"因为会话正在生成中，跳过本次触发"，方便排查。
     */
    private val claimLock = Any()

    fun tryClaimGeneration(newJob: Job): Boolean = synchronized(claimLock) {
        if (isDeleted || isGenerating) return@synchronized false
        setJob(newJob)
        true
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        _generationJob.value = null
        cancelJobs()
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}

/** Serialize approval saves without cancelling an earlier approval decision. */
internal suspend fun afterPreviousGeneration(previous: Job?, block: suspend () -> Unit) {
    try {
        previous?.join()
        block()
    } catch (e: CancellationException) {
        previous?.cancel()
        withContext(NonCancellable) { previous?.join() }
        throw e
    }
}
