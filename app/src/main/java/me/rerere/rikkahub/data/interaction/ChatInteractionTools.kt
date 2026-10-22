package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant

data class ReactionPresetToolResult(
    val success: Boolean,
    val message: String,
    val presets: List<String>,
)

fun buildChatInteractionTools(
    assistant: Assistant,
    reactionPresets: List<String> = CHAT_REACTION_KAOMOJI,
    onAvatar: suspend () -> String,
    onReaction: suspend (String, String, Boolean, String) -> Unit,
    onManageReactionPresets: suspend (String, String) -> ReactionPresetToolResult,
): List<Tool> = buildList {
    if (assistant.enableAvatarInteractions) add(
        Tool(
            name = "avatar_interaction",
            description = "Gently interact with the user's avatar using their configured action and suffix. " +
                "The app displays a centered notice. Use naturally, do not repeatedly spam this tool.",
            parameters = { InputSchema.Obj(properties = buildJsonObject { }, required = emptyList()) },
            execute = {
                val text = onAvatar()
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("actor", "assistant")
                    put("target", "user")
                    put("text", text)
                }.toString()))
            },
        )
    )
    if (assistant.enableMessageReactions) add(
        Tool(
            name = "message_reaction",
            description = "Add or remove YOUR kaomoji reaction underneath one specific message. " +
                "This is independent of avatar interactions. You cannot remove the user's reactions. " +
                "Use last_user, last_assistant, by_quote (with a unique sentence), or an exact message ID.",
            parameters = {
                InputSchema.Obj(properties = buildJsonObject {
                    put("message_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Exact ID, last_user, last_assistant, or by_quote; default last_user")
                    })
                    put("quote", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional exact sentence to react to within a split message")
                    })
                    put("emoji", buildJsonObject {
                        put("type", "string")
                        put("description", "A kaomoji from the supported reaction list")
                        put("enum", buildJsonArray { reactionPresets.forEach { add(it) } })
                    })
                    put("operation", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("add")
                            add("remove")
                        })
                    })
                }, required = listOf("emoji"))
            },
            execute = { input ->
                val values = input.jsonObject
                val target = values["message_id"]?.jsonPrimitive?.contentOrNull ?: "last_user"
                val emoji = values["emoji"]?.jsonPrimitive?.contentOrNull ?: error("emoji is required")
                val operation = values["operation"]?.jsonPrimitive?.contentOrNull ?: "add"
                require(operation == "add" || operation == "remove")
                onReaction(
                    target,
                    emoji,
                    operation == "add",
                    values["quote"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                listOf(UIMessagePart.Text(buildJsonObject { put("success", true) }.toString()))
            },
        )
    )
    if (assistant.enableMessageReactions) add(
        Tool(
            name = "reaction_preset",
            description = "List, add, or remove global custom kaomoji presets used by the user's " +
                "message-reaction picker. Only modify presets when the user explicitly asks to save " +
                "or remove one. Built-in presets cannot be removed.",
            parameters = {
                InputSchema.Obj(properties = buildJsonObject {
                    put("operation", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("list")
                            add("add")
                            add("remove")
                        })
                    })
                    put("value", buildJsonObject {
                        put("type", "string")
                        put("description", "Kaomoji to add or remove; omit for list")
                    })
                }, required = listOf("operation"))
            },
            execute = { input ->
                val values = input.jsonObject
                val operation = values["operation"]?.jsonPrimitive?.contentOrNull
                    ?: error("operation is required")
                val value = values["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val result = onManageReactionPresets(operation, value)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", result.success)
                    put("message", result.message)
                    put("presets", buildJsonArray { result.presets.forEach { add(it) } })
                }.toString()))
            },
        )
    )
}
