package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationConfigTest {
    private val first = Model(modelId = "first", tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext))
    private val second = Model(modelId = "second", tools = setOf(BuiltInTools.ImageGeneration))
    private val owner = Assistant(
        chatModelId = first.id,
        temperature = 0.4f,
        topP = 0.7f,
        maxTokens = 2048,
        mcpServers = setOf(Uuid.random()),
        enabledSkills = setOf("skill"),
    )
    private val settings = Settings.dummy().copy(
        assistantId = owner.id,
        assistants = listOf(owner),
        chatModelId = first.id,
        enableWebSearch = true,
        searchServiceSelected = 2,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(first, second))),
    )

    private fun chat() = Conversation(assistantId = owner.id, messageNodes = emptyList())

    @Test
    fun unboundOldChatUsesLiveDefaultsWithoutMutatingOrBindingOnRead() {
        val old = chat()
        val changed = settings.copy(assistants = listOf(owner.copy(chatModelId = second.id, temperature = 1.5f)))
        val effective = changed.withConversationConfig(old)

        assertNull(old.config)
        assertEquals(second.id, changed.getChatModelOf(old)!!.id)
        assertEquals(1.5f, effective.getAssistantOf(old).temperature)
        assertTrue(effective.enableWebSearch)
        assertEquals(2, effective.searchServiceSelected)
        assertEquals(changed, effective)
    }

    @Test
    fun firstBindingFreezesRequestFieldsWhilePersonaAndMemoryStayLive() {
        val bound = chat().bindConfig(settings)
        val changed = settings.copy(
            enableWebSearch = false,
            searchServiceSelected = 0,
            assistants = listOf(owner.copy(
                chatModelId = second.id,
                temperature = 1.5f,
                systemPrompt = "new persona",
                enableMemory = true,
                mcpServers = emptySet(),
            )),
        )
        val effective = changed.withConversationConfig(bound)
        val resolved = effective.getAssistantOf(bound)

        assertEquals(first.id, resolved.chatModelId)
        assertEquals(0.4f, resolved.temperature)
        assertEquals(owner.mcpServers, resolved.mcpServers)
        assertEquals("new persona", resolved.systemPrompt)
        assertTrue(resolved.enableMemory)
        assertTrue(effective.enableWebSearch)
        assertEquals(2, effective.searchServiceSelected)
        assertEquals(second.id, changed.getChatModelOf(chat())!!.id)
        assertEquals(bound, bound.bindConfig(changed))
    }

    @Test
    fun twoConversationsOfSameAssistantKeepIndependentSettingsAndRawDefaults() {
        val firstChat = chat().bindConfig(settings)
        val secondChat = chat().copy(config = firstChat.config!!.copy(
            chatModelId = second.id,
            builtInSearch = true,
            enableWebSearch = false,
            searchServiceSelected = 1,
            temperature = 0.9f,
            enabledSkills = emptySet(),
        ))
        val firstView = settings.withConversationConfig(firstChat)
        val secondView = settings.withConversationConfig(secondChat)

        assertEquals(first.id, firstView.getChatModelOf(firstChat)!!.id)
        assertEquals(second.id, secondView.getChatModelOf(secondChat)!!.id)
        assertEquals(0.4f, firstView.getAssistantOf(firstChat).temperature)
        assertEquals(0.9f, secondView.getAssistantOf(secondChat).temperature)
        assertEquals(setOf("skill"), firstView.getAssistantOf(firstChat).enabledSkills)
        assertTrue(secondView.getAssistantOf(secondChat).enabledSkills.isEmpty())
        assertTrue(firstView.enableWebSearch)
        assertFalse(secondView.enableWebSearch)
        assertEquals(2, firstView.searchServiceSelected)
        assertEquals(1, secondView.searchServiceSelected)
        assertEquals(owner, settings.assistants.single())
        assertEquals(listOf(first, second), settings.providers.single().models)
    }

    @Test
    fun explicitEmptyToolSelectionsAndDisabledSearchDoNotInheritDefaults() {
        val bound = chat().copy(config = ConversationConfig(chatModelId = first.id, builtInSearch = false))
        val view = settings.withConversationConfig(bound)
        val assistant = view.getAssistantOf(bound)

        assertTrue(assistant.mcpServers.isEmpty())
        assertTrue(assistant.enabledSkills.isEmpty())
        assertEquals(owner.localTools, assistant.localTools)
        assertFalse(view.enableWebSearch)
        assertFalse(BuiltInTools.Search in view.findModelById(first.id)!!.tools)
        assertTrue(BuiltInTools.UrlContext in view.findModelById(first.id)!!.tools)
        assertTrue(BuiltInTools.Search in settings.findModelById(first.id)!!.tools)
        assertEquals(second, view.findModelById(second.id))
    }

    @Test
    fun enablingBuiltInSearchChangesOnlyTheSelectedModelInTheOverlay() {
        val bound = chat().copy(config = ConversationConfig(chatModelId = second.id, builtInSearch = true))
        val view = settings.withConversationConfig(bound)

        assertEquals(setOf(BuiltInTools.ImageGeneration, BuiltInTools.Search), view.findModelById(second.id)!!.tools)
        assertEquals(first, view.findModelById(first.id))
        assertEquals(second, settings.findModelById(second.id))
    }

    @Test
    fun nullableRequestControlsCanBeExplicitlyCleared() {
        val bound = chat().bindConfig(settings)
        val previous = settings.getAssistantOf(bound)
        val cleared = bound.config!!.withAssistantEdit(previous, previous.copy(
            temperature = null,
            topP = null,
            maxTokens = null,
        ))
        val resolved = settings.getAssistantOf(bound.copy(config = cleared))

        assertNull(resolved.temperature)
        assertNull(resolved.topP)
        assertNull(resolved.maxTokens)
        assertEquals(0.4f, owner.temperature)
        assertEquals(2048, owner.maxTokens)
    }

    @Test
    fun requestLimitsCustomFieldsAndStreamModeBindAndRoundTrip() {
        val source = owner.copy(
            reasoningLevel = ReasoningLevel.OFF,
            contextMessageSize = 12,
            maxContextImages = 3,
            maxContextVoices = 4,
            streamOutput = false,
            customHeaders = listOf(CustomHeader("X-Session-Test", "session")),
            customBodies = listOf(CustomBody("session_test", JsonPrimitive(true))),
            localTools = listOf(LocalToolOption.TimeInfo),
            workspaceId = Uuid.random(),
            modeInjectionIds = setOf(Uuid.random()),
            lorebookIds = setOf(Uuid.random()),
        )
        val sourceSettings = settings.copy(assistants = listOf(source))
        val bound = chat().bindConfig(sourceSettings)
        val restored = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(bound))

        assertEquals(bound.config, restored.config)
        assertEquals(source, source.withConversation(restored.config))
        val resolvedFromOldDefaults = settings.getAssistantOf(restored)
        assertEquals(source.reasoningLevel, resolvedFromOldDefaults.reasoningLevel)
        assertEquals(source.maxContextImages, resolvedFromOldDefaults.maxContextImages)
        assertEquals(source.maxContextVoices, resolvedFromOldDefaults.maxContextVoices)
        assertEquals(source.contextMessageSize, resolvedFromOldDefaults.contextMessageSize)
        assertEquals(source.streamOutput, resolvedFromOldDefaults.streamOutput)
        assertEquals(source.customHeaders, resolvedFromOldDefaults.customHeaders)
        assertEquals(source.customBodies, resolvedFromOldDefaults.customBodies)
        assertEquals(source.workspaceId, resolvedFromOldDefaults.workspaceId)
        assertEquals(source.modeInjectionIds, resolvedFromOldDefaults.modeInjectionIds)
        assertEquals(source.lorebookIds, resolvedFromOldDefaults.lorebookIds)
    }

    @Test
    fun staleAssistantEditPreservesNewerControlsAndNonAssistantConfigFields() {
        val previous = owner
        val newer = ConversationConfig(
            enableWebSearch = true,
            searchServiceSelected = 3,
            builtInSearch = true,
        ).withAssistant(owner.copy(
            chatModelId = second.id,
            maxTokens = 8192,
            maxContextImages = 5,
            customHeaders = listOf(CustomHeader("newer", "keep")),
            mcpServers = emptySet(),
        ))
        val merged = newer.withAssistantEdit(previous, previous.copy(temperature = 0.8f))

        assertEquals(newer.copy(temperature = 0.8f), merged)
        assertEquals(newer, newer.withAssistantEdit(previous, previous.copy(systemPrompt = "persona only")))
    }

    @Test
    fun clearingCustomFieldsAndSelectionsCountsAsAnExplicitEdit() {
        val previous = owner.copy(
            customHeaders = listOf(CustomHeader("old", "clear")),
            customBodies = listOf(CustomBody("old", JsonPrimitive("clear"))),
            workspaceId = Uuid.random(),
        )
        val changed = ConversationConfig().withAssistant(previous).withAssistantEdit(previous, previous.copy(
            customHeaders = emptyList(),
            customBodies = emptyList(),
            localTools = emptyList(),
            mcpServers = emptySet(),
            enabledSkills = emptySet(),
            workspaceId = null,
        ))
        val resolved = previous.withConversation(changed)

        assertTrue(resolved.customHeaders.isEmpty())
        assertTrue(resolved.customBodies.isEmpty())
        assertEquals(previous.localTools, resolved.localTools)
        assertTrue(resolved.mcpServers.isEmpty())
        assertTrue(resolved.enabledSkills.isEmpty())
        assertNull(resolved.workspaceId)
    }

    @Test
    fun deletedModelFallsBackAndRebindsWithoutLosingConversationOverrides() {
        val bound = chat().bindConfig(settings)
        val changed = settings.copy(
            assistants = listOf(owner.copy(chatModelId = second.id)),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(second))),
        )
        val rebound = bound.bindConfig(changed)

        assertEquals(second.id, changed.getChatModelOf(bound)!!.id)
        assertEquals(second.id, changed.getAssistantOf(bound).chatModelId)
        assertEquals(second.id, changed.withConversationConfig(bound).getAssistantOf(bound).chatModelId)
        assertEquals(second.id, rebound.config!!.chatModelId)
        assertEquals(bound.config!!.copy(chatModelId = second.id), rebound.config)
        assertTrue(BuiltInTools.Search in changed.getChatModelOf(rebound)!!.tools)
        assertEquals(second, changed.findModelById(second.id))
    }

    @Test
    fun missingAssistantModelFallsBackToGlobalAndNoModelRemainsSafe() {
        val missing = Uuid.random()
        val unboundSettings = settings.copy(assistants = listOf(owner.copy(chatModelId = missing)))
        assertEquals(first.id, unboundSettings.getChatModelOf(chat())!!.id)
        val oldWithMissingAssistantModel = chat()
        assertEquals(first.id, unboundSettings.getAssistantOf(oldWithMissingAssistantModel).chatModelId)
        assertEquals(first.id, unboundSettings.withConversationConfig(oldWithMissingAssistantModel)
            .getAssistantOf(oldWithMissingAssistantModel).chatModelId)

        val noModels = unboundSettings.copy(providers = emptyList())
        val old = chat()
        assertNull(noModels.getChatModelOf(old))
        assertNull(old.bindConfig(noModels).config!!.chatModelId)
        assertNull(noModels.withConversationConfig(old).getChatModelOf(old))
    }

    @Test
    fun legacySerializationIsUnboundAndCopiedForkConfigStaysIndependent() {
        val legacy = JsonInstant.decodeFromString<Conversation>(
            """{"id":"${Uuid.random()}","assistantId":"${owner.id}","messageNodes":[]}""",
        )
        assertNull(legacy.config)
        val original = legacy.bindConfig(settings)
        val fork = original.copy(id = Uuid.random())
        val restored = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(fork))
        val changedFork = restored.copy(config = restored.config!!.copy(enableWebSearch = false, maxTokens = null))

        assertEquals(original.config, restored.config)
        assertNotEquals(original.id, restored.id)
        assertTrue(original.config!!.enableWebSearch)
        assertEquals(2048, original.config!!.maxTokens)
        assertFalse(changedFork.config!!.enableWebSearch)
        assertNull(changedFork.config!!.maxTokens)
    }

    @Test
    fun localToolsAlwaysFollowTheAssistantInExistingConversations() {
        val bound = chat().bindConfig(settings)
        val enabled = owner.copy(localTools = owner.localTools + LocalToolOption.ChartDisplay)
        val later = settings.copy(assistants = listOf(enabled))

        assertTrue(LocalToolOption.ChartDisplay in later.getAssistantOf(bound).localTools)
        assertTrue(LocalToolOption.ChartDisplay in later.withConversationConfig(bound).getAssistantOf(bound).localTools)
    }

    @Test
    fun legacyConfigWithStoredLocalToolsStillDecodesAndIsIgnored() {
        val legacy = """{"chatModelId":"${first.id}","localTools":[{"type":"time_info"}]}"""
        val config = JsonInstant.decodeFromString<ConversationConfig>(legacy)
        val resolved = settings.getAssistantOf(chat().copy(config = config))

        assertEquals(first.id, config.chatModelId)
        assertEquals(owner.localTools, resolved.localTools)
    }
}
