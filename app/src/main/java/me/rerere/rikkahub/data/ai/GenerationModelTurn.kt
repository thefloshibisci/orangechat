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
): List<UIMessage> {
    var messages = initialMessages

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
        onUpdateMessages(messages)
    }

    if (stream) {
        providerImpl.streamText(providerSetting, requestMessages, params).collect { accept(it) }
    } else {
        accept(providerImpl.generateText(providerSetting, requestMessages, params))
    }
    return messages
}
