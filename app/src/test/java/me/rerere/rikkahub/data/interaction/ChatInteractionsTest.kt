package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.extraInfoMessagePart
import me.rerere.rikkahub.data.ai.transformers.isExtraInfoInjectionPart
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatInteractionsTest {
    @Test fun `same-turn new preset remains expressible in the reaction schema`() = runBlocking {
        var presets = emptyList<String>()
        var reacted = ""
        val tools = buildChatInteractionTools(
            Assistant(), onAvatar = { "" },
            onReaction = { _, emoji, _, _ ->
                require(isSupportedChatReaction(emoji, presets))
                reacted = emoji
            },
            onManageReactionPresets = { _, value ->
                presets = listOf(value)
                ReactionPresetToolResult(true, "saved", presets)
            },
        )
        val added = "꒰ *•ɷ•* ꒱"
        tools.single { it.name == "reaction_preset" }.execute(buildJsonObject {
            put("operation", "add"); put("value", added)
        })
        val reaction = tools.single { it.name == "message_reaction" }
        val schema = reaction.parameters() as me.rerere.ai.core.InputSchema.Obj
        assertFalse(schema.properties["emoji"]!!.jsonObject.containsKey("enum"))
        reaction.execute(buildJsonObject { put("emoji", added) })
        assertEquals(added, reacted)
        assertTrue(runCatching {
            reaction.execute(buildJsonObject { put("emoji", "unsupported") })
        }.isFailure)
    }

    @Test
    fun `reaction preferences use kaomoji and remain compatible with old emoji rows`() {
        assertTrue("˃ ˄ ˂̥̥" in CHAT_REACTION_KAOMOJI)
        assertTrue(isSupportedChatReaction("˃ ˄ ˂̥̥"))
        assertTrue(isSupportedChatReaction("❤️"))
        assertFalse(isSupportedChatReaction("🚀"))
    }

    @Test
    fun `custom reaction presets are normalized deduplicated and included`() {
        val custom = normalizeCustomChatReactionPresets(
            listOf("  (๑>؂<๑)  ", "(๑>؂<๑)", " ˃ ˄ ˂̥̥ ", " ", "(ᵕ—ᴗ—)")
        )

        assertEquals(listOf("(๑>؂<๑)", "(ᵕ—ᴗ—)"), custom)
        assertTrue(isSupportedChatReaction("(๑>؂<๑)", custom))
        assertTrue(effectiveChatReactionPresets(custom).containsAll(custom))
        assertTrue(CHAT_REACTION_KAOMOJI.none { it in custom })
    }

    @Test
    fun `AI can add a custom reaction preset through its tool`() {
        val json = Json { ignoreUnknownKeys = true }
        var receivedOperation = ""
        var receivedValue = ""
        val tools = buildChatInteractionTools(
            assistant = Assistant(enableMessageReactions = true),
            onAvatar = { "avatar" },
            onReaction = { _, _, _, _ -> },
            onManageReactionPresets = { operation, value ->
                receivedOperation = operation
                receivedValue = value
                ReactionPresetToolResult(true, "Preset added.", listOf(value))
            },
        )
        val tool = checkNotNull(tools.find { it.name == "reaction_preset" })
        val output = runBlocking {
            tool.execute(buildJsonObject {
                put("operation", "add")
                put("value", "(ᵕ—ᴗ—)")
            })
        }
        val payload = json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject

        assertEquals("add", receivedOperation)
        assertEquals("(ᵕ—ᴗ—)", receivedValue)
        assertTrue(payload["success"]!!.jsonPrimitive.boolean)
        assertEquals("(ᵕ—ᴗ—)", payload["presets"]!!.jsonArray.single().jsonPrimitive.content)
        val available = payload["available_presets"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(available.containsAll(CHAT_REACTION_KAOMOJI))
        assertTrue("(ᵕ—ᴗ—)" in available)
    }

    @Test
    fun `listing reactions exposes built-ins even without custom presets`() = runBlocking {
        val tool = buildChatInteractionTools(
            assistant = Assistant(enableMessageReactions = true),
            onAvatar = { "avatar" },
            onReaction = { _, _, _, _ -> },
            onManageReactionPresets = { operation, _ ->
                assertEquals("list", operation)
                ReactionPresetToolResult(true, "Loaded 0 custom kaomoji presets.", emptyList())
            },
        ).single { it.name == "reaction_preset" }
        val output = tool.execute(buildJsonObject { put("operation", "list") })
        val payload = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject

        assertTrue(payload["presets"]!!.jsonArray.isEmpty())
        assertEquals(
            CHAT_REACTION_KAOMOJI,
            payload["available_presets"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `typed notices are removed without hiding literal text`() {
        val user = UIMessage.user("普通文字：拍了拍")
        val notice = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text("AI拍了拍你")),
            annotations = listOf(UIMessageAnnotation.AvatarInteraction("assistant", "user")),
        )
        val userNotice = user.copy(
            annotations = listOf(UIMessageAnnotation.AvatarInteraction("user", "assistant")),
        )
        val system = UIMessage.system("Actual persona")
        assertEquals(
            listOf(system, user, userNotice),
            listOf(system, user, notice, userNotice).withoutAssistantInteractionNotices(),
        )
        assertNull(user.avatarInteraction())
    }

    @Test
    fun `annotation survives serialization`() {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val notice = UIMessage.user("你揉了揉AI").copy(
            annotations = listOf(UIMessageAnnotation.AvatarInteraction("user", "assistant")),
        )
        assertEquals(notice, json.decodeFromString(UIMessage.serializer(), json.encodeToString(UIMessage.serializer(), notice)))
    }

    @Test
    fun `split bubble targets preserve sentences and code blocks`() {
        val message = UIMessage.user("我喜欢牛奶\n但不喜欢咖啡\n```kotlin\nval n = 1\nprintln(n)\n```")
        val segments = reactionSegments(message, Assistant(splitUserBubbleByLine = true))
        assertTrue("我喜欢牛奶" in segments)
        assertTrue("但不喜欢咖啡" in segments)
        assertTrue(segments.any { it.contains("val n = 1\nprintln(n)") })
    }

    @Test
    fun `quote resolves uniquely and hidden IDs cannot be targeted`() {
        val assistant = Assistant(splitUserBubbleByLine = true)
        val visible = UIMessage.user("喜欢牛奶\n不喜欢咖啡")
        val reply = UIMessage.assistant("知道啦")
        assertEquals(visible, resolveReactionTarget(listOf(visible, reply), assistant, "by_quote", "牛奶"))
        assertEquals("喜欢牛奶", resolveReactionQuote(visible, assistant, "牛奶"))
        assertEquals(reply, resolveReactionTarget(listOf(visible, reply), assistant, "last_assistant", ""))
        val hidden = UIMessage.user("牛奶")
        runCatching { resolveReactionTarget(listOf(visible), assistant, hidden.id.toString(), "") }
            .onSuccess { fail("Hidden target") }
        runCatching { resolveReactionTarget(listOf(visible, hidden), assistant, "by_quote", "牛奶") }
            .onSuccess { fail("Ambiguous quote") }
    }

    @Test
    fun `provider decoration preserves background metadata`() {
        val background = extraInfoMessagePart("<extra_info_context>current time</extra_info_context>")
        val notice = UIMessage.user("你拍了拍AI").copy(
            parts = listOf(UIMessagePart.Text("你拍了拍AI"), background),
            annotations = listOf(UIMessageAnnotation.AvatarInteraction("user", "assistant")),
        )
        val request = notice.forInteractionRequest()
        assertTrue((request.parts.first() as UIMessagePart.Text).text.contains("actor=user, target=assistant"))
        assertEquals(background, request.parts.last())
        assertTrue(request.parts.last().isExtraInfoInjectionPart())
        assertFalse(request.avatarInteractionText().contains("current time"))
        assertEquals("你拍了拍AI", notice.avatarInteractionText())
    }

    @Test
    fun `custom preset saved with stray whitespace still validates`() {
        // Repository validates against the normalized live list, so a preset just added via
        // reaction_preset (same turn) is accepted for the assistant's own reaction.
        val stored = listOf("  ꒰ *•ɷ•* ꒱ ")
        assertFalse(isSupportedChatReaction("꒰ *•ɷ•* ꒱", stored))
        assertTrue(isSupportedChatReaction("꒰ *•ɷ•* ꒱", normalizeCustomChatReactionPresets(stored)))
    }
}
