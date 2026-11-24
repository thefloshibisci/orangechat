/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.model.boundWorkspaceIds
import me.rerere.rikkahub.data.ai.tools.machineLabel

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val live = workspaceRepository.currentAssistant(ctx.assistant.id.toString()) ?: return messages
        val workspaces = live.boundWorkspaceIds().mapNotNull { workspaceRepository.getById(it.toString()) }
        if (workspaces.isEmpty()) return messages
        val prompt = buildString {
            appendLine("<workspaces>")
            appendLine("Each workspace_* call accepts workspace_id. Select the exact ID below; if omitted, the current live default is used. Bindings may change mid-conversation; trust the tool result's machine label. Never retry a remote failure locally.")
            for (workspace in workspaces) {
                appendLine("- id=${workspace.id}; machine=${workspace.machineLabel()}; root=${workspace.workspaceDir()}; status=${workspace.shellStatus}; default=${workspace.id == live.workspaceId?.toString()}")
            }
            appendLine("Paths outside the selected root are rejected. File paths are absolute; shell cwd must stay within that root. Do not use local /workspace paths on remote hosts. Local skills and uploads are not mounted remotely.")
            appendLine("workspace_shell runs with that machine's account permissions: cwd checks are not an OS sandbox. Do not place outside-root paths in commands. Use dedicated skill tools to read /skills. File transfer requires explicit source/destination IDs, defaults to no overwrite, and has a hard 20 MiB limit.")
            appendLine("To offer a generated file download use Markdown [file](workspace://WORKSPACE_ID/absolute/path), e.g. workspace://ID/workspace/report.txt for local, or workspace://ID/srv/chat/report.txt for remote. Percent-encode special path characters. Multi-workspace chats do not guess a target from sandbox: links.")
            appendLine("Scheduled workflow workspace actions may omit workspace_id to use the authoring assistant's current default when they execute. Explicit targets must still be bound to that assistant. File transfers always require both workspace IDs.")
            append("</workspaces>")
        }

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
