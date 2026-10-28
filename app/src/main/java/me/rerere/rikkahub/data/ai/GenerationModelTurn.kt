package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.merge
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.handleMessageChunk

/**
 * Performs one model turn while keeping request history separate from the local reply state.
 * Callers retain transforms, tool approval, persistence and proactive-specific policy.
 */
internal suspend fun generateModelTurn(
    providerImpl: Provider<ProviderSetting>,
    providerSetting: ProviderSetting,
    requestMessages: List<UIMessage>,
    initialMessages: List<UIMessage>,
    params: TextGenerationParams,
    model: Model,
    stream: Boolean,
    mergeUsage: Boolean,
    onUpdateMessages: suspend (List<UIMessage>) -> Unit,
    updateIntervalMillis: Long = 0,
    nanoTime: () -> Long = System::nanoTime,
): List<UIMessage> {
    var messages = initialMessages
    var lastUpdateNanos: Long? = null
    var pendingUpdate = false
    var updateFailed = false

    suspend fun publish() {
        try {
            onUpdateMessages(messages)
        } catch (error: Exception) {
            updateFailed = true
            throw error
        }
        pendingUpdate = false
        lastUpdateNanos = nanoTime()
    }

    suspend fun accept(chunk: MessageChunk) {
        messages = messages.handleMessageChunk(chunk = chunk, model = model)
        if (mergeUsage) {
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(usage = message.usage.merge(usage))
                    } else {
                        message
                    }
                }
            }
        }
        pendingUpdate = true
        val last = lastUpdateNanos
        if (last == null || updateIntervalMillis <= 0 ||
            nanoTime() - last >= updateIntervalMillis * 1_000_000) publish()
    }

    try {
        if (stream) {
            providerImpl.streamText(providerSetting, requestMessages, params).collect { accept(it) }
        } else {
            accept(providerImpl.generateText(providerSetting, requestMessages, params))
        }
    } catch (error: Exception) {
        // Retain the latest partial reply on provider failure/cancellation too. A cancelled
        // downstream may reject the emission; it must never replace the original failure.
        if (pendingUpdate && !updateFailed) runCatching { publish() }
        throw error
    }
    // Flush usage/signatures/tool arguments before finishing or executing/approving any tool.
    if (pendingUpdate) publish()
    return messages
}
