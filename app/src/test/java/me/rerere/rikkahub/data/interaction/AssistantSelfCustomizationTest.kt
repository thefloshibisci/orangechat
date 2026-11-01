package me.rerere.rikkahub.data.interaction

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import org.junit.Assert.*
import org.junit.Test

class AssistantSelfCustomizationTest {
    @Test fun `tool updates actual own settings and reloaded values preserve manual controls`() = runBlocking {
        val assistant = Assistant(name = "AI", systemPrompt = "original", backgroundOpacity = .6f)
        val other = Assistant(name = "other", avatar = Avatar.Emoji("🌸"))
        var settings = Settings(assistants = listOf(assistant, other))
        val tool = buildAssistantSelfCustomizationTools(assistant) { patch ->
            customizeAssistant(assistant.id, patch, { settings }, { source ->
                // Simulate a concurrent manual edit while the real image loader runs off-thread.
                settings = settings.copy(assistants = settings.assistants.map {
                    if (it.id == assistant.id) it.copy(name = "updated name", systemPrompt = "new prompt") else it
                }, launchCount = 42)
                "file:///upload/${source.substringAfterLast('/')}"
            }, { edit -> settings = edit(settings) })
        }.single()
        val result = tool.execute(buildJsonObject {
            put("operation", "update")
            put("pat_action", "揉了揉"); put("pat_suffix", "的头发")
            put("outgoing_pat_action", "抱了抱"); put("outgoing_pat_suffix", "，递来牛奶")
            put("avatar_source", "https://images.example/avatar.png")
            put("background_source", "https://images.example/background.png")
        })
        val saved = settings.assistants.first()
        assertEquals("updated name", saved.name)
        assertEquals("new prompt", saved.systemPrompt)
        assertEquals(42, settings.launchCount)
        assertEquals(other, settings.assistants[1])
        assertEquals("揉了揉", saved.userInteractionAction)
        assertEquals("的头发", saved.userInteractionSuffix)
        assertEquals("抱了抱", saved.assistantInteractionAction)
        assertEquals("，递来牛奶", saved.assistantInteractionSuffix)
        assertTrue(saved.useAssistantAvatar)
        assertEquals(Avatar.Image("file:///upload/avatar.png"), saved.avatar)
        assertEquals("file:///upload/background.png", saved.background)
        assertEquals(.6f, saved.backgroundOpacity, 0f)
        val reloaded = Json.decodeFromString<Assistant>(Json.encodeToString(saved))
        assertEquals(saved, reloaded)
        val payload = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(assistant.id.toString(), payload["assistant_id"]!!.jsonPrimitive.content)
        assertEquals("揉了揉", payload["pat_action"]!!.jsonPrimitive.content)
        assertEquals("你揉了揉updated name的头发", interactionText("你", saved.name,
            saved.userInteractionAction, saved.userInteractionSuffix))
        assertEquals("updated name抱了抱你，递来牛奶", interactionText(saved.name, "你",
            saved.assistantInteractionAction, saved.assistantInteractionSuffix))
    }

    @Test fun `permission is checked at execution and again after image loading`() = runBlocking {
        val assistant = Assistant()
        assertTrue(buildAssistantSelfCustomizationTools(assistant.copy(allowSelfCustomization = false)) { error("unused") }.isEmpty())
        var settings = Settings(assistants = listOf(assistant))
        assertTrue(runCatching {
            customizeAssistant(assistant.id, AssistantCustomizationPatch(avatarSource = "https://example/a.png"),
                { settings }, {
                    settings = settings.copy(assistants = listOf(assistant.copy(allowSelfCustomization = false)))
                    "file:///upload/a.png"
                }, { edit -> settings = edit(settings) })
        }.isFailure)
        assertEquals(Avatar.Dummy, settings.assistants.single().avatar)
        assertFalse(settings.assistants.single().allowSelfCustomization)
        assertTrue(runCatching {
            customizeAssistant(assistant.id, null, { settings }, { error("must not import") }, { error("must not save") })
        }.isFailure)
    }

    @Test fun `deleted assistant is not recreated and disabled interactions remain disabled`() = runBlocking {
        val assistant = Assistant()
        var settings = Settings(assistants = listOf(assistant))
        assertTrue(runCatching {
            customizeAssistant(assistant.id, AssistantCustomizationPatch(backgroundSource = "https://example/a.png"),
                { settings }, {
                    settings = settings.copy(assistants = emptyList())
                    "file:///upload/a.png"
                }, { edit -> settings = edit(settings) })
        }.isFailure)
        assertTrue(settings.assistants.isEmpty())
        assertTrue(runCatching {
            AssistantCustomizationPatch(outgoingPatAction = "抱了抱").applyTo(
                assistant.copy(enableAvatarInteractions = false), null, null)
        }.isFailure)
    }

    @Test fun `get does not write and explicit reset preserves unrelated fields`() = runBlocking {
        val assistant = Assistant(avatar = Avatar.Image("file:///old.png"), background = "file:///background.png")
        var settings = Settings(assistants = listOf(assistant))
        val tool = buildAssistantSelfCustomizationTools(assistant) { patch ->
            customizeAssistant(assistant.id, patch, { settings }, { error("must not import") }, { edit -> settings = edit(settings) })
        }.single()
        tool.execute(buildJsonObject { put("operation", "get") })
        assertEquals(assistant, settings.assistants.single())
        tool.execute(buildJsonObject {
            put("operation", "update"); put("avatar_source", ""); put("background_source", "")
        })
        assertEquals(Avatar.Dummy, settings.assistants.single().avatar)
        assertNull(settings.assistants.single().background)
        assertEquals(assistant.userInteractionAction, settings.assistants.single().userInteractionAction)
    }

    @Test fun `invalid update fields never reach mutation`() = runBlocking {
        val tool = buildAssistantSelfCustomizationTools(Assistant()) { error("must not mutate") }.single()
        listOf(
            buildJsonObject { put("operation", "update"); put("pat_action", "") },
            buildJsonObject { put("operation", "update"); put("pat_suffix", "x".repeat(81)) },
            buildJsonObject { put("operation", "update"); put("avatar_source", "url"); put("avatar_emoji", "😀") },
            buildJsonObject { put("operation", "update"); put("background_opacity", 2) },
            buildJsonObject { put("operation", "update"); put("background_opacity", "0.5") },
            buildJsonObject { put("operation", "update"); put("assistant_id", "other") },
            buildJsonObject { put("operation", "update"); put("pat_action", true) },
        ).forEach { input ->
            val failure = runCatching { tool.execute(input) }.exceptionOrNull()
            assertNotNull(failure)
            assertNotEquals("must not mutate", failure!!.message)
        }
    }
}
