/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.rikkahub.data.ai.recoverToolHistoryForRequest

/**
 * 已发送的主动消息仍属于真实对话历史，但下一轮只需要看见正文。
 * 去掉其可见思考和工具原始结果，避免把旧判断误当成示例，也减少上下文噪声。
 */
internal fun sanitizeProactiveHistory(
    messages: List<UIMessage>,
    proactiveMessageIds: Set<String>,
    preserveReasoning: Boolean = false,
): List<UIMessage> = messages.mapNotNull { message ->
    if (preserveReasoning || message.role != MessageRole.ASSISTANT || message.id.toString() !in proactiveMessageIds) {
        return@mapNotNull message
    }

    val text = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    text.takeIf(String::isNotBlank)?.let {
        message.copy(parts = listOf(UIMessagePart.Text(it)))
    }
}

/** Remove old tool protocol parts when this proactive run exposes no tools. */
internal fun stripHistoricalToolParts(messages: List<UIMessage>): List<UIMessage> = messages.mapNotNull { message ->
    if (message.role != MessageRole.ASSISTANT || message.getTools().isEmpty()) return@mapNotNull message
    message.copy(parts = message.parts.filterNot { it is UIMessagePart.Tool })
        .takeIf { it.parts.isNotEmpty() }
}

internal fun isDeepSeekCompatible(providerSetting: ProviderSetting, model: Model): Boolean {
    val openAi = providerSetting as? ProviderSetting.OpenAI ?: return false
    return openAi.baseUrl.contains("deepseek", ignoreCase = true) ||
        model.modelId.contains("deepseek", ignoreCase = true) ||
        Regex("(^|[-_/])ds(?:[-_/]|$)", RegexOption.IGNORE_CASE).containsMatchIn(model.modelId)
}

/**
 * 过滤历史消息中"悬空"的工具调用：
 * 若某条消息存在未执行(isExecuted=false)且不可恢复(approvalState.canResumeToolExecution()==false)的工具调用，
 * 直接把整条消息从历史里剔除，避免把结构不完整的 tool_use 发给 API 触发 400。
 */
internal fun filterInvalidToolMessages(messages: List<UIMessage>, json: Json): List<UIMessage> {
    return messages.recoverToolHistoryForRequest(json, allowLastMessageResume = false).filterNot { message ->
        val tools = message.getTools()
        val hasPendingTools = tools.any { !it.isExecuted }
        if (!hasPendingTools) return@filterNot false
        val hasResumableTool = tools.any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
        !hasResumableTool
    }
}

/**
 * 合并相邻同角色消息（ASSISTANT-ASSISTANT / USER-USER 都要合并），
 * 避免相邻同角色消息触发 Anthropic 等 API 的 400 错误
 * （"roles must alternate between user and assistant"）。
 */
internal fun mergeAdjacentSameRoleMessages(messages: List<UIMessage>): List<UIMessage> {
    if (messages.size < 2) return messages
    return messages.fold(emptyList()) { acc, msg ->
        val prev = acc.lastOrNull()
        if (prev != null && prev.role == msg.role) {
            acc.dropLast(1) + prev.copy(parts = prev.parts + msg.parts)
        } else {
            acc + msg
        }
    }
}
