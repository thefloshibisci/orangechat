/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.Application
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.transformers.ExtraInfoInjectionCollector
import me.rerere.rikkahub.data.ai.transformers.ExtraInfoRequestCache
import me.rerere.rikkahub.data.ai.transformers.ExtraInfoRequestTransformer
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.buildAnniversaryPrompt
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.interaction.ReactionContextTurn
import me.rerere.rikkahub.data.interaction.associateVideoCallReplies
import me.rerere.rikkahub.data.interaction.interactionStatePrompt
import me.rerere.rikkahub.data.interaction.paymentToolUnavailableReason
import me.rerere.rikkahub.data.interaction.withoutJumpControl
import me.rerere.rikkahub.data.listen.ListenRepository
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.withConversationConfig
import me.rerere.rikkahub.data.reading.ReadContextTransformer
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.serein.HookRecall
import me.rerere.rikkahub.data.serein.SereinHookConfig
import me.rerere.rikkahub.data.serein.SereinHookRepository
import me.rerere.rikkahub.data.serein.SereinRecallTransformer
import me.rerere.rikkahub.data.serein.isSereinCompletedReply
import me.rerere.rikkahub.data.serein.sereinSuccessfulRequestStatus
import me.rerere.rikkahub.data.service.ProactiveActivity
import me.rerere.rikkahub.data.service.ProactiveActivityStore
import me.rerere.rikkahub.data.service.ProactiveActivityTransformer
import me.rerere.rikkahub.data.video.VideoFrameRequestScope
import me.rerere.rikkahub.diagnostics.TaskWatchdog
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import org.koin.core.context.GlobalContext
import java.time.Instant
import kotlin.uuid.Uuid

private const val COMPLETION_TAG = "ChatService"

/**
 * Runs one full assistant completion turn (context gathering, streaming collection,
 * checkpoints, Serein/ProactiveActivity acks, [JUMP] launch, and post-turn hooks).
 */
internal class ChatMessageCompletionRunner(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val mcpManager: McpManager,
    private val pluginToolProvider: PluginToolProvider,
    private val pluginLoader: PluginLoader,
    private val generationHandler: GenerationHandler,
    private val extraInfoInjectionCollector: ExtraInfoInjectionCollector,
    private val extraInfoRequestCache: ExtraInfoRequestCache,
    private val sereinHookRepository: SereinHookRepository,
    private val inputTransformers: List<InputMessageTransformer>,
    private val outputTransformers: List<OutputMessageTransformer>,
    private val templateTransformer: TemplateTransformer,
    private val workspaceReminderTransformer: WorkspaceReminderTransformer,
    private val isForeground: StateFlow<Boolean>,
    private val bindConversationConfig: suspend (Uuid) -> Conversation,
    private val getOrCreateSession: (Uuid) -> ConversationSession,
    private val getConversationFlow: (Uuid) -> StateFlow<Conversation>,
    private val updateConversation: (Uuid, Conversation) -> Unit,
    private val checkInvalidMessages: suspend (Uuid) -> Unit,
    private val buildAvailableTools: suspend (
        settings: Settings,
        assistant: Assistant,
        conversationId: Uuid,
        conversation: Conversation,
        momentsTurnAnchorUserId: Uuid?,
    ) -> List<Tool>,
    private val applyMessageMutationLocked: (Uuid, ConversationMessageMutation) -> Unit,
    private val saveConversationLocked: suspend (Uuid, Conversation) -> Unit,
    private val cancelLiveUpdateNotification: (Uuid) -> Unit,
    private val sendGenerationDoneNotification: (Uuid, String) -> Unit,
    private val sendLiveUpdateNotification: (Uuid, List<UIMessage>, String) -> Unit,
    private val launchNeteaseCloudMusic: (String) -> Unit,
    private val launchWithConversationReference: (Uuid, suspend () -> Unit) -> Unit,
    private val generateTitle: suspend (Uuid, Conversation) -> Unit,
    private val generateSuggestion: suspend (Uuid, Conversation) -> Unit,
    private val addError: (Throwable, Uuid, String?) -> Unit,
) {
    suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        transientSystemPrompt: String? = null,
        enableTools: Boolean = true,
        diagnosticTrace: TaskWatchdog.Trace? = null,
        retryFailedRecall: Boolean = false,
        transientImageUrls: List<String> = emptyList(),
        transientImageScope: VideoFrameRequestScope? = null,
        transientImageAnchorMessageId: String? = null,
        requestToken: String? = null,
        videoCallUserMessageId: Uuid? = null,
        transientAudioUrl: String? = null,
        transientAudioAnchorMessageId: String? = null,
    ): Boolean {
        diagnosticTrace?.stage("reading generation settings")
        val initialConversation = bindConversationConfig(conversationId)
        val settings = settingsStore.settingsFlow.value.let { live ->
            // Read latest snapshot after config binding; first() in ChatService also matches value
            live.withConversationConfig(initialConversation)
        }
        val assistant = settings.getAssistantOf(initialConversation)
        val model = settings.getChatModelOf(initialConversation) ?: error("请先为会话选择可用的聊天模型")

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        // session 需要在 runCatching 外声明，以便 .onSuccess 中也能访问 saveMutex
        val session = getOrCreateSession(conversationId)
        val activityStore = ProactiveActivityStore(
            context,
            assistant.id.toString(),
            conversationId.toString(),
        )
        var activitySnapshot = emptyList<ProactiveActivity>()
        var activityUserId: String? = null
        var activityTransformer: ProactiveActivityTransformer? = null
        var reactionTurn: ReactionContextTurn? = null
        val rawArchiveOwner = currentCoroutineContext()[Job]
        val rawArchiveMessageIds = mutableSetOf<String>()
        val hookConfig = settings.sereinHook
        fun hookIsCurrent(): Boolean = settingsStore.settingsFlow.value.let { live ->
            live.sereinHook == hookConfig && hookConfig.usableBy(assistant.id.toString()) &&
                live.assistants.any { it.id == assistant.id } && !session.isDeleted
        }
        var hookTransformer: SereinRecallTransformer? = null
        var hookParticipating = false
        var hookUser: UIMessage? = null
        var hookRecall: HookRecall? = null
        var hookElapsedMs = 0L
        var completedGenerationReplyId: Uuid? = null
        val hookObservedAt = System.currentTimeMillis()
        val hookReceipt = "orangechat:${Uuid.random()}"
        suspend fun reportHook(status: String, delivered: List<String> = emptyList()) {
            val user = hookUser ?: return
            if (!hookParticipating || !hookIsCurrent()) return
            try {
                sereinHookRepository.report(
                    hookConfig,
                    conversationId.toString(),
                    hookReceipt,
                    user,
                    hookRecall,
                    delivered,
                    status,
                    hookElapsedMs,
                    hookObservedAt,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Log.w(COMPLETION_TAG, "Could not queue Serein observation")
            }
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
                        context.getString(R.string.error_title_tool_unavailable),
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
            val extraInfoContext = coroutineScope {
                val hookPending = async {
                    if (latestUserMessage == null || (messageRange != null && !retryFailedRecall) ||
                        transientSystemPrompt != null || !hookIsCurrent()
                    ) {
                        null
                    } else {
                        hookParticipating = true
                        hookUser = latestUserMessage
                        val started = System.nanoTime()
                        try {
                            sereinHookRepository.prepare(
                                hookConfig,
                                assistant.id.toString(),
                                conversationId.toString(),
                                latestUserMessage,
                                messagesForGeneration.lastOrNull()?.role == MessageRole.USER,
                                ::hookIsCurrent,
                                retryFailed = retryFailedRecall,
                            ).also { hookRecall = it }
                        } finally {
                            hookElapsedMs = (System.nanoTime() - started) / 1_000_000
                        }
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
                        hookTransformer = SereinRecallTransformer(it.id, recalled, ::hookIsCurrent)
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
                    extraInfoContext?.takeIf { it.isNotBlank() }?.let { latestUserMessage.id to it },
                )
            }
            activitySnapshot = runCatching { activityStore.snapshot() }.getOrElse {
                Log.w(COMPLETION_TAG, "Could not read proactive activity records", it)
                emptyList()
            }
            activityUserId = messagesForGeneration.lastOrNull { it.role == MessageRole.USER }
                ?.id?.toString()?.takeIf { messageRange == null }
            val activityCutoff = messagesForGeneration.lastOrNull { it.id.toString() == activityUserId }
                ?.createdAt?.toInstant(TimeZone.currentSystemDefault())?.toEpochMilliseconds()
            activitySnapshot = activitySnapshot.filter {
                it.claimedByUserId != null || (activityCutoff != null && it.timestamp <= activityCutoff)
            }
            activityTransformer = ProactiveActivityTransformer(
                activitySnapshot,
                activityUserId,
            )

            // start generating
            diagnosticTrace?.stage("building generation tools and context")
            val availableTools = if (enableTools) {
                buildAvailableTools(
                    settings,
                    assistant,
                    conversationId,
                    conversation,
                    latestUserMessage?.id,
                )
            } else {
                emptyList()
            }
            val paymentToolsUnavailableReason = paymentToolUnavailableReason(
                model,
                model.findProvider(settings.providers),
            )
            val paymentToolsAvailable = paymentToolsUnavailableReason == null &&
                availableTools.any { it.name == "respond_to_payment" }
            if (enableTools && paymentToolsUnavailableReason != null && conversation.currentMessages.any { message ->
                    message.annotations.any {
                        it is UIMessageAnnotation.ChatInteraction &&
                            it.kind in setOf("red_packet", "transfer") &&
                            it.status == "pending"
                    }
                }
            ) {
                addError(IllegalStateException(paymentToolsUnavailableReason), conversationId, "红包/转账工具不可用")
            }
            val listenTurn = latestUserMessage?.let { user ->
                GlobalContext.get().get<ListenRepository>()
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
                inputTransformers = buildCompletionInputTransformers(
                    session = session,
                    conversationId = conversationId,
                    assistant = assistant,
                    requestMessageIds = requestMessageIds,
                    messageRange = messageRange,
                    activityTransformer = requireNotNull(activityTransformer),
                    extraInfoSnapshots = extraInfoSnapshots,
                    hookTransformer = hookTransformer,
                    paymentToolsAvailable = paymentToolsAvailable,
                    paymentToolsUnavailableReason = paymentToolsUnavailableReason,
                ),
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
                            associateVideoCallReplies(
                                checkpointMessages,
                                requestMessageIds,
                                conversationId,
                                videoCallUserMessageId,
                            ),
                            buildMap {
                                if (regenerationNodeId != null) regenerationMessageId?.let { put(it, regenerationNodeId) }
                            },
                        )
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
                    if (!session.isDeleted) {
                        session.withStateLock {
                            val current = session.state.value
                            val archived = current.activeCompressedMessageNodeIds
                            val mutation = current.generationMessageMutation(
                                current.messageNodes
                                    .filterNot { it.id in archived }
                                    .flatMap { it.messages }
                                    .map { it.finishReasoning() },
                            )
                            applyMessageMutationLocked(conversationId, mutation)
                            updateConversation(conversationId, session.state.value.copy(updateAt = Instant.now()))
                        }
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
                                associateVideoCallReplies(
                                    chunk.messages,
                                    requestMessageIds,
                                    conversationId,
                                    videoCallUserMessageId,
                                ),
                                buildMap {
                                    if (regenerationNodeId != null) {
                                        regenerationMessageId?.let { put(it, regenerationNodeId) }
                                    }
                                },
                            )
                            applyMessageMutationLocked(conversationId, mutation)
                        }

                        // 如果应用不在前台，发送 Live Update 通知
                        if (!isForeground.value &&
                            settings.displaySetting.enableNotificationOnMessageGeneration &&
                            settings.displaySetting.enableLiveUpdateNotification
                        ) {
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
                }.onFailure { Log.w(COMPLETION_TAG, "Could not finalize local original-text archive") }
            }

            if (it is CancellationException) throw it
            currentCoroutineContext().ensureActive()
            it.printStackTrace()
            addError(it, conversationId, context.getString(R.string.error_title_generation))
            Logging.log(COMPLETION_TAG, "handleMessageComplete: $it")
            Logging.log(COMPLETION_TAG, it.stackTraceToString())
        }.onSuccess {
            currentCoroutineContext().ensureActive()
            val finalConversation = session.withStateLock {
                currentCoroutineContext().ensureActive()
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
                        applyMessageMutationLocked(
                            conversationId,
                            session.state.value.generationMessageMutation(listOf(reply)),
                        )
                    }
                    throw error
                }
                session.state.value
            }
            handlePostCompletionSideEffects(
                conversationId = conversationId,
                assistant = assistant,
                finalConversation = finalConversation,
                session = session,
                hookParticipating = hookParticipating,
                hookIsCurrent = ::hookIsCurrent,
                hookConfig = hookConfig,
                completedGenerationReplyId = completedGenerationReplyId,
                hookTransformer = hookTransformer,
                reportHook = ::reportHook,
                activityUserId = activityUserId,
                activityStore = activityStore,
                activityTransformer = activityTransformer,
            )
        }
        return generationResult.isSuccess
    }

    private fun buildCompletionInputTransformers(
        session: ConversationSession,
        conversationId: Uuid,
        assistant: Assistant,
        requestMessageIds: Set<Uuid>,
        messageRange: ClosedRange<Int>?,
        activityTransformer: ProactiveActivityTransformer,
        extraInfoSnapshots: Map<Uuid, String>,
        hookTransformer: SereinRecallTransformer?,
        paymentToolsAvailable: Boolean,
        paymentToolsUnavailableReason: String?,
    ): List<InputMessageTransformer> = buildList {
        addAll(inputTransformers)
        add(templateTransformer)
        add(workspaceReminderTransformer)
        add(
            ReadContextTransformer(
                GlobalContext.get().get(),
                conversationId.toString(),
                assistant.id.toString(),
            ),
        )
        add(activityTransformer)
        add(ExtraInfoRequestTransformer(extraInfoSnapshots))
        hookTransformer?.let { add(it) }
        add(
            object : InputMessageTransformer {
                override suspend fun transform(
                    ctx: TransformerContext,
                    messages: List<UIMessage>,
                ): List<UIMessage> {
                    val current = session.state.value
                    val prompt = interactionStatePrompt(
                        current.currentMessages,
                        assistant.pretendRich,
                        VoiceCallService.activeConversationId.value == conversationId.toString() &&
                            VoiceCallService.activeUiState.value.status in setOf(
                                VoiceCallStatus.Listening,
                                VoiceCallStatus.Processing,
                                VoiceCallStatus.Speaking,
                            ),
                        paymentToolsAvailable = paymentToolsAvailable,
                        paymentToolsUnavailableReason = paymentToolsUnavailableReason,
                        paymentMessageIds = requestMessageIds.takeIf { messageRange != null },
                    )
                    val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
                    return if (systemIndex < 0) {
                        listOf(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(prompt)))) + messages
                    } else {
                        messages.mapIndexed { index, message ->
                            if (index == systemIndex) {
                                message.copy(parts = message.parts + UIMessagePart.Text(prompt))
                            } else {
                                message
                            }
                        }
                    }
                }
            },
        )
    }

    private suspend fun handlePostCompletionSideEffects(
        conversationId: Uuid,
        assistant: Assistant,
        finalConversation: Conversation,
        session: ConversationSession,
        hookParticipating: Boolean,
        hookIsCurrent: () -> Boolean,
        hookConfig: SereinHookConfig,
        completedGenerationReplyId: Uuid?,
        hookTransformer: SereinRecallTransformer?,
        reportHook: suspend (String, List<String>) -> Unit,
        activityUserId: String?,
        activityStore: ProactiveActivityStore,
        activityTransformer: ProactiveActivityTransformer?,
    ) {
        val completedReply = finalConversation.currentMessages.lastOrNull()
        if (hookParticipating && hookIsCurrent() && completedReply != null) {
            try {
                val status = sereinSuccessfulRequestStatus(completedGenerationReplyId, completedReply)
                if (status == "completed") {
                    sereinHookRepository.acknowledge(
                        hookConfig,
                        conversationId.toString(),
                        completedReply,
                        hookTransformer?.deliveredIds.orEmpty(),
                    )
                }
                val valid = completedReply.isSereinCompletedReply()
                // Completion is an observed model event; ACK/upload eligibility remains stricter.
                reportHook(
                    status,
                    if (status == "completed" && valid) hookTransformer?.deliveredIds.orEmpty() else emptyList(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Log.w(COMPLETION_TAG, "Could not record Serein recall delivery")
            }
        }
        if (activityUserId != null &&
            completedReply?.role == MessageRole.ASSISTANT &&
            completedReply.finishedAt != null &&
            completedReply.getTools().isEmpty()
        ) {
            runCatching {
                activityStore.claim(
                    activityTransformer?.deliveredPendingIds.orEmpty(),
                    activityUserId,
                )
            }.onFailure { Log.w(COMPLETION_TAG, "Could not acknowledge proactive activity records", it) }
        }

        // 自动唤起网易云音乐：扫描刚完成的 assistant 文本中的 orpheus:// scheme
        try {
            val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            val messageText = lastAssistantMessage?.toText() ?: ""
            launchNeteaseCloudMusic(messageText)
        } catch (e: Exception) {
            Log.w(COMPLETION_TAG, "Failed to launch NetEase Cloud Music", e)
        }

        // 检测并执行 [JUMP] 标记 - 正常聊天中的切屏（AI总是可以跳转，不需要开关）
        try {
            val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            val rawText = lastAssistantMessage?.parts?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("\n") { it.text } ?: ""
            if (rawText.contains("[JUMP]", ignoreCase = true)) {
                // Merge into the live state under its lock: an older reply snapshot must not
                // overwrite a payment receipt or hang-up that arrived during generation.
                if (lastAssistantMessage != null) {
                    session.withStateLock {
                        val current = session.state.value
                        val cleanedConversation = current.copy(
                            messageNodes = current.messageNodes.map { node ->
                                node.copy(
                                    messages = node.messages.map { msg ->
                                        if (msg.id == lastAssistantMessage.id) msg.withoutJumpControl() else msg
                                    },
                                )
                            },
                        )
                        saveConversationLocked(conversationId, cleanedConversation)
                    }
                }
                // 拉起 RouteActivity 切屏（正常聊天中不受时间阈值限制）
                val jumpIntent = Intent(context, RouteActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    putExtra("conversationId", conversationId.toString())
                }
                context.startActivity(jumpIntent)
                Log.d(COMPLETION_TAG, "[JUMP] detected in normal chat, force jump to conversation $conversationId")
            }
        } catch (e: Exception) {
            Log.w(COMPLETION_TAG, "Failed to handle [JUMP] in normal chat", e)
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
                    "timestamp" to JsonPrimitive(System.currentTimeMillis()),
                ),
            )
            appScope.launch {
                try {
                    pluginLoader.callEvent("message_received", eventData)
                } catch (e: Exception) {
                    Log.w(COMPLETION_TAG, "Failed to trigger message_received event", e)
                }
            }
        }.onFailure { e ->
            Log.w(COMPLETION_TAG, "Failed to trigger message_received event", e)
        }

        launchWithConversationReference(conversationId) {
            generateTitle(conversationId, finalConversation)
        }
        launchWithConversationReference(conversationId) {
            generateSuggestion(conversationId, finalConversation)
        }
    }
}
