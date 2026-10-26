package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** Only official API paths opt in; lookalike hosts and arbitrary proxies retain their settings. */
fun openCodeApiRoot(baseUrl: String): String? {
    val url = baseUrl.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" || url.host != "opencode.ai" || url.port != 443 ||
        url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null
    ) return null
    val path = url.encodedPath.trimEnd('/')
    val go = when {
        path.matches(Regex("/(?:inference/go|zen/go)/(?:v1|openai/v1|anthropic/v1|google/v1beta)")) -> true
        path.matches(Regex("/(?:inference|zen)/(?:v1|openai/v1|anthropic/v1|google/v1beta)")) -> false
        else -> return null
    }
    return "https://opencode.ai/inference" + if (go) "/go" else ""
}

internal enum class OpenCodeProtocol { CHAT, RESPONSES, ANTHROPIC, GOOGLE }

internal fun openCodeProtocol(modelId: String, go: Boolean): OpenCodeProtocol {
    val id = modelId.lowercase()
    return when {
        id.startsWith("claude-") -> OpenCodeProtocol.ANTHROPIC
        id.startsWith("gemini-") -> OpenCodeProtocol.GOOGLE
        id.startsWith("gpt-") || id.startsWith("grok-") || id.startsWith("muse-") -> OpenCodeProtocol.RESPONSES
        go && id in setOf("minimax-m3", "qwen3.8-max", "qwen3.8-flash", "qwen3.7-plus") -> OpenCodeProtocol.ANTHROPIC
        !go && id in setOf("qwen3.8-flash", "qwen3.6-plus", "qwen3.5-plus") -> OpenCodeProtocol.ANTHROPIC
        else -> OpenCodeProtocol.CHAT
    }
}

internal data class OpenCodeRequest(val provider: ProviderSetting, val params: TextGenerationParams)

internal fun adaptOpenCodeRequest(
    provider: ProviderSetting.OpenAI,
    params: TextGenerationParams,
    fallbackSessionId: String? = null,
): OpenCodeRequest {
    val root = openCodeApiRoot(provider.baseUrl) ?: return OpenCodeRequest(provider, params)
    val protocol = openCodeProtocol(params.model.modelId, root.endsWith("/go"))
    val sessionId = params.conversationId?.takeIf { it.isNotBlank() }
        ?: fallbackSessionId ?: UUID.randomUUID().toString()
    val headers = params.customHeaders.filterNot {
        it.name.equals("User-Agent", true) || it.name.equals("x-opencode-session", true)
    } + listOf(CustomHeader("User-Agent", "OrangeChat/1.0"), CustomHeader("x-opencode-session", sessionId))
    // OpenCode accepts 'none'; the generic OpenAI adapter otherwise coerces OFF to 'low'.
    val body = if (protocol == OpenCodeProtocol.CHAT && ModelAbility.REASONING in params.model.abilities &&
        params.reasoningLevel != ReasoningLevel.AUTO && params.customBody.none { it.key == "reasoning_effort" }
    ) listOf(CustomBody("reasoning_effort", JsonPrimitive(params.reasoningLevel.effort))) + params.customBody
    else params.customBody
    val setting = when (protocol) {
        OpenCodeProtocol.CHAT, OpenCodeProtocol.RESPONSES -> provider.copy(
            baseUrl = "$root/openai/v1",
            chatCompletionsPath = "/chat/completions",
            responsesPath = "/responses",
            useResponseApi = protocol == OpenCodeProtocol.RESPONSES,
        )
        OpenCodeProtocol.ANTHROPIC -> ProviderSetting.Claude(
            id = provider.id, name = provider.name, apiKey = provider.apiKey,
            baseUrl = "$root/anthropic/v1", models = provider.models,
        )
        OpenCodeProtocol.GOOGLE -> ProviderSetting.Google(
            id = provider.id, name = provider.name, apiKey = provider.apiKey,
            baseUrl = "$root/google/v1beta", models = provider.models,
        )
    }
    return OpenCodeRequest(setting, params.copy(customHeaders = headers, customBody = body))
}
