package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.files.attachmentReferenceGuard
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import kotlin.uuid.Uuid

/** Only fields the assistant is permitted to edit; omitted values always preserve the live setting. */
internal data class AssistantCustomizationPatch(
    val patAction: String? = null,
    val patSuffix: String? = null,
    val outgoingPatAction: String? = null,
    val outgoingPatSuffix: String? = null,
    val avatarSource: String? = null,
    val avatarEmoji: String? = null,
    val backgroundSource: String? = null,
    val backgroundOpacity: Float? = null,
) {
    init {
        listOf(patAction, outgoingPatAction).filterNotNull().forEach {
            require(it.isNotBlank() && it.length <= 20 && '\n' !in it && '\r' !in it) {
                "拍一拍动作需为 1–20 个字，且不能换行"
            }
        }
        listOf(patSuffix, outgoingPatSuffix).filterNotNull().forEach {
            require(it.length <= 80 && '\n' !in it && '\r' !in it) { "拍一拍后缀最多 80 个字，且不能换行" }
        }
        require(avatarSource == null || avatarEmoji == null) { "avatar_source 和 avatar_emoji 只能指定一个" }
        require(avatarEmoji == null || (avatarEmoji.isNotBlank() && avatarEmoji.length <= 40)) {
            "头像表情需为 1–40 个字符"
        }
        require(backgroundOpacity == null || (backgroundOpacity.isFinite() && backgroundOpacity in 0f..1f)) {
            "背景不透明度需在 0–1 之间"
        }
        listOfNotNull(avatarSource, backgroundSource).forEach {
            require(it.length <= 4096) { "图片地址过长，请使用图片链接或已上传图片的本地路径" }
        }
    }

    val changesInteraction: Boolean get() = listOf(patAction, patSuffix, outgoingPatAction, outgoingPatSuffix)
        .any { it != null }

    fun applyTo(current: Assistant, avatarImage: String?, backgroundImage: String?): Assistant {
        check(current.allowSelfCustomization) { "此助手的 AI 自己设置已关闭" }
        check(!changesInteraction || current.enableAvatarInteractions) { "头像互动已关闭" }
        return current.copy(
            userInteractionAction = patAction ?: current.userInteractionAction,
            userInteractionSuffix = patSuffix ?: current.userInteractionSuffix,
            assistantInteractionAction = outgoingPatAction ?: current.assistantInteractionAction,
            assistantInteractionSuffix = outgoingPatSuffix ?: current.assistantInteractionSuffix,
            avatar = when {
                avatarEmoji != null -> Avatar.Emoji(avatarEmoji)
                avatarSource == "" -> Avatar.Dummy
                avatarImage != null -> Avatar.Image(avatarImage)
                else -> current.avatar
            },
            useAssistantAvatar = if (avatarSource != null || avatarEmoji != null) true else current.useAssistantAvatar,
            background = when {
                backgroundSource == "" -> null
                backgroundImage != null -> backgroundImage
                else -> current.background
            },
            backgroundOpacity = backgroundOpacity ?: current.backgroundOpacity,
        )
    }
}

/** Image loading happens before the atomic settings edit; permission and live fields are checked again at commit. */
internal suspend fun customizeAssistant(
    assistantId: Uuid,
    patch: AssistantCustomizationPatch?,
    readSettings: suspend () -> Settings,
    importImage: suspend (String) -> String,
    commit: suspend ((Settings) -> Settings) -> Unit,
): Assistant = attachmentReferenceGuard.beginWrite().use {
    val initial = checkNotNull(readSettings().getAssistantById(assistantId)) { "助手已删除" }
    check(initial.allowSelfCustomization) { "此助手的 AI 自己设置已关闭" }
    if (patch == null) return@use initial
    check(!patch.changesInteraction || initial.enableAvatarInteractions) { "头像互动已关闭" }
    val avatar = patch.avatarSource?.takeIf { it.isNotEmpty() }?.let { importImage(it) }
    val background = patch.backgroundSource?.takeIf { it.isNotEmpty() }?.let { importImage(it) }
    var saved: Assistant? = null
    commit { settings ->
        val current = checkNotNull(settings.getAssistantById(assistantId)) { "助手已删除" }
        val updated = patch.applyTo(current, avatar, background)
        saved = updated
        settings.copy(assistants = settings.assistants.map { if (it.id == assistantId) updated else it })
    }
    checkNotNull(saved) { "设置未保存" }
}

internal fun buildAssistantSelfCustomizationTools(
    assistant: Assistant,
    onCustomize: suspend (AssistantCustomizationPatch?) -> Assistant,
): List<Tool> {
    if (!assistant.allowSelfCustomization) return emptyList()
    return listOf(Tool(
        name = "self_customization",
        description = "View or update YOUR OWN avatar, pat settings, and this assistant's chat background. " +
            "Only affects the current assistant, never the user's avatar or another assistant. " +
            "Images must be real HTTP(S) image URLs or existing generated/uploaded image paths, not invented paths. " +
            "Omitted fields stay unchanged. Changes are saved and visible immediately; the user can also edit them manually.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("operation", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("get"); add("update") })
                })
                listOf(
                    "pat_action" to "Action when the USER double-taps YOUR avatar, 1–20 characters; e.g. 揉了揉",
                    "pat_suffix" to "Suffix after your name when the user pats YOU, up to 80 characters; empty clears it",
                    "outgoing_pat_action" to "Action when YOU call avatar_interaction to pat the USER, 1–20 characters",
                    "outgoing_pat_suffix" to "Suffix when YOU pat the USER, up to 80 characters; empty clears it",
                    "avatar_source" to "Your image URL or generated/uploaded local image path; empty resets your avatar",
                    "avatar_emoji" to "Set your own emoji avatar instead of an image; 1–40 characters",
                    "background_source" to "Image URL or generated/uploaded local image path; empty removes chat background",
                ).forEach { (name, description) ->
                    put(name, buildJsonObject { put("type", "string"); put("description", description) })
                }
                put("background_opacity", buildJsonObject {
                    put("type", "number"); put("minimum", 0); put("maximum", 1)
                    put("description", "Chat background opacity from 0 to 1; omit to preserve user's setting")
                })
            }, required = listOf("operation"))
        },
        execute = { input ->
            val values = input as? JsonObject ?: error("参数必须是对象")
            fun string(name: String): String? = values[name]?.let {
                require(it is JsonPrimitive && it.isString) { "$name 必须是字符串" }
                it.content
            }
            val operation = string("operation") ?: error("operation is required")
            require(operation == "get" || operation == "update") { "operation must be get or update" }
            val patch = if (operation == "get") null else {
                val allowed = setOf("operation", "pat_action", "pat_suffix", "outgoing_pat_action",
                    "outgoing_pat_suffix", "avatar_source", "avatar_emoji", "background_source", "background_opacity")
                require(values.keys.all { it in allowed } && values.size > 1) { "请指定有效的设置字段" }
                AssistantCustomizationPatch(
                    patAction = string("pat_action"), patSuffix = string("pat_suffix"),
                    outgoingPatAction = string("outgoing_pat_action"), outgoingPatSuffix = string("outgoing_pat_suffix"),
                    avatarSource = string("avatar_source"), avatarEmoji = string("avatar_emoji"),
                    backgroundSource = string("background_source"),
                    backgroundOpacity = values["background_opacity"]?.let {
                        require(it is JsonPrimitive && !it.isString) { "background_opacity 必须是数字" }
                        it.floatOrNull ?: error("background_opacity 必须是数字")
                    },
                )
            }
            val current = onCustomize(patch)
            listOf(UIMessagePart.Text(current.customizationResult().toString()))
        },
    ))
}

private fun Assistant.customizationResult(): JsonElement = buildJsonObject {
    put("success", true)
    put("assistant_id", id.toString())
    put("pat_action", userInteractionAction); put("pat_suffix", userInteractionSuffix)
    put("outgoing_pat_action", assistantInteractionAction); put("outgoing_pat_suffix", assistantInteractionSuffix)
    put("avatar_source", (avatar as? Avatar.Image)?.url.orEmpty())
    put("avatar_emoji", (avatar as? Avatar.Emoji)?.content.orEmpty())
    put("background_source", background.orEmpty()); put("background_opacity", backgroundOpacity)
}
