package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import kotlin.uuid.Uuid

/** Frozen request controls; persona, memory and appearance continue to follow the assistant. */
@Serializable
data class ConversationConfig(
    val enableWebSearch: Boolean = false,
    val searchServiceSelected: Int = 0,
    val builtInSearch: Boolean = false,
    val chatModelId: Uuid? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val contextMessageSize: Int = 0,
    val maxContextImages: Int = 0,
    val maxContextVoices: Int = 0,
    val streamOutput: Boolean = true,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val enabledSkills: Set<String> = emptySet(),
    val localTools: List<LocalToolOption> = emptyList(),
    val workspaceId: Uuid? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
) {
    fun withAssistant(value: Assistant): ConversationConfig = copy(
        chatModelId = value.chatModelId,
        reasoningLevel = value.reasoningLevel,
        temperature = value.temperature,
        topP = value.topP,
        maxTokens = value.maxTokens,
        contextMessageSize = value.contextMessageSize,
        maxContextImages = value.maxContextImages,
        maxContextVoices = value.maxContextVoices,
        streamOutput = value.streamOutput,
        customHeaders = value.customHeaders,
        customBodies = value.customBodies,
        mcpServers = value.mcpServers,
        enabledSkills = value.enabledSkills,
        localTools = value.localTools,
        workspaceId = value.workspaceId,
        modeInjectionIds = value.modeInjectionIds,
        lorebookIds = value.lorebookIds,
    )

    /** Merge only edited controls so stale UI snapshots cannot revert a different control. */
    fun withAssistantEdit(previous: Assistant, desired: Assistant): ConversationConfig = copy(
        chatModelId = if (previous.chatModelId != desired.chatModelId) desired.chatModelId else chatModelId,
        reasoningLevel = if (previous.reasoningLevel != desired.reasoningLevel) desired.reasoningLevel else reasoningLevel,
        temperature = if (previous.temperature != desired.temperature) desired.temperature else temperature,
        topP = if (previous.topP != desired.topP) desired.topP else topP,
        maxTokens = if (previous.maxTokens != desired.maxTokens) desired.maxTokens else maxTokens,
        contextMessageSize = if (previous.contextMessageSize != desired.contextMessageSize) desired.contextMessageSize else contextMessageSize,
        maxContextImages = if (previous.maxContextImages != desired.maxContextImages) desired.maxContextImages else maxContextImages,
        maxContextVoices = if (previous.maxContextVoices != desired.maxContextVoices) desired.maxContextVoices else maxContextVoices,
        streamOutput = if (previous.streamOutput != desired.streamOutput) desired.streamOutput else streamOutput,
        customHeaders = if (previous.customHeaders != desired.customHeaders) desired.customHeaders else customHeaders,
        customBodies = if (previous.customBodies != desired.customBodies) desired.customBodies else customBodies,
        mcpServers = if (previous.mcpServers != desired.mcpServers) desired.mcpServers else mcpServers,
        enabledSkills = if (previous.enabledSkills != desired.enabledSkills) desired.enabledSkills else enabledSkills,
        localTools = if (previous.localTools != desired.localTools) desired.localTools else localTools,
        workspaceId = if (previous.workspaceId != desired.workspaceId) desired.workspaceId else workspaceId,
        modeInjectionIds = if (previous.modeInjectionIds != desired.modeInjectionIds) desired.modeInjectionIds else modeInjectionIds,
        lorebookIds = if (previous.lorebookIds != desired.lorebookIds) desired.lorebookIds else lorebookIds,
    )
}

fun Assistant.withConversation(config: ConversationConfig?): Assistant = config?.let {
    copy(
        chatModelId = it.chatModelId,
        reasoningLevel = it.reasoningLevel,
        temperature = it.temperature,
        topP = it.topP,
        maxTokens = it.maxTokens,
        contextMessageSize = it.contextMessageSize,
        maxContextImages = it.maxContextImages,
        maxContextVoices = it.maxContextVoices,
        streamOutput = it.streamOutput,
        customHeaders = it.customHeaders,
        customBodies = it.customBodies,
        mcpServers = it.mcpServers,
        enabledSkills = it.enabledSkills,
        localTools = it.localTools,
        workspaceId = it.workspaceId,
        modeInjectionIds = it.modeInjectionIds,
        lorebookIds = it.lorebookIds,
    )
} ?: this

fun Settings.getAssistantOf(conversation: Conversation): Assistant {
    val assistant = (getAssistantById(conversation.assistantId) ?: getCurrentAssistant())
        .withConversation(conversation.config)
    return getChatModelOf(conversation)?.let { assistant.copy(chatModelId = it.id) } ?: assistant
}

fun Settings.getChatModelOf(conversation: Conversation): Model? {
    val assistant = getAssistantById(conversation.assistantId) ?: getCurrentAssistant()
    val selected = conversation.config?.chatModelId?.let { findModelById(it) }
        ?: assistant.chatModelId?.let { findModelById(it) }
        ?: findModelById(chatModelId) ?: return null
    val config = conversation.config ?: return selected
    return selected.copy(tools = if (config.builtInSearch) selected.tools + BuiltInTools.Search
        else selected.tools - BuiltInTools.Search)
}

/** A read-only overlay for request consumers. Never persist this view to SettingsStore. */
fun Settings.withConversationConfig(conversation: Conversation): Settings {
    val model = getChatModelOf(conversation)
    val resolved = getAssistantOf(conversation)
    val assistant = if (model == null) resolved else resolved.copy(chatModelId = model.id)
    return copy(
        assistantId = assistant.id,
        assistants = assistants.map { if (it.id == assistant.id) assistant else it },
        enableWebSearch = conversation.config?.enableWebSearch ?: enableWebSearch,
        searchServiceSelected = conversation.config?.searchServiceSelected ?: searchServiceSelected,
        providers = providers.map { provider ->
            if (model == null || provider.models.none { it.id == model.id }) provider else provider.editModel(model)
        },
    )
}

/** Bind at actual generation or explicit edit; opening an old chat never writes defaults. */
fun Conversation.bindConfig(settings: Settings): Conversation {
    val model = settings.getChatModelOf(this)
    val current = config
    if (current != null) {
        return if (model == null || current.chatModelId == model.id) this
        else copy(config = current.copy(chatModelId = model.id))
    }
    val assistant = settings.getAssistantById(assistantId) ?: settings.getCurrentAssistant()
    return copy(config = ConversationConfig(
        enableWebSearch = settings.enableWebSearch,
        searchServiceSelected = settings.searchServiceSelected,
        builtInSearch = model?.tools?.contains(BuiltInTools.Search) == true,
    ).withAssistant(assistant).copy(chatModelId = model?.id))
}
