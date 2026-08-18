/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal const val RECENT_CONTINUITY_TOOL_NAME = "xinchao_continuity_sync"
private const val RECENT_CONTINUITY_CLIENT = "orangechat-android"
private const val MAX_SYNC_MESSAGES = 6
private const val MAX_SYNC_TEXT_CHARS = 2_000
private val SENSITIVE_TEXT_PATTERNS = listOf(
    Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----", RegexOption.IGNORE_CASE),
    Regex("\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}", RegexOption.IGNORE_CASE),
    Regex("\\b(?:sk-[A-Za-z0-9_-]{12,}|ghp_[A-Za-z0-9]{12,}|github_pat_[A-Za-z0-9_]{12,})\\b"),
    Regex(
        "\\b(?:password|passwd|passphrase|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|client[_ -]?secret|authorization)\\b\\s*[:=]\\s*\\S+",
        RegexOption.IGNORE_CASE,
    ),
)

internal data class RecentContinuityTurn(
    val turnId: String,
    val role: String,
    val text: String,
)

/**
 * Select only the small amount of ordinary conversation that Xinchao's
 * short-lived continuity layer is allowed to receive. System prompts, tool
 * calls/results, reasoning, attachments and the full conversation archive are
 * intentionally excluded here rather than relying on model instructions.
 */
internal fun selectRecentContinuityTurns(
    messages: List<UIMessage>,
    limit: Int = MAX_SYNC_MESSAGES,
): List<RecentContinuityTurn> {
    if (limit <= 0) return emptyList()

    return messages.asSequence()
        .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        .mapNotNull { message ->
            if (message.parts.any { it !is UIMessagePart.Text }) return@mapNotNull null
            val text = message.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
                .take(MAX_SYNC_TEXT_CHARS)
            if (text.isBlank() || SENSITIVE_TEXT_PATTERNS.any { it.containsMatchIn(text) }) {
                null
            } else {
                RecentContinuityTurn(
                    turnId = message.id.toString(),
                    role = if (message.role == MessageRole.USER) "user" else "assistant",
                    text = text,
                )
            }
        }
        .toList()
        .takeLast(limit.coerceAtMost(MAX_SYNC_MESSAGES))
}

internal fun buildRecentContinuityArguments(
    sessionId: String,
    turns: List<RecentContinuityTurn>,
    returnLimit: Int = 8,
): JsonObject = JsonObject(
    mapOf(
        "session_id" to JsonPrimitive(sessionId.take(120)),
        "client" to JsonPrimitive(RECENT_CONTINUITY_CLIENT),
        "messages" to JsonArray(turns.map { turn ->
            JsonObject(
                mapOf(
                    "turn_id" to JsonPrimitive(turn.turnId),
                    "role" to JsonPrimitive(turn.role),
                    "text" to JsonPrimitive(turn.text),
                )
            )
        }),
        "limit" to JsonPrimitive(returnLimit.coerceIn(1, 24)),
    )
)

/** Extract only context returned by another MCP session, not the status line. */
internal fun parseRecentContinuityResult(parts: List<UIMessagePart>): String? {
    val text = parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    if (text.isBlank() || text.contains("当前没有可返回的近期内容")) return null

    val context = text.substringAfter("\n\n", missingDelimiterValue = "").trim()
    return context.ifBlank { null }
}

internal fun recentContinuityPrompt(context: String): String = """
    ## 近期跨端上下文
    以下内容来自同一人的其他聊天窗口，只用于自然接续当下对话。它是短期原文，不是长期记忆，也不覆盖当前窗口已经明确的信息。不要向用户复述来源标签、时间戳或这段说明；若与当前消息无关，忽略即可。

    $context
""".trimIndent()

internal fun recentContinuitySessionId(conversationId: String): String =
    "orangechat:$conversationId".take(120)
