package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.TextGenerationParams
import java.util.UUID

internal fun codexCacheKey(params: TextGenerationParams): String? = params.conversationId
    ?.takeIf { it.isNotBlank() }
    ?.let { UUID.nameUUIDFromBytes("orangechat:codex:$it".toByteArray(Charsets.UTF_8)).toString() }

internal fun codexReasoningEffort(params: TextGenerationParams): String? {
    if (params.reasoningLevel == ReasoningLevel.AUTO) return null
    val requested = params.reasoningLevel.effort
    val supported = params.model.codexReasoningEfforts.ifEmpty {
        // Capability constraint, not a fallback model catalog. Astra cannot use none/minimal.
        if (params.model.modelId == "gpt-6-astra" || params.model.modelId.startsWith("gpt-6-astra-")) {
            listOf("low", "medium", "high", "xhigh", "max")
        } else emptyList()
    }
    if (supported.isEmpty() || requested in supported) return requested
    val order = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
    val levels = order.filter { it in supported }
    // Raise unsupported OFF to the lowest supported effort, clamp higher levels downward.
    return levels.lastOrNull { order.indexOf(it) <= order.indexOf(requested) } ?: levels.firstOrNull()
}
