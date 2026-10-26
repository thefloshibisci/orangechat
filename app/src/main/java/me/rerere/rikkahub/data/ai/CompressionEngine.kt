package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.prompts.buildCompressionPrompt
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import java.util.Locale
import kotlin.uuid.Uuid

internal const val COMPRESSION_REQUEST_TIMEOUT_MS = 180_000L
internal const val COMPRESSION_OPERATION_TIMEOUT_MS = 600_000L

/** Turn our own timeout into an actionable failure; caller cancellation still propagates. */
internal suspend fun <T : Any> withCompressionDeadline(
    timeoutMillis: Long = COMPRESSION_OPERATION_TIMEOUT_MS,
    block: suspend () -> T,
): T = withTimeoutOrNull(timeoutMillis) { block() }
    ?: error("压缩等待超时，请增加保留的近期消息数量或更换模型后重试；原文和已有摘要未修改")

/** Streaming cancellation closes the provider connection, including keep-alive-only responses. */
internal suspend fun collectCompressionSummary(
    timeoutMillis: Long = COMPRESSION_REQUEST_TIMEOUT_MS,
    stream: suspend () -> Flow<MessageChunk>,
): String = withCompressionDeadline(timeoutMillis) {
    var summary = UIMessage.assistant("")
    stream().collect { chunk ->
        chunk.choices.firstOrNull()?.let { choice ->
            check(choice.finishReason?.lowercase() !in setOf("length", "max_tokens", "max_output_tokens")) {
                "摘要输出被模型长度限制截断，请调整模型或目标长度后重试；原文未修改"
            }
            val message = choice.delta ?: choice.message
            @Suppress("DEPRECATION")
            check(message?.parts?.none {
                it is UIMessagePart.Tool || it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult
            } != false) { "模型返回了工具调用，未生成摘要；原文未修改" }
            summary = if (choice.delta != null) summary + chunk else choice.message ?: summary
        }
    }
    summary.compressionSummaryText()
}

/** Resolves a stable model/provider once per operation; session revision checks stay with ChatService. */
internal class CompressionEngine(
    private val providers: ProviderManager,
    private val usageRecorder: UsageRecorder,
) {
    fun request(
        settings: Settings,
        assistantId: Uuid,
        targetTokens: Int,
        additionalPrompt: String,
        conversationId: Uuid? = null,
    ): suspend (String) -> String {
        require(targetTokens > 0)
        val model = settings.compressionModelFor(assistantId) ?: error("没有可用的压缩模型")
        val provider = model.findProvider(settings.providers) ?: error("压缩模型的提供商不存在")
        val handler = usageRecorder.wrap(
            providers.getProviderByType(provider), UsageTag(UsageSource.COMPRESSION, assistantId, conversationId),
        )
        val language = Locale.getDefault().displayName
        return { content ->
            val prompt = buildCompressionPrompt(settings.compressPrompt, content, targetTokens, additionalPrompt, language)
            collectCompressionSummary {
                handler.streamText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(prompt)),
                    params = TextGenerationParams(model = model),
                )
            }
        }
    }
}
