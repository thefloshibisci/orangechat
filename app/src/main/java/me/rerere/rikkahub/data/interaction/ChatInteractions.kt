package me.rerere.rikkahub.data.interaction

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.isExtraInfoInjectionPart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.splitIntoBubbleSegments

val CHAT_REACTION_KAOMOJI = listOf(
    "˃ ˄ ˂̥̥",
    "ᵔ ᵕ ᵔ",
    "˶ᵔ ᵕ ᵔ˶",
    "(˶˃ ᵕ ˂˶)",
    "(๑•ᴗ•๑)",
    "(〃▽〃)",
    "(つ≧▽≦)つ",
    "(｡•́︿•̀｡)",
    "(≧◡≦)",
    "(￣▽￣)",
    "(╯°□°)╯",
    "( ´ ▽ ` )ﾉ",
    ">ᴗoಣ",
    "꒰｡ › ·̮ ‹ ｡꒱",
    "˶╹ꇴ╹˶",
    "ε٩(๑> ₃ <)۶з",
    "(*/ω＼*)",
    "●▽●",
    "(๑•́ ₃•̀๑)",
    "(◍ ´꒳` ◍)",
    "→_→",
    "╭(╯^╰)╮",
    "Ծ‸ Ծ",
)

const val MAX_CUSTOM_CHAT_REACTION_PRESETS = 40
const val MAX_CHAT_REACTION_PRESET_LENGTH = 40

// Keep old rows removable after the picker moves from emoji presets to kaomoji.
private val LEGACY_CHAT_REACTION_EMOJIS = setOf(
    "❤️", "👍", "👎", "😂", "🥰", "😢", "😡", "😮", "🥛", "🤗", "🤔", "🎉",
)

fun normalizeCustomChatReactionPreset(value: String): String? {
    val normalized = value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
    return normalized.takeIf {
        it.isNotBlank() &&
            it.length <= MAX_CHAT_REACTION_PRESET_LENGTH &&
            it !in CHAT_REACTION_KAOMOJI
    }
}

fun normalizeCustomChatReactionPresets(values: List<String>): List<String> =
    values.asSequence()
        .mapNotNull(::normalizeCustomChatReactionPreset)
        .distinct()
        .take(MAX_CUSTOM_CHAT_REACTION_PRESETS)
        .toList()

fun effectiveChatReactionPresets(customPresets: List<String>): List<String> =
    (CHAT_REACTION_KAOMOJI + normalizeCustomChatReactionPresets(customPresets)).distinct()

internal fun isSupportedChatReaction(
    reaction: String,
    customPresets: Collection<String> = emptyList(),
): Boolean =
    reaction in CHAT_REACTION_KAOMOJI ||
        reaction in customPresets ||
        reaction in LEGACY_CHAT_REACTION_EMOJIS

fun UIMessage.avatarInteraction(): UIMessageAnnotation.AvatarInteraction? =
    annotations.filterIsInstance<UIMessageAnnotation.AvatarInteraction>().firstOrNull()

fun UIMessage.avatarInteractionText(): String = parts.filterIsInstance<UIMessagePart.Text>()
    .filterNot { it.isExtraInfoInjectionPart() }
    .joinToString(" ") { it.text }

internal fun UIMessage.forInteractionRequest(): UIMessage {
    val event = avatarInteraction() ?: return this
    if (role != MessageRole.USER) return this
    val firstText = parts.indexOfFirst { it is UIMessagePart.Text && !it.isExtraInfoInjectionPart() }
    return copy(parts = parts.mapIndexed { index, part ->
        if (index != firstText || part !is UIMessagePart.Text) {
            part
        } else {
            part.copy(
                text = "App avatar event initiated by the user; actor=${event.actor}, target=${event.target}. " +
                    part.text,
            )
        }
    })
}

fun List<UIMessage>.withoutAssistantInteractionNotices(): List<UIMessage> = filterNot {
    it.role == MessageRole.SYSTEM && it.avatarInteraction() != null
}

fun reactionSegments(message: UIMessage, assistant: Assistant): List<String> = message.parts
    .filterIsInstance<UIMessagePart.Text>()
    .filterNot { it.isExtraInfoInjectionPart() }
    .flatMap {
        val text = it.text.replace(Regex("\\[zip:[^\\]]+\\]", RegexOption.IGNORE_CASE), "")
        val split = if (message.role == MessageRole.USER) {
            assistant.splitUserBubbleByLine
        } else {
            assistant.splitBubbleByLine
        }
        if (split) text.splitIntoBubbleSegments() else listOf(text)
    }
    .filter { it.isNotBlank() }

fun resolveReactionTarget(
    messages: List<UIMessage>,
    assistant: Assistant,
    target: String,
    quote: String,
): UIMessage {
    val eligible = messages.filter {
        it.avatarInteraction() == null && it.role in listOf(MessageRole.USER, MessageRole.ASSISTANT)
    }
    return when (target) {
        "last_user" -> eligible.lastOrNull { it.role == MessageRole.USER }
        "last_assistant" -> eligible.lastOrNull { it.role == MessageRole.ASSISTANT }
        "by_quote" -> {
            require(quote.isNotBlank()) { "by_quote 需要指定原句" }
            val matches = eligible.filter { message ->
                reactionSegments(message, assistant).any { segment -> segment.contains(quote) }
            }
            require(matches.size == 1) { "原句未找到或不唯一，请指定消息 ID" }
            matches.single()
        }
        else -> eligible.firstOrNull { it.id.toString() == target }
    } ?: error("目标消息不存在或不在当前分支")
}

fun resolveReactionQuote(message: UIMessage, assistant: Assistant, quote: String): String {
    val segments = reactionSegments(message, assistant)
    if (quote.isEmpty()) return segments.lastOrNull().orEmpty()
    return segments.firstOrNull { it == quote } ?: segments.filter { it.contains(quote) }.let { matches ->
        require(matches.size == 1) { "请指定唯一的一句原文" }
        matches.single()
    }
}
