/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import kotlinx.coroutines.async

import me.rerere.rikkahub.data.files.attachmentReferenceGuard

import me.rerere.rikkahub.diagnostics.LoadingDiagnostics
import me.rerere.rikkahub.diagnostics.TaskWatchdog
import kotlinx.coroutines.flow.onStart

import me.rerere.rikkahub.data.repository.ConversationNodeChanges

import me.rerere.rikkahub.data.serein.isSereinCompletedReply
import me.rerere.rikkahub.data.serein.sereinSuccessfulRequestStatus
import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.model.ConversationSummary
import me.rerere.rikkahub.data.model.CompressionUndoMode
import me.rerere.rikkahub.data.ai.buildConversationRequestMessages
import me.rerere.rikkahub.data.ai.hasDurableToolEvidence
import me.rerere.rikkahub.data.ai.recoverInterruptedExecution
import me.rerere.rikkahub.data.ai.withExecutionCheckpoint
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.LocalMomentsBudgetRegistry
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatSelectionPrepareResult
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatSelectionStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.ExtraInfoInjectionCollector
import me.rerere.rikkahub.data.ai.transformers.ExtraInfoRequestCache
import me.rerere.rikkahub.data.ai.transformers.ExtraInfoRequestTransformer
import me.rerere.rikkahub.data.ai.transformers.extraInfoSnapshotDir
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.SpeechOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.video.withoutHistoricalCameraImages
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.buildAnniversaryPrompt
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.video.FrameFileOwnership
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoFrameStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.withConversationConfig
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.interaction.ChatInteractionRepository
import me.rerere.rikkahub.data.interaction.withoutJumpControl
import me.rerere.rikkahub.data.interaction.ReactionContextTurn
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.UsageRecorder

private const val TAG = "ChatService"

/**
 * R2-3: the tool context must describe the model that will actually run this turn, not the
 * assistant's default. `getChatModelOf` resolves the explicit conversation override first and
 * falls back to the assistant/global model; doing the same lookup here keeps `modelCanSeeImages`
 * in sync with the request provider + model. Callers that cannot resolve a turn (headless
 * workflow / sub-agent) keep the conservative false default in [ToolInvocationContext].
 *
 * Internal + Android-free so the conversation-override regression is JVM-testable.
 */
internal fun buildToolInvocationContext(
    settings: me.rerere.rikkahub.data.datastore.Settings,
    assistant: Assistant,
    conversationId: Uuid,
    conversation: Conversation,
    headless: Boolean,
): me.rerere.rikkahub.data.ai.tools.ToolInvocationContext {
    val actualModel = settings.getChatModelOf(conversation)
    return me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
        callerAssistantId = assistant.id.toString(),
        callerConversationId = conversationId.toString(),
        isHeadless = headless,
        modelCanSeeImages = actualModel?.inputModalities
            ?.contains(me.rerere.ai.provider.Modality.IMAGE) == true,
    )
}

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        *me.rerere.rikkahub.data.ai.transformers.historyMediaTransformers.toTypedArray(),
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
        me.rerere.rikkahub.data.ai.transformers.InteractionOutputTransformer,
        me.rerere.rikkahub.data.ai.transformers.VideoCallOutputTransformer,
        SpeechOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val chatInteractionRepository: ChatInteractionRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val pluginToolProvider: PluginToolProvider,
    private val pluginLoader: PluginLoader,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val extraInfoInjectionCollector: ExtraInfoInjectionCollector,
    private val sereinHookRepository: me.rerere.rikkahub.data.serein.SereinHookRepository,
    private val usageRecorder: UsageRecorder,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val extraInfoRequestCache = ExtraInfoRequestCache(directory = extraInfoSnapshotDir(context))

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    // R2-4: LocalMoments read budget is scoped to the user turn (conversation + newest user
    // message), not to one buildAvailableTools call, so an approval-resume rebuild reuses it.
    // Owned here (one registry per service/process) rather than a global singleton.
    private val localMomentsTurnBudgets = LocalMomentsBudgetRegistry()
    private val notificationHelper = ChatNotificationHelper(context)
    private val momentsBridge = ChatMomentsBridge(
        context = context,
        settingsStore = settingsStore,
        localTools = localTools,
        submitMomentsInstruction = { cid, content, anchor ->
            submitMessage(conversationId = cid, content = content, newMessageId = anchor)
        },
    )
    private val interactionCoordinator = ChatInteractionCoordinator(
        context = context,
        settingsStore = settingsStore,
        conversationRepo = conversationRepo,
        filesManager = filesManager,
        getOrCreateSession = ::getOrCreateSession,
        sendMessage = { cid, content, interaction ->
            sendMessage(conversationId = cid, content = content, interaction = interaction)
        },
        saveConversationLocked = ::saveConversationLocked,
        addError = { error, cid, title -> addError(error, cid, title) },
    )
    private val toolBuilder = ChatToolBuilder(
        context = context,
        settingsStore = settingsStore,
        conversationRepo = conversationRepo,
        memoryRepository = memoryRepository,
        chatInteractionRepository = chatInteractionRepository,
        localTools = localTools,
        mcpManager = mcpManager,
        filesManager = filesManager,
        skillManager = skillManager,
        pluginToolProvider = pluginToolProvider,
        workspaceRepository = workspaceRepository,
        localMomentsTurnBudgets = localMomentsTurnBudgets,
        getOrCreateSession = ::getOrCreateSession,
        customizeCurrentAssistant = { cid, aid, patch ->
            interactionCoordinator.customizeCurrentAssistant(cid, aid, patch)
        },
        createChatInteraction = { cid, aid, actor, kind, amount, note, requestId, walletLinked ->
            createChatInteraction(cid, aid, actor, kind, amount, note, requestId, walletLinked)
        },
        respondChatInteraction = { cid, id, actor, action ->
            respondChatInteraction(cid, id, actor, action)
        },
        appendAssistantAvatarInteraction = { cid, assistant ->
            interactionCoordinator.appendAssistantAvatarInteraction(cid, assistant)
        },
        manageReactionPresets = { operation, value ->
            interactionCoordinator.manageReactionPresets(operation, value)
        },
    )
    private val attachmentMaintenance = ChatAttachmentMaintenance(
        context = context,
        filesManager = filesManager,
        conversationRepo = conversationRepo,
        liveSessions = { sessions.values.toList() },
        saveConversationLocked = ::saveConversationLocked,
    )
    private val messageMutations = ChatMessageMutations(
        settingsStore = settingsStore,
        filesManager = filesManager,
        chatInteractionRepository = chatInteractionRepository,
        getOrCreateSession = ::getOrCreateSession,
        getConversationFlow = ::getConversationFlow,
        preprocessUserInputParts = ::preprocessUserInputParts,
        saveConversation = ::saveConversation,
        saveConversationLocked = ::saveConversationLocked,
    )
    private val auxiliaryCoordinator by lazy {
        ChatAuxiliaryCoordinator(
            context = context,
            appScope = appScope,
            settingsStore = settingsStore,
            conversationRepo = conversationRepo,
            generationHandler = generationHandler,
            providerManager = providerManager,
            usageRecorder = usageRecorder,
            getOrCreateSession = ::getOrCreateSession,
            getConversationFlow = ::getConversationFlow,
            bindConversationConfig = ::bindConversationConfig,
            updateConversation = ::updateConversation,
            saveConversation = ::saveConversation,
            saveConversationLocked = ::saveConversationLocked,
            addProactiveMessage = ::addProactiveMessage,
            isForeground = isForeground,
            sendGenerationDoneNotification = ::sendGenerationDoneNotification,
            addError = { error, cid, title, solution -> addError(error, cid, title, solution) },
        )
    }
    private data class QueueGenerationOwner(val message: QueuedMessage, val job: Job)
    private val activeQueueGenerations = ConcurrentHashMap<Uuid, QueueGenerationOwner>()
    private val deletedConversationIds = ConcurrentHashMap.newKeySet<Uuid>()
    private val conversationLifecycleLocks = ConcurrentHashMap<Uuid, Mutex>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 前台状态管理
    internal val visibleDoorbellChat = MutableStateFlow<Uuid?>(null)
    internal fun hasQueuedDoorbell(target: Uuid, marker: String) = sessions[target]?.messageQueue?.let { queue ->
        (queue.state.value.messages + queue.inFlightMessages).any {
            me.rerere.rikkahub.data.gongdu.GongduDoorbell.hasReceipt(it.parts, marker)
        }
    } == true

    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    // 标记 lifecycleObserver 是否已真正 addObserver 成功。
    // 用于规避 init 的 post 任务还没执行就被 cleanup() 的竞态:
    // 若 addObserver 是异步派发到主线程, cleanup 可能在它之前执行 removeObserver,
    // 此时 observer 实际没挂上(后续 ON_START/ON_STOP 回调丢失, 前后台状态失效)。
    private val observerAdded = AtomicBoolean(false)
    private val cleanedUp = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 真正执行 addObserver 的 Runnable, 保存引用供 cleanup 精确取消 pending 的 post,
    // 避免用 removeCallbacksAndMessages(null) 误删同 handler 上其它任务。
    private val addObserverRunnable = Runnable {
        if (cleanedUp.get()) return@Runnable
        try {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            lifecycle.addObserver(lifecycleObserver)
            observerAdded.set(true)
            if (cleanedUp.get()) {
                lifecycle.removeObserver(lifecycleObserver)
                observerAdded.set(false)
            } else {
                Log.i(TAG, "Lifecycle observer added")
            }
        } catch (e: Exception) {
            // 例如 ProcessLifecycleOwner 尚未就绪等异常边界, 记日志不崩
            Log.e(TAG, "Failed to add lifecycle observer", e)
        }
    }

    init {
        // 添加生命周期观察者。ProcessLifecycleOwner.get().lifecycle.addObserver
        // 强制要求主线程调用。这里加线程判断 + 派发, 既能在主线程构造时直接注册,
        // 也能在后台线程首次访问 ChatService 时安全派发到主线程异步执行。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            addObserverRunnable.run()
        } else {
            Log.w(TAG, "ChatService constructed off main thread; dispatching addObserver to main thread")
            mainHandler.post(addObserverRunnable)
        }
    }

    fun cleanup() = runCatching {
        cleanedUp.set(true)
        // 同样在主线程操作 observer, 与 addObserver 的执行线程保持一致,
        // 规避"post 中的 add 还没执行, 这里先 remove"导致 observer 实际没挂上的竞态。
        val removeObserverRunnable = Runnable {
            try {
                if (observerAdded.get()) {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
                    observerAdded.set(false)
                    Log.i(TAG, "Lifecycle observer removed")
                } else {
                    mainHandler.removeCallbacks(addObserverRunnable)
                    Log.i(TAG, "Cancelled pending lifecycle observer add (cleanup before add)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove lifecycle observer", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeObserverRunnable.run()
        } else {
            mainHandler.post(removeObserverRunnable)
            Unit
        }
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
        localMomentsTurnBudgets.clear()
    }

    // ---- Session 管理 ----

    fun beginInteractionCall(conversationId: Uuid, startedAt: Long, videoCall: Boolean = false) = appScope.launch {
        runCatching { beginChatInteractionCall(conversationId, startedAt, videoCall) }
    }

    internal val pendingIncomingCall = MutableStateFlow<me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction?>(null)

    fun finishInteractionCalls(conversationId: Uuid, startJob: Job? = null, startedAt: Long? = null,
        transcript: List<me.rerere.ai.ui.CallTranscriptEntry>? = null, giftSummary: String = "",
        videoCall: Boolean = false, linkedUserMessageIds: List<String> = emptyList()) {
        val endedAt = System.currentTimeMillis()
        val connectingIds = getConversationFlow(conversationId).value.currentMessages.flatMap { it.annotations }
            .filterIsInstance<me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction>()
            .filter { it.kind == "call" && it.status == "connecting" }.map { it.id }.toSet()
        appScope.launch {
            startJob?.join()
            runCatching {
                endChatInteractionCalls(conversationId, startedAt, endedAt, transcript, connectingIds, giftSummary,
                    videoCall = videoCall, linkedUserMessageIds = linkedUserMessageIds)
            }
        }
    }
    fun respondIncomingInteraction(conversationId: Uuid, action: String, onReady: () -> Unit) {
        appScope.launch {
            val pending = getConversationFlow(conversationId).value.currentMessages.flatMap { it.annotations }
                .filterIsInstance<me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction>()
                .lastOrNull { it.kind == "call" && it.actor == "assistant" && it.status == "pending" }
            if (pending != null) {
                val result = runCatching { respondChatInteraction(conversationId, pending.id, "user", action) }
                if (result.isFailure) return@launch
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onReady() }
        }
    }

    fun answerChatGift(conversationId: Uuid, cardId: String) {
        val message = getOrCreateSession(conversationId).state.value.currentMessages.firstOrNull { message ->
            message.role == MessageRole.USER && message.annotations.any {
                it is me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction && it.id == cardId && it.actor == "user"
            }
        } ?: return
        submitMessage(conversationId, emptyList(), existingMessageId = message.id)
    }

    internal val interactionSettings get() = settingsStore
    internal val interactionPlugins get() = pluginToolProvider
    internal suspend fun interactionConversationExists(id: Uuid) = conversationRepo.existsConversationById(id)

    internal fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ).copy(messageNodesLoaded = false),
                scope = appScope,
                onGenerationIdle = { processMessageQueue(it) },
                onIdle = { removeSession(it) }
            ).also {
                // 队列是"本轮附图"的唯一持有者：撤销/清空时由它终结文件（正常派发后的所有权
                // 已经移交给请求本身，由 job 的 finally 删）。
                it.messageQueue.onDiscardTransientImages = { discarded ->
                    VideoFrameStore.deleteTransient(context, discarded.transientImageUrls)
                }
                // 队列项被彻底放弃（撤回/清空/暂停）：只终结**显式所有权**里的文件。
                // 绝不能按目录把 parts 里的 Image 全删掉：用户重发一张历史里的截图时，
                // 那也是一条 UIMessagePart.Image，删掉就等于把历史里的照片永久断链。
                it.messageQueue.onMessageDiscarded = { discarded ->
                    VideoFrameStore.deleteTransient(context, discarded.transientImageUrls)
                    discarded.ownedImageUrls.forEach { url -> VideoFrameStore.deleteOwned(context, url) }
                }
                // 队列项被放弃/暂停：撤销这一轮的通话授权（恢复队列是一次全新请求）。
                it.messageQueue.onRequestTokenRevoked = { token ->
                    VideoCallRuntime.cancelTurnRequest(token)
                }
                if (id in deletedConversationIds) it.markDeleted()
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            getOrCreateSession(conversationId).requireActive()
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(
        conversationId: Uuid,
        assistantId: Uuid? = null,
        updateSelectedAssistant: Boolean = true,
    ) {
        val session = getOrCreateSession(conversationId)
        val loaded = session.initialize(
            load = { conversationRepo.getConversationById(conversationId) },
            create = {
                val settings = settingsStore.settingsFlowRaw.first()
                val assistant = assistantId?.let(settings::getAssistantById) ?: settings.getCurrentAssistant()
                Conversation.ofId(conversationId, assistant.id, newConversation = true)
                    .updateCurrentMessages(assistant.presetMessages)
            },
        )
        if (updateSelectedAssistant && settingsStore.settingsFlow.value.assistantId != loaded.assistantId) {
            settingsStore.updateAssistant(loaded.assistantId)
        }
    }

    /** Only explicit edits and generation bind controls; merely opening a legacy chat does not. */
    suspend fun updateConversationConfig(
        conversationId: Uuid,
        edit: (ConversationConfig) -> ConversationConfig,
    ) {
        initializeConversation(conversationId, updateSelectedAssistant = false)
        val session = getOrCreateSession(conversationId)
        session.withStateLock {
            val current = session.state.value
            val bound = current.bindConfig(settingsStore.settingsFlow.first())
            persistConversationConfigLocked(session, edit(requireNotNull(bound.config)))
        }
    }

    suspend fun resetConversationConfig(conversationId: Uuid) {
        initializeConversation(conversationId, updateSelectedAssistant = false)
        val session = getOrCreateSession(conversationId)
        session.withStateLock { persistConversationConfigLocked(session, null) }
    }

    suspend fun bindConversationConfig(conversationId: Uuid): Conversation {
        initializeConversation(conversationId, updateSelectedAssistant = false)
        val session = getOrCreateSession(conversationId)
        return session.withStateLock {
            val current = session.state.value
            val bound = current.bindConfig(settingsStore.settingsFlow.first())
            if (bound.config != current.config) persistConversationConfigLocked(session, bound.config)
            session.state.value
        }
    }

    /** Caller holds the session state lock. A control edit must never save an old message list. */
    private suspend fun persistConversationConfigLocked(session: ConversationSession, config: ConversationConfig?) {
        session.requireActive()
        val current = session.state.value
        current.requireMessageNodesLoaded()
        if (conversationRepo.existsConversationById(current.id)) {
            check(conversationRepo.updateConfig(current.id, config)) { "Conversation no longer exists" }
            session.persistedConversation = session.persistedConversation?.copy(config = config)
        } else {
            val saved = conversationRepo.insertConversation(current.copy(config = config))
            session.persistedConversation = saved
            session.messageJournal.committed()
        }
        session.state.update { it.copy(config = config) }
    }

    suspend fun replyToBot(conversationId: Uuid, assistantId: Uuid, text: String, timeoutMillis: Long): String =
        getOrCreateSession(conversationId).awaitQueuedBotReply(
            timeoutMillis = timeoutMillis,
            initialize = { initializeConversation(conversationId, assistantId, updateSelectedAssistant = false) },
            submit = { submitMessage(conversationId, listOf(UIMessagePart.Text(text))) },
            cancelRequest = { cancelQueuedMessage(conversationId, it) },
        )

    // ---- 发送消息 ----

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        getOrCreateSession(conversationId).messageQueue.state

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessions[conversationId]?.messageQueue?.remove(messageId)
        LocalMomentsChatSelectionStore.clearIfAnchor(conversationId.toString(), messageId.toString())
        processMessageQueue(conversationId)
    }

    fun beginQueuedMessageEdit(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessions[conversationId]?.messageQueue?.beginEdit(messageId)

    fun finishQueuedMessageEdit(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>?) {
        sessions[conversationId]?.messageQueue?.finishEdit(messageId, parts)
        processMessageQueue(conversationId)
    }

    fun pauseMessageQueue(conversationId: Uuid) {
        sessions[conversationId]?.messageQueue?.pause()
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessions[conversationId]?.messageQueue?.resume()
        processMessageQueue(conversationId)
    }

    /** Only this request can be withdrawn or interrupted; other callers retain their own turns. */
    fun cancelQueuedMessage(conversationId: Uuid, requestId: Uuid) {
        val session = sessions[conversationId] ?: return
        session.messageQueue.remove(requestId)?.let {
            // 队列自己已经终结了这次专用临时帧与"本次新建、还没落库"的永久帧；
            // 正在进行的请求不在这里删（所有权在 job 的 finally）。
            processMessageQueue(conversationId)
            return
        }
        if (activeQueueGenerations[conversationId]?.message?.id != requestId) return
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            stopGenerationInternal(conversationId, requestId = requestId, pauseQueue = false)
        }
    }

    fun submitMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        transientSystemPrompt: String? = null,
        enableTools: Boolean = true,
        interaction: UIMessageAnnotation.AvatarInteraction? = null,
        existingMessageId: Uuid? = null,
        /** 只用于本次请求的图片（视频通话自动抓帧），不进聊天历史。 */
        transientImageUrls: List<String> = emptyList(),
        transientImageScope: me.rerere.rikkahub.data.video.VideoFrameRequestScope? = null,
        /**
         * 本次**新建**、还没有被聊天历史引用的永久帧（手动夹帧）。
         *
         * 所有权判定：请求的 finally 才知道这条用户消息到底有没有落库。
         * 落库了 → 文件属于历史，永远不删；没落库（保存失败/取消）→ 删掉，避免孤儿照片。
         */
        ownedImageUrls: List<String> = emptyList(),
        /** 队列暂停摘掉"本轮附图"之后要改用的 system prompt（视频 context 的无图版本）。 */
        transientSystemPromptWithoutImages: String? = null,
        /**
         * 这一轮的授权 token（视频通话）。
         *
         * 随队列项**不可变**地传下去，由生成管线带到 `TransformerContext.requestToken`。
         * 以前这里是一个"入队后再回调去登记"的可失败副作用；现在改成纯参数：
         * 入队与授权要么一起成立，要么都不成立，不存在"回调失败但消息已经进队列"的中间态。
         */
        requestToken: String? = null,
        /**
         * 这一轮**只为本次请求**提供的一段录音（视频通话手动录的那一段）。
         *
         * 所有权在 [me.rerere.rikkahub.data.video.TransientAudioClip] 上：
         * 请求开始（job 体）时认领，请求结束（正常/失败/取消）时释放并删除文件。
         * 聊天历史里**不会**出现 audio part，所以下一轮不会再 base64 一次旧录音。
         */
        transientAudio: me.rerere.rikkahub.data.video.TransientAudioClip? = null,
        newMessageId: Uuid? = null,
    ): QueuedMessage? {
        if ((content.isEmptyInputMessage() && existingMessageId == null) || conversationId in deletedConversationIds) return null
        val session = getOrCreateSession(conversationId)
        val queued = synchronized(session) {
            if (session.isDeleted) return null
            // A fresh send after stopping an empty queue is explicit intent to continue.
            // A manually paused backlog remains paused until the user resumes it.
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(
                parts = content,
                answer = answer,
                interaction = interaction,
                transientSystemPrompt = transientSystemPrompt,
                transientSystemPromptWithoutImages = transientSystemPromptWithoutImages,
                enableTools = enableTools,
                existingMessageId = existingMessageId,
                transientImageUrls = transientImageUrls,
                transientImageScope = transientImageScope,
                ownedImageUrls = ownedImageUrls,
                requestToken = requestToken,
                transientAudio = transientAudio,
                newMessageId = newMessageId,
            )
        } ?: return null
        queued.completion.invokeOnCompletion { cause ->
            if (cause is CancellationException && !queued.reply.isCompleted) cancelQueuedMessage(conversationId, queued.id)
        }
        appScope.launch {
            try {
                initializeConversation(conversationId, updateSelectedAssistant = false)
                processMessageQueue(conversationId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                session.messageQueue.pause()
                session.messageQueue.failReplyWaiters("会话初始化失败")
                addError(error, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        return queued
    }

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        transientSystemPrompt: String? = null,
        enableTools: Boolean = true,
        interaction: UIMessageAnnotation.AvatarInteraction? = null,
    ): Job? = submitMessage(conversationId, content, answer, transientSystemPrompt, enableTools, interaction)?.completion

    /**
     * User entry: "let the assistant organize a Moments draft from my selection".
     *
     * It validates the host-owned selection, binds it to the exact user instruction message this
     * call enqueues (one-shot anchor), and submits a *real user message* through the normal queue.
     * The model then decides whether to call `moments_chat_draft`; the native tool approval stays
     * the pre-write gate. No draft is written here and nothing is published.
     */
    fun prepareMomentsChatSelection(
        conversation: Conversation,
        selectedMessages: List<UIMessage>,
        includeImages: Boolean = false,
    ): LocalMomentsChatSelectionPrepareResult =
        momentsBridge.prepareMomentsChatSelection(conversation, selectedMessages, includeImages)

    private fun processMessageQueue(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        session.dispatchNextQueuedMessage { queued ->
            createQueuedMessageJob(session, queued).also { job ->
                activeQueueGenerations[conversationId] = QueueGenerationOwner(queued, job)
            }
        }?.start()
    }

    private fun createQueuedMessageJob(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        val transientSystemPrompt = queued.transientSystemPrompt
        val enableTools = queued.enableTools
        val interaction = queued.interaction
        // 本次请求专用的临时图片（视频通话自动抓帧）：请求结束（含失败/取消）后删除。
        val transientImageUrls = queued.transientImageUrls
        val transientImageScope = queued.transientImageScope
        // 本次新建、还没被历史引用的永久帧（手动夹帧）：**只有没落库**才允许删。
        val ownedImageUrls = queued.ownedImageUrls
        // 临时图的锚点：就是这条用户消息自己的 ID；工具多步插进来的消息不会把它顶掉。
        val transientImageAnchorMessageId = (queued.existingMessageId ?: queued.id).toString()
        // 这一轮的授权 token（视频通话）；非通话请求为 null。
        val requestToken = queued.requestToken
        // 这一轮仅本次请求可用的录音（视频通话手动录音，直传路线）。
        val transientAudio = queued.transientAudio
        val audioOwnership = transientAudio?.let {
            me.rerere.rikkahub.data.video.TransientAudioClip.RequestOwnership(it)
        }
        // 用户一开口就立即撤销旧主动闹钟。新的休眠计时必须等本轮正常回复结束后再开始，
        // 避免 AI 正在聊天时主动器到点，也避免长回复吃掉休眠间隔。
        val currentProactiveSetting = settingsStore.settingsFlow.value.proactiveMessageSetting
        if (currentProactiveSetting.enabled) {
            me.rerere.rikkahub.data.service.ProactiveMessageService.resetConversationSlot(context, conversationId.toString())
            if (currentProactiveSetting.assistantConfigs.orEmpty().any {
                    it.enabled && it.conversationId != conversationId.toString()
                } || currentProactiveSetting.allowAiScheduledWakeup &&
                me.rerere.rikkahub.data.service.ProactiveWakeupStore(context).all().isNotEmpty()
            ) {
                // Other conversations keep their appointments while this one is chatting.
                // A busy target retains its appointment and retries after ownership is released.
                me.rerere.rikkahub.data.service.ProactiveMessageService.scheduleNext(context, currentProactiveSetting)
            } else {
                me.rerere.rikkahub.data.service.ProactiveMessageService.cancel(context)
            }
        }

        val trace = LoadingDiagnostics.begin(context, "chat.send", timeoutMillis = 0)
        // 本次新建的永久帧（手动夹帧/留影）的归属状态。
        // 必须在协程外面创建：`invokeOnCompletion` 的兜底和协程体的 finally 必须看**同一份**状态
        // （旧实现把 Boolean 声明在 launch 里，兜底根本引用不到，编译就过不去；即使能引用，
        // 一个布尔也区分不了"取消时事务可能已提交"这种真正危险的中间态）。
        // 礼物/转账这种已有持久消息的请求从一开始就是 PUBLISHED：文件归历史所有，永不删除。
        val frameOwnership = FrameFileOwnership(alreadyDurable = queued.existingMessageId != null)
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            var failure: Throwable? = null
            try {
                // 录音的所有权先交给请求：在构造 provider payload 之前认领，
                // 这样"重新派发队列/暂停/撤回"都不可能把正在序列化的文件删掉。
                check(audioOwnership == null || audioOwnership.beginRequest()) {
                    "本轮录音已释放或正被其他请求使用，已停止发送"
                }
                trace.stage("initializing conversation")
                initializeConversation(conversationId, updateSelectedAssistant = false)
                val settings = settingsStore.settingsFlow.first()

                // A gift is already a durable user message. Reply through the normal queue
                // without appending a fabricated sentence or duplicating user event hooks.
                if (queued.existingMessageId == null) {
                // 读取最新状态 -> 追加用户消息 -> 落库，整体加锁。
                // 防止跟同一时刻可能在跑的标题生成/建议生成/语音通话挂断反馈互相覆盖对方刚写入的消息。
                trace.stage("waiting for conversation state lock")
                val (assistant, processedContent) = session.withStateLock {
                    val latestConversation = session.state.value
                    val assistant = settings.getAssistantOf(latestConversation)
                    val processedContent = if (interaction == null) {
                        preprocessUserInputParts(content, assistant)
                    } else {
                        content
                    }

                    val newConversation = latestConversation.copy(
                        messageNodes = latestConversation.messageNodes + UIMessage(
                            id = queued.id,
                            role = MessageRole.USER,
                            parts = processedContent,
                            annotations = listOfNotNull(interaction),
                        ).toMessageNode(),
                    )
                    trace.stage("saving user message")
                    // 归属交接就在这一句里：发布之前先标记"可能已发布"，成功之后才确认 PUBLISHED。
                    // 取消落在 Room 的挂起提交中间时状态停在 PUBLISHING —— 两条兜底路径都不会删文件。
                    saveConversationWithOwnership(
                        session = session,
                        conversationId = conversationId,
                        conversation = newConversation,
                        ownership = frameOwnership,
                        publishedMessageId = queued.id,
                    )
                    assistant to processedContent
                }

                // 触发 message_sent 事件钩子
                // 关键: 这里用 appScope.launch 提交独立协程, 而不是直接 await callEvent。
                // 原因: callEvent 内部对订阅插件的 handler 在单线程 pluginDispatcher 上串行执行,
                // supabase_memory 等插件会同步 fetch 网络请求 (最长 15s 超时)。若直接 await,
                // 用户点发送后会卡在这里直到所有插件 handler 跑完才继续走 sendMessage 后续逻辑。
                // 改为 fire-and-forget 提交到 AppScope (SupervisorJob) 上, 不挂在当前 sendMessage
                // 的 job 下 —— 这样用户连续发消息触发 session.getJob()?.cancel() 取消上一条消息 job 时,
                // 不会把这次插件同步也连累取消掉 (Supabase 记录保持完整)。
                runCatching {
                    val eventData = JsonObject(
                        mapOf(
                            "assistant_id" to JsonPrimitive(assistant.id.toString()),
                            "conversation_id" to JsonPrimitive(conversationId.toString()),
                            "message" to JsonPrimitive(processedContent.mapNotNull { part ->
                                if (part is UIMessagePart.Text) part.text else null
                            }.joinToString("\n")),
                            "role" to JsonPrimitive("user"),
                            "timestamp" to JsonPrimitive(System.currentTimeMillis())
                        )
                    )
                    appScope.launch {
                        try {
                            pluginLoader.callEvent("message_sent", eventData)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to trigger message_sent event", e)
                        }
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Failed to trigger message_sent event", e)
                }

                } else {
                    session.withStateLock {
                        check(session.state.value.currentMessages.any { it.id == queued.existingMessageId && it.role == MessageRole.USER }) {
                            "红包或转账已不在当前分支，未继续生成"
                        }
                    }
                }

                // 开始补全
                if (answer) {
                    val completed = handleMessageComplete(
                        conversationId = conversationId,
                        transientSystemPrompt = transientSystemPrompt,
                        enableTools = enableTools,
                        diagnosticTrace = trace,
                        transientImageUrls = transientImageUrls,
                        transientImageScope = transientImageScope,
                        transientImageAnchorMessageId = transientImageAnchorMessageId,
                        requestToken = requestToken,
                        videoCallUserMessageId = queued.id.takeIf { requestToken != null },
                        // 音频锚点与图片锚点是同一条用户消息（队列项 ID）；工具多步不会把它顶掉。
                        transientAudioUrl = transientAudio?.fileUrl,
                        transientAudioAnchorMessageId = transientImageAnchorMessageId,
                    )
                    if (!completed) {
                        failure = IllegalStateException("生成失败，请检查会话中的错误信息")
                        session.messageQueue.pause()
                    }
                }
                if (session.state.value.currentMessages.lastOrNull()?.getTools().orEmpty().any { it.isPending || it.canResumeExecution }) {
                    queued.reply.completeExceptionally(IllegalStateException("请先处理工具审批"))
                }

                _generationDoneFlow.emit(conversationId)

            } catch (e: CancellationException) {
                failure = e
                throw e
            } catch (e: Exception) {
                // A cancelled provider may throw a transport error during cleanup. It cannot pause a newer queue.
                if (!coroutineContext.isActive) {
                    failure = CancellationException("Generation cancelled", e)
                    throw CancellationException("Generation cancelled", e)
                }
                failure = e
                session.messageQueue.pause()
                Log.e(TAG, "sendMessage failed, conversationId=$conversationId", e)
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            } finally {
                session.messageQueue.complete(
                    queued.id,
                    result = queuedReplyText(session.state.value.currentMessages, queued.existingMessageId ?: queued.id),
                    error = failure,
                )
                activeQueueGenerations.computeIfPresent(conversationId) { _, owner ->
                    owner.takeUnless { it.message.id == queued.id }
                }
                // 请求作用域结束：删除本次专用的临时帧（自动抓帧），聊天历史里只有文字。
                // 临时帧的所有权始终在本请求，任何队列清理都不会提前删掉它们。
                VideoFrameStore.deleteTransient(context, transientImageUrls)
                // 这一轮的录音到此为止：请求结束（完成/失败/取消）后立刻删文件，
                // 不留任何"受管音频"在磁盘上；历史里本来就只有占位文字。
                audioOwnership?.release()
                // 本次**新建**的永久帧（手动夹帧）：只有"从未被历史引用"才删。
                // 落库了它就是历史的一部分（旧实现按目录删 parts 里的 Image，会把历史照片删掉）。
                // claimDeletion 是幂等的 CAS：这个 finally 和下面的 invokeOnCompletion 兜底
                // 只有一条会真正删；状态一旦进过 PUBLISHING 就永远返回 false。
                if (frameOwnership.claimDeletion()) {
                    ownedImageUrls.forEach { url -> VideoFrameStore.deleteOwned(context, url) }
                }
                // 这一轮的通话授权（礼物/留影）：请求自己决定是"正常完成"还是"被取消"。
                // complete 保留已经发出的留影许可（合法的异步留影落在请求结束之后）；
                // cancel 会撤销这次请求的全部许可。
                requestToken?.let { token ->
                    if (failure == null) {
                        VideoCallRuntime.completeTurnRequest(token)
                    } else {
                        VideoCallRuntime.cancelTurnRequest(token)
                    }
                }
                // An API failure must not leave the proactive timer cancelled forever. Do not
                // restart it for a cancelled job that was superseded by a newer user message.
                if (coroutineContext.isActive && !session.messageQueue.hasPendingOrInFlight) {
                    val proactiveSetting = settingsStore.settingsFlow.value.proactiveMessageSetting
                    if (proactiveSetting.enabled) {
                        // Other participants kept their alarms while this request ran. Start
                        // this conversation's full idle interval only after its queue is done.
                        me.rerere.rikkahub.data.service.ProactiveMessageService.resetConversationSlot(context, conversationId.toString())
                        me.rerere.rikkahub.data.service.ProactiveMessageService.resetTimer(context, proactiveSetting)
                    }
                }
            }
        }
        job.invokeOnCompletion { cause ->
            trace.close()
            // A lazy request can be cancelled before its body (and finally block) ever starts.
            session.messageQueue.complete(queued.id,
                result = queuedReplyText(session.state.value.currentMessages, queued.existingMessageId ?: queued.id), error = cause)
            activeQueueGenerations.computeIfPresent(conversationId) { _, owner ->
                    owner.takeUnless { it.message.id == queued.id }
                }
            // 连协程体都没跑起来就被取消：临时帧和授权都必须在这里收尾。
            if (cause != null) {
                VideoFrameStore.deleteTransient(context, transientImageUrls)
                // 未启动只释放 QUEUED；已启动只凭本 job 的认领释放，不能删其他请求的录音。
                audioOwnership?.release()
                if (frameOwnership.claimDeletion()) {
                    ownedImageUrls.forEach { url -> VideoFrameStore.deleteOwned(context, url) }
                }
                requestToken?.let { VideoCallRuntime.cancelTurnRequest(it) }
            }
        }
        return job
    }

    /** Avatar actions are genuine user events and follow the normal send/cancel/notification lifecycle. */
    fun sendAvatarInteraction(conversationId: Uuid, targetMessage: UIMessage) =
        interactionCoordinator.sendAvatarInteraction(conversationId, targetMessage)

    // ---- 添加主动消息 ----

    fun addProactiveMessage(conversationId: Uuid, aiMessage: UIMessage) {
        launchWithConversationReference(conversationId) {
            try {
                appendProactiveAiMessageUnderLock(conversationId, aiMessage)
            } catch (e: Exception) {
                Log.e(TAG, "addProactiveMessage failed, conversationId=$conversationId", e)
            }
        }
    }

    /**
     * 在 saveMutex 保护下，把 AI 主动生成的消息追加到对话并落库。
     * 与 sendMessage/regenerate 等共享同一把锁，避免 read-modify-write 竞态导致消息被覆盖。
     */
    private suspend fun appendProactiveAiMessageUnderLock(
        conversationId: Uuid,
        aiMessage: UIMessage,
    ) {
        val session = getOrCreateSession(conversationId)

        // 等待当前正在进行的生成任务（如果有）先完全结束，再追加这条主动消息。
        // 消息现已按 UUID 写回；等待仍确保主动回复位于本轮工具续接之后。
        session.getJob()?.let { job ->
            if (job.isActive) {
                Log.i(TAG, "appendProactiveAiMessageUnderLock: waiting for ongoing generation to finish, conversationId=$conversationId")
                job.join()
            }
        }

        session.withStateLock {
            // 优先从数据库读取完整对话，避免 session 被 idle 清除后用空对话覆盖数据库已有数据
            val currentConversation = conversationRepo.getConversationById(conversationId)
                ?: session.state.value
            val updated = currentConversation.copy(
                messageNodes = currentConversation.messageNodes + aiMessage.toMessageNode(),
                updateAt = java.time.Instant.now()
            )
            updateConversation(conversationId, updated)
            saveConversationLocked(conversationId, updated)
        }
    }

    /** Isolated background output enters chat only at this guarded, non-suspending publication point.
     * Both deletion and normal user writes share the session lock. Never recreate a deleted window. */
    internal suspend fun publishProactiveMessages(
        conversationId: Uuid,
        assistantId: Uuid,
        anchorId: Uuid,
        task: me.rerere.rikkahub.data.service.ProactiveTask,
        messages: List<UIMessage>,
        executor: me.rerere.rikkahub.data.service.ProactiveTaskExecutor,
    ): Boolean {
        val session = getOrCreateSession(conversationId)
        return session.withStateLock {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (conversationId in deletedConversationIds || session.isDeleted) return@withStateLock false
            if (!conversationRepo.existsConversationById(conversationId)) return@withStateLock false
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            session.requireActive()
            if (conversationId in deletedConversationIds) return@withStateLock false
            val current = session.state.value
            if (current.assistantId != assistantId ||
                current.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id != anchorId ||
                messages.isEmpty() || messages.any { message ->
                    message.role != MessageRole.ASSISTANT || current.messageNodes.any { node ->
                        node.messages.any { it.id == message.id }
                    }
                }
            ) return@withStateLock false
            if (!executor.prepareCommit(task, messages)) return@withStateLock false
            val committing = executor.startCommit(task) ?: return@withStateLock false
            val payload = executor.decodeCommitPayload(committing) ?: run {
                executor.store.abandonCommit(task.taskId,
                    me.rerere.rikkahub.data.service.ProactiveTaskStatus.Failed, "invalid_commit_payload")
                return@withStateLock false
            }
            val saved = withContext(NonCancellable) {
                conversationRepo.appendProactiveMessagesIdempotently(conversationId, assistantId, anchorId, payload)
            } ?: run {
                // A null result is a definitive guard rejection, not an ambiguous database failure.
                // Retaining CommitPending here used to lock out every subsequent proactive task.
                executor.store.abandonCommit(task.taskId,
                    me.rerere.rikkahub.data.service.ProactiveTaskStatus.Interrupted, "publication_guard_changed")
                return@withStateLock false
            }
            // Room already committed. A ledger receipt failure must not hide the saved message or
            // cause regeneration: keep its payload for exact-ID reconciliation on the next check.
            runCatching { executor.markCommitSent(committing) }.onFailure {
                Log.w(TAG, "Proactive message committed; ledger receipt needs reconciliation", it)
            }
            updateConversation(conversationId, saved)
            session.persistedConversation = saved
            session.messageJournal.invalidate()
            true
        }
    }

    // ---- 语音通话被拒接通知 ----

    fun notifyVoiceCallDeclined(conversationId: Uuid) =
        auxiliaryCoordinator.notifyVoiceCallDeclined(conversationId)

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        try {
            session.state.value.requireEditableMessage(message.id)
        } catch (e: IllegalStateException) {
            addError(e, conversationId)
            return
        }
        val (previousJob, barrier) = synchronized(session) {
            val barrier = session.blockQueueDispatch()
            session.messageQueue.pause()
            val previous = session.getJob()
            previous?.cancel()
            previous to barrier
        }
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                previousJob?.join()
                session.awaitQueueDispatchUnblocked()
                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    session.withStateLock {
                        val conversation = session.state.value
                        conversation.requireEditableMessage(message.id)
                        val node = conversation.getMessageNodeByMessage(message)
                        val indexAt = conversation.messageNodes.indexOf(node)
                        val newConversation = conversation.copy(
                            messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                        )
                        saveConversationLocked(conversationId, newConversation)
                    }
                    if (!handleMessageComplete(conversationId, retryFailedRecall = true)) session.messageQueue.pause()
                } else {
                    if (regenerateAssistantMsg) {
                        val conversation = session.state.value
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        if (!handleMessageComplete(conversationId, messageRange = 0..<nodeIndex, retryFailedRecall = true)) session.messageQueue.pause()
                    } else {
                        session.withStateLock {
                            saveConversationLocked(conversationId, session.state.value)
                        }
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                session.messageQueue.pause()
                Log.e(TAG, "regenerateAtMessage failed, conversationId=$conversationId", e)
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        try {
            session.setJob(job)
        } finally {
            // The registered lazy job blocks new sends; only older stop barriers must be awaited.
            barrier.close()
        }
        job.start()
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()

        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                afterPreviousGeneration(previousJob) {
                    session.awaitQueueDispatchUnblocked()
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    val updatedNodes = session.withStateLock {
                        val conversation = session.state.value
                        if (conversation.messagesForGeneration().none { message ->
                                message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                            }) return@withStateLock null
                        val nodes = conversation.messageNodes.map { node ->
                            if (node.id in conversation.activeCompressedMessageNodeIds) return@map node
                            node.copy(
                                messages = node.messages.map { msg ->
                                    msg.copy(
                                        parts = msg.parts.map { part ->
                                            if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                                                part.copy(approvalState = newApprovalState)
                                            } else part
                                        }
                                    )
                                }
                            )
                        }
                        saveConversationLocked(conversationId, conversation.copy(messageNodes = nodes))
                        nodes
                    } ?: return@afterPreviousGeneration

                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { it is UIMessagePart.Tool && it.isPending }
                    }
                    if (!hasPendingTools && !handleMessageComplete(conversationId)) session.messageQueue.pause()

                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                session.messageQueue.pause()
                Log.e(TAG, "handleToolApproval failed, conversationId=$conversationId, toolCallId=$toolCallId", e)
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
        job.start()
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        transientSystemPrompt: String? = null,
        enableTools: Boolean = true,
        diagnosticTrace: TaskWatchdog.Trace? = null,
        retryFailedRecall: Boolean = false,
        transientImageUrls: List<String> = emptyList(),
        transientImageScope: me.rerere.rikkahub.data.video.VideoFrameRequestScope? = null,
        transientImageAnchorMessageId: String? = null,
        requestToken: String? = null,
        videoCallUserMessageId: Uuid? = null,
        /** 这一轮仅本次请求可用的录音（视频通话直传）；历史里永远不存 audio part。 */
        transientAudioUrl: String? = null,
        transientAudioAnchorMessageId: String? = null,
    ): Boolean {
        diagnosticTrace?.stage("reading generation settings")
        val initialConversation = bindConversationConfig(conversationId)
        val settings = settingsStore.settingsFlow.first().withConversationConfig(initialConversation)
        val assistant = settings.getAssistantOf(initialConversation)
        val model = settings.getChatModelOf(initialConversation) ?: error("请先为会话选择可用的聊天模型")

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        // session 需要在 runCatching 外声明，以便 .onSuccess 中也能访问 saveMutex
        val session = getOrCreateSession(conversationId)
        val activityStore = me.rerere.rikkahub.data.service.ProactiveActivityStore(
            context, assistant.id.toString(), conversationId.toString(),
        )
        var activitySnapshot = emptyList<me.rerere.rikkahub.data.service.ProactiveActivity>()
        var activityUserId: String? = null
        var activityTransformer: me.rerere.rikkahub.data.service.ProactiveActivityTransformer? = null
        var reactionTurn: ReactionContextTurn? = null
        val rawArchiveOwner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val rawArchiveMessageIds = mutableSetOf<String>()
        val hookConfig = settings.sereinHook
        fun hookIsCurrent(): Boolean = settingsStore.settingsFlow.value.let { live ->
            live.sereinHook == hookConfig && hookConfig.usableBy(assistant.id.toString()) &&
                live.assistants.any { it.id == assistant.id } && !session.isDeleted
        }
        var hookTransformer: me.rerere.rikkahub.data.serein.SereinRecallTransformer? = null
        var hookParticipating = false
        var hookUser: UIMessage? = null
        var hookRecall: me.rerere.rikkahub.data.serein.HookRecall? = null
        var hookElapsedMs = 0L
        var completedGenerationReplyId: Uuid? = null
        val hookObservedAt = System.currentTimeMillis()
        val hookReceipt = "orangechat:${Uuid.random()}"
        suspend fun reportHook(status: String, delivered: List<String> = emptyList()) {
            val user = hookUser ?: return
            if (!hookParticipating || !hookIsCurrent()) return
            try {
                sereinHookRepository.report(hookConfig, conversationId.toString(), hookReceipt, user,
                    hookRecall, delivered, status, hookElapsedMs, hookObservedAt)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Log.w(TAG, "Could not queue Serein observation") }
        }

        val generationResult = runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (settings.enableWebSearch || mcpManager.getAllAvailableTools(assistant).isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            diagnosticTrace?.stage("validating message attachments")
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value
            val messagesForGeneration = conversation.messagesForGeneration(messageRange)
            val regenerationNodeId = messageRange?.let { conversation.messageNodes.getOrNull(it.endInclusive + 1)?.id }
            val requestMessageIds = messagesForGeneration.map { it.id }.toSet()
            var regenerationMessageId: Uuid? = null
            val latestUserMessage = messagesForGeneration.lastOrNull { it.role == MessageRole.USER }
            diagnosticTrace?.stage("collecting extra information")
            val extraInfoContext = kotlinx.coroutines.coroutineScope {
                val hookPending = async {
                    if (latestUserMessage == null || (messageRange != null && !retryFailedRecall) || transientSystemPrompt != null || !hookIsCurrent()) null
                    else {
                        hookParticipating = true
                        hookUser = latestUserMessage
                        val started = System.nanoTime()
                        try {
                            sereinHookRepository.prepare(hookConfig, assistant.id.toString(), conversationId.toString(),
                                latestUserMessage, messagesForGeneration.lastOrNull()?.role == MessageRole.USER, ::hookIsCurrent,
                                retryFailed = retryFailedRecall)
                                .also { hookRecall = it }
                        } finally { hookElapsedMs = (System.nanoTime() - started) / 1_000_000 }
                    }
                }
                val extra = if (latestUserMessage != null && messageRange == null) {
                    extraInfoRequestCache.resolve(
                        conversationId = conversationId,
                        assistantId = assistant.id,
                        user = latestUserMessage,
                        option = settings.systemToolsSetting,
                        allowCollection = messagesForGeneration.lastOrNull()?.role == MessageRole.USER,
                    ) {
                        session.processingStatus.value = "正在读取额外信息…"
                        try {
                            extraInfoInjectionCollector.collect(
                                settings = settings,
                                assistantId = assistant.id.toString(),
                                queryText = latestUserMessage.toText().take(300),
                            )
                        } finally {
                            session.processingStatus.value = null
                        }
                    }
                } else {
                    null
                }
                hookPending.await()?.let { recalled ->
                    latestUserMessage?.let {
                        hookTransformer = me.rerere.rikkahub.data.serein.SereinRecallTransformer(it.id, recalled, ::hookIsCurrent)
                    }
                }
                extra
            }
            // Every historical user message keeps the snapshot it was first sent with, so the
            // request prefix stays byte-identical across turns and the provider cache survives.
            // The latest message uses only this turn's resolved snapshot.
            val extraInfoSnapshots = extraInfoRequestCache.snapshots(
                conversationId = conversationId,
                messages = messagesForGeneration,
                option = settings.systemToolsSetting,
            ).let { stored ->
                if (latestUserMessage == null || messageRange != null) stored
                else stored - latestUserMessage.id + listOfNotNull(
                    extraInfoContext?.takeIf { it.isNotBlank() }?.let { latestUserMessage.id to it }
                )
            }
            activitySnapshot = runCatching { activityStore.snapshot() }.getOrElse {
                Log.w(TAG, "Could not read proactive activity records", it)
                emptyList()
            }
            activityUserId = messagesForGeneration.lastOrNull { it.role == MessageRole.USER }
                ?.id?.toString()?.takeIf { messageRange == null }
            val activityCutoff = messagesForGeneration.lastOrNull { it.id.toString() == activityUserId }
                ?.createdAt?.toInstant(TimeZone.currentSystemDefault())?.toEpochMilliseconds()
            activitySnapshot = activitySnapshot.filter {
                it.claimedByUserId != null || (activityCutoff != null && it.timestamp <= activityCutoff)
            }
            activityTransformer = me.rerere.rikkahub.data.service.ProactiveActivityTransformer(
                activitySnapshot, activityUserId,
            )

            // start generating
            diagnosticTrace?.stage("building generation tools and context")
            val availableTools = if (enableTools) buildAvailableTools(
                settings = settings, assistant = assistant, conversationId = conversationId,
                conversation = conversation, allowAppUsage = true,
                // Scope LocalMoments budget to the user message this request answers; an older
                // assistant regeneration must not borrow a newer user turn's budget.
                momentsTurnAnchorUserId = latestUserMessage?.id,
            ) else emptyList()
            val paymentToolsUnavailableReason = me.rerere.rikkahub.data.interaction.paymentToolUnavailableReason(
                model, model.findProvider(settings.providers))
            val paymentToolsAvailable = paymentToolsUnavailableReason == null &&
                availableTools.any { it.name == "respond_to_payment" }
            if (enableTools && paymentToolsUnavailableReason != null && conversation.currentMessages.any { message ->
                message.annotations.any { it is me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction &&
                    it.kind in setOf("red_packet", "transfer") && it.status == "pending" }
            }) addError(IllegalStateException(paymentToolsUnavailableReason), conversationId, title = "红包/转账工具不可用")
            val listenTurn = latestUserMessage?.let { user ->
                org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.listen.ListenRepository>()
                    .captureTurn(conversationId.toString(), assistant.id.toString(), user.id)
            }
            generationHandler.generateText(
                listenRequestContext = listenTurn?.let { it::attach },
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = messagesForGeneration,
                conversationContextSummary = conversation.summaryForGeneration(),
                assistant = assistant,
                conversationSystemPrompt = listOfNotNull(
                    conversation.customSystemPrompt?.takeIf { it.isNotBlank() },
                    // 视频通话轮：这一轮的通话上下文走 appendedSystemContext（**追加**在主文后面），
                    // 不再混进"对话级 prompt"里被 allowConversationSystemPrompt 开关整段丢掉。
                    transientSystemPrompt?.takeIf { it.isNotBlank() && requestToken == null },
                ).joinToString("\n\n").ifBlank { null },
                appendedSystemContext = transientSystemPrompt?.takeIf { it.isNotBlank() && requestToken != null },
                workspaceCwd = conversation.workspaceCwd,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                    add(me.rerere.rikkahub.data.reading.ReadContextTransformer(
                        org.koin.core.context.GlobalContext.get().get(), conversationId.toString(), assistant.id.toString()))
                    add(requireNotNull(activityTransformer))
                    add(ExtraInfoRequestTransformer(extraInfoSnapshots))
                    hookTransformer?.let { add(it) }
                    add(object : me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer {
                        override suspend fun transform(ctx: me.rerere.rikkahub.data.ai.transformers.TransformerContext, messages: List<UIMessage>): List<UIMessage> {
                            val current = session.state.value
                            val prompt = me.rerere.rikkahub.data.interaction.interactionStatePrompt(
                                current.currentMessages, assistant.pretendRich,
                                VoiceCallService.activeConversationId.value == conversationId.toString() &&
                                    VoiceCallService.activeUiState.value.status in setOf(
                                        me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Listening,
                                        me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Processing,
                                        me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Speaking),
                                paymentToolsAvailable = paymentToolsAvailable,
                                paymentToolsUnavailableReason = paymentToolsUnavailableReason,
                                paymentMessageIds = requestMessageIds.takeIf { messageRange != null })
                            val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
                            return if (systemIndex < 0) listOf(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(prompt)))) + messages
                            else messages.mapIndexed { index, message ->
                                if (index == systemIndex) message.copy(parts = message.parts + UIMessagePart.Text(prompt)) else message
                            }
                        }
                    })
                },
                outputTransformers = outputTransformers,
                tools = availableTools,
                pluginPromptInjections = buildList {
                    diagnosticTrace?.stage("loading plugin prompt injections")
                    addAll(pluginToolProvider.getPluginPromptInjections())
                    settings.displaySetting.buildAnniversaryPrompt()?.let(::add)
                },
                conversationId = conversationId.toString(),
                reactionSourceMessages = conversation.currentMessages.take(
                    conversation.currentMessages.indexOfLast { it.id in requestMessageIds } + 1,
                ),
                // 视频通话自动抓帧：只作为本次请求的临时图片输入，不写聊天历史。
                transientImageUrls = transientImageUrls,
                transientImageScope = transientImageScope,
                transientImageAnchorMessageId = transientImageAnchorMessageId,
                // 视频通话手动录音：只作为本次请求的临时音频输入，不写聊天历史。
                transientAudioUrl = transientAudioUrl,
                transientAudioAnchorMessageId = transientAudioAnchorMessageId,
                requestToken = requestToken,
                onReactionContextPrepared = { reactionTurn = it },
                onGenerationCompleted = { completedGenerationReplyId = it },
                onToolCheckpoint = { checkpointMessages ->
                    diagnosticTrace?.stage("saving tool checkpoint")
                    session.withStateLock {
                        if (session.isDeleted || session.getJob() !== rawArchiveOwner) return@withStateLock
                        if (regenerationNodeId != null && regenerationMessageId == null) {
                            regenerationMessageId = checkpointMessages.firstOrNull { it.id !in requestMessageIds }?.id
                        }
                        val mutation = session.state.value.generationMessageMutation(
                            me.rerere.rikkahub.data.interaction.associateVideoCallReplies(
                                checkpointMessages, requestMessageIds, conversationId, videoCallUserMessageId), buildMap {
                            if (regenerationNodeId != null) regenerationMessageId?.let { put(it, regenerationNodeId) }
                        })
                        applyMessageMutationLocked(conversationId, mutation)
                        saveConversationLocked(conversationId, session.state.value)
                    }
                },
            ).onStart {
                diagnosticTrace?.stage("collecting generation pipeline")
            }.onCompletion {
                // 取消 Live Update 通知
                cancelLiveUpdateNotification(conversationId)

                // 可能被取消了，或者意外结束，兜底更新
                withContext(NonCancellable) {
                    if (!session.isDeleted) session.withStateLock {
                        val current = session.state.value
                        val archived = current.activeCompressedMessageNodeIds
                        val mutation = current.generationMessageMutation(current.messageNodes
                            .filterNot { it.id in archived }.flatMap { it.messages }.map { it.finishReasoning() })
                        applyMessageMutationLocked(conversationId, mutation)
                        updateConversation(conversationId, session.state.value.copy(updateAt = Instant.now()))
                    }
                }

                // Show notification if app is not in foreground
                if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            }.collect { chunk ->
                diagnosticTrace?.stage("applying generation update")
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        chunk.messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.let {
                            rawArchiveMessageIds += it.id.toString()
                        }
                        if (regenerationNodeId != null && regenerationMessageId == null) {
                            regenerationMessageId = chunk.messages.firstOrNull { it.id !in requestMessageIds }?.id
                        }
                        session.withStateLock {
                            val mutation = session.state.value.generationMessageMutation(
                                me.rerere.rikkahub.data.interaction.associateVideoCallReplies(
                                    chunk.messages, requestMessageIds, conversationId, videoCallUserMessageId), buildMap {
                                if (regenerationNodeId != null) regenerationMessageId?.let { put(it, regenerationNodeId) }
                            })
                            applyMessageMutationLocked(conversationId, mutation)
                        }

                        // 如果应用不在前台，发送 Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            sendLiveUpdateNotification(conversationId, chunk.messages, senderName)
                        }
                    }
                }
            }
        }.onFailure {
            withContext(NonCancellable) { reportHook(if (it is CancellationException) "interrupted" else "failed") }
            // 取消 Live Update 通知
            cancelLiveUpdateNotification(conversationId)

            // Freeze only already-persisted partial originals. Never archive request injections
            // or let a cancelled writer overwrite a newer session checkpoint.
            withContext(NonCancellable) {
                runCatching {
                    session.withStateLock {
                        if (!session.isDeleted && session.getJob() === rawArchiveOwner) {
                            conversationRepo.interruptPendingRawOriginals(conversationId, rawArchiveMessageIds)
                        }
                    }
                }.onFailure { Log.w(TAG, "Could not finalize local original-text archive") }
            }

            if (it is CancellationException) throw it
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val finalConversation = session.withStateLock {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val latest = getConversationFlow(conversationId).value
                check(latest.assistantId == assistant.id) { "助手已变更，请重新发送" }
                val reply = latest.currentMessages.lastOrNull()
                val receipted = reply?.let {
                    reactionTurn?.finish(it, latest.currentMessages, latest.id.toString(), latest.assistantId.toString())
                } ?: reply
                if (receipted != null && receipted != reply) {
                    applyMessageMutationLocked(conversationId, latest.generationMessageMutation(listOf(receipted)))
                }
                try {
                    saveConversationLocked(conversationId, session.state.value)
                } catch (error: Exception) {
                    if (receipted != null && receipted != reply && reply != null) {
                        applyMessageMutationLocked(conversationId, session.state.value.generationMessageMutation(listOf(reply)))
                    }
                    throw error
                }
                session.state.value
            }
            val completedReply = finalConversation.currentMessages.lastOrNull()
            if (hookParticipating && hookIsCurrent() && completedReply != null) {
                try {
                    val status = sereinSuccessfulRequestStatus(completedGenerationReplyId, completedReply)
                    if (status == "completed") {
                        sereinHookRepository.acknowledge(hookConfig, conversationId.toString(), completedReply,
                            hookTransformer?.deliveredIds.orEmpty())
                    }
                    val valid = completedReply.isSereinCompletedReply()
                    // Completion is an observed model event; ACK/upload eligibility remains stricter.
                    reportHook(status, if (status == "completed" && valid) hookTransformer?.deliveredIds.orEmpty() else emptyList())
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { Log.w(TAG, "Could not record Serein recall delivery") }
            }
            if (activityUserId != null && completedReply?.role == MessageRole.ASSISTANT &&
                completedReply.finishedAt != null && completedReply.getTools().isEmpty()
            ) {
                runCatching {
                    activityStore.claim(
                        activityTransformer?.deliveredPendingIds.orEmpty(),
                        activityUserId,
                    )
                }.onFailure { Log.w(TAG, "Could not acknowledge proactive activity records", it) }
            }

            // 自动唤起网易云音乐：扫描刚完成的 assistant 文本中的 orpheus:// scheme
            try {
                val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                val messageText = lastAssistantMessage?.toText() ?: ""
                launchNeteaseCloudMusic(messageText)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch NetEase Cloud Music", e)
            }

            // 检测并执行 [JUMP] 标记 - 正常聊天中的切屏（AI总是可以跳转，不需要开关）
            try {
                val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                val rawText = lastAssistantMessage?.parts?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("\n") { it.text } ?: ""
                if (rawText.contains("[JUMP]", ignoreCase = true)) {
                    // Merge into the live state under its lock: an older reply snapshot must not
                    // overwrite a payment receipt or hang-up that arrived during generation.
                    if (lastAssistantMessage != null) session.withStateLock {
                        val current = session.state.value
                        val cleanedConversation = current.copy(messageNodes = current.messageNodes.map { node ->
                            node.copy(messages = node.messages.map { msg ->
                                if (msg.id == lastAssistantMessage.id) msg.withoutJumpControl() else msg
                            })
                        })
                        saveConversationLocked(conversationId, cleanedConversation)
                    }
                    // 拉起 RouteActivity 切屏（正常聊天中不受时间阈值限制）
                    val jumpIntent = Intent(context, RouteActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                        putExtra("conversationId", conversationId.toString())
                    }
                    context.startActivity(jumpIntent)
                    Log.d(TAG, "[JUMP] detected in normal chat, force jump to conversation $conversationId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to handle [JUMP] in normal chat", e)
            }

            // 触发 message_received 事件钩子
            // 同 message_sent: 用 appScope.launch 提交独立协程, 不阻塞 handleMessageComplete
            // 后续的标题生成/建议生成等流程, 也不随上一条消息的 job 取消而中断。
            runCatching {
                val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                val eventData = JsonObject(
                    mapOf(
                        "assistant_id" to JsonPrimitive(assistant.id.toString()),
                        "conversation_id" to JsonPrimitive(conversationId.toString()),
                        "message" to JsonPrimitive(lastAssistantMessage?.toText() ?: ""),
                        "role" to JsonPrimitive("assistant"),
                        "timestamp" to JsonPrimitive(System.currentTimeMillis())
                    )
                )
                appScope.launch {
                    try {
                        pluginLoader.callEvent("message_received", eventData)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to trigger message_received event", e)
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to trigger message_received event", e)
            }

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }

        }
        return generationResult.isSuccess
    }

    internal fun buildProactiveWakeupTools(
        settings: me.rerere.rikkahub.data.datastore.Settings,
        assistant: Assistant,
        conversation: Conversation,
    ): List<Tool> = toolBuilder.buildProactiveWakeupTools(settings, assistant, conversation)

    internal suspend fun buildProactiveMemoryTools(assistant: Assistant): List<Tool> =
        toolBuilder.buildProactiveMemoryTools(assistant)

    internal suspend fun buildAvailableTools(
        settings: me.rerere.rikkahub.data.datastore.Settings,
        assistant: Assistant,
        conversationId: Uuid,
        conversation: Conversation,
        allowAppUsage: Boolean,
        allowImageGeneration: Boolean = false,
        headless: Boolean = false,
        momentsTurnAnchorUserId: Uuid? = null,
    ): List<Tool> = toolBuilder.buildAvailableTools(
        settings = settings,
        assistant = assistant,
        conversationId = conversationId,
        conversation = conversation,
        allowAppUsage = allowAppUsage,
        allowImageGeneration = allowImageGeneration,
        headless = headless,
        momentsTurnAnchorUserId = momentsTurnAnchorUserId,
    )

    // ---- 检查无效消息 ----

    private suspend fun checkInvalidMessages(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        session.withStateLock {
            val current = session.state.value
            val recovered = current.withRecoveredToolHistory(JsonInstant)
            if (recovered != current) saveConversationLocked(conversationId, recovered)
        }
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        if (tool.hasDurableToolEvidence) return tool.recoverInterruptedExecution(JsonInstant)
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        ).withExecutionCheckpoint("cancelled")
    }

    // ---- 生成标题 / 建议 / 压缩 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false,
    ) = auxiliaryCoordinator.generateTitle(conversationId, conversation, force)

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) =
        auxiliaryCoordinator.generateSuggestion(conversationId, conversation)

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32,
        mergeWithPrevious: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): Result<Unit> = auxiliaryCoordinator.compressConversation(
        conversationId = conversationId,
        conversation = conversation,
        additionalPrompt = additionalPrompt,
        targetTokens = targetTokens,
        keepRecentMessages = keepRecentMessages,
        mergeWithPrevious = mergeWithPrevious,
        onProgress = onProgress,
    )

    suspend fun mergeExistingSummaries(
        conversationId: Uuid,
        revision: Long,
        summaries: List<ConversationSummary>,
        enabled: Boolean,
        additionalPrompt: String,
        targetTokens: Int,
    ): List<ConversationSummary> = auxiliaryCoordinator.mergeExistingSummaries(
        conversationId = conversationId,
        revision = revision,
        summaries = summaries,
        enabled = enabled,
        additionalPrompt = additionalPrompt,
        targetTokens = targetTokens,
    )

    suspend fun saveCompressionSettings(
        conversationId: Uuid,
        revision: Long,
        summaries: List<ConversationSummary>,
        enabled: Boolean,
        undo: CompressionUndoMode? = null,
    ) = auxiliaryCoordinator.saveCompressionSettings(
        conversationId = conversationId,
        revision = revision,
        summaries = summaries,
        enabled = enabled,
        undo = undo,
    )

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        notificationHelper.sendGenerationDoneNotification(
            conversationId = conversationId,
            senderName = senderName,
            conversation = getConversationFlow(conversationId).value,
        )
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String,
    ) {
        notificationHelper.sendLiveUpdateNotification(conversationId, messages, senderName)
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        notificationHelper.cancelLiveUpdateNotification(conversationId)
    }

    // ---- 对话状态更新 ----

    internal fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        attachmentReferenceGuard.beginWrite().use {
            if (conversation.id != conversationId) return
            val session = getOrCreateSession(conversationId)
            session.requireActive()
            check(conversation.compressionRevision >= session.state.value.compressionRevision) {
                "摘要已变更，请重新打开会话后操作。"
            }
            // Stream/title snapshots may predate a settings edit. Config has its own writer.
            val latest = session.state.value
            val incoming = if (latest.messageNodesLoaded) conversation.copy(config = latest.config) else conversation
            val cleaned = if (incoming.messageNodes === latest.messageNodes) incoming else
                conversationRepo.withoutDeletedAttachments(incoming)
            if (cleaned.messageNodes !== session.state.value.messageNodes) {
                session.messageJournal.invalidate()
            }
            session.publishMessageSnapshot(cleaned)
        }
    }

    /** Caller holds the pinned state lock; unknown full-history edits keep the conservative path. */
    internal fun applyMessageMutationLocked(conversationId: Uuid, mutation: ConversationMessageMutation) {
        attachmentReferenceGuard.beginWrite().use {
            val session = getOrCreateSession(conversationId)
            session.requireActive()
            require(mutation.previous.id == conversationId && mutation.conversation.id == conversationId)
            check(session.state.value === mutation.previous) { "Message mutation no longer matches current state" }
            // Filter only changed nodes on this hot path; maintenance separately cleans untouched
            // active history. A delayed stream chunk cannot reintroduce a manually deleted file.
            var nodes: MutableList<me.rerere.rikkahub.data.model.MessageNode>? = null
            val upserts = mutation.changes.upserts.map { (index, node) ->
                val messages = filesManager.withoutDeletedAttachments(node.messages)
                val cleaned = if (messages === node.messages) node else node.copy(messages = messages)
                if (cleaned !== node) {
                    if (nodes == null) nodes = mutation.conversation.messageNodes.toMutableList()
                    nodes!![index] = cleaned
                }
                index to cleaned
            }
            val cleaned = nodes?.let { mutation.conversation.copy(messageNodes = it) } ?: mutation.conversation
            val safeMutation = if (cleaned === mutation.conversation) mutation else ConversationMessageMutation(
                mutation.previous, cleaned, ConversationNodeChanges(upserts, mutation.changes.deletions),
            )
            session.messageJournal.record(safeMutation, session.persistedConversation)
            session.state.value = cleaned
        }
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        val session = getOrCreateSession(conversationId)
        session.withStateLock {
            conversationRepo.updateConversationFolderId(conversationId, folderId)
            session.state.value = session.state.value.copy(folderId = folderId)
            session.persistedConversation = session.persistedConversation?.copy(folderId = folderId)
        }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        session.withStateLock {
            val pinned = conversationRepo.togglePinStatus(conversationId) ?: return@withStateLock
            session.state.value = session.state.value.copy(isPinned = pinned)
            session.persistedConversation = session.persistedConversation?.copy(isPinned = pinned)
        }
    }

    suspend fun updateTitle(conversationId: Uuid, title: String) {
        val session = getOrCreateSession(conversationId)
        session.withStateLock {
            if (conversationRepo.updateGeneratedTitle(conversationId, title)) {
                session.state.value = session.state.value.copy(title = title)
                session.persistedConversation = session.persistedConversation?.copy(title = title)
            } else {
                saveConversationLocked(conversationId, session.state.value.copy(title = title))
            }
        }
    }

    /** Close the lifecycle before cancellation; finally/late callbacks cannot recreate this id. */
    suspend fun deleteConversation(
        conversation: Conversation,
        retainAttachments: Boolean = false,
    ): Conversation = withContext(NonCancellable) {
        conversationLifecycleLocks.getOrPut(conversation.id) { Mutex() }.withLock {
            val session = getOrCreateSession(conversation.id)
            deletedConversationIds.add(conversation.id)
            LocalMomentsChatSelectionStore.clear(conversation.id.toString())
            localMomentsTurnBudgets.remove(conversation.id.toString())
            var deletedSnapshot = conversation
            try {
                session.deleteAndAwait {
                    deletedSnapshot = conversationRepo.deleteConversation(conversation, retainAttachments)
                }
            } catch (error: Exception) {
                // If the database delete did not commit, keep the history usable/retryable.
                // An uncertain DB result stays closed rather than accepting late writers.
                if (runCatching { conversationRepo.existsConversationById(conversation.id) }.getOrDefault(false)) {
                    deletedConversationIds.remove(conversation.id)
                    session.reopenAfterFailedDelete()
                }
                throw error
            }
            cancelLiveUpdateNotification(conversation.id)
            deletedSnapshot
        }
    }

    suspend fun deleteConversationsOfAssistant(assistantId: Uuid) {
        val persisted = conversationRepo.getConversationsOfAssistant(assistantId).first()
        val active = sessions.values.map { it.state.value }.filter { it.assistantId == assistantId }
        (persisted + active).distinctBy { it.id }.forEach { deleteConversation(it) }
    }

    suspend fun restoreConversation(conversation: Conversation) = withContext(NonCancellable) {
        conversationLifecycleLocks.getOrPut(conversation.id) { Mutex() }.withLock {
            val session = getOrCreateSession(conversation.id)
            session.restore { conversationRepo.insertConversation(conversation) }
            deletedConversationIds.remove(conversation.id)
        }
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    suspend fun deleteManagedAttachments(ids: Set<Long>): Int =
        attachmentMaintenance.deleteManagedAttachments(ids)

    suspend fun cleanInvalidAttachments(): Pair<Int, Int> =
        attachmentMaintenance.cleanInvalidAttachments()

    suspend fun findUnreferencedAttachments(): Set<Long> =
        attachmentMaintenance.findUnreferencedAttachments()

    suspend fun deleteUnreferencedAttachments(ids: Set<Long>): Int =
        attachmentMaintenance.deleteUnreferencedAttachments(ids)

    data class DuplicateAttachmentSummary(val files: Int, val bytes: Long)
    data class DuplicateMergeResult(val removed: Int, val freedBytes: Long, val kept: Int, val rewrittenNodes: Int)

    suspend fun previewDuplicateAttachments(): DuplicateAttachmentSummary =
        attachmentMaintenance.previewDuplicateAttachments()

    suspend fun mergeDuplicateAttachments(): DuplicateMergeResult =
        attachmentMaintenance.mergeDuplicateAttachments()

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        require(conversation.id == conversationId)
        getOrCreateSession(conversationId).withStateLock {
            saveConversationLocked(conversationId, conversation)
        }
    }

    /**
     * 在**已持有 saveMutex** 的前提下保存对话，并同步推进永久帧文件的归属状态。
     *
     * 这是"文件所有权"和"数据库取消"之间唯一被允许的交接点：
     *
     * 1. [saveConversationLocked] 第一件事是 `updateConversation()`，也就是把新消息发布进
     *    内存里的会话状态（UI 立刻可见），**然后**才是 Room 的挂起提交。所以"发布"和
     *    "提交"之间天然有窗口，取消可以落在里面。
     * 2. 因此进入保存之前先 [FrameFileOwnership.markPublishing]：从这一刻起这些文件
     *    就当"可能已经被引用"处理，任何取消路径都不许删。
     * 3. 正常返回 → [FrameFileOwnership.markPublished]（历史真的引用了它）。
     * 4. 抛出普通异常 → 事务一定回滚了，但消息**可能**已经被发布进内存状态（那样它迟早会
     *    被别的保存写进数据库）。这里直接查会话状态里有没有这条消息来决定能不能删文件，
     *    而不是猜。只有"确实从没被引用"才退回可删除，正常失败仍然能清理干净。
     *
     * @param ownership 本次新建的永久帧；`null` 表示这次请求没有新建永久文件。
     * @param publishedMessageId 这次保存要发布的消息 ID，用于失败时判断是否真的发布过。
     */
    internal suspend fun saveConversationWithOwnership(
        session: ConversationSession,
        conversationId: Uuid,
        conversation: Conversation,
        ownership: FrameFileOwnership?,
        publishedMessageId: Uuid,
    ) {
        ownership?.markPublishing()
        try {
            saveConversationLocked(conversationId, conversation)
        } catch (cancelled: CancellationException) {
            // 不知道事务提交没有：状态停在 PUBLISHING，文件归历史（保守，宁可留孤儿）。
            throw cancelled
        } catch (error: Throwable) {
            val published = session.state.value.currentMessages.any { it.id == publishedMessageId }
            if (published) ownership?.markPublished() else ownership?.markSaveFailed()
            throw error
        }
        ownership?.markPublished()
    }

    // Caller holds saveMutex. Publish before the first database suspension so a
    // streaming chunk cannot be overwritten by the snapshot after exists().
    internal suspend fun saveConversationLocked(conversationId: Uuid, conversation: Conversation) {
        val session = getOrCreateSession(conversationId)
        session.requireActive()
        conversation.requireMessageNodesLoaded()
        check(conversation.compressionRevision >= getConversationFlow(conversationId).value.compressionRevision) {
            "摘要已变更，请重新打开会话后操作。"
        }
        updateConversation(conversationId, conversation)
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = getConversationFlow(conversationId).value

        val saved = if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            val previous = session.persistedConversation
            val delta = session.messageJournal.deltaFor(previous, updatedConversation)
            if (previous != null && delta != null) {
                conversationRepo.updateConversationWithDelta(updatedConversation, previous, delta)
            } else conversationRepo.updateConversation(updatedConversation, previous)
        }
        // Repository filtering also runs after acquiring its DB writer transaction. Only
        // patch this saved list if a caller has not published newer nodes during suspension.
        if (saved.messageNodes !== updatedConversation.messageNodes &&
            session.state.value.messageNodes === updatedConversation.messageNodes) {
            session.state.value = session.state.value.copy(messageNodes = saved.messageNodes)
        }
        session.persistedConversation = saved
        session.messageJournal.committed()
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale,
    ) = auxiliaryCoordinator.translateMessage(conversationId, message, targetLanguage)

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>,
    ) = messageMutations.editMessage(conversationId, messageId, parts)

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid,
    ): Conversation = messageMutations.forkConversationAtMessage(conversationId, messageId)

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int,
    ) = messageMutations.selectMessageNode(conversationId, nodeId, selectIndex)

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) = messageMutations.deleteMessage(conversationId, messageId, failIfMissing)

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) = messageMutations.deleteMessage(conversationId, message)

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) =
        auxiliaryCoordinator.clearTranslationField(conversationId, messageId)

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) =
        stopGenerationInternal(conversationId, requestId = null, pauseQueue = true)

    private suspend fun stopGenerationInternal(conversationId: Uuid, requestId: Uuid?, pauseQueue: Boolean) {
        // A cancelled turn can never be approved for writing, so retire the one-shot selection.
        LocalMomentsChatSelectionStore.clear(conversationId.toString())
        val session = sessions[conversationId] ?: return
        session.stopQueuedGeneration(
            pauseQueue = pauseQueue,
            ownsRequest = {
                requestId == null || activeQueueGenerations[conversationId]?.let { owner ->
                    owner.message.id == requestId && session.getJob() === owner.job
                } == true
            },
        ) {
            session.withStateLock {
                val currentConversation = session.state.value
                val lastNode = currentConversation.messageNodes.lastOrNull() ?: return@withStateLock
                val lastMessage = lastNode.currentMessage
                val updatedMessage = lastMessage.finishReasoning().finishPendingTools(::cancelToolByUser)
                val updatedConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                        messages = lastNode.messages.map { message ->
                            if (message.id == lastMessage.id) updatedMessage else message
                        }
                    )
                )
                saveConversationLocked(conversationId, updatedConversation)
            }
        }
    }

    private fun launchNeteaseCloudMusic(text: String) =
        auxiliaryCoordinator.launchNeteaseCloudMusic(text)
}

/**
 * One natural reply to a declined/ended call event.
 *
 * This turn has no camera capability even when it follows a video call, so historical
 * `video_call_frames|clips|snapshots` images are removed before the request is assembled while
 * ordinary attachments stay untouched. The historical event text itself is unchanged.
 */
internal fun buildVoiceCallDeclinedRequestMessages(
    systemPrompt: String,
    history: List<UIMessage>,
    contextMessageSize: Int,
    summary: String?,
    eventDescription: String,
): List<UIMessage> = buildConversationRequestMessages(
    systemPrompt = systemPrompt,
    messages = history.withoutHistoricalCameraImages(),
    contextMessageSize = contextMessageSize,
    summary = summary,
) + UIMessage.user(eventDescription)
