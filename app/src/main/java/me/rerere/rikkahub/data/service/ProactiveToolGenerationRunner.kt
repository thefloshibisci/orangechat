/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationToolApproval
import me.rerere.rikkahub.data.ai.GenerationToolUpdate
import me.rerere.rikkahub.data.ai.executeGenerationToolBatch
import me.rerere.rikkahub.data.ai.executionCheckpointState
import me.rerere.rikkahub.data.ai.generateModelTurn
import me.rerere.rikkahub.data.ai.hasDurableToolEvidence
import me.rerere.rikkahub.data.ai.recoverToolHistoryForRequest
import me.rerere.rikkahub.data.ai.replaceLastGenerationTools
import me.rerere.rikkahub.data.ai.runGenerationToolLoop
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.withExecutionCheckpoint
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.gongdu.GongduToolResultTransformer
import me.rerere.rikkahub.data.interaction.ChatInteractionRepository
import me.rerere.rikkahub.data.interaction.ReactionContextTurn
import me.rerere.rikkahub.data.interaction.withInteractionRequestScope
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.video.withoutHistoricalCameraImages
import me.rerere.rikkahub.service.ChatService
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val RUNNER_TAG = "ProactiveMessageTrigger"

internal fun trackGeneratedProactiveMessage(
    id: Uuid,
    trace: ProactiveMessageTrace,
    runAssistantMessageIds: MutableSet<Uuid>,
    protectedMessageIds: Set<Uuid>,
) {
    if (!registerProactiveRunMessageId(
            messageId = id,
            protectedMessageIds = protectedMessageIds,
            runMessageIds = runAssistantMessageIds,
        )
    ) {
        trace.event(
            "ownership_collision",
            "message=${ProactiveMessageTrace.safeId(id)} protected=true",
        )
        error("Proactive generation attempted to overwrite a pre-existing message")
    }
}

internal class ProactiveToolGenerationRunner(
    private val context: Context,
    private val chatService: ChatService,
    private val filesManager: FilesManager,
    private val chatInteractionRepository: ChatInteractionRepository,
    private val executor: ProactiveTaskExecutor,
    private val json: Json,
    private val outputTransformers: List<OutputMessageTransformer>,
    private val maxToolSteps: Int,
    private val disallowedPaidTools: Set<String>,
) {
    suspend fun generateWithTools(
        conversationId: Uuid,
        providerImpl: Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        initialMessages: List<UIMessage>,
        params: TextGenerationParams,
        tools: List<Tool>,
        model: Model,
        assistant: Assistant,
        settings: Settings,
        trace: ProactiveMessageTrace,
        runAssistantMessageIds: MutableSet<Uuid>,
        protectedMessageIds: Set<Uuid>,
        activityStore: ProactiveActivityStore,
        reactionTurn: ReactionContextTurn,
        task: ProactiveTask,
    ): Triple<List<UIMessage>, Boolean, Boolean> {
        var messages = initialMessages
        var hasToolCalls = false
        var hasJumpFlag = false

        suspend fun persistToolCheckpoint(call: UIMessagePart.Tool) {
            val checkpointed = reactionTurn.markPending(messages.replaceLastGenerationTools(listOf(call)))
            trackGeneratedProactiveMessage(
                checkpointed.last().id,
                trace,
                runAssistantMessageIds,
                protectedMessageIds,
            )
            messages = checkpointed
            when (call.executionCheckpointState) {
                "started" -> activityStore.append(
                    ProactiveActivity(
                        id = "tool-start:${call.toolCallId}",
                        timestamp = System.currentTimeMillis(),
                        outcome = "开始尝试后台工具；如无后续返回记录，结果尚未确认，勿盲目重试",
                        note = call.toolName.take(80),
                    ),
                )

                "completed", "failed" -> {
                    runCatching {
                        activityStore.append(
                            ProactiveActivity(
                                id = "tool:${call.toolCallId}",
                                timestamp = System.currentTimeMillis(),
                                outcome = "后台工具调用已返回；如需确认结果，应核查业务状态，不要盲目重复执行",
                                toolNames = listOf(call.toolName.take(80)),
                            ),
                        )
                    }.onFailure { Log.w(RUNNER_TAG, "Could not persist background tool receipt", it) }
                    trace.event(
                        "tool_result",
                        "name=${call.toolName} success=${call.executionCheckpointState == "completed"} " +
                            "outputParts=${call.output.size}",
                    )
                }
            }
        }

        messages = runGenerationToolLoop(
            initialMessages = initialMessages,
            maxSteps = maxToolSteps,
            reserveFinalModelTurn = true,
            toolLimitError = "Proactive tool limit reached before a final reply",
            toolsForStep = { step -> if (step == maxToolSteps) emptyList() else tools },
            onStep = { step, finalTurn ->
                Log.d(RUNNER_TAG, "generateWithTools: step $step/$maxToolSteps final=$finalTurn")
                trace.event("generation_step", "step=$step final=$finalTurn")
            },
            generateTurn = { turnMessages, turnTools, step ->
                val sourceMessages = chatService.getConversationFlow(conversationId).value.currentMessages.let { source ->
                    val requestedIds = turnMessages.mapTo(hashSetOf()) { it.id }
                    source.take(source.indexOfLast { it.id in requestedIds } + 1)
                }
                val requestMessages = filesManager.validAttachments(
                    mergeAdjacentSameRoleMessages(
                        prepareProactiveToolContinuation(
                            chatInteractionRepository.decorate(
                                turnMessages.withoutHistoricalCameraImages(),
                                conversationId.toString(),
                                assistant,
                                sourceMessages = sourceMessages,
                                turn = reactionTurn,
                            ).recoverToolHistoryForRequest(json, allowLastMessageResume = false),
                        ),
                    ),
                    forSending = true,
                ).filterNot { it.parts.isEmpty() }.transforms(
                    transformers = listOf(GongduToolResultTransformer),
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                ).withoutHistoricalCameraImages()
                var streamMessages = beginProactiveAssistantTurn(turnMessages, model.id)
                trace.event(
                    "assistant_turn",
                    "step=$step message=${ProactiveMessageTrace.safeId(streamMessages.last().id)} fresh=true",
                )
                streamMessages = executor.networkTurn(task) {
                    generateModelTurn(
                        providerImpl = providerImpl,
                        providerSetting = providerSetting,
                        requestMessages = requestMessages,
                        initialMessages = streamMessages,
                        params = params.copy(tools = turnTools),
                        model = model,
                        stream = assistant.streamOutput,
                        mergeUsage = false,
                        onUpdateMessages = { updated ->
                            updated.lastOrNull { it.role == MessageRole.ASSISTANT }?.let { currentAiMessage ->
                                trackGeneratedProactiveMessage(
                                    currentAiMessage.id,
                                    trace,
                                    runAssistantMessageIds,
                                    protectedMessageIds,
                                )
                            }
                        },
                    )
                }
                val aiMessage = streamMessages.lastOrNull() ?: run {
                    Log.w(RUNNER_TAG, "No message in AI response")
                    return@runGenerationToolLoop turnMessages
                }
                val rawAiText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                if (parseProactiveDecision(rawAiText, false).shouldJump) {
                    hasJumpFlag = true
                    Log.d(RUNNER_TAG, "[JUMP] flag detected in raw AI output")
                }
                val processedMessage = listOf(aiMessage).transforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                ).first()
                messages = reactionTurn.markPending(turnMessages + processedMessage)
                val toolCalls = processedMessage.getTools().filter { !it.isExecuted }
                trace.event(
                    "model_result",
                    "step=$step shape=${ProactiveMessageTrace.messageShape(processedMessage)} " +
                        "pendingTools=${toolCalls.size}",
                )
                if (toolCalls.isEmpty()) {
                    val now = Clock.System.now()
                    val finalMessage = processedMessage.copy(
                        parts = processedMessage.parts.map { part ->
                            if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                                part.copy(finishedAt = now)
                            } else {
                                part
                            }
                        },
                    )
                    messages = reactionTurn.markPending(messages.dropLast(1) + finalMessage)
                    trackGeneratedProactiveMessage(
                        finalMessage.id,
                        trace,
                        runAssistantMessageIds,
                        protectedMessageIds,
                    )
                } else {
                    hasToolCalls = true
                    Log.d(RUNNER_TAG, "Tool calls detected: ${toolCalls.size}")
                }
                messages
            },
            executeTool = { toolCall, toolDef, step ->
                executor.requireAllowed(task)
                trace.event(
                    "tool_call",
                    "step=$step name=${toolCall.toolName} id=${ProactiveMessageTrace.safeId(toolCall.toolCallId)} " +
                        "definitionFound=${toolDef != null}",
                )
                if (!toolCall.hasDurableToolEvidence && ToolNaming.toDisplayName(
                        toolDef?.name.orEmpty(),
                    ) in disallowedPaidTools
                ) {
                    val rejected = toolCall.copy(
                        output = listOf(
                            UIMessagePart.Text("This paid tool is not available in proactive background runs."),
                        ),
                    ).withExecutionCheckpoint("denied")
                    withContext(NonCancellable) { persistToolCheckpoint(rejected) }
                    rejected
                } else if (!toolCall.hasDurableToolEvidence && toolDef != null && !canExecuteProactiveTool(
                        toolDef.needsApproval,
                        settings.forceConfirmToolCalls,
                        settings.autoApproveAllTools,
                    )
                ) {
                    val rejected = toolCall.copy(
                        output = listOf(
                            UIMessagePart.Text("This tool requires foreground approval. It was not executed in the background."),
                        ),
                    ).withExecutionCheckpoint("denied")
                    withContext(NonCancellable) { persistToolCheckpoint(rejected) }
                    rejected
                } else {
                    executeGenerationToolBatch(
                        approval = GenerationToolApproval(listOf(toolCall)),
                        definitions = listOfNotNull(toolDef?.withInteractionRequestScope(messages.last().id)),
                        json = json,
                        onCheckpoint = ::persistToolCheckpoint,
                    ).singleOrNull() ?: toolCall
                }
            },
            onToolsUpdated = { updated, update ->
                messages = reactionTurn.markPending(updated)
                if (update == GenerationToolUpdate.APPROVAL) {
                    trace.event("tool_approval", "background approval unexpectedly paused")
                }
            },
        )

        return Triple(messages, hasToolCalls, hasJumpFlag)
    }
}
