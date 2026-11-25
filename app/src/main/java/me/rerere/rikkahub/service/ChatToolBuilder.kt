/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.Application
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.normalizeMcpArguments
import me.rerere.rikkahub.data.ai.tools.LocalMomentsBudgetRegistry
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatSelectionStore
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.SystemToolOption
import me.rerere.rikkahub.data.ai.tools.SystemTools
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.ai.tools.buildLocalMomentsChatDraftTool
import me.rerere.rikkahub.data.ai.tools.buildProactiveMemorySearchTool
import me.rerere.rikkahub.data.ai.tools.buildProactiveWakeupTool
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.gongdu.gongduTools
import me.rerere.rikkahub.data.interaction.AssistantCustomizationPatch
import me.rerere.rikkahub.data.interaction.ChatInteraction
import me.rerere.rikkahub.data.interaction.ChatInteractionRepository
import me.rerere.rikkahub.data.interaction.ReactionPresetToolResult
import me.rerere.rikkahub.data.interaction.buildAssistantSelfCustomizationTools
import me.rerere.rikkahub.data.interaction.buildChatInteractionTools
import me.rerere.rikkahub.data.interaction.buildRichChatTools
import me.rerere.rikkahub.data.interaction.effectiveChatReactionPresets
import me.rerere.rikkahub.data.listen.listenTools
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.reading.readingTools
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.data.service.ProactiveWakeup
import me.rerere.rikkahub.data.service.ProactiveWakeupStore
import me.rerere.rikkahub.data.service.acceptsWindow
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid

internal class ChatToolBuilder(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val chatInteractionRepository: ChatInteractionRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val pluginToolProvider: PluginToolProvider,
    private val workspaceRepository: WorkspaceRepository,
    private val localMomentsTurnBudgets: LocalMomentsBudgetRegistry,
    private val getOrCreateSession: (Uuid) -> ConversationSession,
    private val customizeCurrentAssistant: suspend (Uuid, Uuid, AssistantCustomizationPatch?) -> Assistant,
    private val createChatInteraction: suspend (Uuid, Uuid, String, String, String, String, String, Boolean?) -> ChatInteraction,
    private val respondChatInteraction: suspend (Uuid, String, String, String) -> ChatInteraction,
    private val appendAssistantAvatarInteraction: suspend (Uuid, Assistant) -> String,
    private val manageReactionPresets: suspend (String, String) -> ReactionPresetToolResult,
) {
    fun buildProactiveWakeupTools(
        settings: Settings,
        assistant: Assistant,
        conversation: Conversation,
    ): List<Tool> {
        fun canSchedule(current: Settings): Boolean {
            val setting = current.proactiveMessageSetting
            if (!setting.enabled || !setting.allowAiScheduledWakeup) return false
            val roleId = assistant.id.toString()
            val participants = setting.assistantConfigs
                ?: return roleId == setting.assistantId.ifBlank { current.getCurrentAssistant().id.toString() }
            return participants.singleOrNull { it.assistantId == roleId }
                ?.acceptsWindow(conversation.id.toString(), appointment = true) == true
        }
        if (!canSchedule(settings)) return emptyList()
        val anchor = conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id
        return listOf(
            buildProactiveWakeupTool { time, reason ->
                currentCoroutineContext().ensureActive()
                val current = settingsStore.settingsFlow.first()
                check(canSchedule(current)) {
                    "此角色或会话未启用 AI 约定唤醒"
                }
                check(current.assistants.any { it.id == assistant.id }) { "Assistant no longer exists" }
                val session = getOrCreateSession(conversation.id)
                session.withStateLock {
                    val latest = session.state.value
                    check(
                        latest.assistantId == assistant.id &&
                            latest.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id == anchor,
                    ) {
                        "Conversation changed; use its latest chat before making an appointment"
                    }
                    check(conversationRepo.existsConversationById(conversation.id)) { "Conversation no longer exists" }
                    currentCoroutineContext().ensureActive()
                    val store = ProactiveWakeupStore(context)
                    if (time == null) {
                        store.cancel(conversation.id.toString())
                    } else {
                        store.put(
                            ProactiveWakeup(
                                assistantId = assistant.id.toString(),
                                conversationId = conversation.id.toString(),
                                timeMillis = time,
                                reason = reason,
                            ),
                        )
                    }
                    ProactiveMessageService.scheduleNext(context, current.proactiveMessageSetting)
                    if (time == null) {
                        "已取消当前会话的待执行约定。无约定时恢复按原间隔检查。"
                    } else {
                        "已保存当前会话的唯一待执行约定：${Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())}。" +
                            "到点尝试读取最新聊天，再决定是否发消息。不受普通活跃时段限制，仍遵守连续追问上限；系统后台限制可能导致延后。"
                    }
                }
            },
        )
    }

    suspend fun buildProactiveMemoryTools(assistant: Assistant): List<Tool> = buildList {
        if (assistant.enableMemory) {
            add(
                buildProactiveMemorySearchTool {
                    if (assistant.useGlobalMemory) memoryRepository.getGlobalMemories()
                    else memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
            )
        }
        // Only configured, named read-only memory retrieval endpoints belong to the light tier.
        mcpManager.getAllAvailableTools(assistant)
            .filter { (_, tool) -> tool.name in setOf("breath_search", "search_memory", "search_memories") }
            .sortedBy { (serverId, tool) -> "$serverId/${tool.name}" }
            .take(2)
            .forEach { (serverId, tool) ->
                add(
                    Tool(
                        name = ToolNaming.buildMcpToolName(serverId, tool.name),
                        description = tool.description ?: "Search configured memories",
                        parameters = { tool.inputSchema },
                        needsApproval = tool.needsApproval,
                        execute = {
                            mcpManager.callToolForModel(
                                serverId,
                                tool.name,
                                if (it is JsonObject) it else normalizeMcpArguments(it),
                                assistant.id,
                            )
                        },
                    ),
                )
            }
    }

    suspend fun buildAvailableTools(
        settings: Settings,
        assistant: Assistant,
        conversationId: Uuid,
        conversation: Conversation,
        allowAppUsage: Boolean,
        allowImageGeneration: Boolean = false,
        headless: Boolean = false,
        momentsTurnAnchorUserId: Uuid? = null,
    ): List<Tool> = buildList {
        addAll(buildProactiveWakeupTools(settings, assistant, conversation))
        addAll(gongduTools(settings, assistant.id))
        if (settings.enableWebSearch) {
            addAll(createSearchTools(settings))
        }
        val invocationContext = buildToolInvocationContext(
            settings = settings,
            assistant = assistant,
            conversationId = conversationId,
            conversation = conversation,
            headless = headless,
        )
        addAll(listenTools(invocationContext))
        addAll(readingTools(invocationContext))
        val momentsBudgetAnchorUserId = if (headless) {
            null
        } else {
            (momentsTurnAnchorUserId
                ?: conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id)?.toString()
        }
        addAll(
            localTools.getTools(
                assistant.localTools.filterNot { it is LocalToolOption.RequestVoiceCall },
                invocationContext,
                momentsBudgetRegistry = localMomentsTurnBudgets,
                momentsBudgetAnchorUserId = momentsBudgetAnchorUserId,
            ),
        )
        addAll(
            buildLocalMomentsChatDraftTool(
                context = context,
                settingsStore = settingsStore,
                pluginManager = localTools.hostPluginManager,
                invocationContext = invocationContext,
                turn = LocalMomentsChatSelectionStore.turnFor(
                    conversationId = conversationId.toString(),
                    assistantId = assistant.id.toString(),
                    messages = conversation.currentMessages,
                ),
            ),
        )
        if (allowImageGeneration) add(localTools.imageGenerationTool())

        val systemToolsOptions = settings.systemToolsSetting.getEnabledOptions().toMutableSet()
        if (!allowAppUsage) {
            systemToolsOptions.remove(SystemToolOption.AppUsage)
        }
        if (systemToolsOptions.isNotEmpty()) {
            addAll(
                SystemTools(context, settings).getTools(
                    systemToolsOptions,
                    conversation.currentMessages,
                    filesManager,
                ),
            )
        }

        addAll(
            createWorkspaceTools(
                assistant.workspaceId?.toString(),
                workspaceRepository,
                assistantId = assistant.id.toString(),
            ),
        )
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                    skillManager = skillManager,
                ),
            )
        }
        mcpManager.getAllAvailableTools(assistant).forEach { (serverId, tool) ->
            add(
                Tool(
                    name = ToolNaming.buildMcpToolName(serverId, tool.name),
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = tool.needsApproval,
                    execute = {
                        mcpManager.callToolForModel(
                            serverId,
                            tool.name,
                            if (it is JsonObject) it else normalizeMcpArguments(it),
                            assistant.id,
                        )
                    },
                ),
            )
        }
        addAll(pluginToolProvider.getTools(conversation.currentMessages, conversation.id.toString()))
        addAll(
            buildAssistantSelfCustomizationTools(assistant) { patch ->
                customizeCurrentAssistant(conversationId, assistant.id, patch)
            },
        )
        addAll(
            buildRichChatTools(
                allowCalls = assistant.allowIncomingVoiceCalls,
                pretendRich = assistant.pretendRich,
                create = { kind, amount, note, requestId ->
                    createChatInteraction(
                        conversationId,
                        assistant.id,
                        "assistant",
                        kind,
                        amount,
                        note,
                        requestId,
                        if (kind == "call") null else !assistant.pretendRich,
                    ).also { card ->
                        if (kind == "call" && card.status == "pending") {
                            localTools.showIncomingChatCall(conversationId.toString())
                        }
                    }
                },
                respond = { id, action -> respondChatInteraction(conversationId, id, "assistant", action) },
            ),
        )
        addAll(
            buildChatInteractionTools(
                assistant = assistant,
                reactionPresets = effectiveChatReactionPresets(settings.customReactionPresets),
                onAvatar = { appendAssistantAvatarInteraction(conversationId, assistant) },
                onReaction = { target, emoji, add, quote ->
                    // Read presets at execution time: reaction_preset may have added one earlier in
                    // this same turn, after the tool list (and its settings snapshot) was built.
                    chatInteractionRepository.react(
                        conversationId,
                        assistant,
                        target,
                        "assistant",
                        emoji,
                        settingsStore.settingsFlowRaw.first().customReactionPresets,
                        add,
                        quote,
                    )
                },
                onManageReactionPresets = manageReactionPresets,
            ),
        )
    }
}
