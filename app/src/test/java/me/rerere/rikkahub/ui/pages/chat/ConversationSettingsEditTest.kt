package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.withSettingsEdit
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSettingsEditTest {
    @Test
    fun `session search edits leave global settings unchanged`() {
        val model = Model(modelId = "gpt-test", tools = emptySet())
        val assistant = Assistant(chatModelId = model.id)
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        val before = Settings(assistants = listOf(assistant), assistantId = assistant.id, chatModelId = model.id, providers = listOf(provider))
        val chat = Conversation(assistantId = assistant.id, messageNodes = emptyList())
        val desired = before.copy(enableWebSearch = true, searchServiceSelected = 3, providers = listOf(
            provider.copy(models = listOf(model.copy(tools = setOf(BuiltInTools.Search)))),
        ))
        val edit = conversationSettingsEdit(before, desired, chat)
        assertEquals(true, edit.enableWebSearch)
        assertEquals(3, edit.searchServiceSelected)
        assertEquals(true, edit.builtInSearch)
        assertEquals(before, edit.globalDesired)
    }

    @Test
    fun `session built in search does not swallow unrelated provider or global edits`() {
        val model = Model(modelId = "gpt-test", tools = emptySet())
        val otherModel = Model(modelId = "other")
        val assistant = Assistant(chatModelId = model.id)
        val provider = ProviderSetting.OpenAI(models = listOf(model, otherModel))
        val otherProvider = ProviderSetting.OpenAI(name = "Other provider")
        val before = Settings(assistants = listOf(assistant), assistantId = assistant.id, chatModelId = model.id, providers = listOf(provider, otherProvider))
        val desired = before.copy(dynamicColor = !before.dynamicColor, providers = listOf(
            provider.copy(name = "Renamed", models = listOf(model.copy(tools = setOf(BuiltInTools.Search)), otherModel.copy(displayName = "New name"))),
            otherProvider.copy(baseUrl = "https://other.example/v1"),
        ))
        val edit = conversationSettingsEdit(before, desired, Conversation(assistantId = assistant.id, messageNodes = emptyList()))
        assertEquals(true, edit.builtInSearch)
        assertNull(edit.enableWebSearch)
        assertNull(edit.searchServiceSelected)
        assertEquals("Renamed", edit.globalDesired.providers.first().name)
        assertFalse(BuiltInTools.Search in edit.globalDesired.providers.first().models.first().tools)
        assertEquals("New name", edit.globalDesired.providers.first().models.last().displayName)
        assertEquals("https://other.example/v1", (edit.globalDesired.providers.last() as ProviderSetting.OpenAI).baseUrl)
        val concurrent = before.copy(launchCount = before.launchCount + 1)
        val merged = concurrent.withSettingsEdit(before, edit.globalDesired)
        assertEquals(concurrent.launchCount, merged.launchCount)
        assertEquals(!before.dynamicColor, merged.dynamicColor)
    }

    @Test
    fun `request draft rejects non finite fractions negative counts and integer overflow`() {
        val valid = ConversationRequestDraft.from(Assistant())
        assertTrue(valid.isValid)
        listOf("NaN", "Infinity", "-0.1", "2.1").forEach { assertFalse(valid.copy(temperature = it).isValid) }
        assertFalse(valid.copy(topP = "1.1").isValid)
        listOf("0", "-1", "1.5", "2147483648").forEach { assertFalse(valid.copy(maxTokens = it).isValid) }
        listOf("-1", "", "2147483648").forEach { assertFalse(valid.copy(contextImages = it).isValid) }
        assertFalse(valid.copy(contextVoices = "-1").isValid)
        assertFalse(valid.copy(contextMessages = "1.5").isValid)
    }

    @Test
    fun `request draft keeps persona and explicit zero context limits`() {
        val assistant = Assistant(name = "Role", systemPrompt = "Keep persona", enableMemory = true)
        val updated = ConversationRequestDraft.from(assistant).copy(
            temperature = "0", topP = "1", maxTokens = "1024", contextMessages = "0", contextImages = "2", contextVoices = "3",
        ).applyTo(assistant.copy(streamOutput = false))
        assertEquals("Keep persona", updated.systemPrompt)
        assertEquals("Role", updated.name)
        assertTrue(updated.enableMemory)
        assertFalse(updated.streamOutput)
        assertEquals(0f, updated.temperature)
        assertEquals(1f, updated.topP)
        assertEquals(1024, updated.maxTokens)
        assertEquals(0, updated.contextMessageSize)
        assertEquals(2, updated.maxContextImages)
        assertEquals(3, updated.maxContextVoices)
    }
}
