package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.model.Assistant

/**
 * Shared settings projection for regular and proactive generation. Callers retain ownership of
 * their messages, tool exposure, approval policy and any finalization-only parameter overrides.
 */
internal fun buildAssistantGenerationParams(
    assistant: Assistant,
    model: Model,
    provider: ProviderSetting,
    tools: List<Tool>,
    conversationId: String?,
): TextGenerationParams = TextGenerationParams(
    model = model,
    conversationId = conversationId.takeIf { provider is ProviderSetting.Codex },
    temperature = assistant.temperature,
    topP = assistant.topP,
    maxTokens = assistant.maxTokens,
    tools = tools,
    reasoningLevel = assistant.reasoningLevel,
    // Model settings are applied after assistant settings by the provider's existing merger.
    customHeaders = assistant.customHeaders + model.customHeaders,
    customBody = assistant.customBodies + model.customBodies,
)
