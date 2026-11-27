/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.files.attachmentReferenceGuard

import me.rerere.rikkahub.diagnostics.TaskWatchdog

import me.rerere.rikkahub.data.repository.ConversationNodeChanges

import android.app.Application
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
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
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
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
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoFrameStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.video.FrameFileOwnership
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.interaction.ChatInteractionRepository
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
    private val completionRunner by lazy {
        ChatMessageCompletionRunner(
            context = context,
            appScope = appScope,
            settingsStore = settingsStore,
            conversationRepo = conversationRepo,
            memoryRepository = memoryRepository,
            mcpManager = mcpManager,
            pluginToolProvider = pluginToolProvider,
            pluginLoader = pluginLoader,
            generationHandler = generationHandler,
            extraInfoInjectionCollector = extraInfoInjectionCollector,
            extraInfoRequestCache = extraInfoRequestCache,
            sereinHookRepository = sereinHookRepository,
            inputTransformers = inputTransformers,
            outputTransformers = outputTransformers,
            templateTransformer = templateTransformer,
            workspaceReminderTransformer = workspaceReminderTransformer,
            isForeground = isForeground,
            bindConversationConfig = ::bindConversationConfig,
            getOrCreateSession = ::getOrCreateSession,
            getConversationFlow = ::getConversationFlow,
            updateConversation = ::updateConversation,
            checkInvalidMessages = ::checkInvalidMessages,
            buildAvailableTools = { settings, assistant, cid, conversation, anchorId ->
                buildAvailableTools(
                    settings = settings,
                    assistant = assistant,
                    conversationId = cid,
                    conversation = conversation,
                    allowAppUsage = true,
                    momentsTurnAnchorUserId = anchorId,
                )
            },
            applyMessageMutationLocked = ::applyMessageMutationLocked,
            saveConversationLocked = ::saveConversationLocked,
            cancelLiveUpdateNotification = ::cancelLiveUpdateNotification,
            sendGenerationDoneNotification = ::sendGenerationDoneNotification,
            sendLiveUpdateNotification = ::sendLiveUpdateNotification,
            launchNeteaseCloudMusic = ::launchNeteaseCloudMusic,
            launchWithConversationReference = ::launchWithConversationReference,
            generateTitle = { cid, conversation -> generateTitle(cid, conversation) },
            generateSuggestion = { cid, conversation -> generateSuggestion(cid, conversation) },
            addError = { error, cid, title -> addError(error, cid, title) },
        )
    }
    private val queuedGenerationRunner by lazy {
        ChatQueuedGenerationRunner(
            context = context,
            appScope = appScope,
            settingsStore = settingsStore,
            pluginLoader = pluginLoader,
            initializeConversation = { cid -> initializeConversation(cid, updateSelectedAssistant = false) },
            preprocessUserInputParts = ::preprocessUserInputParts,
            saveConversationWithOwnership = ::saveConversationWithOwnership,
            handleMessageComplete = { cid, prompt, toolsEnabled, trace, transientImgs, imageScope, anchorId, token, vcUserId, audioUrl, audioAnchorId ->
                handleMessageComplete(
                    conversationId = cid,
                    transientSystemPrompt = prompt,
                    enableTools = toolsEnabled,
                    diagnosticTrace = trace,
                    transientImageUrls = transientImgs,
                    transientImageScope = imageScope,
                    transientImageAnchorMessageId = anchorId,
                    requestToken = token,
                    videoCallUserMessageId = vcUserId,
                    transientAudioUrl = audioUrl,
                    transientAudioAnchorMessageId = audioAnchorId,
                )
            },
            clearQueueOwner = { cid, queuedId ->
                activeQueueGenerations.computeIfPresent(cid) { _, owner ->
                    owner.takeUnless { it.message.id == queuedId }
                }
            },
            emitGenerationDone = { cid -> _generationDoneFlow.emit(cid) },
            addError = { error, cid, title -> addError(error, cid, title) },
        )
    }
    private val proactivePublisher by lazy {
        ChatProactivePublisher(
            conversationRepo = conversationRepo,
            launchWithConversationReference = ::launchWithConversationReference,
            getOrCreateSession = ::getOrCreateSession,
            isConversationDeleted = { cid -> cid in deletedConversationIds },
            updateConversation = ::updateConversation,
            saveConversationLocked = ::saveConversationLocked,
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
        val result = session.submitMessage(
            enqueuePending = {
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
            },
            enqueueInFlight = {
                session.messageQueue.enqueueInFlight(
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
            },
            createJob = { queued ->
                createQueuedMessageJob(session, queued).also { job ->
                    activeQueueGenerations[conversationId] = QueueGenerationOwner(queued, job)
                }
            },
        ) ?: return null
        val queued = result.message
        queued.completion.invokeOnCompletion { cause ->
            if (cause is CancellationException && !queued.reply.isCompleted) cancelQueuedMessage(conversationId, queued.id)
        }
        val immediateJob = result.immediateJob
        if (immediateJob != null) {
            immediateJob.start()
        } else {
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

    private fun createQueuedMessageJob(session: ConversationSession, queued: QueuedMessage): Job =
        queuedGenerationRunner.createJob(session, queued)

    /** Avatar actions are genuine user events and follow the normal send/cancel/notification lifecycle. */
    fun sendAvatarInteraction(conversationId: Uuid, targetMessage: UIMessage) =
        interactionCoordinator.sendAvatarInteraction(conversationId, targetMessage)

    // ---- 添加主动消息 ----

    fun addProactiveMessage(conversationId: Uuid, aiMessage: UIMessage) =
        proactivePublisher.addProactiveMessage(conversationId, aiMessage)

    /** Isolated background output enters chat only at this guarded, non-suspending publication point.
     * Both deletion and normal user writes share the session lock. Never recreate a deleted window. */
    internal suspend fun publishProactiveMessages(
        conversationId: Uuid,
        assistantId: Uuid,
        anchorId: Uuid,
        task: me.rerere.rikkahub.data.service.ProactiveTask,
        messages: List<UIMessage>,
        executor: me.rerere.rikkahub.data.service.ProactiveTaskExecutor,
    ): Boolean = proactivePublisher.publishProactiveMessages(
        conversationId = conversationId,
        assistantId = assistantId,
        anchorId = anchorId,
        task = task,
        messages = messages,
        executor = executor,
    )

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
    ): Boolean = completionRunner.handleMessageComplete(
        conversationId = conversationId,
        messageRange = messageRange,
        transientSystemPrompt = transientSystemPrompt,
        enableTools = enableTools,
        diagnosticTrace = diagnosticTrace,
        retryFailedRecall = retryFailedRecall,
        transientImageUrls = transientImageUrls,
        transientImageScope = transientImageScope,
        transientImageAnchorMessageId = transientImageAnchorMessageId,
        requestToken = requestToken,
        videoCallUserMessageId = videoCallUserMessageId,
        transientAudioUrl = transientAudioUrl,
        transientAudioAnchorMessageId = transientAudioAnchorMessageId,
    )

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
