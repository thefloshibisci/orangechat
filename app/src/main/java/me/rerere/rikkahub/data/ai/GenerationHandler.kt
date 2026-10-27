/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai
 
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.diagnostics.LatencyProbe
import me.rerere.rikkahub.data.ai.tools.buildWriteFilesTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.interaction.ChatInteractionRepository
import me.rerere.rikkahub.data.interaction.ReactionContextTurn
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
 
private const val TAG = "GenerationHandler"
 
// 流式生成时往 UI 推送消息更新的最小间隔。
// AI 的 SSE 增量可能每秒到达几十次，如果每次都原样同步到 UI 的 StateFlow，
// 会导致 Compose 高频重组（Markdown 全量重解析、代码高亮重新分词、
// animateContentSize 的尺寸补间动画被不断打断重启），表现为打字机效果的"抖动/掉帧"。
// 这里把推送频率限制在这个间隔以内，肉眼完全感知不到延迟，但能大幅降低重组频率。
private const val STREAM_UI_THROTTLE_MS = 50L
 
@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}
 
class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val memoryBankService: MemoryBankService,
    private val filesManager: me.rerere.rikkahub.data.files.FilesManager,
    private val chatInteractionRepository: ChatInteractionRepository,
    private val localScenes: me.rerere.rikkahub.data.serein.LocalSceneRepository,
    private val localSceneSettings: me.rerere.rikkahub.data.datastore.SettingsStore,
    private val usageRecorder: UsageRecorder,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationContextSummary: String? = null,
        workspaceCwd: String? = null,
        pluginPromptInjections: List<String> = emptyList(),
        conversationId: String? = null,
        onToolCheckpoint: (suspend (List<UIMessage>) -> Unit)? = null,
        reactionSourceMessages: List<UIMessage> = messages,
        onReactionContextPrepared: (ReactionContextTurn) -> Unit = {},
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = usageRecorder.wrap(
            providerManager.getProviderByType(provider),
            UsageTag(UsageSource.CHAT, assistant.id, conversationId?.let {
                runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull()
            }),
        )
 
        val reactionTurn = chatInteractionRepository.prepareContext(
            reactionSourceMessages, conversationId, assistant, messages,
        )
        onReactionContextPrepared(reactionTurn)
        LatencyProbe.mark(conversationId, "表情上下文")
        var messages: List<UIMessage> = messages
        val recoveredMessages = messages.recoverToolHistoryForRequest(json)
        if (recoveredMessages != messages) {
            messages = reactionTurn.markPending(recoveredMessages)
            withContext(NonCancellable) { onToolCheckpoint?.invoke(messages) }
            emit(GenerationChunk.Messages(messages))
        }
 
        messages = runGenerationToolLoop(
            initialMessages = messages,
            maxSteps = maxSteps,
            resumeApprovedTools = true,
            toolsForStep = { stepIndex ->
                Log.i(TAG, "streamText: build tools for step #$stepIndex (${model.id})")
                buildList {
                    if (!localSceneSettings.settingsFlow.value.sereinHook.usableBy(assistant.id.toString()) &&
                        tools.none { it.name == "local_scene_search" }) {
                        addAll(me.rerere.rikkahub.data.serein.buildLocalSceneTools(assistant, localScenes) {
                            localSceneSettings.settingsFlow.value.let { live ->
                                !live.sereinHook.usableBy(assistant.id.toString()) &&
                                    live.assistants.any { it.id == assistant.id && it.enableLocalSceneTools }
                            }
                        })
                    }
                    if (assistant.enableMemory) {
                        val memoryAssistantId = if (assistant.useGlobalMemory) {
                            MemoryRepository.GLOBAL_MEMORY_ID
                        } else {
                            assistant.id.toString()
                        }
                        addAll(buildMemoryTools(
                            json = json,
                            onCreation = { content -> memoryRepo.addMemory(memoryAssistantId, content) },
                            onUpdate = { id, content -> memoryRepo.updateContent(id, content) },
                            onDelete = { id -> memoryRepo.deleteMemory(id) },
                        ))
                    }
                    add(buildWriteFilesTool(conversationId))
                    addAll(tools)
                }
            },
            generateTurn = { turnMessages, toolsInternal, _ ->
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = turnMessages,
                    pluginPromptInjections = pluginPromptInjections,
                    onUpdateMessages = {
                        LatencyProbe.mark(conversationId, LatencyProbe.FIRST_CHUNK)
                        val hasText = it.lastOrNull()?.let { last ->
                            LatencyProbe.bindMessage(conversationId, last.id.toString())
                            last.parts.any { p -> p is me.rerere.ai.ui.UIMessagePart.Text && p.text.isNotBlank() }
                        } == true
                        if (hasText) LatencyProbe.mark(conversationId, LatencyProbe.FIRST_TEXT)
                        messages = reactionTurn.markPending(it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                        ))
                        emit(GenerationChunk.Messages(reactionTurn.markPending(messages.visualTransforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                        ))))
                        if (hasText) LatencyProbe.mark(conversationId, LatencyProbe.TRANSFORMED)
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationContextSummary = conversationContextSummary,
                    workspaceCwd = workspaceCwd,
                    conversationId = conversationId,
                    reactionTurn = reactionTurn,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                )
                messages = reactionTurn.markPending(messages.dropLast(1) + messages.last().copy(
                    finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                ))
                emit(GenerationChunk.Messages(messages))
                messages
            },
            prepareTools = { calls, definitions ->
                prepareGenerationToolApproval(
                    calls = calls,
                    definitions = definitions,
                    autoApproveAllTools = settings.autoApproveAllTools,
                    forceConfirmToolCalls = settings.forceConfirmToolCalls,
                )
            },
            executeTool = { call, definition, _ ->
                executeGenerationToolBatch(
                    approval = GenerationToolApproval(listOf(call)),
                    definitions = listOfNotNull(definition),
                    json = json,
                    onCheckpoint = { checkpointedTool ->
                        val checkpointed = reactionTurn.markPending(messages.replaceLastGenerationTools(listOf(checkpointedTool)))
                        withContext(NonCancellable) { onToolCheckpoint?.invoke(checkpointed) }
                        messages = checkpointed
                    },
                ).singleOrNull() ?: call
            },
            onToolsUpdated = { updated, update ->
                messages = reactionTurn.markPending(updated)
                when (update) {
                    GenerationToolUpdate.APPROVAL -> {
                        withContext(NonCancellable) { onToolCheckpoint?.invoke(messages) }
                        emit(GenerationChunk.Messages(messages))
                    }
                    GenerationToolUpdate.RESULTS -> emit(GenerationChunk.Messages(
                        reactionTurn.markPending(messages.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                        ))
                    ))
                }
            },
        )
        reactionTurn.onGenerationSucceeded(messages.lastOrNull())
 
    }.throttleLatest(STREAM_UI_THROTTLE_MS)
        .flowOn(Dispatchers.IO)
 
    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        pluginPromptInjections: List<String> = emptyList(),
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationContextSummary: String? = null,
        workspaceCwd: String? = null,
        conversationId: String? = null,
        reactionTurn: ReactionContextTurn? = null,
    ) {
        LatencyProbe.mark(conversationId, "进入拼装")
        val baseMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }
 
                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
 
                LatencyProbe.mark(conversationId, "系统词")
                // 外置记忆库召回
                try {
                    val externalMemoryConfigs = settings.externalMemories.filter {
                        it.enabled && it.id in assistant.externalMemoryIds
                    }
                    externalMemoryConfigs.forEach { config ->
                        Log.i(TAG, "ExternalMemory config: name=${config.name}, url=${config.supabaseUrl}, table=${config.tableName}, summaryTable=${config.summariesTableName}, embeddingModelId=${config.embeddingModelId}, autoSaveDiarySummary=${config.autoSaveDiarySummary}")
                    }
                    if (externalMemoryConfigs.isNotEmpty()) {
                        val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }
                        val queryText = lastUserMessage?.toText()?.take(200)?.trim() ?: ""
                        // 并发检索所有外置记忆库配置，每个配置最多 8 秒超时
                        val allRecalled = coroutineScope {
                            externalMemoryConfigs.map { config ->
                                async {
                                    withTimeoutOrNull(8.seconds) {
                                        runCatching {
                                            val service = me.rerere.rikkahub.data.service.ExternalMemoryService(config)
                                            val recalled = mutableListOf<String>()

                            // 如果配置了向量模型且开启了日记摘要，使用向量召回日记摘要
                            if (config.embeddingModelId != null && queryText.isNotBlank() && config.autoSaveDiarySummary) {
                                                val embeddingModel = settings.findModelById(config.embeddingModelId)
                                                if (embeddingModel != null) {
                                                    val embeddingProvider = embeddingModel.findProvider(settings.providers)
                                                    if (embeddingProvider != null) {
                                                        val embeddingProviderImpl = providerManager.getProviderByType(embeddingProvider)
                                                        val embedResult = embeddingProviderImpl.generateEmbedding(
                                                            providerSetting = embeddingProvider,
                                                            params = EmbeddingGenerationParams(
                                                                model = embeddingModel,
                                                                input = listOf(queryText),
                                                            )
                                                        )
                                                        val queryEmbedding = embedResult.embeddings.firstOrNull()
                                                        if (queryEmbedding != null) {
                                                            val recalledSummaries = service.vectorRecallSummaries(
                                                                queryEmbedding = queryEmbedding,
                                                                assistantId = assistant.id.toString(),
                                                                count = config.recallCount,
                                                            ).getOrDefault(emptyList())
                                                            recalledSummaries.forEach { summary ->
                                                                recalled.add(summary.content)
                                                            }
                                                            Log.d(TAG, "Vector recall ${recalledSummaries.size} summaries from ${config.name}")
                                                        }
                                                    }
                                                }
                                            } else {
                                                // 回退：文本召回聊天记录
                                                val recalledMessages = if (queryText.isNotBlank()) {
                                                    service.searchMessages(
                                                        assistantId = assistant.id.toString(),
                                                        keyword = queryText,
                                                        limit = config.recallCount,
                                                    ).getOrDefault(emptyList())
                                                } else {
                                                    service.queryLatestMessages(
                                                        assistantId = assistant.id.toString(),
                                                        limit = config.recallCount,
                                                    ).getOrDefault(emptyList())
                                                }
                                                recalledMessages.forEach { msg ->
                                                    val prefix = when (msg.role) {
                                                        "assistant" -> "AI"
                                                        "user" -> "用户"
                                                        else -> msg.role
                                                    }
                                                    recalled.add("[$prefix] ${msg.content}")
                                                }
                                            }
                                            recalled
                                        }.onFailure {
                                            Log.w(TAG, "External memory recall failed for ${config.name}", it)
                                        }.getOrNull()
                                    } ?: run {
                                        Log.w(TAG, "External memory recall timed out for ${config.name}")
                                        null
                                    }
                                }
                            }.awaitAll()
                                .filterNotNull()
                                .flatten()
                        }
                        if (allRecalled.isNotEmpty()) {
                            appendLine()
                            appendLine("## 外置记忆库")
                            allRecalled.reversed().forEachIndexed { index, memory ->
                                appendLine("${index + 1}. ${memory}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "External memory recall failed", e)
                }
 
                LatencyProbe.mark(conversationId, "外置记忆")
                if (assistant.enableRecentChatsReference) {
                    appendLine()
                    append(buildRecentChatsPrompt(assistant, conversationRepo))
                }
 
                LatencyProbe.mark(conversationId, "最近聊天")
                // 代码文件命名和ZIP打包功能说明
                appendLine()
                append(buildCodeBlockPrompt())
 
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
 
                // 插件提示词注入
                if (pluginPromptInjections.isNotEmpty()) {
                    pluginPromptInjections.forEach { injection ->
                        appendLine()
                        appendLine()
                        append(injection)
                    }
                }
 
                // 允许跳过回复
                if (assistant.allowSkipReply) {
                    appendLine()
                    appendLine()
                    appendLine("## Skip Reply")
                    appendLine("If you determine that no reply is needed (e.g., the user's message doesn't require a response, or you have nothing meaningful to add), you may reply with exactly `[SKIP]` (without any other text). This message will be hidden from the user. Use this sparingly and only when truly appropriate.")
                }

                // 屏幕跳转能力（AI总是可以跳转，不需要开关）
                if (true) {
                    appendLine()
                    appendLine()
                    appendLine("## 屏幕跳转能力")
                    appendLine("你可以在回复末尾追加 [JUMP] 标记（单独一行）来把聊天界面拉到用户屏幕最前面。")
                    appendLine("适用场景：")
                    appendLine("- 用户说要去别的应用，你觉得需要把用户拉回来时")
                    appendLine("- 你觉得接下来的内容需要用户立即看到时")
                    appendLine("不适用场景：")
                    appendLine("- 一般闲聊不需要跳转")
                    appendLine("- 用户正在跟你正常对话时不需要跳转")
                    appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
                }
 
                // 分气泡: 告知模型它自己能控制消息如何被拆成多个气泡
                if (assistant.splitBubbleByLine) {
                    appendLine()
                    appendLine()
                    appendLine("## Message Bubbles")
                    appendLine("Your reply will be automatically split into separate chat bubbles at every line break (\\n) you write, similar to how a person sends several short texts in a row instead of one long message. You are fully in control of this: write a line break whenever you want the previous thought/sentence to appear as its own bubble, and keep things on the same line when they belong together. Do not insert blank lines purely for spacing — every line break becomes a new bubble, so use them intentionally. Exception: line breaks inside fenced code blocks (```) and Markdown tables are preserved as-is and will NOT create new bubbles, since those must stay intact as a single block.")
                }
 
            }
            LatencyProbe.mark(conversationId, "工具说明等")
            addAll(buildConversationRequestMessages(system, messages, assistant.contextMessageSize, conversationContextSummary))
            LatencyProbe.mark(conversationId, "截上下文")
        }
        val internalMessages = chatInteractionRepository.decorate(
            baseMessages,
            conversationId,
            assistant,
            sourceMessages = messages,
            turn = reactionTurn,
        ).also { LatencyProbe.mark(conversationId, "表情装饰") }
            .let { filesManager.validAttachments(it, forSending = true) }
            .also { LatencyProbe.mark(conversationId, "附件校验") }
            .transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        ).also { LatencyProbe.mark(conversationId, "输入转换") }
            .let { filesManager.validAttachments(it, forSending = true) }.filterNot { it.parts.isEmpty() }
        LatencyProbe.mark(conversationId, "二次校验")
 
        var messages: List<UIMessage> = messages
        val params = buildAssistantGenerationParams(
            assistant = assistant,
            model = model,
            provider = provider,
            conversationId = conversationId,
            tools = tools,
        )
        LatencyProbe.mark(conversationId, "参数")
        aiLoggingManager.addLog(
            AILogging.Generation(
                params = params,
                messages = messages,
                providerSetting = provider,
                stream = stream,
            )
        )
        LatencyProbe.mark(conversationId, LatencyProbe.REQUEST_BUILT)
        messages = generateModelTurn(
            providerImpl = providerImpl,
            providerSetting = provider,
            requestMessages = internalMessages,
            initialMessages = messages,
            params = params,
            model = model,
            stream = stream,
            mergeUsage = true,
            onUpdateMessages = onUpdateMessages,
        )
    }
 
    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")
 
        val providerHandler = usageRecorder.wrap(
            providerManager.getProviderByType(provider), UsageTag(UsageSource.TRANSLATION),
        )
 
        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )
 
            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""
 
            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""
 
                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""
 
            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
 
/**
 * 把原始 Flow 的高频发射节流成"每 periodMillis 毫秒最多发一次最新值"。
 *
 * 实现方式：对上游调用 conflate()（只保留未被消费的最新一个值，中间值会被丢弃），
 * 然后在 collect 里每处理完一个值就 delay(periodMillis)。这样在上游快速连续发射时，
 * delay 期间产生的多个值会被 conflate 自动合并成"最新一个"，delay 结束后立刻拿到它；
 * 但由于用的是"发一个、等一段时间、再要下一个"的顺序结构，上游结束前最后一次真正的发射
 * 一定会被完整地 collect 到并 emit 出去，不会像 sample() 那样有丢失最终值的风险。
 *
 * 用于把 AI 流式输出的高频消息更新（可能每秒几十次）降频到 UI 友好的节奏，从源头
 * 消除打字机效果的抖动/掉帧，同时保证生成结束时 UI 一定能拿到完整的最终内容。
 */
private fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> {
    val upstream = this
    return flow {
        upstream.conflate().collect { value ->
            emit(value)
            delay(periodMillis)
        }
    }
}
 
/**
 * 构建代码块提示 - 告知AI代码文件命名和ZIP打包功能
 */
private fun buildCodeBlockPrompt(): String = buildString {
    appendLine("## Code Block Rules (MUST FOLLOW)")
    appendLine()
    appendLine("1. **ALWAYS name code blocks with filenames**: You MUST use the actual filename as the code block language tag instead of just the language name. This is critical for proper file saving and syntax highlighting. Examples:")
    appendLine("   - ✅ Correct: ```MainActivity.kt instead of ```kotlin")
    appendLine("   - ✅ Correct: ```index.html instead of ```html")
    appendLine("   - ✅ Correct: ```styles.css instead of ```css")
    appendLine("   - ✅ Correct: ```package.json instead of ```json")
    appendLine("   - ✅ Correct: ```manifest.xml instead of ```xml")
    appendLine("   - ✅ Correct: ```main.py instead of ```python")
    appendLine("   - ✅ Correct: ```App.vue instead of ```vue")
    appendLine("   - ❌ Wrong: ```kotlin, ```python, ```javascript (these don't provide filenames)")
    appendLine("   - For code without a specific filename, use a descriptive name like ```example.ts, ```helper.py")
    appendLine()
    appendLine("2. **ZIP Download via `write_files` tool**: Users can download code files as a ZIP ONLY when you call this tool.")
    appendLine("   - **Full write** (first time / new files): `{\"zip_name\":\"project.zip\",\"files\":[{\"name\":\"MainActivity.kt\",\"content\":\"...\"}]}`")
    appendLine("   - **Incremental edit** (saves tokens! For modifying existing files): `{\"zip_name\":\"project-v2.zip\",\"base_files\":\"previous\",\"edits\":[{\"name\":\"MainActivity.kt\",\"search\":\"old code\",\"replace\":\"new code\"}]}`")
    appendLine("   - The `edits` mode applies search/replace to the files from your previous `write_files` call. Files not mentioned in `edits` keep their content unchanged.")
    appendLine("   - Always use actual filenames (e.g. `MainActivity.kt`) as code block language tags, not just language names (e.g. `kotlin`).")
}
