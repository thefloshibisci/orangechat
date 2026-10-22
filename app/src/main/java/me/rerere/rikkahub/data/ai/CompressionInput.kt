package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import kotlin.uuid.Uuid

internal fun Settings.compressionModelFor(assistantId: Uuid): Model? =
    compressModelId?.let { findModelById(it) } ?: getChatModelForAssistant(assistantId)

@Suppress("DEPRECATION")
private fun UIMessagePart.compressionText(): String = when (this) {
    is UIMessagePart.Text -> text
    is UIMessagePart.Tool -> "[Tool $toolName, id=$toolCallId]\nInput: $input\nOutput:\n" +
        output.joinToString("\n") { it.compressionText() }
    is UIMessagePart.ToolCall -> "[Tool call $toolName, id=$toolCallId]\n$arguments"
    is UIMessagePart.ToolResult -> "[Tool result $toolName, id=$toolCallId]\nArguments: $arguments\n$content"
    is UIMessagePart.VoiceMessage -> "[Voice message]\n$transcript"
    is UIMessagePart.Document -> "[Document: $fileName, $mime] ${compressionReference(url)}"
    is UIMessagePart.Image -> "[Image] ${compressionReference(url)}"
    is UIMessagePart.Audio -> "[Audio] ${compressionReference(url)}"
    is UIMessagePart.Video -> "[Video] ${compressionReference(url)}"
    is UIMessagePart.Reasoning, UIMessagePart.Search -> ""
}

private fun compressionReference(url: String): String =
    if (url.startsWith("data:", ignoreCase = true)) "[embedded media; content unavailable as text]" else url

internal fun UIMessage.compressionTranscript(): String =
    "[${role.name}]: " + parts.map { it.compressionText() }.filter { it.isNotBlank() }.joinToString("\n")

/** Submit the complete transcript once. Never split, truncate or automatically retry model requests. */
internal suspend fun compressTextOnce(
    texts: List<String>,
    summarize: suspend (String) -> String,
): String {
    val content = texts.joinToString("\n\n")
    require(content.isNotBlank()) { "没有可压缩的内容" }
    return summarize(content).also {
        check(it.isNotBlank()) { "模型返回了空摘要，原文未修改" }
    }
}

internal fun UIMessage.compressionSummaryText(): String {
    @Suppress("DEPRECATION")
    check(parts.none { it is UIMessagePart.Tool || it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult }) {
        "模型返回了工具调用，未生成摘要；原文未修改"
    }
    return toText().replace(Regex("<think>[\\s\\S]*?(?:</think>|$)"), "").trim()
        .takeIf { it.isNotBlank() } ?: error("模型未返回有效摘要，原文未修改")
}
