package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import kotlin.uuid.Uuid

enum class UsageSource {
    CHAT, COMPRESSION, TITLE, SUGGESTION, TRANSLATION, MEMORY, OCR, PROACTIVE,
    VOICE_EVENT, PLUGIN, CONNECTION_TEST, OTHER,
}

data class UsageTag(
    val source: UsageSource,
    val assistantId: Uuid? = null,
    val conversationId: Uuid? = null,
)

private interface RecordedProvider {
    val unrecordedProvider: Provider<*>
}

/** One row per text request. Only provider-reported counters are stored, never request content. */
class UsageRecorder(
    private val dao: UsageRecordDAO,
    private val scope: CoroutineScope,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T : ProviderSetting> wrap(provider: Provider<T>, tag: UsageTag): Provider<T> {
        // Retagging an already wrapped provider must not record the same request twice.
        val delegate = (provider as? RecordedProvider)?.unrecordedProvider as? Provider<T> ?: provider
        return UsageRecordingProvider(delegate, tag)
    }

    private fun record(
        startedAt: Long,
        tag: UsageTag,
        provider: ProviderSetting,
        params: TextGenerationParams,
        usage: TokenUsage?,
        outcome: String,
    ) {
        // Both row creation and scheduling are accounting work: neither may mask a provider result.
        runCatching {
            val row = UsageRecordEntity(
                createdAt = startedAt,
                source = tag.source.name,
                providerId = provider.id.toString(),
                modelId = params.model.id.toString(),
                modelName = params.model.modelId,
                assistantId = tag.assistantId?.toString(),
                conversationId = tag.conversationId?.toString() ?: params.conversationId,
                promptTokens = usage?.promptTokens ?: 0,
                completionTokens = usage?.completionTokens ?: 0,
                cachedTokens = usage?.cachedTokens ?: 0,
                // Some providers report input/output in separate frames with frame-local totals.
                // Their exact reported components form a floor; retain a larger provider total
                // (for example Gemini's total can also include thinking tokens).
                totalTokens = usage?.let { maxOf(it.totalTokens, it.promptTokens + it.completionTokens) } ?: 0,
                usageReported = usage != null,
                outcome = outcome,
            )
            scope.launch(Dispatchers.IO) {
                // This app-owned scope outlives request cancellation. Never log a request or an error body.
                try { dao.insert(row) } catch (_: Exception) { /* Best-effort accounting. */ }
            }
        }
    }

    private inner class UsageRecordingProvider<T : ProviderSetting>(
        private val delegate: Provider<T>,
        private val tag: UsageTag,
    ) : Provider<T> by delegate, RecordedProvider {
        override val unrecordedProvider: Provider<*> get() = delegate

        override suspend fun generateText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk {
            val startedAt = currentTimeMillis()
            var usage: TokenUsage? = null
            var outcome = "SUCCESS"
            try {
                return delegate.generateText(providerSetting, messages, params).also { usage = it.usage }
            } catch (error: CancellationException) {
                outcome = "CANCELLED"
                throw error
            } catch (error: Throwable) {
                outcome = "ERROR"
                throw error
            } finally {
                record(startedAt, tag, providerSetting, params, usage, outcome)
            }
        }

        override suspend fun streamText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow {
            // A cold flow starts a real provider call on each collection. A never-collected flow
            // does not count, and a provider that fails while constructing its flow still counts.
            val startedAt = currentTimeMillis()
            var usage: TokenUsage? = null
            var outcome = "SUCCESS"
            try {
                delegate.streamText(providerSetting, messages, params).collect { chunk ->
                    chunk.usage?.let { incoming ->
                        // Streaming reports cumulative snapshots, sometimes one field at a time.
                        // Keep reported counters without adding snapshots. The final row derives
                        // only the exact sum of reported input/output when a frame-local total is lower.
                        usage = TokenUsage(
                            promptTokens = maxOf(usage?.promptTokens ?: 0, incoming.promptTokens),
                            completionTokens = maxOf(usage?.completionTokens ?: 0, incoming.completionTokens),
                            cachedTokens = maxOf(usage?.cachedTokens ?: 0, incoming.cachedTokens),
                            totalTokens = maxOf(usage?.totalTokens ?: 0, incoming.totalTokens),
                        )
                    }
                    emit(chunk)
                }
            } catch (error: CancellationException) {
                outcome = "CANCELLED"
                throw error
            } catch (error: Throwable) {
                outcome = "ERROR"
                throw error
            } finally {
                record(startedAt, tag, providerSetting, params, usage, outcome)
            }
        }
    }
}
