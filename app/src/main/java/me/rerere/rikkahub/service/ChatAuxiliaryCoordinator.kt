/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.CompressionEngine
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import me.rerere.rikkahub.data.ai.compressTextOnce
import me.rerere.rikkahub.data.ai.compressionTranscript
import me.rerere.rikkahub.data.ai.mergeExistingSummaryDraft
import me.rerere.rikkahub.data.ai.planCompressionSummaryMerge
import me.rerere.rikkahub.data.ai.withCompressionDeadline
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.CompressionUndoMode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationSummary
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.summaryParagraphs
import me.rerere.rikkahub.data.model.withConversationConfig
import me.rerere.rikkahub.data.model.withResolvedGroups
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.video.cameraFreeRequests
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid

private const val AUX_TAG = "ChatService"

internal class ChatAuxiliaryCoordinator(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val generationHandler: GenerationHandler,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val getOrCreateSession: (Uuid) -> ConversationSession,
    private val getConversationFlow: (Uuid) -> StateFlow<Conversation>,
    private val bindConversationConfig: suspend (Uuid) -> Conversation,
    private val updateConversation: (Uuid, Conversation) -> Unit,
    private val saveConversation: suspend (Uuid, Conversation) -> Unit,
    private val saveConversationLocked: suspend (Uuid, Conversation) -> Unit,
    private val addProactiveMessage: (Uuid, UIMessage) -> Unit,
    private val isForeground: StateFlow<Boolean>,
    private val sendGenerationDoneNotification: (Uuid, String) -> Unit,
    private val addError: (Throwable, Uuid?, String?, ChatErrorSolution?) -> Unit,
) {
    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false,
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return
        val session = getOrCreateSession(conversationId)
        val epoch = session.epoch

        session.withRefSuspend {
            runCatching {
                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.titleModelId) ?: return
                val provider = model.findProvider(settings.providers) ?: return

                val providerHandler = usageRecorder.wrap(
                    providerManager.getProviderByType(provider),
                    UsageTag(UsageSource.TITLE, conversation.assistantId, conversationId),
                )
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.user(
                            prompt = settings.titlePrompt.applyPlaceholders(
                                "locale" to Locale.getDefault().displayName,
                                "content" to (listOfNotNull(conversation.summaryForGeneration()) +
                                    conversation.messagesForGeneration().takeLast(4).map { it.summaryAsText() })
                                    .joinToString("\n\n"),
                            ),
                        ),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        reasoningLevel = ReasoningLevel.OFF,
                    ),
                )

                val title = result.choices[0].message?.toText()?.trim() ?: ""
                session.saveGeneratedTitle(title, expectedEpoch = epoch) {
                    conversationRepo.updateGeneratedTitle(conversationId, title)
                }
            }.onFailure {
                it.printStackTrace()
                Log.e(AUX_TAG, "generateTitle failed, conversationId=$conversationId", it)
                addError(
                    it,
                    conversationId,
                    context.getString(R.string.error_title_generate_title),
                    ChatErrorSolution.CheckTitleModelSettings,
                )
            }
        }
    }

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        val session = getOrCreateSession(conversationId)
        val epoch = session.epoch
        session.withRefSuspend {
            runCatching {
                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.suggestionModelId) ?: return
                val provider = model.findProvider(settings.providers) ?: return

                session.withStateLock {
                    session.state.value = session.state.value.copy(chatSuggestions = emptyList())
                }

                val providerHandler = usageRecorder.wrap(
                    providerManager.getProviderByType(provider),
                    UsageTag(UsageSource.SUGGESTION, conversation.assistantId, conversationId),
                )
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.user(
                            settings.suggestionPrompt.applyPlaceholders(
                                "locale" to Locale.getDefault().displayName,
                                "content" to (listOfNotNull(conversation.summaryForGeneration()) +
                                    conversation.messagesForGeneration().takeLast(8).map { it.summaryAsText() })
                                    .joinToString("\n\n"),
                            ),
                        ),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        reasoningLevel = ReasoningLevel.OFF,
                    ),
                )
                val suggestions = result.choices[0].message?.toText()?.split("\n")
                    ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

                val limitedSuggestions = suggestions.take(10)
                session.saveGeneratedSuggestions(limitedSuggestions, expectedEpoch = epoch) {
                    conversationRepo.updateGeneratedSuggestions(conversationId, limitedSuggestions)
                }
            }.onFailure {
                it.printStackTrace()
                Log.e(AUX_TAG, "generateSuggestion failed, conversationId=$conversationId", it)
            }
        }
    }

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32,
        mergeWithPrevious: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): Result<Unit> = runCatching {
        require(targetTokens > 0)
        require(conversation.id == conversationId)
        val session = getOrCreateSession(conversationId)
        check(!session.isGenerating) { "请先停止生成再压缩对话" }
        val base = session.state.value
        val sources = base.compressionCandidates(keepRecentMessages)
        if (sources.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }
        check(sources.none { node -> node.currentMessage.getTools().any { it.isPending || it.canResumeExecution } }) {
            "请先处理待执行的工具调用再压缩对话"
        }
        val settings = settingsStore.settingsFlow.first()
        val compressor = CompressionEngine(providerManager, usageRecorder)
            .request(settings.withConversationConfig(base), base.assistantId, targetTokens, additionalPrompt, conversationId)
        suspend fun compressContent(content: String): String = compressor(content)

        val summaries = withCompressionDeadline {
            onProgress("正在整段压缩聊天记录（含工具内容）…")
            val compressedSummary = compressTextOnce(
                sources.map { it.currentMessage.compressionTranscript() },
                ::compressContent,
            )
            val mergePlan = base.planCompressionSummaryMerge(listOf(compressedSummary), mergeWithPrevious)
            val textsToMerge = mergePlan.textsToMerge
            val merged = if (textsToMerge.size == 1) {
                textsToMerge.single()
            } else {
                onProgress("正在合并往期摘要…")
                compressTextOnce(textsToMerge, ::compressContent)
            }
            mergePlan.preservedSummaries.withResolvedGroups(base.compressionHistory) + summaryParagraphs(merged)
        }
        onProgress("正在保存摘要…")
        session.withStateLock {
            check(!session.isGenerating) { "对话正在生成，请停止后重新压缩" }
            val updated = session.state.value.withCompressionResult(base, sources, summaries)
            saveConversationLocked(conversationId, updated)
        }
    }.onFailure {
        if (it is CancellationException) throw it
    }

    suspend fun mergeExistingSummaries(
        conversationId: Uuid,
        revision: Long,
        summaries: List<ConversationSummary>,
        enabled: Boolean,
        additionalPrompt: String,
        targetTokens: Int,
    ): List<ConversationSummary> {
        require(targetTokens > 0)
        val session = getOrCreateSession(conversationId)
        check(session.state.value.compressionRevision == revision) { "摘要已变更，请关闭后重新打开。" }
        val draft = mergeExistingSummaryDraft(summaries, enabled) { content ->
            val settings = settingsStore.settingsFlow.first()
            val compressor = CompressionEngine(providerManager, usageRecorder)
                .request(
                    settings.withConversationConfig(session.state.value),
                    session.state.value.assistantId,
                    targetTokens,
                    additionalPrompt,
                    conversationId,
                )
            compressTextOnce(listOf(content), compressor)
        }
        check(session.state.value.compressionRevision == revision) { "摘要已变更，请关闭后重新打开。" }
        return draft
    }

    suspend fun saveCompressionSettings(
        conversationId: Uuid,
        revision: Long,
        summaries: List<ConversationSummary>,
        enabled: Boolean,
        undo: CompressionUndoMode? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.withStateLock {
            val latest = session.state.value
            val updated = when (undo) {
                CompressionUndoMode.LATEST -> latest.undoLatestCompression(revision)
                CompressionUndoMode.ALL -> latest.withoutCompression(revision)
                null -> latest.withSummaryEdits(revision, summaries, enabled)
            }
            saveConversationLocked(conversationId, updated)
        }
    }

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale,
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage,
                ) { translatedText ->
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, context.getString(R.string.error_title_translate_message), null)
            }
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        updateTranslationField(conversationId, messageId, null)
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String?,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(translation = translationText) else msg
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }
        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    fun notifyVoiceCallDeclined(conversationId: Uuid) {
        appScope.launch(Dispatchers.IO) {
            try {
                val session = getOrCreateSession(conversationId)
                session.getJob()?.let { job ->
                    if (job.isActive) {
                        Log.i(AUX_TAG, "notifyVoiceCallDeclined: waiting for ongoing generation to finish, conversationId=$conversationId")
                        job.join()
                    }
                }

                val currentConversation = bindConversationConfig(conversationId)
                val baseSettings = settingsStore.settingsFlow.first()
                val settings = baseSettings.withConversationConfig(currentConversation)
                val assistant = settings.getAssistantOf(currentConversation)
                val model = settings.getChatModelOf(currentConversation)
                if (model == null) {
                    Log.e(AUX_TAG, "notifyVoiceCallDeclined: no model found, conversationId=$conversationId")
                    return@launch
                }
                val provider = model.findProvider(settings.providers)
                if (provider == null) {
                    Log.e(AUX_TAG, "notifyVoiceCallDeclined: no provider found, conversationId=$conversationId, modelId=${model.id}")
                    return@launch
                }
                val providerHandler = usageRecorder.wrap(
                    providerManager.getProviderByType(provider),
                    UsageTag(UsageSource.VOICE_EVENT, assistant.id, conversationId),
                ).cameraFreeRequests()

                val nodeCountBeforeGeneration = currentConversation.messageNodes.size
                val eventDescription =
                    "[系统事件] 你刚刚主动发起的语音通话邀请被用户直接挂断了（拒接，未接听）。请用你自己的语气自然地回应这件事，简短即可，不要提及\"系统事件\"\"工具\"等技术词汇，不要用引号或标注包裹，就当作正常聊天说一句话。"

                val messages = buildVoiceCallDeclinedRequestMessages(
                    systemPrompt = assistant.systemPrompt,
                    history = currentConversation.messagesForGeneration(),
                    contextMessageSize = assistant.contextMessageSize,
                    summary = currentConversation.summaryForGeneration(),
                    eventDescription = eventDescription,
                )

                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = messages,
                    params = TextGenerationParams(
                        model = model,
                        temperature = assistant.temperature ?: 0.8f,
                        topP = assistant.topP,
                        maxTokens = assistant.maxTokens,
                        reasoningLevel = ReasoningLevel.OFF,
                        customHeaders = buildList {
                            addAll(assistant.customHeaders)
                            addAll(model.customHeaders)
                        },
                        customBody = buildList {
                            addAll(assistant.customBodies)
                            addAll(model.customBodies)
                        },
                    ),
                )

                val replyText = result.choices[0].message?.toText()?.trim().orEmpty()
                if (replyText.isBlank()) {
                    Log.w(AUX_TAG, "notifyVoiceCallDeclined: empty reply, conversationId=$conversationId")
                    return@launch
                }

                val latestConversation = conversationRepo.getConversationById(conversationId)
                    ?: getConversationFlow(conversationId).value
                val newNodesSinceStart = if (latestConversation.messageNodes.size > nodeCountBeforeGeneration) {
                    latestConversation.messageNodes.subList(nodeCountBeforeGeneration, latestConversation.messageNodes.size)
                } else {
                    emptyList()
                }
                val userSentNewMessage = newNodesSinceStart.any { node ->
                    node.messages.any { it.role == MessageRole.USER }
                }
                if (userSentNewMessage) {
                    Log.i(AUX_TAG, "notifyVoiceCallDeclined: user already sent a new message during generation, skip. conversationId=$conversationId")
                    return@launch
                }

                val aiMessage = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(replyText)),
                )
                addProactiveMessage(conversationId, aiMessage)

                if (!isForeground.value) {
                    val senderName = if (assistant.useAssistantAvatar) {
                        assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
                    } else {
                        model.displayName
                    }
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(AUX_TAG, "notifyVoiceCallDeclined failed, conversationId=$conversationId", e)
            }
        }
    }

    fun launchNeteaseCloudMusic(text: String) {
        if (text.isBlank()) return
        val songId = extractNeteaseSongId(text) ?: return
        val uri = "orpheus://song/$songId/?autoplay=1"
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.netease.cloudmusic")
            }
            context.startActivity(intent)
            Logging.log(AUX_TAG, "Launched NetEase Cloud Music, songId=$songId, uri=$uri")
        }.onFailure { e ->
            runCatching {
                val fallback = Intent(Intent.ACTION_VIEW, uri.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
                Logging.log(AUX_TAG, "Launched NetEase Cloud Music (no package), songId=$songId")
            }.onFailure {
                runCatching {
                    val widgetUri = "orpheuswidget://song/$songId"
                    val widgetIntent = Intent(Intent.ACTION_VIEW, widgetUri.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(widgetIntent)
                    Logging.log(AUX_TAG, "Launched NetEase Cloud Music (widget scheme), songId=$songId")
                }.onFailure {
                    Log.w(AUX_TAG, "NetEase Cloud Music not available, songId=$songId", e)
                }
            }
        }
    }
}

internal fun extractNeteaseSongId(text: String): String? {
    Regex("orpheus://song/(\\d+)").find(text)?.let {
        return it.groupValues[1]
    }
    Regex("music\\.163\\.com/song\\?.*?(?:^|[^0-9])(\\d{4,})").find(text)?.let {
        return it.groupValues[1]
    }
    Regex("music\\.163\\.com/song\\?id=(\\d+)").find(text)?.let {
        return it.groupValues[1]
    }
    return null
}
